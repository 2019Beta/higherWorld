package org.devt.higherworld.world;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.block.BlockState;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.Blocks;
import net.minecraft.block.MultifaceBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.util.math.random.Xoroshiro128PlusPlusRandom;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.GenerationSettings;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.UpgradeData;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.feature.PlacedFeature;
import org.devt.higherworld.storage.CubePos;

/** Decorates sparse cubes by executing the biome's registered vanilla placed features. */
final class VanillaPlacedFeatureGenerator {
    private static final int VANILLA_BOTTOM_Y = -64;
    private static final int VANILLA_HEIGHT = 384;
    private static final int REPEATED_BAND_HEIGHT = 64;
    private static final int FEATURE_ORIGIN_RADIUS = 1;
    private static final List<GenerationStep.Feature> SAFE_UNDERGROUND_STEPS = List.of(
            GenerationStep.Feature.RAW_GENERATION,
            GenerationStep.Feature.LOCAL_MODIFICATIONS,
            GenerationStep.Feature.UNDERGROUND_STRUCTURES,
            GenerationStep.Feature.UNDERGROUND_ORES,
            GenerationStep.Feature.UNDERGROUND_DECORATION);
    private static final List<GenerationStep.Feature> LEGACY_UNDERGROUND_STEPS = List.of(
            GenerationStep.Feature.RAW_GENERATION,
            GenerationStep.Feature.LAKES,
            GenerationStep.Feature.LOCAL_MODIFICATIONS,
            GenerationStep.Feature.UNDERGROUND_STRUCTURES,
            GenerationStep.Feature.UNDERGROUND_ORES,
            GenerationStep.Feature.UNDERGROUND_DECORATION,
            GenerationStep.Feature.FLUID_SPRINGS,
            GenerationStep.Feature.VEGETAL_DECORATION);
    private static final int MAX_FEATURE_BATCHES = 512;
    private static final int MAX_FEATURE_PLANS = 1_024;
    private static final int MAX_BIOME_QUERIES = 1_024;
    private static final Map<ServerWorld, FeatureCache> CACHES = new ConcurrentHashMap<>();

    private VanillaPlacedFeatureGenerator() {
    }

    static void release(ServerWorld world) {
        CACHES.remove(world);
    }

    /**
     * Returns the terrain-only read set needed before a 64-high feature batch
     * can run.  This is intentionally separate from the FEATURES dependency
     * radius: the lifecycle still exposes the normal 3 x 3 x 3 feature halo,
     * while the batched implementation additionally needs all four vertical
     * slices that it reads as one immutable band.
     */
    static List<CubePos> terrainBatchPositions(ServerWorld world, CubePos pos) {
        int depthIndex = world.getBottomSectionCoord() - 1 - pos.y();
        int sectionsPerBand = REPEATED_BAND_HEIGHT / CubePos.SIZE;
        int repeatedBand = Math.floorDiv(depthIndex, sectionsPerBand);
        int highestSection = world.getBottomSectionCoord() - 1
                - repeatedBand * sectionsPerBand;
        int lowestSection = highestSection - sectionsPerBand + 1;
        List<CubePos> result = new ArrayList<>(36);
        for (int sectionY = lowestSection; sectionY <= highestSection; sectionY++) {
            for (int chunkZ = pos.z() - FEATURE_ORIGIN_RADIUS;
                    chunkZ <= pos.z() + FEATURE_ORIGIN_RADIUS; chunkZ++) {
                for (int chunkX = pos.x() - FEATURE_ORIGIN_RADIUS;
                        chunkX <= pos.x() + FEATURE_ORIGIN_RADIUS; chunkX++) {
                    result.add(new CubePos(chunkX, sectionY, chunkZ));
                }
            }
        }
        return List.copyOf(result);
    }

    private static final class FeatureCache {
        private final LinkedHashMap<FeatureBatchKey, FeatureBatchSnapshot> batches =
                new LinkedHashMap<>(32, 0.75f, true);
        private final LinkedHashMap<FeaturePlanKey, List<FeatureCall>> plans =
                new LinkedHashMap<>(64, 0.75f, true);
        private final LinkedHashMap<BiomeQueryKey, List<RegistryEntry<Biome>>> biomes =
                new LinkedHashMap<>(64, 0.75f, true);

        private synchronized FeatureBatchSnapshot batch(
                FeatureBatchKey key, Supplier<FeatureBatchSnapshot> factory) {
            FeatureBatchSnapshot existing = batches.get(key);
            if (existing != null) return existing;
            FeatureBatchSnapshot created = factory.get();
            batches.put(key, created);
            trim(batches, MAX_FEATURE_BATCHES);
            return created;
        }

        private synchronized List<FeatureCall> plan(
                FeaturePlanKey key, Supplier<List<FeatureCall>> factory) {
            List<FeatureCall> existing = plans.get(key);
            if (existing != null) return existing;
            List<FeatureCall> created = List.copyOf(factory.get());
            plans.put(key, created);
            trim(plans, MAX_FEATURE_PLANS);
            return created;
        }

        private synchronized List<RegistryEntry<Biome>> biomes(
                BiomeQueryKey key, Supplier<List<RegistryEntry<Biome>>> factory) {
            List<RegistryEntry<Biome>> existing = biomes.get(key);
            if (existing != null) return existing;
            List<RegistryEntry<Biome>> created = List.copyOf(factory.get());
            biomes.put(key, created);
            trim(biomes, MAX_BIOME_QUERIES);
            return created;
        }

        private static <K, V> void trim(LinkedHashMap<K, V> values, int maximum) {
            while (values.size() > maximum) {
                values.remove(values.keySet().iterator().next());
            }
        }
    }

    private static final class FeatureBatchWriter {
        private final BlockBox virtualBand;
        private final int offsetY;
        private final Map<BlockPos, BlockState> writes = new java.util.LinkedHashMap<>();
        private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();

