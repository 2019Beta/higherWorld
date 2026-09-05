package org.devt.higherworld.world;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.noise.DoublePerlinNoiseSampler;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettesFactory;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.UpgradeData;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.GenerationShapeConfig;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.noise.NoiseRouter;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.biome.source.BiomeSource;
import org.devt.higherworld.gpu.GpuTerrainAccelerator;
import org.devt.higherworld.mixin.NoiseChunkGeneratorInvoker;
import org.devt.higherworld.storage.CubePos;

/** Samples vanilla noise into immutable sparse-cube batches. */
final class VanillaCubeTerrainGenerator {
    private static final int SECTIONS_PER_BATCH = 4;
    private static final int BATCH_HEIGHT = CubePos.SIZE * SECTIONS_PER_BATCH;
    // A 33x33 horizontal view touches 1089 origins.  The 64-high pipeline
    // addresses two adjacent vertical planes while the player is near the
    // vanilla floor, so 2048 was below the working set and caused constant
    // re-generation.  Keep four planes hot, but retain bounded LRU eviction.
    private static final int MAX_CACHED_BATCHES = 4096;
    private static final int MAX_CACHED_GENERATORS = 64;
    private static final int MAX_GPU_BATCHES = 16;
    private static final Map<ServerWorld, Context> CONTEXTS = new ConcurrentHashMap<>();

    private VanillaCubeTerrainGenerator() {
    }

    static boolean generate(ServerWorld world, LoadedCube cube) {
        ChunkGenerator source = world.getChunkManager().getChunkGenerator();
        if (!(source instanceof NoiseChunkGenerator noiseGenerator)) {
            return false;
        }

        Context context = CONTEXTS.computeIfAbsent(
                world, ignored -> createContext(world, noiseGenerator));
        int batchSectionY = Math.floorDiv(cube.pos().y(), SECTIONS_PER_BATCH)
                * SECTIONS_PER_BATCH;
        int minimumY = batchSectionY * CubePos.SIZE;
        NoiseChunkGenerator generator = context.generatorFor(noiseGenerator, minimumY);
        BatchPos batchPos = new BatchPos(cube.pos().x(), batchSectionY, cube.pos().z());
        ChunkSection generated = context.getOrGenerate(
                batchPos, () -> generateBatch(
                        world, batchPos, minimumY, generator, context.noiseConfig))
                .section(cube.pos().y() - batchSectionY);
        for (int y = 0; y < CubePos.SIZE; y++) {
            for (int z = 0; z < CubePos.SIZE; z++) {
                for (int x = 0; x < CubePos.SIZE; x++) {
                    BlockState state = generated.getBlockState(x, y, z);
                    cube.setGeneratedBlockState(x, y, z, state);
                }
            }
        }
        return true;
    }

    /**
     * Captures the read-only inputs needed by the asynchronous terrain phase.
     * The request deliberately contains no ServerWorld or LoadedCube. The
     * palettes factory and structure accessor are the same read-only worldgen
     * collaborators passed to vanilla's own asynchronous noise task.
     */
    static TerrainRequest prepareRequest(ServerWorld world, CubePos pos) {
        ChunkGenerator source = world.getChunkManager().getChunkGenerator();
        if (!(source instanceof NoiseChunkGenerator noiseGenerator)) {
            return null;
        }

        Context context = CONTEXTS.computeIfAbsent(
                world, ignored -> createContext(world, noiseGenerator));
        int batchSectionY = Math.floorDiv(pos.y(), SECTIONS_PER_BATCH)
                * SECTIONS_PER_BATCH;
        int minimumY = batchSectionY * CubePos.SIZE;
        return new TerrainRequest(
                pos, new BatchPos(pos.x(), batchSectionY, pos.z()), minimumY,
                noiseGenerator.getBiomeSource(), context.settings,
                context.noiseParameters, context.seed, context.asyncBatches,
                world.getPalettesFactory(), world.getStructureAccessor(),
                minimumY + BATCH_HEIGHT <= world.getBottomY());
    }

