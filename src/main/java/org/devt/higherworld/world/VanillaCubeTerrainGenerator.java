package org.devt.higherworld.world;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;

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
import net.minecraft.world.biome.source.BiomeSource;
import org.devt.higherworld.mixin.NoiseChunkGeneratorInvoker;
import org.devt.higherworld.storage.CubePos;

/** Samples vanilla noise into immutable sparse-cube batches. */
final class VanillaCubeTerrainGenerator {
    private static final int SECTIONS_PER_BATCH = 4;
    private static final int BATCH_HEIGHT = CubePos.SIZE * SECTIONS_PER_BATCH;
    private static final int MAX_CACHED_BATCHES = 256;
    private static final int MAX_CACHED_GENERATORS = 64;
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
                batchPos, () -> generateBatch(world, cube, minimumY, generator, context.noiseConfig))
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
                world.getPalettesFactory(), world.getStructureAccessor());
    }

    /**
     * Runs vanilla's chunk-wide interpolated sampler. Calling getColumnSample
     * 256 times rebuilds a ChunkNoiseSampler (including its aquifer) for every
     * column and made one batch take several CPU-seconds. Vanilla's normal
     * noise fill builds that state once for the whole 16 x 16 x 64 batch.
     */
    static CubeTerrainSnapshot prepareTerrain(TerrainRequest request) {
        return getOrGenerateAsync(request.asyncBatches(), request.batchPos(),
                () -> generateSampleBatch(
                        request.biomeSource(), request.settings(),
                        NoiseConfig.create(
                                request.settings(), request.noiseParameters(), request.seed()),
                        request.batchPos(), request.minimumY(), request.palettesFactory(),
                        request.structureAccessor()));
    }

    static void release(ServerWorld world) {
        CONTEXTS.remove(world);
    }

    private static Batch generateBatch(
            ServerWorld world, LoadedCube cube, int minimumY,
            NoiseChunkGenerator generator, NoiseConfig noiseConfig) {
        HeightLimitView height = HeightLimitView.create(minimumY, BATCH_HEIGHT);
        ProtoChunk protoChunk = new ProtoChunk(
                new ChunkPos(cube.pos().x(), cube.pos().z()), UpgradeData.NO_UPGRADE_DATA,
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
                new LinkedHashMap<>(32, 0.75f, true), new ConcurrentHashMap<>());
    }

    private static TerrainBatchSnapshot generateSampleBatch(
            BiomeSource biomeSource, ChunkGeneratorSettings settings,
            NoiseConfig noiseConfig,
            BatchPos batchPos, int minimumY, PalettesFactory palettesFactory,
            StructureAccessor structureAccessor) {
        // Build a private generator and proto chunk for this worker. This is
        // the same thread-safe path used by NoiseChunkGenerator.populateNoise;
        // only copying the result into a live LoadedCube is server-thread work.
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
        private final ConcurrentMap<BatchPos, CompletableFuture<TerrainBatchSnapshot>> asyncBatches;

        private Context(
                ChunkGeneratorSettings settings, NoiseConfig noiseConfig,
                RegistryEntryLookup<DoublePerlinNoiseSampler.NoiseParameters> noiseParameters,
                long seed,
                LinkedHashMap<Integer, NoiseChunkGenerator> generators,
                LinkedHashMap<BatchPos, Batch> batches,
                ConcurrentMap<BatchPos, CompletableFuture<TerrainBatchSnapshot>> asyncBatches) {
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
            ConcurrentMap<BatchPos, CompletableFuture<TerrainBatchSnapshot>> batches,
            BatchPos pos, java.util.function.Supplier<TerrainBatchSnapshot> factory) {
        CompletableFuture<TerrainBatchSnapshot> candidate = new CompletableFuture<>();
        CompletableFuture<TerrainBatchSnapshot> existing = batches.putIfAbsent(pos, candidate);
        if (existing == null) {
            try {
                candidate.complete(factory.get());
            } catch (Throwable throwable) {
                candidate.completeExceptionally(throwable);
                batches.remove(pos, candidate);
            }
            existing = candidate;
        }
        trimAsyncBatches(batches);
        return existing.join();
    }

    private static void trimAsyncBatches(
            ConcurrentMap<BatchPos, CompletableFuture<TerrainBatchSnapshot>> batches) {
        if (batches.size() <= MAX_CACHED_BATCHES) return;
        batches.entrySet().removeIf(entry ->
                batches.size() > MAX_CACHED_BATCHES && entry.getValue().isDone());
    }

    private record BatchPos(int x, int sectionY, int z) {
    }

    static record TerrainRequest(
            CubePos cubePos, BatchPos batchPos, int minimumY,
            BiomeSource biomeSource, ChunkGeneratorSettings settings,
            RegistryEntryLookup<DoublePerlinNoiseSampler.NoiseParameters> noiseParameters,
            long seed,
            ConcurrentMap<BatchPos, CompletableFuture<TerrainBatchSnapshot>> asyncBatches,
            PalettesFactory palettesFactory, StructureAccessor structureAccessor) {
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