        private FeatureBatchWriter(BlockBox virtualBand, int offsetY) {
            this.virtualBand = virtualBand;
            this.offsetY = offsetY;
        }

        private BlockState read(ServerWorld world, BlockPos virtualPos) {
            BlockState written = writes.get(virtualPos);
            return written != null
                    ? written : world.getBlockState(translate(virtualPos, offsetY));
        }

        private void write(BlockPos virtualPos, BlockState state) {
            writes.put(virtualPos.toImmutable(), state);
        }

        private FeatureBatchSnapshot snapshot(ServerWorld world) {
            List<FeatureWrite> result = new ArrayList<>(writes.size());
            writes.forEach((pos, state) -> result.add(new FeatureWrite(pos, state)));
            List<FeatureBlockEntity> entities = new ArrayList<>(blockEntities.size());
            blockEntities.forEach((pos, blockEntity) -> {
                try {
                    entities.add(new FeatureBlockEntity(
                            pos, blockEntity.createNbtWithIdentifyingData(
                                    world.getRegistryManager())));
                } catch (RuntimeException ignored) {
                    // The state write is still valid; applyBatch will create
                    // the provider's default block entity as a safe fallback.
                }
            });
            return new FeatureBatchSnapshot(result, entities);
        }
    }

    private record FeatureBatchKey(int chunkX, int chunkZ, long repeatedBand, int stepsMask) {
    }

    private record FeaturePlanKey(int chunkX, int chunkZ, long repeatedBand, int stepsMask) {
    }

    private record BiomeQueryKey(int chunkX, int chunkZ, long repeatedBand) {
    }

    private record FeatureWrite(BlockPos pos, BlockState state) {
        private FeatureWrite {
            pos = pos.toImmutable();
        }
    }

    private record FeatureBlockEntity(BlockPos pos, NbtCompound nbt) {
        private FeatureBlockEntity {
            pos = pos.toImmutable();
            nbt = nbt.copy();
        }

        @Override
        public NbtCompound nbt() {
            return nbt.copy();
        }
    }

    private record FeatureBatchSnapshot(
            List<FeatureWrite> writes, List<FeatureBlockEntity> blockEntities) {
        private FeatureBatchSnapshot {
            writes = List.copyOf(writes);
            blockEntities = List.copyOf(blockEntities);
        }
    }

    static void generate(ServerWorld world, LoadedCube cube) {
        generate(world, cube, SAFE_UNDERGROUND_STEPS, BoundaryMode.TRANSLATED);
    }

    /** Reproduces the version-12 pass exactly enough to identify untouched cubes. */
    static void generateVersion12(ServerWorld world, LoadedCube cube) {
        generate(world, cube, LEGACY_UNDERGROUND_STEPS, BoundaryMode.VERSION_12);
    }

    private static void generate(
            ServerWorld world, LoadedCube cube, List<GenerationStep.Feature> steps,
            BoundaryMode boundaryMode) {
        if (boundaryMode == BoundaryMode.TRANSLATED) {
            generateBatched(world, cube, steps);
            return;
        }
        int depthIndex = world.getBottomSectionCoord() - 1 - cube.pos().y();
        int sectionsPerBand = REPEATED_BAND_HEIGHT / CubePos.SIZE;
        int virtualSectionIndex = sectionsPerBand - 1
                - Math.floorMod(depthIndex, sectionsPerBand);
        int virtualMinY = VANILLA_BOTTOM_Y
                + virtualSectionIndex * CubePos.SIZE;
        int offsetY = cube.pos().minBlockY() - virtualMinY;
        long repeatedBand = Math.floorDiv(depthIndex, sectionsPerBand);
        ChunkGenerator generator = world.getChunkManager().getChunkGenerator();
        Registry<PlacedFeature> registry = world.getRegistryManager()
                .getOrThrow(RegistryKeys.PLACED_FEATURE);
        long bandSeed = world.getSeed() ^ repeatedBand * 0xD1B54A32D192ED03L;
        StructureWorldAccess sharedAccess = boundaryMode == BoundaryMode.TRANSLATED
                ? translatedAccess(world, cube, virtualMinY, offsetY, boundaryMode)
                : null;

        // Vanilla decorates a chunk region and permits features to spill into
        // neighbouring chunks. Sparse cubes have no writable ChunkRegion, so
        // replay every nearby source chunk and retain only writes intersecting
        // this cube. Each cube reconstructs the same complete feature boundary.
        for (int chunkZ = cube.pos().z() - FEATURE_ORIGIN_RADIUS;
                chunkZ <= cube.pos().z() + FEATURE_ORIGIN_RADIUS; chunkZ++) {
            for (int chunkX = cube.pos().x() - FEATURE_ORIGIN_RADIUS;
                    chunkX <= cube.pos().x() + FEATURE_ORIGIN_RADIUS; chunkX++) {
                int originX = chunkX * CubePos.SIZE;
                int originZ = chunkZ * CubePos.SIZE;
                List<FeatureCall> features = collectFeatures(
                        world, originX, originZ, virtualMinY, generator, steps);
                StructureWorldAccess access = sharedAccess != null
                        ? sharedAccess
                        : translatedAccess(world, cube, virtualMinY, offsetY, boundaryMode);
                ChunkRandom random = new ChunkRandom(new Xoroshiro128PlusPlusRandom(
                        world.getSeed() ^ repeatedBand * 0x9E3779B97F4A7C15L));
                long populationSeed = random.setPopulationSeed(
                        bandSeed, originX, originZ);
                BlockPos origin = new BlockPos(originX, VANILLA_BOTTOM_Y, originZ);

                for (FeatureCall call : features) {
                    PlacedFeature feature = call.feature();
                    int registryId = registry.getRawId(feature);
                    int decoratorIndex = registryId >= 0 ? registryId : call.index();
                    random.setDecoratorSeed(
                            populationSeed, decoratorIndex, call.step().ordinal());
                    feature.generate(access, generator, random, origin);
                }
            }
        }
    }