    /**
     * Prepares one deduplicated 64-high batch. Deep-only batches use the
     * sparse density rasterizer; the vanilla-height band keeps the exact
     * chunk-wide interpolated sampler so aquifers and modded details remain
     * unchanged.
     */
    static CubeTerrainSnapshot prepareTerrain(TerrainRequest request) {
        return getOrGenerateAsync(request.asyncBatches(), request.batchPos(),
                () -> {
                    if (request.gpuEligible()) {
                        TerrainBatchSnapshot sparse = generateDeepSparseBatch(request);
                        if (sparse != null) return sparse;
                    }
                    return generateSampleBatch(
                            request.biomeSource(), request.settings(),
                            NoiseConfig.create(
                                    request.settings(), request.noiseParameters(), request.seed()),
                            request.batchPos(), request.minimumY(), request.palettesFactory(),
                            request.structureAccessor());
                });
    }

    /** CPU fallback for deep batches when OpenCL is disabled or unavailable. */
    private static TerrainBatchSnapshot generateDeepSparseBatch(TerrainRequest request) {
        try {
            NoiseConfig noiseConfig = NoiseConfig.create(
                    request.settings(), request.noiseParameters(), request.seed());
            DensityGrid grid = sampleDensityGrid(request, noiseConfig);
            if (grid == null) return null;

            BlockState[] states = new BlockState[CubePos.SIZE * BATCH_HEIGHT * CubePos.SIZE];
            BlockState air = Blocks.AIR.getDefaultState();
            BlockState defaultBlock = request.settings().defaultBlock();
            Arrays.fill(states, air);
            int sectionCellsY = CubePos.SIZE / grid.stepY();
            if (TerrainInterpolation.isAllSolid(grid.samples())) {
                Arrays.fill(states, defaultBlock);
                return new TerrainBatchSnapshot(request.batchPos(), request.minimumY(), states);
            }
            for (int section = 0; section < SECTIONS_PER_BATCH; section++) {
                boolean[] solid = new boolean[CubePos.SIZE * CubePos.SIZE * CubePos.SIZE];
                TerrainInterpolation.fillSolid(
                        sliceDensityGrid(grid, section, sectionCellsY),
                        grid.stepX(), grid.stepY(), grid.stepZ(),
                        grid.cellsX(), sectionCellsY, grid.cellsZ(), solid);
                for (int voxel = 0; voxel < solid.length; voxel++) {
                    if (!solid[voxel]) continue;
                    int localX = voxel & 15;
                    int remainder = voxel >> 4;
                    int localZ = remainder & 15;
                    int localY = remainder >> 4;
                    states[batchIndex(
                            localX, section * CubePos.SIZE + localY, localZ)] =
                            defaultBlock;
                }
            }
            return new TerrainBatchSnapshot(request.batchPos(), request.minimumY(), states);
        } catch (RuntimeException exception) {
            // Retain the exact NoiseChunkGenerator path for unusual/modded
            // settings that cannot be represented by the sparse density pass.
            return null;
        }
    }

