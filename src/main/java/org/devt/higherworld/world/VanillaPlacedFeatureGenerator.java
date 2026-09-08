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
    /** Monster-room placement counts are tuned for a full-height vanilla chunk. */
    private static final int DUNGEON_REPETITION_BANDS = 4; // 4 x 64 = 256 blocks
    /** Large dripstone searches up to 30 blocks across a band boundary. */
    private static final int FEATURE_VERTICAL_HALO = 32;
    private static final int FEATURE_SOURCE_BAND_RADIUS = 1;
    private static final int FEATURE_TERRAIN_BAND_RADIUS = 2;
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
    // Feature batches are keyed by source chunk and repeated 64-high band.
    // 512 entries covered less than one 33x33 horizontal view and made a
    // vertical scan replay the same vanilla features continuously.  Keep a
    // bounded LRU window large enough for the nearby source bands without
    // allowing an unbounded deep-world cache.
    private static final int MAX_FEATURE_BATCHES = 2_048;
    private static final int MAX_FEATURE_PLANS = 4_096;
    private static final int MAX_BIOME_QUERIES = 4_096;
    private static final Map<ServerWorld, FeatureCache> CACHES = new ConcurrentHashMap<>();

    private VanillaPlacedFeatureGenerator() {
    }

    static void release(ServerWorld world) {
        CACHES.remove(world);
    }

    private static int sectionsPerBand() {
        return REPEATED_BAND_HEIGHT / CubePos.SIZE;
    }

    private static long depthIndex(ServerWorld world, CubePos pos) {
        return (long) world.getBottomSectionCoord() - 1L - pos.y();
    }

    private static long repeatedBand(ServerWorld world, CubePos pos) {
        return Math.floorDiv(depthIndex(world, pos), sectionsPerBand());
    }

    private static int highestSection(
            ServerWorld world, long repeatedBand, int sectionsPerBand) {
        long section = (long) world.getBottomSectionCoord() - 1L
                - repeatedBand * sectionsPerBand;
        return Math.toIntExact(section);
    }

    private static int bandOffsetY(
            ServerWorld world, long repeatedBand, int sectionsPerBand) {
        int highestSection = highestSection(world, repeatedBand, sectionsPerBand);
        int lowestSection = highestSection - sectionsPerBand + 1;
        return lowestSection * CubePos.SIZE - VANILLA_BOTTOM_Y;
    }

    /**
     * Returns the terrain-only read set needed before a feature batch and its
     * vertical spill halo can run. This is intentionally separate from the
     * FEATURES dependency radius: batches read neighbouring source bands but
     * only mutate the cube whose FEATURES stage is being committed.
     */
    static List<CubePos> terrainBatchPositions(ServerWorld world, CubePos pos) {
        long repeatedBand = repeatedBand(world, pos);
        int sectionsPerBand = sectionsPerBand();
        List<CubePos> result = new ArrayList<>(180);
        for (long sourceBand = repeatedBand - FEATURE_TERRAIN_BAND_RADIUS;
                sourceBand <= repeatedBand + FEATURE_TERRAIN_BAND_RADIUS; sourceBand++) {
            int highestSection = highestSection(world, sourceBand, sectionsPerBand);
            int lowestSection = highestSection - sectionsPerBand + 1;
            for (int sectionY = lowestSection; sectionY <= highestSection; sectionY++) {
                // The vanilla band is already owned by Minecraft's normal
                // chunk pipeline.  It is still a valid feature source, but it
                // must not be requested as a sparse terrain dependency.
                if (sectionY >= world.getBottomSectionCoord()) continue;
                for (int chunkZ = pos.z() - FEATURE_ORIGIN_RADIUS;
                        chunkZ <= pos.z() + FEATURE_ORIGIN_RADIUS; chunkZ++) {
                    for (int chunkX = pos.x() - FEATURE_ORIGIN_RADIUS;
                            chunkX <= pos.x() + FEATURE_ORIGIN_RADIUS; chunkX++) {
                        result.add(new CubePos(chunkX, sectionY, chunkZ));
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    private static final class FeatureCache {
        private final LinkedHashMap<FeatureBatchKey, FeatureBatchSnapshot> batches =
                new LinkedHashMap<>(256, 0.75f, true);
        private final LinkedHashMap<FeaturePlanKey, List<FeatureCall>> plans =
                new LinkedHashMap<>(256, 0.75f, true);
        private final LinkedHashMap<BiomeQueryKey, List<RegistryEntry<Biome>>> biomes =
                new LinkedHashMap<>(256, 0.75f, true);

        private synchronized FeatureBatchSnapshot batch(
                FeatureBatchKey key, Supplier<FeatureBatchSnapshot> factory) {
            FeatureBatchSnapshot existing = batches.get(key);
            if (existing != null) return existing;
            FeatureBatchSnapshot created = factory.get();
            batches.put(key, created);
            trim(batches, MAX_FEATURE_BATCHES);
            return created;
        }

        /** Non-computing peek used by the sliced commit to decide the deadline gate. */
        private synchronized FeatureBatchSnapshot batchIfPresent(FeatureBatchKey key) {
            return batches.get(key);
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
        /** Reads of cubes that are not loaded (or below TERRAIN) are air. */
        private static final BlockState[] AIR_SECTION = new BlockState[CubePos.SIZE * CubePos.SIZE * CubePos.SIZE];
        static {
            java.util.Arrays.fill(AIR_SECTION, Blocks.AIR.getDefaultState());
        }
        private final ServerWorld world;
        private final BlockBox virtualBand;
        private final int offsetY;
        private final Map<BlockPos, BlockState> writes = new java.util.LinkedHashMap<>();
        private final Map<BlockPos, BlockEntity> blockEntities = new HashMap<>();
        /**
         * Lazily copied sparse sections under the virtual band.  Features
         * probe tens of thousands of terrain positions while a fresh batch is
         * generated; serving those probes from a snapshot removes one
         * ServerWorld/palette dispatch per probe.  Missing cubes use the
         * shared air section so the snapshot cache stays total.
         */
        private final Map<CubePos, BlockState[]> terrainSnapshots = new HashMap<>();

        private FeatureBatchWriter(ServerWorld world, BlockBox virtualBand, int offsetY) {
            this.world = world;
            this.virtualBand = virtualBand;
            this.offsetY = offsetY;
        }

        private BlockState read(BlockPos virtualPos) {
            BlockState written = writes.get(virtualPos);
            if (written != null) return written;
            int actualX = virtualPos.getX();
            int actualY = virtualPos.getY() + offsetY;
            int actualZ = virtualPos.getZ();
            if (actualY < world.getBottomY()) {
                CubePos cubePos = CubePos.fromBlock(actualX, actualY, actualZ);
                BlockState[] snapshot = terrainSnapshots.get(cubePos);
                if (snapshot == null) {
                    BlockState[] copied = CubicWorldManager.snapshotCubeSection(world, cubePos);
                    snapshot = copied == null ? AIR_SECTION : copied;
                    terrainSnapshots.put(cubePos, snapshot);
                }
                return snapshot[(Math.floorMod(actualY, CubePos.SIZE) * CubePos.SIZE
                        + Math.floorMod(actualZ, CubePos.SIZE)) * CubePos.SIZE
                        + Math.floorMod(actualX, CubePos.SIZE)];
            }
            return world.getBlockState(new BlockPos(actualX, actualY, actualZ));
        }

        private boolean acceptsY(int y) {
            return y >= virtualBand.getMinY() && y <= virtualBand.getMaxY();
        }

        private void write(BlockPos virtualPos, BlockState state) {
            writes.put(virtualPos.toImmutable(), state);
        }

        private FeatureBatchSnapshot snapshot(ServerWorld world) {
            Map<CubePos, List<FeatureWrite>> writesByCube = new HashMap<>();
            writes.forEach((pos, state) -> {
                FeatureWrite write = new FeatureWrite(pos, state);
                writesByCube.computeIfAbsent(
                        CubePos.fromBlock(pos.getX(), pos.getY(), pos.getZ()),
                        ignored -> new ArrayList<>()).add(write);
            });
            Map<CubePos, List<FeatureBlockEntity>> entitiesByCube = new HashMap<>();
            blockEntities.forEach((pos, blockEntity) -> {
                try {
                    FeatureBlockEntity entity = new FeatureBlockEntity(
                            pos, blockEntity.createNbtWithIdentifyingData(
                                    world.getRegistryManager()));
                    entitiesByCube.computeIfAbsent(
                            CubePos.fromBlock(pos.getX(), pos.getY(), pos.getZ()),
                            ignored -> new ArrayList<>()).add(entity);
                } catch (RuntimeException ignored) {
                    // The state write is still valid; applyBatch will create
                    // the provider's default block entity as a safe fallback.
                }
            });
            return new FeatureBatchSnapshot(writesByCube, entitiesByCube);
        }
    }

    /**
     * OreFeature writes straight into the section returned by
     * ChunkSectionCache, bypassing StructureWorldAccess#setBlockState. Keep
     * that scratch section connected to the batch write set so those writes
     * survive until the target cube is committed.
     *
     * <p>The section is deliberately lazy: instead of pre-filling 8 x 4096
     * blocks per feature batch key (the old hot path), reads are served on
     * demand from the batch writer and the palette only accumulates the few
     * states a feature actually places.</p>
     */
    private static final class TrackingChunkSection extends net.minecraft.world.chunk.ChunkSection {
        private final FeatureBatchWriter writer;
        private final int baseX;
        private final int baseY;
        private final int baseZ;
        private final BlockPos.Mutable scratch = new BlockPos.Mutable();

        private TrackingChunkSection(
                net.minecraft.world.chunk.PalettesFactory palettesFactory,
                FeatureBatchWriter writer, int baseX, int baseY, int baseZ) {
            super(palettesFactory);
            this.writer = writer;
            this.baseX = baseX;
            this.baseY = baseY;
            this.baseZ = baseZ;
        }

        @Override
        public BlockState setBlockState(
                int localX, int localY, int localZ, BlockState state, boolean lock) {
            scratch.set(baseX + localX, baseY + localY, baseZ + localZ);
            BlockState previous = writer.read(scratch);
            super.setBlockState(localX, localY, localZ, state, lock);
            if (!previous.equals(state)) {
                writer.write(scratch, state);
            }
            return previous;
        }

        @Override
        public BlockState getBlockState(int localX, int localY, int localZ) {
            scratch.set(baseX + localX, baseY + localY, baseZ + localZ);
            return writer.read(scratch);
        }

        @Override
        public FluidState getFluidState(int localX, int localY, int localZ) {
            scratch.set(baseX + localX, baseY + localY, baseZ + localZ);
            return writer.read(scratch).getFluidState();
        }
    }

    private record FeatureBatchKey(int chunkX, int chunkZ, long repeatedBand, int stepsMask) {
    }

    private record FeaturePlanKey(int chunkX, int chunkZ, long repeatedBand, int stepsMask) {
    }

    private record BiomeQueryKey(int chunkX, int chunkZ, long repeatedBand) {
    }

    /** The enclosing snapshot partition owns the full cube coordinate. */
    record FeatureWrite(int localIndex, BlockState state) {
        FeatureWrite(BlockPos pos, BlockState state) {
            this((pos.getX() & 15) | ((pos.getY() & 15) << 4)
                    | ((pos.getZ() & 15) << 8), state);
        }

        int localX() { return localIndex & 15; }
        int localY() { return (localIndex >>> 4) & 15; }
        int localZ() { return (localIndex >>> 8) & 15; }
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
            Map<CubePos, List<FeatureWrite>> writesByCube,
            Map<CubePos, List<FeatureBlockEntity>> blockEntitiesByCube) {
        private FeatureBatchSnapshot {
            writesByCube = freezePartitions(writesByCube);
            blockEntitiesByCube = freezePartitions(blockEntitiesByCube);
        }

        private static <T> Map<CubePos, List<T>> freezePartitions(
                Map<CubePos, List<T>> partitions) {
            Map<CubePos, List<T>> frozen = new HashMap<>(partitions.size());
            partitions.forEach((pos, values) -> frozen.put(pos, List.copyOf(values)));
            return Map.copyOf(frozen);
        }

        private List<FeatureWrite> writesFor(CubePos virtualCube) {
            return writesByCube.getOrDefault(virtualCube, List.of());
        }

        private List<FeatureBlockEntity> blockEntitiesFor(CubePos virtualCube) {
            return blockEntitiesByCube.getOrDefault(virtualCube, List.of());
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
        long depthIndex = depthIndex(world, cube.pos());
        int sectionsPerBand = sectionsPerBand();
        int virtualSectionIndex = sectionsPerBand - 1
                - (int) Math.floorMod(depthIndex, sectionsPerBand);
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
                    if (!shouldGenerateFeature(registry, feature, repeatedBand,
                            boundaryMode)) {
                        continue;
                    }
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
     * Executes each source-chunk feature origin once for a repeated band plus
     * the neighbouring source bands. The immutable result is translated and
     * clipped only when applied to the current cube. Structure starts are
     * intentionally not part of this cache.
     */
    private static void generateBatched(
            ServerWorld world, LoadedCube cube, List<GenerationStep.Feature> steps) {
        generateBatched(world, cube, steps, 0, Long.MAX_VALUE);
    }

    /** Sliced entry for the streaming feature pass with the safe underground steps. */
    static int generateBatchedSafe(
            ServerWorld world, LoadedCube cube, int keysDone, long deadlineNanos) {
        return generateBatched(world, cube, SAFE_UNDERGROUND_STEPS, keysDone, deadlineNanos);
    }

    /**
     * Deadline-sliced variant.  {@code keysDone} counts the batch keys already
     * applied to this cube; a negative result means the cube is complete,
     * otherwise the returned value is the next resume cursor.  The deadline is
     * checked before generating a fresh (uncached) key, so the first fresh key
     * always makes progress while a 27-key frontier column is spread over as
     * many commit slices as the budget requires.
     */
    static int generateBatched(
            ServerWorld world, LoadedCube cube, List<GenerationStep.Feature> steps,
            int keysDone, long deadlineNanos) {
        long repeatedBand = repeatedBand(world, cube.pos());
        int sectionsPerBand = sectionsPerBand();
        ChunkGenerator generator = world.getChunkManager().getChunkGenerator();
        Registry<PlacedFeature> registry = world.getRegistryManager()
                .getOrThrow(RegistryKeys.PLACED_FEATURE);
        int stepsMask = stepsMask(steps);
        FeatureCache cache = CACHES.computeIfAbsent(world, ignored -> new FeatureCache());

        // A placed feature may cross a 64-block repeat boundary.  Replay the
        // adjacent source bands as read-only batches and apply only the blocks
        // whose translated coordinates belong to this cube.  This is what
        // keeps a dripstone column (or an ore vein) from being sliced at the
        // old vanilla floor.
        int keyIndex = 0;
        for (long sourceBand = repeatedBand - FEATURE_SOURCE_BAND_RADIUS;
                sourceBand <= repeatedBand + FEATURE_SOURCE_BAND_RADIUS; sourceBand++) {
            long bandSeed = world.getSeed() ^ sourceBand * 0xD1B54A32D192ED03L;
            int sourceOffsetY = bandOffsetY(world, sourceBand, sectionsPerBand);
            for (int chunkZ = cube.pos().z() - FEATURE_ORIGIN_RADIUS;
                    chunkZ <= cube.pos().z() + FEATURE_ORIGIN_RADIUS; chunkZ++) {
                for (int chunkX = cube.pos().x() - FEATURE_ORIGIN_RADIUS;
                        chunkX <= cube.pos().x() + FEATURE_ORIGIN_RADIUS; chunkX++) {
                    if (keyIndex < keysDone) {
                        keyIndex++;
                        continue;
                    }
                    FeatureBatchKey key = new FeatureBatchKey(
                            chunkX, chunkZ, sourceBand, stepsMask);
                    FeatureBatchSnapshot batch = cache.batchIfPresent(key);
                    if (batch == null) {
                        if (keyIndex > keysDone && System.nanoTime() >= deadlineNanos) {
                            return keyIndex;
                        }
                        batch = cache.batch(key, () -> generateBatch(
                                world, generator, registry, cache, key, steps,
                                bandSeed, sourceOffsetY));
                    }
                    applyBatch(world, cube, batch, sourceOffsetY);
                    keyIndex++;
                }
            }
        }
        return -1;
    }

    private static FeatureBatchSnapshot generateBatch(
            ServerWorld world, ChunkGenerator generator, Registry<PlacedFeature> registry,
            FeatureCache cache, FeatureBatchKey key, List<GenerationStep.Feature> steps,
            long bandSeed, int offsetY) {
        try (CubeWorkEvent ignored = CubeWorkEvent.start("vanilla.features.batch",
                new CubePos(key.chunkX(), Math.floorDiv(VANILLA_BOTTOM_Y + offsetY, CubePos.SIZE), key.chunkZ()))) {
            return generateBatchMeasured(world, generator, registry, cache, key, steps, bandSeed, offsetY);
        }
    }

    private static FeatureBatchSnapshot generateBatchMeasured(
            ServerWorld world, ChunkGenerator generator, Registry<PlacedFeature> registry,
            FeatureCache cache, FeatureBatchKey key, List<GenerationStep.Feature> steps,
            long bandSeed, int offsetY) {
        FeatureBatchWriter writer = new FeatureBatchWriter(
                world,
                new BlockBox(key.chunkX() * CubePos.SIZE,
                        VANILLA_BOTTOM_Y - FEATURE_VERTICAL_HALO,
                        key.chunkZ() * CubePos.SIZE,
                        key.chunkX() * CubePos.SIZE + CubePos.SIZE - 1,
                        VANILLA_BOTTOM_Y + REPEATED_BAND_HEIGHT
                                + FEATURE_VERTICAL_HALO - 1,
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
            if (!shouldGenerateFeature(registry, call.feature(), key.repeatedBand(),
                    BoundaryMode.TRANSLATED)) {
                continue;
            }
            int registryId = registry.getRawId(call.feature());
            int decoratorIndex = registryId >= 0 ? registryId : call.index();
            random.setDecoratorSeed(populationSeed, decoratorIndex, call.step().ordinal());
            call.feature().generate(access, generator, random, origin);
        }
        return writer.snapshot(world);
    }

    /**
     * A vanilla monster-room placed feature already contains a count for a
     * full-height chunk. Replaying it in every 64-block deep band multiplies
     * that count as the sparse world is extended downward. Keep the feature in
     * one band out of four, matching the 256-block budget used by custom
     * dungeons. Other placed features retain their existing repeated-band
     * behavior.
     */
    private static boolean shouldGenerateFeature(
            Registry<PlacedFeature> registry, PlacedFeature feature,
            long repeatedBand, BoundaryMode boundaryMode) {
        if (boundaryMode != BoundaryMode.TRANSLATED || !isDungeonFeature(registry, feature)) {
            return true;
        }
        return Math.floorMod(repeatedBand, DUNGEON_REPETITION_BANDS) == 0;
    }

    private static boolean isDungeonFeature(
            Registry<PlacedFeature> registry, PlacedFeature feature) {
        return registry.getKey(feature).map(key -> {
            String path = key.getValue().getPath();
            return "monster_room".equals(path) || "monster_room_deep".equals(path);
        }).orElse(false);
    }

    private static void applyBatch(
            ServerWorld world, LoadedCube cube, FeatureBatchSnapshot batch,
            int offsetY) {
        int minX = cube.pos().minBlockX();
        int minY = cube.pos().minBlockY();
        int minZ = cube.pos().minBlockZ();
        int maxX = minX + CubePos.SIZE - 1;
        int maxY = minY + CubePos.SIZE - 1;
        int maxZ = minZ + CubePos.SIZE - 1;
        CubePos virtualTarget = new CubePos(
                cube.pos().x(),
                Math.floorDiv(cube.pos().minBlockY() - offsetY, CubePos.SIZE),
                cube.pos().z());
        for (FeatureWrite write : batch.writesFor(virtualTarget)) {
            // Repeated bands and cube partitions are section-aligned. Their
            // local coordinates survive translation, including negative Y.
            BlockState previous = cube.section().getBlockState(
                    write.localX(), write.localY(), write.localZ());
            BlockState state = write.state();
            cube.setGeneratedBlockState(
                    write.localX(), write.localY(), write.localZ(), state);
            if (!previous.hasBlockEntity() && !state.hasBlockEntity()) continue;
            BlockPos actual = new BlockPos(
                    minX + write.localX(), minY + write.localY(), minZ + write.localZ());
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
        for (FeatureBlockEntity featureBlockEntity : batch.blockEntitiesFor(virtualTarget)) {
            BlockPos virtual = featureBlockEntity.pos();
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
        if (batchWriter != null && "getSectionIndex".equals(name)
                && arguments != null && arguments.length == 1
                && arguments[0] instanceof Integer y) {
            // OreFeature reaches ChunkSection directly through
            // ChunkSectionCache.  Map that lookup into the temporary
            // [virtualMinY, virtualMaxY] section array instead of letting the
            // real world's -64-based section index discard deep positions.
            return Math.floorDiv(y - batchWriter.virtualBand.getMinY(), CubePos.SIZE);
        }
        if (batchWriter != null && "getBottomSectionCoord".equals(name)) {
            return Math.floorDiv(batchWriter.virtualBand.getMinY(), CubePos.SIZE);
        }
        if (batchWriter != null && "getTopSectionCoord".equals(name)) {
            return Math.floorDiv(batchWriter.virtualBand.getMaxY() + 1, CubePos.SIZE);
        }
        if (batchWriter != null && "countVerticalSections".equals(name)) {
            return (batchWriter.virtualBand.getMaxY()
                    - batchWriter.virtualBand.getMinY() + 1) / CubePos.SIZE;
        }
        BlockPos virtualPos = firstPos(arguments);
        if ("setBlockState".equals(name) && virtualPos != null
                && arguments.length >= 2 && arguments[1] instanceof BlockState state) {
            if (state.isOf(Blocks.SCULK_VEIN)) {
                state = removeUnsupportedSculkFaces((BlockView) proxy, virtualPos, state);
            }
            if (batchWriter != null) {
                BlockPos stablePos = virtualPos.toImmutable();
                if (!batchWriter.acceptsY(stablePos.getY())) {
                    // A feature can scan farther than the bounded halo.  It
                    // must not escape into an unbounded map, but returning
                    // false still gives vanilla feature code its normal
                    // "nothing was placed" result.
                    return false;
                }
                BlockState previous = batchWriter.read(stablePos);
                batchWriter.write(stablePos, state);
                updateFeatureChunk(featureChunks, stablePos, state, batchWriter);
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
                    : batchWriter.read(virtualPos);
        }
        if ("getFluidState".equals(name) && virtualPos != null) {
            return (batchWriter == null
                    ? featureFluidState(world, cube, virtualCube, offsetY,
                            boundaryMode, clippedBlockStates, virtualPos)
                    : batchWriter.read(virtualPos).getFluidState());
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
                    : batchWriter.read(virtualPos);
            return ((Predicate<BlockState>) predicate).test(state);
        }
        if ("testFluidState".equals(name) && virtualPos != null
                && arguments[1] instanceof Predicate<?> predicate) {
            FluidState state = batchWriter == null
                    ? featureFluidState(world, cube, virtualCube, offsetY,
                            boundaryMode, clippedBlockStates, virtualPos)
                    : batchWriter.read(virtualPos).getFluidState();
            return ((Predicate<FluidState>) predicate).test(state);
        }
        if (("removeBlock".equals(name) || "breakBlock".equals(name)) && virtualPos != null) {
            if (batchWriter != null) {
                BlockPos stablePos = virtualPos.toImmutable();
                if (!batchWriter.acceptsY(stablePos.getY())) return false;
                BlockState previous = batchWriter.read(stablePos);
                batchWriter.write(stablePos, Blocks.AIR.getDefaultState());
                batchWriter.blockEntities.remove(stablePos);
                updateFeatureChunk(
                        featureChunks, stablePos, Blocks.AIR.getDefaultState(), batchWriter);
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
            // The proxy has a finite virtual height for placement modifiers,
            // but a translated batch also has a vertical halo.  Using the
            // vanilla height here was the actual -64 cut: OreFeature and
            // dripstone refuse to write the portion below that coordinate.
            return batchWriter != null
                    ? batchWriter.acceptsY(virtualPos.getY())
                    : isInVanillaHeight(virtualPos);
        }
        if ("markBlockForPostProcessing".equals(name) || "markForPostProcessing".equals(name)) {
            // ProtoChunk post-processing lists do not exist for sparse cubes.
            // Forwarding this to the vanilla ChunkRegion only emits warnings
            // and records a position in the unrelated virtual-height chunk.
            return null;
        }
        if ("getBottomY".equals(name)) {
            return batchWriter != null
                    ? batchWriter.virtualBand.getMinY() : VANILLA_BOTTOM_Y;
        }
        if ("getHeight".equals(name)) {
            return batchWriter != null
                    ? batchWriter.virtualBand.getMaxY()
                            - batchWriter.virtualBand.getMinY() + 1
                    : VANILLA_HEIGHT;
        }
        if ("getTopYInclusive".equals(name)) {
            return batchWriter != null
                    ? batchWriter.virtualBand.getMaxY()
                    : VANILLA_BOTTOM_Y + VANILLA_HEIGHT - 1;
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
            return batchWriter != null
                    ? batchWriter.acceptsY(y)
                    : isInVanillaHeight(y);
        }
        if ("isOutOfHeightLimit".equals(name) && arguments != null && arguments.length == 1) {
            int y = arguments[0] instanceof BlockPos pos ? pos.getY() : (int) arguments[0];
            return batchWriter != null
                    ? !batchWriter.acceptsY(y)
                    : !isInVanillaHeight(y);
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
        HeightLimitView height = batchWriter == null
                ? HeightLimitView.create(VANILLA_BOTTOM_Y, VANILLA_HEIGHT)
                : HeightLimitView.create(
                        batchWriter.virtualBand.getMinY(),
                        batchWriter.virtualBand.getMaxY()
                                - batchWriter.virtualBand.getMinY() + 1);
        ProtoChunk chunk = new ProtoChunk(
                new ChunkPos(chunkX, chunkZ), UpgradeData.NO_UPGRADE_DATA,
                height, world.getPalettesFactory(), null);
        if (batchWriter != null) {
            // Give OreFeature a real section for every coordinate in the
            // virtual halo.  The old fixed vanilla-height ProtoChunk returned
            // null for y < -64 and silently dropped the ore vein.  Reads are
            // served lazily by TrackingChunkSection, so creating the eight
            // scratch sections no longer means pre-filling 32k blocks from
            // the world per feature batch key.
            int lastSection = chunk.getSectionArray().length - 1;
            net.minecraft.world.chunk.PalettesFactory palettesFactory = world.getPalettesFactory();
            for (int sectionIndex = 0; sectionIndex <= lastSection; sectionIndex++) {
                int sectionMinY = height.getBottomY() + sectionIndex * CubePos.SIZE;
                chunk.getSectionArray()[sectionIndex] = new TrackingChunkSection(
                        palettesFactory, batchWriter,
                        chunkX * CubePos.SIZE, sectionMinY, chunkZ * CubePos.SIZE);
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
        updateFeatureChunk(featureChunks, pos, state, null);
    }

    private static void updateFeatureChunk(
            Map<Long, Chunk> featureChunks, BlockPos pos, BlockState state,
            FeatureBatchWriter batchWriter) {
        int chunkX = Math.floorDiv(pos.getX(), CubePos.SIZE);
        int chunkZ = Math.floorDiv(pos.getZ(), CubePos.SIZE);
        long key = ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
        Chunk chunk = featureChunks.get(key);
        if (chunk == null) return;
        int sectionIndex;
        int localY;
        if (batchWriter != null) {
            if (!batchWriter.acceptsY(pos.getY())) return;
            sectionIndex = Math.floorDiv(
                    pos.getY() - batchWriter.virtualBand.getMinY(), CubePos.SIZE);
            localY = Math.floorMod(
                    pos.getY() - batchWriter.virtualBand.getMinY(), CubePos.SIZE);
        } else {
            if (!isInVanillaHeight(pos)) return;
            sectionIndex = Math.floorDiv(pos.getY() - VANILLA_BOTTOM_Y, CubePos.SIZE);
            localY = Math.floorMod(pos.getY() - VANILLA_BOTTOM_Y, CubePos.SIZE);
        }
        if (sectionIndex < 0 || sectionIndex >= chunk.getSectionArray().length) return;
        chunk.getSectionArray()[sectionIndex].setBlockState(
                Math.floorMod(pos.getX(), CubePos.SIZE),
                localY,
                Math.floorMod(pos.getZ(), CubePos.SIZE), state, false);
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
        int minY = batchWriter == null
                ? virtualCube.getMinY() : VANILLA_BOTTOM_Y;
        int maxY = batchWriter == null
                ? virtualCube.getMaxY() : VANILLA_BOTTOM_Y + REPEATED_BAND_HEIGHT - 1;
        for (int y = maxY; y >= minY; y--) {
            mutable.set(blockX, y, blockZ);
            BlockState state = batchWriter == null
                    ? featureBlockState(world, cube, virtualCube, offsetY,
                            boundaryMode, clippedBlockStates, mutable)
                    : batchWriter.read(mutable);
            if (!state.isAir()) {
                return y + 1;
            }
        }
        return minY;
    }

    private static boolean isInVanillaHeight(int y) {
        return y >= VANILLA_BOTTOM_Y && y < VANILLA_BOTTOM_Y + VANILLA_HEIGHT;
    }

    private static boolean isInVanillaHeight(BlockPos pos) {
        return isInVanillaHeight(pos.getY());
    }

    private record FeatureCall(GenerationStep.Feature step, int index, PlacedFeature feature) {
    }

    private enum BoundaryMode {
        VERSION_12,
        TRANSLATED
    }
}