    /**
     * Executes each source-chunk feature origin once for the whole repeated
     * 64-high band. The worker-like result is an immutable list of virtual
     * writes; the server thread clips and applies only the current 16-high
     * cube slice. Structure starts are intentionally not part of this cache.
     */
    private static void generateBatched(
            ServerWorld world, LoadedCube cube, List<GenerationStep.Feature> steps) {
        int depthIndex = world.getBottomSectionCoord() - 1 - cube.pos().y();
        int sectionsPerBand = REPEATED_BAND_HEIGHT / CubePos.SIZE;
        int virtualSectionIndex = sectionsPerBand - 1
                - Math.floorMod(depthIndex, sectionsPerBand);
        int virtualMinY = VANILLA_BOTTOM_Y + virtualSectionIndex * CubePos.SIZE;
        int offsetY = cube.pos().minBlockY() - virtualMinY;
        long repeatedBand = Math.floorDiv(depthIndex, sectionsPerBand);
        ChunkGenerator generator = world.getChunkManager().getChunkGenerator();
        Registry<PlacedFeature> registry = world.getRegistryManager()
                .getOrThrow(RegistryKeys.PLACED_FEATURE);
        long bandSeed = world.getSeed() ^ repeatedBand * 0xD1B54A32D192ED03L;
        int stepsMask = stepsMask(steps);
        FeatureCache cache = CACHES.computeIfAbsent(world, ignored -> new FeatureCache());

        // All four physical cubes in a repeated band share this offset.  Each
        // source origin is therefore safe to cache by (x,z,repeatedBand).
        for (int chunkZ = cube.pos().z() - FEATURE_ORIGIN_RADIUS;
                chunkZ <= cube.pos().z() + FEATURE_ORIGIN_RADIUS; chunkZ++) {
            for (int chunkX = cube.pos().x() - FEATURE_ORIGIN_RADIUS;
                    chunkX <= cube.pos().x() + FEATURE_ORIGIN_RADIUS; chunkX++) {
                FeatureBatchKey key = new FeatureBatchKey(
                        chunkX, chunkZ, repeatedBand, stepsMask);
                FeatureBatchSnapshot batch = cache.batch(key, () -> generateBatch(
                        world, generator, registry, cache, key, steps, bandSeed, offsetY));
                applyBatch(world, cube, batch, virtualMinY, offsetY);
            }
        }
    }

    private static FeatureBatchSnapshot generateBatch(
            ServerWorld world, ChunkGenerator generator, Registry<PlacedFeature> registry,
            FeatureCache cache, FeatureBatchKey key, List<GenerationStep.Feature> steps,
            long bandSeed, int offsetY) {
        FeatureBatchWriter writer = new FeatureBatchWriter(
                new BlockBox(key.chunkX() * CubePos.SIZE, VANILLA_BOTTOM_Y,
                        key.chunkZ() * CubePos.SIZE,
                        key.chunkX() * CubePos.SIZE + CubePos.SIZE - 1,
                        VANILLA_BOTTOM_Y + REPEATED_BAND_HEIGHT - 1,
                        key.chunkZ() * CubePos.SIZE + CubePos.SIZE - 1),
                offsetY);
        StructureWorldAccess access = translatedAccess(
                world, null, VANILLA_BOTTOM_Y, offsetY, BoundaryMode.TRANSLATED, writer);
        List<FeatureCall> features = cache.plan(
                new FeaturePlanKey(key.chunkX(), key.chunkZ(), key.repeatedBand(), key.stepsMask()),
                () -> collectFeatures(
                        world, key.chunkX() * CubePos.SIZE, key.chunkZ() * CubePos.SIZE,
                        VANILLA_BOTTOM_Y, generator, steps, cache,
                        new BiomeQueryKey(key.chunkX(), key.chunkZ(), key.repeatedBand())));
        ChunkRandom random = new ChunkRandom(new Xoroshiro128PlusPlusRandom(
                world.getSeed() ^ key.repeatedBand() * 0x9E3779B97F4A7C15L));
        int originX = key.chunkX() * CubePos.SIZE;
        int originZ = key.chunkZ() * CubePos.SIZE;
        long populationSeed = random.setPopulationSeed(bandSeed, originX, originZ);
        BlockPos origin = new BlockPos(originX, VANILLA_BOTTOM_Y, originZ);
        for (FeatureCall call : features) {
            int registryId = registry.getRawId(call.feature());
            int decoratorIndex = registryId >= 0 ? registryId : call.index();
            random.setDecoratorSeed(populationSeed, decoratorIndex, call.step().ordinal());
            call.feature().generate(access, generator, random, origin);
        }
        return writer.snapshot(world);
    }