    /**
     * Rasterizes independent deep-only vanilla batches in one batched OpenCL
     * launch for each 64-high density batch.
     * The density function is still sampled by Minecraft on the host because
     * arbitrary modded NoiseRouter graphs cannot safely be compiled here; the
     * expensive 16384-voxel interpolation/sign pass is shared with the GPU
     * bridge already used by the custom terrain path.
     *
     * <p>A null result means the density graph or device is not eligible.  The
     * scheduler then returns every request to its bounded CPU path.</p>
     */
    static Map<CubePos, CubeTerrainSnapshot> prepareGpuTerrainBatch(
            List<TerrainRequest> requests) {
        if (requests == null || requests.isEmpty() || requests.size() > MAX_GPU_BATCHES) {
            throw new IllegalArgumentException(
                    "Vanilla terrain batch must contain 1-" + MAX_GPU_BATCHES + " cubes");
        }
        LinkedHashMap<BatchPos, TerrainRequest> unique = new LinkedHashMap<>();
        for (TerrainRequest request : requests) {
            if (request == null || !request.gpuEligible()) return null;
            unique.putIfAbsent(request.batchPos(), request);
        }

        Map<BatchPos, TerrainBatchSnapshot> snapshots = new HashMap<>();
        List<TerrainRequest> uncached = new ArrayList<>(unique.size());
        for (Map.Entry<BatchPos, TerrainRequest> entry : unique.entrySet()) {
            CompletableFuture<TerrainBatchSnapshot> cached =
                    entry.getValue().asyncBatches().get(entry.getKey());
            if (cached == null) {
                uncached.add(entry.getValue());
            } else {
                snapshots.put(entry.getKey(), cached.join());
            }
        }

        if (!uncached.isEmpty()) {
            TerrainRequest first = uncached.get(0);
            NoiseConfig noiseConfig = NoiseConfig.create(
                    first.settings(), first.noiseParameters(), first.seed());
            DensityGrid firstGrid = sampleDensityGrid(first, noiseConfig);
            if (firstGrid == null) return null;

            DensityGrid[] grids = new DensityGrid[uncached.size()];
            grids[0] = firstGrid;
            for (int index = 1; index < uncached.size(); index++) {
                DensityGrid grid = sampleDensityGrid(uncached.get(index), noiseConfig);
                if (grid == null || !firstGrid.sameShape(grid)) return null;
                grids[index] = grid;
            }

            BlockState air = Blocks.AIR.getDefaultState();
            BlockState defaultBlock = first.settings().defaultBlock();
            BlockState[][] states = new BlockState[uncached.size()][];
            for (int index = 0; index < states.length; index++) {
                states[index] = new BlockState[CubePos.SIZE * BATCH_HEIGHT * CubePos.SIZE];
                Arrays.fill(states[index], air);
            }

            // Keep the complete 64-high density grid in one device buffer. The
            // batch raster kernel derives its output height from cellsY*stepY,
            // so this replaces four blocking write/kernel/readback roundtrips
            // with one while preserving the section-local interpolation math.
            double[][] batchSamples = new double[grids.length][];
            for (int index = 0; index < grids.length; index++) {
                batchSamples[index] = grids[index].samples();
            }
            boolean[][] solid = new boolean[
                    grids.length][CubePos.SIZE * BATCH_HEIGHT * CubePos.SIZE];
            boolean dispatched = GpuTerrainAccelerator.tryRasterizeDensityBatch(
                    batchSamples, firstGrid.stepX(), firstGrid.stepY(), firstGrid.stepZ(),
                    firstGrid.cellsX(), firstGrid.cellsY(), firstGrid.cellsZ(),
                    GpuTerrainAccelerator.InterpolationMode.LINEAR, solid);
            if (!dispatched) return null;
            for (int index = 0; index < solid.length; index++) {
                for (int voxel = 0; voxel < solid[index].length; voxel++) {
                    if (solid[index][voxel]) {
                        int localX = voxel & 15;
                        int remainder = voxel >> 4;
                        int localZ = remainder & 15;
                        int localY = remainder >> 4;
                        states[index][batchIndex(localX, localY, localZ)] = defaultBlock;
                    }
                }
            }

            for (int index = 0; index < uncached.size(); index++) {
                TerrainRequest request = uncached.get(index);
                TerrainBatchSnapshot generated = new TerrainBatchSnapshot(
                        request.batchPos(), request.minimumY(), states[index]);
                CompletableFuture<TerrainBatchSnapshot> completed =
                        CompletableFuture.completedFuture(generated);
                CompletableFuture<TerrainBatchSnapshot> winner =
                        request.asyncBatches().putIfAbsent(request.batchPos(), completed);
                snapshots.put(request.batchPos(), winner == null ? generated : winner.join());
            }
        }

        Map<CubePos, CubeTerrainSnapshot> result = new HashMap<>(requests.size());
        for (TerrainRequest request : requests) {
            TerrainBatchSnapshot snapshot = snapshots.get(request.batchPos());
            if (snapshot == null) return null;
            result.put(request.cubePos(), snapshot);
        }
        return result;
    }

