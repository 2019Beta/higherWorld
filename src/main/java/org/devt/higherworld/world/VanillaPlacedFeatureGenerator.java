package org.devt.higherworld.world;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import net.minecraft.block.BlockState;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
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

    private VanillaPlacedFeatureGenerator() {
    }

    static void generate(ServerWorld world, LoadedCube cube) {
        generate(world, cube, SAFE_UNDERGROUND_STEPS, false);
    }

    /** Reproduces the version-12 pass exactly enough to identify untouched cubes. */
    static void generateVersion12(ServerWorld world, LoadedCube cube) {
        generate(world, cube, LEGACY_UNDERGROUND_STEPS, true);
    }

    private static void generate(
            ServerWorld world, LoadedCube cube, List<GenerationStep.Feature> steps,
            boolean legacyBoundaryReads) {
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
                StructureWorldAccess access = translatedAccess(
                        world, cube, virtualMinY, offsetY, legacyBoundaryReads);
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

    private static List<FeatureCall> collectFeatures(
            ServerWorld world, int baseX, int baseZ, int virtualMinY,
            ChunkGenerator generator, List<GenerationStep.Feature> allowedSteps) {
        Set<RegistryEntry<Biome>> biomes = new LinkedHashSet<>();
        for (int z = 2; z < CubePos.SIZE; z += 4) {
            for (int x = 2; x < CubePos.SIZE; x += 4) {
                biomes.add(world.getBiome(new BlockPos(
                        baseX + x, virtualMinY + CubePos.SIZE / 2, baseZ + z)));
            }
        }

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

    private static StructureWorldAccess translatedAccess(
            ServerWorld world, LoadedCube cube, int virtualMinY, int offsetY,
            boolean legacyBoundaryReads) {
        BlockBox virtualCube = new BlockBox(
                cube.pos().minBlockX(), virtualMinY, cube.pos().minBlockZ(),
                cube.pos().minBlockX() + CubePos.SIZE - 1,
                virtualMinY + CubePos.SIZE - 1,
                cube.pos().minBlockZ() + CubePos.SIZE - 1);
        Map<Long, Chunk> featureChunks = new HashMap<>();
        return (StructureWorldAccess) Proxy.newProxyInstance(
                VanillaPlacedFeatureGenerator.class.getClassLoader(),
                new Class<?>[] {StructureWorldAccess.class},
                (proxy, method, arguments) -> invoke(
                        world, cube, virtualCube, virtualMinY, offsetY,
                        legacyBoundaryReads, featureChunks,
                        method, arguments));
    }

    @SuppressWarnings("unchecked")
    private static Object invoke(
            ServerWorld world, LoadedCube cube, BlockBox virtualCube,
            int virtualMinY, int offsetY, boolean legacyBoundaryReads,
            Map<Long, Chunk> featureChunks,
            Method method, Object[] arguments) throws Throwable {
        String name = method.getName();
        if (!legacyBoundaryReads && "getChunk".equals(name)
                && arguments != null && arguments.length >= 2
                && arguments[0] instanceof Integer chunkX
                && arguments[1] instanceof Integer chunkZ) {
            long key = ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
            return featureChunks.computeIfAbsent(key, ignored ->
                    createFeatureChunk(world, cube, virtualMinY, chunkX, chunkZ));
        }
        if (!legacyBoundaryReads && "getTopY".equals(name)
                && arguments != null && arguments.length == 3
                && arguments[1] instanceof Integer x && arguments[2] instanceof Integer z) {
            return getVirtualTopY(world, cube, virtualCube, x, z);
        }
        if (!legacyBoundaryReads && "getTopY".equals(name)
                && arguments != null && arguments.length == 2
                && arguments[1] instanceof BlockPos pos) {
            return getVirtualTopY(world, cube, virtualCube, pos.getX(), pos.getZ());
        }
        if (!legacyBoundaryReads && "getTopPosition".equals(name)
                && arguments != null && arguments.length == 2
                && arguments[1] instanceof BlockPos pos) {
            return new BlockPos(pos.getX(),
                    getVirtualTopY(world, cube, virtualCube, pos.getX(), pos.getZ()), pos.getZ());
        }
        BlockPos virtualPos = firstPos(arguments);
        if ("setBlockState".equals(name) && virtualPos != null
                && arguments.length >= 2 && arguments[1] instanceof BlockState state) {
            if (!virtualCube.contains(virtualPos)) {
                return false;
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
            if (virtualCube.contains(virtualPos)) {
                return cube.getBlockState(translate(virtualPos, offsetY));
            }
            if (isInVanillaHeight(virtualPos)) {
                return world.getBlockState(virtualPos);
            }
            return Blocks.AIR.getDefaultState();
        }
        if ("getFluidState".equals(name) && virtualPos != null) {
            if (virtualCube.contains(virtualPos)) {
                return cube.getFluidState(translate(virtualPos, offsetY));
            }
            if (isInVanillaHeight(virtualPos)) {
                return world.getFluidState(virtualPos);
            }
            return Fluids.EMPTY.getDefaultState();
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
            if (isInVanillaHeight(virtualPos)) {
                if (arguments.length == 2) {
                    BlockEntity blockEntity = world.getBlockEntity(virtualPos);
                    return blockEntity != null
                            && arguments[1] instanceof net.minecraft.block.entity.BlockEntityType<?> type
                            && blockEntity.getType() == type
                            ? Optional.of(blockEntity) : Optional.empty();
                }
                return world.getBlockEntity(virtualPos);
            }
            return arguments.length == 2 ? Optional.empty() : null;
        }
        if ("testBlockState".equals(name) && virtualPos != null
                && arguments[1] instanceof Predicate<?> predicate) {
            BlockState state = virtualCube.contains(virtualPos)
                    ? cube.getBlockState(translate(virtualPos, offsetY))
                    : isInVanillaHeight(virtualPos)
                            ? world.getBlockState(virtualPos)
                            : Blocks.AIR.getDefaultState();
            return ((Predicate<BlockState>) predicate).test(state);
        }
        if ("testFluidState".equals(name) && virtualPos != null
                && arguments[1] instanceof Predicate<?> predicate) {
            FluidState state = virtualCube.contains(virtualPos)
                    ? cube.getFluidState(translate(virtualPos, offsetY))
                    : isInVanillaHeight(virtualPos)
                            ? world.getFluidState(virtualPos)
                            : Fluids.EMPTY.getDefaultState();
            return ((Predicate<FluidState>) predicate).test(state);
        }
        if (("removeBlock".equals(name) || "breakBlock".equals(name)) && virtualPos != null) {
            if (!virtualCube.contains(virtualPos)) {
                return false;
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
        if ("markBlockForPostProcessing".equals(name)) {
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

    private static BlockPos firstPos(Object[] arguments) {
        return arguments != null && arguments.length > 0 && arguments[0] instanceof BlockPos pos
                ? pos : null;
    }

    private static Chunk createFeatureChunk(
            ServerWorld world, LoadedCube cube, int virtualMinY,
            int chunkX, int chunkZ) {
        HeightLimitView height = HeightLimitView.create(VANILLA_BOTTOM_Y, VANILLA_HEIGHT);
        ProtoChunk chunk = new ProtoChunk(
                new ChunkPos(chunkX, chunkZ), UpgradeData.NO_UPGRADE_DATA,
                height, world.getPalettesFactory(), null);
        if (chunkX == cube.pos().x() && chunkZ == cube.pos().z()) {
            int sectionIndex = Math.floorDiv(virtualMinY - VANILLA_BOTTOM_Y, CubePos.SIZE);
            chunk.getSectionArray()[sectionIndex] = cube.section();
        }
        return chunk;
    }

    private static BlockPos translate(BlockPos pos, int offsetY) {
        return new BlockPos(pos.getX(), pos.getY() + offsetY, pos.getZ());
    }

    private static int getVirtualTopY(
            ServerWorld world, LoadedCube cube, BlockBox virtualCube,
            int blockX, int blockZ) {
        if (blockX < virtualCube.getMinX() || blockX > virtualCube.getMaxX()
                || blockZ < virtualCube.getMinZ() || blockZ > virtualCube.getMaxZ()) {
            BlockPos.Mutable mutable = new BlockPos.Mutable();
            for (int y = virtualCube.getMaxY(); y >= virtualCube.getMinY(); y--) {
                mutable.set(blockX, y, blockZ);
                if (!world.getBlockState(mutable).isAir()) {
                    return y + 1;
                }
            }
            return virtualCube.getMinY();
        }
        int localX = blockX - virtualCube.getMinX();
        int localZ = blockZ - virtualCube.getMinZ();
        for (int localY = CubePos.SIZE - 1; localY >= 0; localY--) {
            if (!cube.section().getBlockState(localX, localY, localZ).isAir()) {
                return virtualCube.getMinY() + localY + 1;
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
}