    private static void applyBatch(
            ServerWorld world, LoadedCube cube, FeatureBatchSnapshot batch,
            int virtualMinY, int offsetY) {
        int minX = cube.pos().minBlockX();
        int minY = cube.pos().minBlockY();
        int minZ = cube.pos().minBlockZ();
        int maxX = minX + CubePos.SIZE - 1;
        int maxY = minY + CubePos.SIZE - 1;
        int maxZ = minZ + CubePos.SIZE - 1;
        for (FeatureWrite write : batch.writes()) {
            BlockPos virtual = write.pos();
            if (virtual.getY() < virtualMinY || virtual.getY() >= virtualMinY + CubePos.SIZE) {
                continue;
            }
            BlockPos actual = translate(virtual, offsetY);
            if (actual.getX() < minX || actual.getX() > maxX
                    || actual.getY() < minY || actual.getY() > maxY
                    || actual.getZ() < minZ || actual.getZ() > maxZ) {
                continue;
            }
            BlockState previous = cube.getBlockState(actual);
            BlockState state = write.state();
            cube.setGeneratedBlockState(
                    actual.getX() - minX, actual.getY() - minY, actual.getZ() - minZ, state);
            if (previous.hasBlockEntity() && !state.hasBlockEntity()) {
                cube.removeBlockEntity(actual);
            }
            if (state.hasBlockEntity() && cube.getBlockEntity(actual) == null
                    && state.getBlock() instanceof BlockEntityProvider provider) {
                BlockEntity blockEntity = provider.createBlockEntity(actual, state);
                if (blockEntity != null) {
                    blockEntity.setWorld(world);
                    cube.putLoadedBlockEntity(blockEntity);
                }
            }
        }
        for (FeatureBlockEntity featureBlockEntity : batch.blockEntities()) {
            BlockPos virtual = featureBlockEntity.pos();
            if (virtual.getY() < virtualMinY || virtual.getY() >= virtualMinY + CubePos.SIZE) {
                continue;
            }
            BlockPos actual = translate(virtual, offsetY);
            if (actual.getX() < minX || actual.getX() > maxX
                    || actual.getY() < minY || actual.getY() > maxY
                    || actual.getZ() < minZ || actual.getZ() > maxZ) {
                continue;
            }
            BlockState state = cube.getBlockState(actual);
            if (!state.hasBlockEntity()) continue;
            NbtCompound nbt = featureBlockEntity.nbt();
            nbt.putInt("x", actual.getX());
            nbt.putInt("y", actual.getY());
            nbt.putInt("z", actual.getZ());
            BlockEntity blockEntity = BlockEntity.createFromNbt(
                    actual, state, nbt, world.getRegistryManager());
            if (blockEntity != null) {
                blockEntity.setWorld(world);
                cube.putLoadedBlockEntity(blockEntity);
            }
        }
    }

    private static int stepsMask(List<GenerationStep.Feature> steps) {
        int mask = 0;
        for (GenerationStep.Feature step : steps) mask |= 1 << step.ordinal();
        return mask;
    }

    private static List<FeatureCall> collectFeatures(
            ServerWorld world, int baseX, int baseZ, int virtualMinY,
            ChunkGenerator generator, List<GenerationStep.Feature> allowedSteps) {
        return collectFeatures(
                world, baseX, baseZ, virtualMinY, generator, allowedSteps, null, null);
    }

    private static List<FeatureCall> collectFeatures(
            ServerWorld world, int baseX, int baseZ, int virtualMinY,
            ChunkGenerator generator, List<GenerationStep.Feature> allowedSteps,
            FeatureCache cache, BiomeQueryKey biomeKey) {
        List<RegistryEntry<Biome>> biomes = cache == null
                ? collectBiomes(world, baseX, baseZ, virtualMinY)
                : cache.biomes(biomeKey,
                        () -> collectBiomes(world, baseX, baseZ, virtualMinY));

        List<FeatureCall> result = new ArrayList<>();
        Set<PlacedFeature> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (RegistryEntry<Biome> biome : biomes) {
            GenerationSettings settings = generator.getGenerationSettings(biome);
            List<RegistryEntryList<PlacedFeature>> steps = settings.getFeatures();
            for (GenerationStep.Feature step : allowedSteps) {
                int stepIndex = step.ordinal();
                if (stepIndex >= steps.size()) {
                    continue;
                }
                RegistryEntryList<PlacedFeature> entries = steps.get(stepIndex);
                for (int index = 0; index < entries.size(); index++) {
                    PlacedFeature feature = entries.get(index).value();
                    if (seen.add(feature)) {
                        result.add(new FeatureCall(step, index, feature));
                    }
                }
            }
        }
        return result;
    }

    private static List<RegistryEntry<Biome>> collectBiomes(
            ServerWorld world, int baseX, int baseZ, int virtualMinY) {
        Set<RegistryEntry<Biome>> biomes = new LinkedHashSet<>();
        // A repeated band replaces four old 16-high passes. Preserve the
        // vertical biome coverage of those passes before caching the union.
        for (int sectionOffset = 0; sectionOffset < REPEATED_BAND_HEIGHT;
                sectionOffset += CubePos.SIZE) {
            for (int z = 2; z < CubePos.SIZE; z += 4) {
                for (int x = 2; x < CubePos.SIZE; x += 4) {
                    biomes.add(world.getBiome(new BlockPos(
                            baseX + x,
                            virtualMinY + sectionOffset + CubePos.SIZE / 2,
                            baseZ + z)));
                }
            }
        }
        return List.copyOf(biomes);
    }

    private static StructureWorldAccess translatedAccess(
            ServerWorld world, LoadedCube cube, int virtualMinY, int offsetY,
            BoundaryMode boundaryMode) {
        return translatedAccess(world, cube, virtualMinY, offsetY, boundaryMode, null);
    }

    private static StructureWorldAccess translatedAccess(
            ServerWorld world, LoadedCube cube, int virtualMinY, int offsetY,
            BoundaryMode boundaryMode, FeatureBatchWriter batchWriter) {
        BlockBox virtualCube = new BlockBox(
                batchWriter == null ? cube.pos().minBlockX() : batchWriter.virtualBand.getMinX(),
                batchWriter == null ? virtualMinY : batchWriter.virtualBand.getMinY(),
                batchWriter == null ? cube.pos().minBlockZ() : batchWriter.virtualBand.getMinZ(),
                batchWriter == null
                        ? cube.pos().minBlockX() + CubePos.SIZE - 1
                        : batchWriter.virtualBand.getMaxX(),
                batchWriter == null
                        ? virtualMinY + CubePos.SIZE - 1
                        : batchWriter.virtualBand.getMaxY(),
                batchWriter == null
                        ? cube.pos().minBlockZ() + CubePos.SIZE - 1
                        : batchWriter.virtualBand.getMaxZ());
        Map<Long, Chunk> featureChunks = new HashMap<>();
        Map<BlockPos, BlockState> clippedBlockStates = new HashMap<>();
        Map<BlockPos, BlockEntity> clippedBlockEntities = new HashMap<>();
        return (StructureWorldAccess) Proxy.newProxyInstance(
                VanillaPlacedFeatureGenerator.class.getClassLoader(),
                new Class<?>[] {StructureWorldAccess.class},
                (proxy, method, arguments) -> invoke(
                        proxy, world, cube, virtualCube, virtualMinY, offsetY,
                        boundaryMode, featureChunks, clippedBlockStates,
                        clippedBlockEntities, batchWriter,
                        method, arguments));
    }