    static void release(ServerWorld world) {
        CONTEXTS.remove(world);
    }

    private static Batch generateBatch(
            ServerWorld world, BatchPos batchPos, int minimumY,
            NoiseChunkGenerator generator, NoiseConfig noiseConfig) {
        TerrainBatchSnapshot solidBatch = tryGenerateSolidBatch(
                generator.getSettings().value(), noiseConfig, batchPos, minimumY);
        if (solidBatch != null) {
            return materializeBatch(world.getPalettesFactory(), solidBatch.rawStates());
        }

        HeightLimitView height = HeightLimitView.create(minimumY, BATCH_HEIGHT);
        ProtoChunk protoChunk = new ProtoChunk(
                new ChunkPos(batchPos.x(), batchPos.z()), UpgradeData.NO_UPGRADE_DATA,
                height, world.getPalettesFactory(), null);
        GenerationShapeConfig shape = generator.getSettings().value()
                .generationShapeConfig().trimHeight(height);
        int verticalCell = shape.verticalCellBlockCount();
        int minimumCellY = Math.floorDiv(shape.minimumY(), verticalCell);
        int cellHeight = Math.floorDiv(shape.height(), verticalCell);

        ChunkSection[] sections = protoChunk.getSectionArray();
        for (ChunkSection section : sections) {
            section.lock();
        }
        try {
            ((NoiseChunkGeneratorInvoker) (Object) generator)
                    .higherworld$populateNoiseSynchronously(
                            Blender.getNoBlending(), world.getStructureAccessor(), noiseConfig,
                            protoChunk, minimumCellY, cellHeight);
        } finally {
            for (ChunkSection section : sections) {
                section.unlock();
            }
        }
        return new Batch(sections);
    }

    private static Context createContext(ServerWorld world, NoiseChunkGenerator source) {
        ChunkGeneratorSettings original = source.getSettings().value();
        NoiseRouter router = InfiniteDownwardGenerator.removeVanillaBottomSlide(
                original.noiseRouter());
        ChunkGeneratorSettings settings = InfiniteNoiseSettings.createSparseSettings(
                original, new GenerationShapeConfig(-128, BATCH_HEIGHT,
                        original.generationShapeConfig().horizontalSize(),
                        original.generationShapeConfig().verticalSize()), router);
        RegistryEntryLookup<DoublePerlinNoiseSampler.NoiseParameters> noiseParameters =
                world.getRegistryManager().getOrThrow(RegistryKeys.NOISE_PARAMETERS);
        NoiseConfig noiseConfig = NoiseConfig.create(settings, noiseParameters, world.getSeed());
        return new Context(settings, noiseConfig, noiseParameters, world.getSeed(),
                new LinkedHashMap<>(16, 0.75f, true),
                new LinkedHashMap<>(32, 0.75f, true), new AsyncBatchCache());
    }