    @SuppressWarnings("unchecked")
    private static Object invoke(
            Object proxy, ServerWorld world, LoadedCube cube, BlockBox virtualCube,
            int virtualMinY, int offsetY, BoundaryMode boundaryMode,
            Map<Long, Chunk> featureChunks, Map<BlockPos, BlockState> clippedBlockStates,
            Map<BlockPos, BlockEntity> clippedBlockEntities,
            FeatureBatchWriter batchWriter,
            Method method, Object[] arguments) throws Throwable {
        String name = method.getName();
        if (boundaryMode != BoundaryMode.VERSION_12 && "getChunk".equals(name)
                && arguments != null && arguments.length >= 1
                && arguments[0] instanceof BlockPos pos) {
            int chunkX = Math.floorDiv(pos.getX(), CubePos.SIZE);
            int chunkZ = Math.floorDiv(pos.getZ(), CubePos.SIZE);
            long key = ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
            return featureChunks.computeIfAbsent(key, ignored ->
                    createFeatureChunk(world, cube, virtualCube, virtualMinY, offsetY,
                            boundaryMode, clippedBlockStates, batchWriter, chunkX, chunkZ));
        }
        if (boundaryMode != BoundaryMode.VERSION_12
                && ("getChunk".equals(name) || "getChunkAsView".equals(name))
                && arguments != null && arguments.length >= 2
                && arguments[0] instanceof Integer chunkX
                && arguments[1] instanceof Integer chunkZ) {
            long key = ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
            return featureChunks.computeIfAbsent(key, ignored ->
                    createFeatureChunk(world, cube, virtualCube, virtualMinY, offsetY,
                            boundaryMode, clippedBlockStates, batchWriter, chunkX, chunkZ));
        }
        if (boundaryMode != BoundaryMode.VERSION_12 && "getTopY".equals(name)
                && arguments != null && arguments.length == 3
                && arguments[1] instanceof Integer x && arguments[2] instanceof Integer z) {
            return getVirtualTopY(world, cube, virtualCube, offsetY,
                    boundaryMode, clippedBlockStates, batchWriter, x, z);
        }
        if (boundaryMode != BoundaryMode.VERSION_12 && "getTopY".equals(name)
                && arguments != null && arguments.length == 2
                && arguments[1] instanceof BlockPos pos) {
            return getVirtualTopY(world, cube, virtualCube, offsetY,
                    boundaryMode, clippedBlockStates, batchWriter, pos.getX(), pos.getZ());
        }
        if (boundaryMode != BoundaryMode.VERSION_12 && "getTopPosition".equals(name)
                && arguments != null && arguments.length == 2
                && arguments[1] instanceof BlockPos pos) {
            return new BlockPos(pos.getX(),
                    getVirtualTopY(world, cube, virtualCube, offsetY,
                            boundaryMode, clippedBlockStates, batchWriter, pos.getX(), pos.getZ()),
                    pos.getZ());
        }
        BlockPos virtualPos = firstPos(arguments);
        if ("setBlockState".equals(name) && virtualPos != null
                && arguments.length >= 2 && arguments[1] instanceof BlockState state) {
            if (state.isOf(Blocks.SCULK_VEIN)) {
                state = removeUnsupportedSculkFaces((BlockView) proxy, virtualPos, state);
            }
            if (batchWriter != null) {
                BlockPos stablePos = virtualPos.toImmutable();
                BlockState previous = batchWriter.read(world, stablePos);
                batchWriter.write(stablePos, state);
                updateFeatureChunk(featureChunks, stablePos, state);
                if (state.hasBlockEntity() && state.getBlock() instanceof BlockEntityProvider provider) {
                    BlockEntity blockEntity = provider.createBlockEntity(stablePos, state);
                    if (blockEntity != null) batchWriter.blockEntities.put(stablePos, blockEntity);
                } else {
                    batchWriter.blockEntities.remove(stablePos);
                }
                return !previous.equals(state);
            }
            if (!virtualCube.contains(virtualPos)) {
                // Features from the eight neighbouring origins are replayed so
                // their writes can spill into this cube. Keep writes outside the
                // target in a tiny per-origin scratch view: generators such as
                // DungeonFeature immediately fetch and configure the block
                // entity they just placed, and returning false/null otherwise
                // emits an error for every replay.
                if (boundaryMode == BoundaryMode.TRANSLATED) {
                    BlockPos stablePos = virtualPos.toImmutable();
                    BlockState previous = featureBlockState(
                            world, cube, virtualCube, offsetY, BoundaryMode.TRANSLATED,
                            clippedBlockStates, stablePos);
                    clippedBlockStates.put(stablePos, state);
                    updateFeatureChunk(featureChunks, stablePos, state);
                    if (state.hasBlockEntity() && state.getBlock() instanceof BlockEntityProvider provider) {
                        BlockEntity blockEntity = provider.createBlockEntity(stablePos, state);
                        if (blockEntity != null) clippedBlockEntities.put(stablePos, blockEntity);
                    } else {
                        clippedBlockEntities.remove(stablePos);
                    }
                    return !previous.equals(state);
                }
                if (state.hasBlockEntity() && state.getBlock() instanceof BlockEntityProvider provider) {
                    BlockPos stablePos = virtualPos.toImmutable();
                    BlockEntity blockEntity = provider.createBlockEntity(stablePos, state);
                    if (blockEntity != null) clippedBlockEntities.put(stablePos, blockEntity);
                } else {
                    clippedBlockEntities.remove(virtualPos);
                }
                return true;
            }
            BlockPos actualPos = translate(virtualPos, offsetY);
            BlockState previous = cube.getBlockState(actualPos);
            cube.setGeneratedBlockState(
                    actualPos.getX() - cube.pos().minBlockX(),
                    actualPos.getY() - cube.pos().minBlockY(),
                    actualPos.getZ() - cube.pos().minBlockZ(), state);
            if (previous.hasBlockEntity() && !state.hasBlockEntity()) {
                cube.removeBlockEntity(actualPos);
            }
            if (state.hasBlockEntity() && cube.getBlockEntity(actualPos) == null
                    && state.getBlock() instanceof BlockEntityProvider provider) {
                BlockEntity blockEntity = provider.createBlockEntity(actualPos, state);
                if (blockEntity != null) {
                    blockEntity.setWorld(world);
                    cube.putLoadedBlockEntity(blockEntity);
                }
            }
            return !previous.equals(state);
        }
        if ("getBlockState".equals(name) && virtualPos != null) {
            return batchWriter == null
                    ? featureBlockState(world, cube, virtualCube, offsetY,
                            boundaryMode, clippedBlockStates, virtualPos)
                    : batchWriter.read(world, virtualPos);
        }
        if ("getFluidState".equals(name) && virtualPos != null) {
            return (batchWriter == null
                    ? featureFluidState(world, cube, virtualCube, offsetY,
                            boundaryMode, clippedBlockStates, virtualPos)
                    : batchWriter.read(world, virtualPos).getFluidState());
        }
        if (batchWriter != null && "getBlockEntity".equals(name)
                && virtualPos != null) {
            BlockEntity blockEntity = batchWriter.blockEntities.get(virtualPos);
            if (blockEntity != null) {
                if (arguments.length == 2
                        && arguments[1] instanceof net.minecraft.block.entity.BlockEntityType<?> type) {
                    return blockEntity.getType() == type ? Optional.of(blockEntity) : Optional.empty();
                }
                return blockEntity;
            }
            BlockEntity actual = world.getBlockEntity(translate(virtualPos, offsetY));
            if (arguments.length == 2
                    && arguments[1] instanceof net.minecraft.block.entity.BlockEntityType<?> type) {
                return actual != null && actual.getType() == type
                        ? Optional.of(actual) : Optional.empty();
            }
            return actual;
        }
        if ("getBlockEntity".equals(name) && virtualPos != null && virtualCube.contains(virtualPos)) {
            BlockEntity blockEntity = cube.getBlockEntity(translate(virtualPos, offsetY));
            if (arguments.length == 2) {
                return blockEntity != null && arguments[1] instanceof net.minecraft.block.entity.BlockEntityType<?> type
                        && blockEntity.getType() == type ? Optional.of(blockEntity) : Optional.empty();
            }
            return blockEntity;
        }
        if ("getBlockEntity".equals(name) && virtualPos != null
                && !virtualCube.contains(virtualPos)) {
            BlockEntity clipped = clippedBlockEntities.get(virtualPos);
            if (clipped != null) {
                if (arguments.length == 2) {
                    return arguments[1] instanceof net.minecraft.block.entity.BlockEntityType<?> type
                            && clipped.getType() == type ? Optional.of(clipped) : Optional.empty();
                }
                return clipped;
            }
            if (isInVanillaHeight(virtualPos)) {
                BlockPos lookupPos = boundaryMode == BoundaryMode.TRANSLATED
                        ? translate(virtualPos, offsetY) : virtualPos;
                if (arguments.length == 2) {
                    BlockEntity blockEntity = world.getBlockEntity(lookupPos);
                    return blockEntity != null
                            && arguments[1] instanceof net.minecraft.block.entity.BlockEntityType<?> type
                            && blockEntity.getType() == type
                            ? Optional.of(blockEntity) : Optional.empty();
                }
                return world.getBlockEntity(lookupPos);
            }
            return arguments.length == 2 ? Optional.empty() : null;
        }
        if ("testBlockState".equals(name) && virtualPos != null
                && arguments[1] instanceof Predicate<?> predicate) {
            BlockState state = batchWriter == null
                    ? featureBlockState(world, cube, virtualCube, offsetY,
                            boundaryMode, clippedBlockStates, virtualPos)
                    : batchWriter.read(world, virtualPos);
            return ((Predicate<BlockState>) predicate).test(state);
        }
        if ("testFluidState".equals(name) && virtualPos != null
                && arguments[1] instanceof Predicate<?> predicate) {
            FluidState state = batchWriter == null
                    ? featureFluidState(world, cube, virtualCube, offsetY,
                            boundaryMode, clippedBlockStates, virtualPos)
                    : batchWriter.read(world, virtualPos).getFluidState();
            return ((Predicate<FluidState>) predicate).test(state);
        }
        if (("removeBlock".equals(name) || "breakBlock".equals(name)) && virtualPos != null) {
            if (batchWriter != null) {
                BlockPos stablePos = virtualPos.toImmutable();
                BlockState previous = batchWriter.read(world, stablePos);
                batchWriter.write(stablePos, Blocks.AIR.getDefaultState());
                batchWriter.blockEntities.remove(stablePos);
                updateFeatureChunk(featureChunks, stablePos, Blocks.AIR.getDefaultState());
                return !previous.isAir();
            }
            if (!virtualCube.contains(virtualPos)) {
                if (boundaryMode != BoundaryMode.TRANSLATED) return false;
                BlockPos stablePos = virtualPos.toImmutable();
                BlockState previous = featureBlockState(
                        world, cube, virtualCube, offsetY, BoundaryMode.TRANSLATED,
                        clippedBlockStates, stablePos);
                clippedBlockStates.put(stablePos, Blocks.AIR.getDefaultState());
                clippedBlockEntities.remove(stablePos);
                updateFeatureChunk(featureChunks, stablePos, Blocks.AIR.getDefaultState());
                return !previous.isAir();
            }
            BlockPos actualPos = translate(virtualPos, offsetY);
            cube.removeBlockEntity(actualPos);
            cube.setGeneratedBlockState(
                    actualPos.getX() - cube.pos().minBlockX(),
                    actualPos.getY() - cube.pos().minBlockY(),
                    actualPos.getZ() - cube.pos().minBlockZ(),
                    net.minecraft.block.Blocks.AIR.getDefaultState());
            return true;
        }
        if ("spawnEntity".equals(name)) {
            // Sparse-cube entities need a dedicated translated entity tracker;
            // never leak a virtual-height feature entity into the vanilla world.
            return false;
        }
        if (("isValidForSetBlock".equals(name) || "isInBuildLimit".equals(name)
                || "isInLoadLimit".equals(name)) && virtualPos != null) {
            // The feature may be centred in an adjacent 16-block section while
            // still intersecting this target cube. Limit only by the virtual
            // world's height; setBlockState remains clipped to the target cube.
            return isInVanillaHeight(virtualPos);
        }
        if ("markBlockForPostProcessing".equals(name) || "markForPostProcessing".equals(name)) {
            // ProtoChunk post-processing lists do not exist for sparse cubes.
            // Forwarding this to the vanilla ChunkRegion only emits warnings
            // and records a position in the unrelated virtual-height chunk.
            return null;
        }
        if ("getBottomY".equals(name)) {
            return VANILLA_BOTTOM_Y;
        }
        if ("getHeight".equals(name)) {
            return VANILLA_HEIGHT;
        }
        if ("getTopYInclusive".equals(name)) {
            return VANILLA_BOTTOM_Y + VANILLA_HEIGHT - 1;
        }
        if ("getBottomSectionCoord".equals(name)) {
            return Math.floorDiv(VANILLA_BOTTOM_Y, CubePos.SIZE);
        }
        if ("getTopSectionCoord".equals(name)) {
            return Math.floorDiv(VANILLA_BOTTOM_Y + VANILLA_HEIGHT, CubePos.SIZE);
        }
        if ("countVerticalSections".equals(name)) {
            return VANILLA_HEIGHT / CubePos.SIZE;
        }
        if ("isInHeightLimit".equals(name) && arguments != null && arguments.length == 1) {
            int y = arguments[0] instanceof BlockPos pos ? pos.getY() : (int) arguments[0];
            return y >= VANILLA_BOTTOM_Y && y < VANILLA_BOTTOM_Y + VANILLA_HEIGHT;
        }
        if ("isOutOfHeightLimit".equals(name) && arguments != null && arguments.length == 1) {
            int y = arguments[0] instanceof BlockPos pos ? pos.getY() : (int) arguments[0];
            return y < VANILLA_BOTTOM_Y || y >= VANILLA_BOTTOM_Y + VANILLA_HEIGHT;
        }
        if (("scheduleBlockTick".equals(name) || "scheduleFluidTick".equals(name)
                || "scheduleTick".equals(name)) && virtualPos != null) {
            return null;
        }
        try {
            return method.invoke(world, arguments);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    /**
     * Sparse cubes do not have ProtoChunk's post-processing pass. Validate
     * sculk-vein faces as they are written so a clipped neighbouring feature
     * cannot leave an unsupported sheet at a cube boundary.
     */
    private static BlockState removeUnsupportedSculkFaces(
            BlockView world, BlockPos pos, BlockState state) {
        for (Direction direction : Direction.values()) {
            if (MultifaceBlock.hasDirection(state, direction)
                    && !MultifaceBlock.canGrowOn(world, pos, direction)) {
                state = state.with(MultifaceBlock.getProperty(direction), false);
            }
        }
        return !MultifaceBlock.collectDirections(state).isEmpty()
                ? state : state.getFluidState().getBlockState();
    }

    private static BlockPos firstPos(Object[] arguments) {
        return arguments != null && arguments.length > 0 && arguments[0] instanceof BlockPos pos
                ? pos : null;
    }

    private static Chunk createFeatureChunk(
            ServerWorld world, LoadedCube cube, BlockBox virtualCube,
            int virtualMinY, int offsetY, BoundaryMode boundaryMode,
            Map<BlockPos, BlockState> clippedBlockStates,
            FeatureBatchWriter batchWriter,
            int chunkX, int chunkZ) {
        HeightLimitView height = HeightLimitView.create(VANILLA_BOTTOM_Y, VANILLA_HEIGHT);
        ProtoChunk chunk = new ProtoChunk(
                new ChunkPos(chunkX, chunkZ), UpgradeData.NO_UPGRADE_DATA,
                height, world.getPalettesFactory(), null);
        if (batchWriter != null) {
            int firstSection = Math.floorDiv(batchWriter.virtualBand.getMinY() - VANILLA_BOTTOM_Y,
                    CubePos.SIZE);
            int lastSection = Math.floorDiv(batchWriter.virtualBand.getMaxY() - VANILLA_BOTTOM_Y,
                    CubePos.SIZE);
            BlockPos.Mutable mutable = new BlockPos.Mutable();
            for (int sectionIndex = firstSection; sectionIndex <= lastSection; sectionIndex++) {
                net.minecraft.world.chunk.ChunkSection section = chunk.getSectionArray()[sectionIndex];
                int sectionMinY = VANILLA_BOTTOM_Y + sectionIndex * CubePos.SIZE;
                for (int localY = 0; localY < CubePos.SIZE; localY++) {
                    for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
                        for (int localX = 0; localX < CubePos.SIZE; localX++) {
                            mutable.set(chunkX * CubePos.SIZE + localX,
                                    sectionMinY + localY, chunkZ * CubePos.SIZE + localZ);
                            section.setBlockState(localX, localY, localZ,
                                    batchWriter.read(world, mutable));
                        }
                    }
                }
            }
        } else if (chunkX == cube.pos().x() && chunkZ == cube.pos().z()) {
            int sectionIndex = Math.floorDiv(virtualMinY - VANILLA_BOTTOM_Y, CubePos.SIZE);
            chunk.getSectionArray()[sectionIndex] = cube.section();
        } else if (boundaryMode == BoundaryMode.TRANSLATED) {
            int sectionIndex = Math.floorDiv(virtualMinY - VANILLA_BOTTOM_Y, CubePos.SIZE);
            net.minecraft.world.chunk.ChunkSection section = chunk.getSectionArray()[sectionIndex];
            BlockPos.Mutable mutable = new BlockPos.Mutable();
            for (int localY = 0; localY < CubePos.SIZE; localY++) {
                for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
                    for (int localX = 0; localX < CubePos.SIZE; localX++) {
                        mutable.set(chunkX * CubePos.SIZE + localX, virtualMinY + localY,
                                chunkZ * CubePos.SIZE + localZ);
                        section.setBlockState(localX, localY, localZ,
                                featureBlockState(world, cube, virtualCube, offsetY,
                                        BoundaryMode.TRANSLATED, clippedBlockStates, mutable));
                    }
                }
            }
        }
        return chunk;
    }