    private static DensityGrid sampleDensityGrid(
            TerrainRequest request, NoiseConfig noiseConfig) {
        GenerationShapeConfig shape = new GenerationShapeConfig(
                request.minimumY(), BATCH_HEIGHT,
                request.settings().generationShapeConfig().horizontalSize(),
                request.settings().generationShapeConfig().verticalSize());
        int stepX = shape.horizontalCellBlockCount();
        int stepY = shape.verticalCellBlockCount();
        int stepZ = stepX;
        if (stepX <= 0 || stepY <= 0
                || CubePos.SIZE % stepX != 0
                || CubePos.SIZE % stepY != 0
                || BATCH_HEIGHT % stepY != 0) {
            return null;
        }
        int cellsX = CubePos.SIZE / stepX;
        int cellsY = BATCH_HEIGHT / stepY;
        int cellsZ = CubePos.SIZE / stepZ;
        DensityFunction density = noiseConfig.getNoiseRouter().finalDensity();
        double[] samples = new double[(cellsX + 1) * (cellsY + 1) * (cellsZ + 1)];
        try {
            for (int gridY = 0; gridY <= cellsY; gridY++) {
                for (int gridZ = 0; gridZ <= cellsZ; gridZ++) {
                    for (int gridX = 0; gridX <= cellsX; gridX++) {
                        samples[(gridY * (cellsZ + 1) + gridZ) * (cellsX + 1) + gridX] =
                                density.sample(new DensityFunction.UnblendedNoisePos(
                                        request.batchPos().x() * CubePos.SIZE + gridX * stepX,
                                        request.minimumY() + gridY * stepY,
                                        request.batchPos().z() * CubePos.SIZE + gridZ * stepZ));
                    }
                }
            }
        } catch (RuntimeException exception) {
            // A modded density graph can require a richer NoisePos.  Let the
            // exact vanilla generator handle that graph instead of failing a
            // streamed cube lifecycle.
            return null;
        }
        return new DensityGrid(samples, stepX, stepY, stepZ, cellsX, cellsY, cellsZ);
    }

    private static double[] sliceDensityGrid(
            DensityGrid grid, int section, int sectionCellsY) {
        int rowLength = (grid.cellsX() + 1) * (grid.cellsZ() + 1);
        double[] slice = new double[rowLength * (sectionCellsY + 1)];
        int sourceOffset = section * sectionCellsY * rowLength;
        for (int row = 0; row <= sectionCellsY; row++) {
            System.arraycopy(
                    grid.samples(), sourceOffset + row * rowLength,
                    slice, row * rowLength, rowLength);
        }
        return slice;
    }