    private static void updateFeatureChunk(
            Map<Long, Chunk> featureChunks, BlockPos pos, BlockState state) {
        int chunkX = Math.floorDiv(pos.getX(), CubePos.SIZE);
        int chunkZ = Math.floorDiv(pos.getZ(), CubePos.SIZE);
        long key = ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
        Chunk chunk = featureChunks.get(key);
        if (chunk == null || !isInVanillaHeight(pos)) return;
        int sectionIndex = Math.floorDiv(pos.getY() - VANILLA_BOTTOM_Y, CubePos.SIZE);
        if (sectionIndex < 0 || sectionIndex >= chunk.getSectionArray().length) return;
        chunk.getSectionArray()[sectionIndex].setBlockState(
                Math.floorMod(pos.getX(), CubePos.SIZE),
                Math.floorMod(pos.getY() - VANILLA_BOTTOM_Y, CubePos.SIZE),
                Math.floorMod(pos.getZ(), CubePos.SIZE), state);
    }

    private static BlockState featureBlockState(
            ServerWorld world, LoadedCube cube, BlockBox virtualCube, int offsetY,
            BoundaryMode boundaryMode, Map<BlockPos, BlockState> clippedBlockStates,
            BlockPos virtualPos) {
        if (virtualCube.contains(virtualPos)) {
            return cube.getBlockState(translate(virtualPos, offsetY));
        }
        if (boundaryMode == BoundaryMode.TRANSLATED) {
            BlockState clipped = clippedBlockStates.get(virtualPos);
            return clipped != null
                    ? clipped : world.getBlockState(translate(virtualPos, offsetY));
        }
        return isInVanillaHeight(virtualPos)
                ? world.getBlockState(virtualPos) : Blocks.AIR.getDefaultState();
    }

    private static FluidState featureFluidState(
            ServerWorld world, LoadedCube cube, BlockBox virtualCube, int offsetY,
            BoundaryMode boundaryMode, Map<BlockPos, BlockState> clippedBlockStates,
            BlockPos virtualPos) {
        if (virtualCube.contains(virtualPos)) {
            return cube.getFluidState(translate(virtualPos, offsetY));
        }
        if (boundaryMode == BoundaryMode.TRANSLATED) {
            BlockState clipped = clippedBlockStates.get(virtualPos);
            return clipped != null
                    ? clipped.getFluidState() : world.getFluidState(translate(virtualPos, offsetY));
        }
        return isInVanillaHeight(virtualPos)
                ? world.getFluidState(virtualPos) : Fluids.EMPTY.getDefaultState();
    }

    private static BlockPos translate(BlockPos pos, int offsetY) {
        return new BlockPos(pos.getX(), pos.getY() + offsetY, pos.getZ());
    }

    private static int getVirtualTopY(
            ServerWorld world, LoadedCube cube, BlockBox virtualCube,
            int offsetY, BoundaryMode boundaryMode,
            Map<BlockPos, BlockState> clippedBlockStates,
            FeatureBatchWriter batchWriter,
            int blockX, int blockZ) {
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int y = virtualCube.getMaxY(); y >= virtualCube.getMinY(); y--) {
            mutable.set(blockX, y, blockZ);
            BlockState state = batchWriter == null
                    ? featureBlockState(world, cube, virtualCube, offsetY,
                            boundaryMode, clippedBlockStates, mutable)
                    : batchWriter.read(world, mutable);
            if (!state.isAir()) {
                return y + 1;
            }
        }
        return virtualCube.getMinY();
    }

    private static boolean isInVanillaHeight(BlockPos pos) {
        return pos.getY() >= VANILLA_BOTTOM_Y
                && pos.getY() < VANILLA_BOTTOM_Y + VANILLA_HEIGHT;
    }

    private record FeatureCall(GenerationStep.Feature step, int index, PlacedFeature feature) {
    }

    private enum BoundaryMode {
        VERSION_12,
        TRANSLATED
    }
}