    private static TerrainBatchSnapshot generateSampleBatch(
            BiomeSource biomeSource, ChunkGeneratorSettings settings,
            NoiseConfig noiseConfig,
            BatchPos batchPos, int minimumY, PalettesFactory palettesFactory,
            StructureAccessor structureAccessor) {
        // Build a private generator and proto chunk for this worker. This is
        // the same thread-safe path used by NoiseChunkGenerator.populateNoise;
        // only copying the result into a live LoadedCube is server-thread work.
        // This path also covers the vanilla-height transition band, where the
        // exact sampler is preferred over the deep sparse approximation.
        NoiseChunkGenerator generator = createGenerator(biomeSource, settings, minimumY);
        HeightLimitView height = HeightLimitView.create(minimumY, BATCH_HEIGHT);
        ProtoChunk protoChunk = new ProtoChunk(
                new ChunkPos(batchPos.x(), batchPos.z()), UpgradeData.NO_UPGRADE_DATA,
                height, palettesFactory, null);
        GenerationShapeConfig shape = generator.getSettings().value()
                .generationShapeConfig().trimHeight(height);
        int verticalCell = shape.verticalCellBlockCount();
        int minimumCellY = Math.floorDiv(shape.minimumY(), verticalCell);
        int cellHeight = Math.floorDiv(shape.height(), verticalCell);

        ChunkSection[] sections = protoChunk.getSectionArray();
        for (ChunkSection section : sections) section.lock();
        try {
            ((NoiseChunkGeneratorInvoker) (Object) generator)
                    .higherworld$populateNoiseSynchronously(
                            Blender.getNoBlending(), structureAccessor, noiseConfig,
                            protoChunk, minimumCellY, cellHeight);
        } finally {
            for (ChunkSection section : sections) section.unlock();
        }

        BlockState[] states = new BlockState[CubePos.SIZE * BATCH_HEIGHT * CubePos.SIZE];
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            ChunkSection section = sections[sectionIndex];
            int sectionOffsetY = sectionIndex * CubePos.SIZE;
            for (int localY = 0; localY < CubePos.SIZE; localY++) {
                for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
                    for (int localX = 0; localX < CubePos.SIZE; localX++) {
                        states[batchIndex(localX, sectionOffsetY + localY, localZ)] =
                                section.getBlockState(localX, localY, localZ);
                    }
                }
            }
        }
        return new TerrainBatchSnapshot(batchPos, minimumY, states);
    }

    private static Batch materializeBatch(PalettesFactory palettesFactory, BlockState[] states) {
        ChunkSection[] sections = new ChunkSection[SECTIONS_PER_BATCH];
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            ChunkSection section = new ChunkSection(palettesFactory);
            int sectionOffsetY = sectionIndex * CubePos.SIZE;
            for (int localY = 0; localY < CubePos.SIZE; localY++) {
                for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
                    for (int localX = 0; localX < CubePos.SIZE; localX++) {
                        section.setBlockState(localX, localY, localZ,
                                states[batchIndex(localX, sectionOffsetY + localY, localZ)]);
                    }
                }
            }
            sections[sectionIndex] = section;
        }
        return new Batch(sections);
    }

    /**
     * Deep sparse cubes are often completely solid.  A conservative density
     * pass can prove that case from the same noise-cell corners used by the
     * vanilla interpolator, avoiding construction of a full NoiseChunk,
     * aquifer sampler, and four temporary sections.  Mixed/uncertain batches
     * keep the exact vanilla path below.  This proof is deliberately kept on
     * the CPU: launching four tiny GPU raster jobs just to check a 64-high
     * batch costs more than the raster it replaces.
     */
    private static TerrainBatchSnapshot tryGenerateSolidBatch(
            ChunkGeneratorSettings settings, NoiseConfig noiseConfig,
            BatchPos batchPos, int minimumY) {
        GenerationShapeConfig shape = new GenerationShapeConfig(
                minimumY, BATCH_HEIGHT,
                settings.generationShapeConfig().horizontalSize(),
                settings.generationShapeConfig().verticalSize());
        int stepX = shape.horizontalCellBlockCount();
        int stepY = shape.verticalCellBlockCount();
        int stepZ = stepX;
        if (stepX <= 0 || stepY <= 0
                || CubePos.SIZE % stepX != 0
                || CubePos.SIZE % stepY != 0
                || BATCH_HEIGHT % stepY != 0) {
            return null;
        }
        int cellsX = CubePos.SIZE / stepX;
        int cellsY = BATCH_HEIGHT / stepY;
        int cellsZ = CubePos.SIZE / stepZ;
        DensityFunction density = noiseConfig.getNoiseRouter().finalDensity();
        try {
            for (int gridY = 0; gridY <= cellsY; gridY++) {
                for (int gridZ = 0; gridZ <= cellsZ; gridZ++) {
                    for (int gridX = 0; gridX <= cellsX; gridX++) {
                        double value = density.sample(new DensityFunction.UnblendedNoisePos(
                                batchPos.x() * CubePos.SIZE + gridX * stepX,
                                minimumY + gridY * stepY,
                                batchPos.z() * CubePos.SIZE + gridZ * stepZ));
                        if (!(value > 0.0)) return null;
                    }
                }
            }
        } catch (RuntimeException exception) {
            // A modded density function may require a richer NoisePos.  It is
            // not eligible for this conservative shortcut; retain the exact
            // vanilla batch path instead of failing generation.
            return null;
        }

        BlockState[] states = new BlockState[CubePos.SIZE * BATCH_HEIGHT * CubePos.SIZE];
        Arrays.fill(states, settings.defaultBlock());
        return new TerrainBatchSnapshot(batchPos, minimumY, states);
    }

    private static int batchIndex(int localX, int localY, int localZ) {
        return (localY * CubePos.SIZE + localZ) * CubePos.SIZE + localX;
    }

    private static NoiseChunkGenerator createGenerator(
            BiomeSource biomeSource, ChunkGeneratorSettings template, int minimumY) {
        GenerationShapeConfig originalShape = template.generationShapeConfig();
        GenerationShapeConfig shape = new GenerationShapeConfig(
                minimumY, BATCH_HEIGHT,
                originalShape.horizontalSize(), originalShape.verticalSize());
        ChunkGeneratorSettings settings = InfiniteNoiseSettings.createSparseSettings(
                template, shape, template.noiseRouter());
        NoiseChunkGenerator generator = new NoiseChunkGenerator(
                biomeSource, RegistryEntry.of(settings));
        InfiniteNoiseSettings.useUnboundedAquifers(generator);
        return generator;
    }

    private static final class Context {
        private final ChunkGeneratorSettings settings;
        private final NoiseConfig noiseConfig;
        private final RegistryEntryLookup<DoublePerlinNoiseSampler.NoiseParameters> noiseParameters;
        private final long seed;
        private final LinkedHashMap<Integer, NoiseChunkGenerator> generators;
        private final LinkedHashMap<BatchPos, Batch> batches;
        private final AsyncBatchCache asyncBatches;

        private Context(
                ChunkGeneratorSettings settings, NoiseConfig noiseConfig,
                RegistryEntryLookup<DoublePerlinNoiseSampler.NoiseParameters> noiseParameters,
                long seed,
                LinkedHashMap<Integer, NoiseChunkGenerator> generators,
                LinkedHashMap<BatchPos, Batch> batches,
                AsyncBatchCache asyncBatches) {
            this.settings = settings;
            this.noiseConfig = noiseConfig;
            this.noiseParameters = noiseParameters;
            this.seed = seed;
            this.generators = generators;
            this.batches = batches;
            this.asyncBatches = asyncBatches;
        }

        private synchronized NoiseChunkGenerator generatorFor(
                NoiseChunkGenerator source, int minimumY) {
            NoiseChunkGenerator generator = generators.get(minimumY);
            if (generator == null) {
                generator = createGenerator(source.getBiomeSource(), settings, minimumY);
                generators.put(minimumY, generator);
                trim(generators, MAX_CACHED_GENERATORS);
            }
            return generator;
        }

        private synchronized Batch getOrGenerate(
                BatchPos pos, java.util.function.Supplier<Batch> factory) {
            Batch batch = batches.get(pos);
            if (batch == null) {
                batch = factory.get();
                batches.put(pos, batch);
                trim(batches, MAX_CACHED_BATCHES);
            }
            return batch;
        }

        private static <K, V> void trim(LinkedHashMap<K, V> values, int maximum) {
            while (values.size() > maximum) {
                values.remove(values.keySet().iterator().next());
            }
        }
    }

    /**
     * Deduplicates a 64-high batch without holding a global lock while noise
     * sampling runs. A competing request waits on the same future, so four
     * vertical cubes cannot each execute the expensive sampler.
     */
    private static CubeTerrainSnapshot getOrGenerateAsync(
            AsyncBatchCache batches,
            BatchPos pos, java.util.function.Supplier<TerrainBatchSnapshot> factory) {
        CompletableFuture<TerrainBatchSnapshot> candidate = new CompletableFuture<>();
        CompletableFuture<TerrainBatchSnapshot> existing = batches.get(pos);
        if (existing == null) existing = batches.putIfAbsent(pos, candidate);
        if (existing == null) {
            try {
                candidate.complete(factory.get());
            } catch (Throwable throwable) {
                candidate.completeExceptionally(throwable);
                batches.remove(pos, candidate);
            } finally {
                batches.trimCompleted();
            }
            existing = candidate;
        }
        return existing.join();
    }

    /** Thread-safe access-order cache that never evicts an in-flight batch. */
    static final class AsyncBatchCache {
        private final LinkedHashMap<BatchPos, CompletableFuture<TerrainBatchSnapshot>> values =
                new LinkedHashMap<>(32, 0.75f, true);

        synchronized CompletableFuture<TerrainBatchSnapshot> get(BatchPos pos) {
            CompletableFuture<TerrainBatchSnapshot> value = values.get(pos);
            trim();
            return value;
        }

        synchronized CompletableFuture<TerrainBatchSnapshot> putIfAbsent(
                BatchPos pos, CompletableFuture<TerrainBatchSnapshot> value) {
            CompletableFuture<TerrainBatchSnapshot> existing = values.putIfAbsent(pos, value);
            trim();
            return existing;
        }

        synchronized void remove(BatchPos pos, CompletableFuture<TerrainBatchSnapshot> value) {
            if (values.get(pos) == value) values.remove(pos);
        }

        synchronized void trimCompleted() {
            trim();
        }

        private void trim() {
            if (values.size() <= MAX_CACHED_BATCHES) return;
            var iterator = values.entrySet().iterator();
            while (values.size() > MAX_CACHED_BATCHES && iterator.hasNext()) {
                Map.Entry<BatchPos, CompletableFuture<TerrainBatchSnapshot>> entry =
                        iterator.next();
                if (entry.getValue().isDone()) iterator.remove();
            }
        }
    }

    private record BatchPos(int x, int sectionY, int z) {
    }

    static record TerrainRequest(
            CubePos cubePos, BatchPos batchPos, int minimumY,
            BiomeSource biomeSource, ChunkGeneratorSettings settings,
            RegistryEntryLookup<DoublePerlinNoiseSampler.NoiseParameters> noiseParameters,
            long seed,
            AsyncBatchCache asyncBatches,
            PalettesFactory palettesFactory, StructureAccessor structureAccessor,
            boolean gpuEligible) {
    }

    private record DensityGrid(
            double[] samples, int stepX, int stepY, int stepZ,
            int cellsX, int cellsY, int cellsZ) {
        private boolean sameShape(DensityGrid other) {
            return stepX == other.stepX && stepY == other.stepY
                    && stepZ == other.stepZ && cellsX == other.cellsX
                    && cellsY == other.cellsY && cellsZ == other.cellsZ;
        }
    }

    private record TerrainBatchSnapshot(
            BatchPos batchPos, int minimumY, BlockState[] states)
            implements CubeTerrainSnapshot {
        private TerrainBatchSnapshot {
            if (states.length != CubePos.SIZE * BATCH_HEIGHT * CubePos.SIZE) {
                throw new IllegalArgumentException("A terrain batch must contain exactly 16384 blocks");
            }
            states = states.clone();
        }

        @Override
        public BlockState[] states() {
            return states.clone();
        }

        /** Internal fast path; the snapshot is immutable after construction. */
        private BlockState[] rawStates() {
            return states;
        }

        @Override
        public void applyTo(LoadedCube cube) {
            if (cube.pos().x() != batchPos.x() || cube.pos().z() != batchPos.z()) {
                throw new IllegalArgumentException("Terrain batch does not match cube " + cube.pos());
            }
            int localSectionY = cube.pos().y() - batchPos.sectionY();
            if (localSectionY < 0 || localSectionY >= SECTIONS_PER_BATCH) {
                throw new IllegalArgumentException("Terrain batch does not contain cube " + cube.pos());
            }
            int yOffset = localSectionY * CubePos.SIZE;
            for (int localY = 0; localY < CubePos.SIZE; localY++) {
                for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
                    for (int localX = 0; localX < CubePos.SIZE; localX++) {
                        cube.setGeneratedBlockState(localX, localY, localZ,
                                states[batchIndex(localX, yOffset + localY, localZ)]);
                    }
                }
            }
        }
    }

    private record Batch(ChunkSection[] sections) {
        private ChunkSection section(int index) {
            return sections[index];
        }
    }
}
