package org.devt.higherworld.world;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.gen.densityfunction.DensityFunctionTypes;
import net.minecraft.world.gen.noise.NoiseConfig;
import org.devt.higherworld.storage.CubePos;

/** Lazily extends Overworld terrain below the vanilla generation band. */
final class InfiniteDownwardGenerator {
    static final int GENERATION_VERSION = 12;
    private static final BlockState AIR = Blocks.AIR.getDefaultState();
    private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.getDefaultState();
    private static final int NOISE_CELL_SIZE = 4;
    private static final int NOISE_GRID_SIZE = CubePos.SIZE / NOISE_CELL_SIZE + 1;
    private static final int VANILLA_TRANSITION_DEPTH = 12;
    private static final int LEGACY_V6_TRANSITION_DEPTH = 32;
    private static final int BOUNDARY_DISTANCE_RADIUS = 4;
    private static final int CAVE_NOISE_BAND_HEIGHT = 136;
    private static final int CAVE_NOISE_BLEND_HEIGHT = 16;
    private static final int LEGACY_SURFACE_BAND_HEIGHT = 56;
    private static final int LEGACY_SURFACE_BLEND_HEIGHT = 12;
    private static final double[] NOISE_DELTAS = {0.0, 0.15625, 0.5, 0.84375};
    private static final Map<ServerWorld, DensityFunction> LEGACY_CAVE_DENSITIES = new ConcurrentHashMap<>();
    private static final Map<ServerWorld, DensityFunction> EXTENDED_OVERWORLD_DENSITIES =
            new ConcurrentHashMap<>();

    private InfiniteDownwardGenerator() {
    }

    static void release(ServerWorld world) {
        LEGACY_CAVE_DENSITIES.remove(world);
        EXTENDED_OVERWORLD_DENSITIES.remove(world);
        VanillaCubeTerrainGenerator.release(world);
    }

    static void generate(
            ServerWorld world, LoadedCube cube, StructureGenerationSettings structureSettings) {
        if (!VanillaCubeTerrainGenerator.generate(world, cube)) {
            generateVanillaNoiseTerrain(world, cube);
        }
        removeAquiferFluids(cube);
        VanillaStructureGenerator.generate(world, cube, structureSettings);
        VanillaPlacedFeatureGenerator.generate(world, cube);
        cube.setGenerationVersion(GENERATION_VERSION);
        cube.markDirty();
    }

    /**
     * Vanilla's fluid-level sampler hard-codes lava below Y=-54. Reusing the
     * noise chunk generator at arbitrary negative heights would consequently
     * turn every density opening into a lava ocean. Strip only this terrain
     * pass here; placed features run afterwards and can still create ordinary
     * springs and lakes.
     */
    private static void removeAquiferFluids(LoadedCube cube) {
        for (int y = 0; y < CubePos.SIZE; y++) {
            for (int z = 0; z < CubePos.SIZE; z++) {
                for (int x = 0; x < CubePos.SIZE; x++) {
                    BlockState state = cube.section().getBlockState(x, y, z);
                    if (!state.getFluidState().isEmpty()) {
                        cube.setGeneratedBlockState(x, y, z, AIR);
                    }
                }
            }
        }
    }

    private static void generateVanillaNoiseTerrain(ServerWorld world, LoadedCube cube) {
        CubePos pos = cube.pos();
        DensityFunction density = extendedOverworldDensity(world);
        int horizontalCell = 4;
        int verticalCell = 8;
        int horizontalCells = CubePos.SIZE / horizontalCell;
        int verticalCells = CubePos.SIZE / verticalCell;
        double[] samples = new double[
                (horizontalCells + 1) * (verticalCells + 1) * (horizontalCells + 1)];
        for (int gridY = 0; gridY <= verticalCells; gridY++) {
            for (int gridZ = 0; gridZ <= horizontalCells; gridZ++) {
                for (int gridX = 0; gridX <= horizontalCells; gridX++) {
                    samples[vanillaSampleIndex(gridX, gridY, gridZ, horizontalCells)] = density.sample(
                            new DensityFunction.UnblendedNoisePos(
                                    pos.minBlockX() + gridX * horizontalCell,
                                    pos.minBlockY() + gridY * verticalCell,
                                    pos.minBlockZ() + gridZ * horizontalCell));
                }
            }
        }

        for (int localY = 0; localY < CubePos.SIZE; localY++) {
            for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
                for (int localX = 0; localX < CubePos.SIZE; localX++) {
                    if (interpolateVanillaDensity(
                            samples, localX, localY, localZ,
                            horizontalCell, verticalCell, horizontalCells) > 0.0) {
                        cube.setGeneratedBlockState(localX, localY, localZ, DEEPSLATE);
                    }
                }
            }
        }
    }

    private static double interpolateVanillaDensity(
            double[] samples, int x, int y, int z,
            int horizontalCell, int verticalCell, int horizontalCells) {
        int gridX = x / horizontalCell;
        int gridY = y / verticalCell;
        int gridZ = z / horizontalCell;
        double tx = (double) (x % horizontalCell) / horizontalCell;
        double ty = (double) (y % verticalCell) / verticalCell;
        double tz = (double) (z % horizontalCell) / horizontalCell;
        double x00 = lerp(
                samples[vanillaSampleIndex(gridX, gridY, gridZ, horizontalCells)],
                samples[vanillaSampleIndex(gridX + 1, gridY, gridZ, horizontalCells)], tx);
        double x10 = lerp(
                samples[vanillaSampleIndex(gridX, gridY + 1, gridZ, horizontalCells)],
                samples[vanillaSampleIndex(gridX + 1, gridY + 1, gridZ, horizontalCells)], tx);
        double x01 = lerp(
                samples[vanillaSampleIndex(gridX, gridY, gridZ + 1, horizontalCells)],
                samples[vanillaSampleIndex(gridX + 1, gridY, gridZ + 1, horizontalCells)], tx);
        double x11 = lerp(
                samples[vanillaSampleIndex(gridX, gridY + 1, gridZ + 1, horizontalCells)],
                samples[vanillaSampleIndex(gridX + 1, gridY + 1, gridZ + 1, horizontalCells)], tx);
        return lerp(lerp(x00, x10, ty), lerp(x01, x11, ty), tz);
    }

    private static int vanillaSampleIndex(int x, int y, int z, int horizontalCells) {
        int side = horizontalCells + 1;
        return (y * side + z) * side + x;
    }

    /** Upgrades untouched cubes produced by either previous generator revision. */
    static boolean upgradeLegacyTerrain(
            ServerWorld world, LoadedCube cube, StructureGenerationSettings structureSettings) {
        if (cube.generationVersion() >= GENERATION_VERSION) {
            return false;
        }
        if (cube.generationVersion() == 11) {
            if (cube.blockEntities().isEmpty()
                    && matchesVersion11(world, cube, structureSettings)) {
                clear(cube);
                generate(world, cube, structureSettings);
                return true;
            }
            cube.setGenerationVersion(GENERATION_VERSION);
            cube.markDirty();
            return false;
        }
        if (cube.generationVersion() == 10) {
            if (cube.section().isEmpty() && cube.blockEntities().isEmpty()) {
                generate(world, cube, structureSettings);
                return true;
            }
            cube.setGenerationVersion(GENERATION_VERSION);
            cube.markDirty();
            return false;
        }
        if (cube.generationVersion() == 9) {
            VanillaPlacedFeatureGenerator.generate(world, cube);
            cube.setGenerationVersion(GENERATION_VERSION);
            cube.markDirty();
            return true;
        }
        if (cube.generationVersion() == 8) {
            VanillaPlacedFeatureGenerator.generate(world, cube);
            cube.setGenerationVersion(GENERATION_VERSION);
            cube.markDirty();
            return true;
        }
        if (cube.generationVersion() == 7) {
            if (cube.blockEntities().isEmpty()
                    && matchesVersion7(world, cube, structureSettings)) {
                clear(cube);
                generate(world, cube, structureSettings);
                return true;
            }
            cube.setGenerationVersion(GENERATION_VERSION);
            cube.markDirty();
            return false;
        }
        if (cube.generationVersion() == 6) {
            if (cube.blockEntities().isEmpty()
                    && matchesVersion6(world, cube, structureSettings)) {
                clear(cube);
                generate(world, cube, structureSettings);
                return true;
            }
            cube.setGenerationVersion(GENERATION_VERSION);
            cube.markDirty();
            return false;
        }
        // Version 5 cubes may contain player edits. Preserve them and let only
        // newly generated cubes receive the newly introduced structures.
        if (cube.generationVersion() == 5) {
            cube.setGenerationVersion(GENERATION_VERSION);
            cube.markDirty();
            return false;
        }
        if (!cube.blockEntities().isEmpty()) {
            cube.setGenerationVersion(GENERATION_VERSION);
            cube.markDirty();
            return false;
        }
        boolean untouched = cube.generationVersion() == 4
                ? matchesSurfaceRouterTerrain(world, cube)
                : matchesLegacyGrid(world, cube)
                        || matchesPreviousTransition(world, cube)
                        || matchesLegacyPerBlock(world, cube);
        if (!untouched) {
            // The cube contains player changes or unknown generator output. Keep
            // its blocks unchanged, but persist the decision so later loads do not
            // repeat the expensive legacy comparisons.
            cube.setGenerationVersion(GENERATION_VERSION);
            cube.markDirty();
            return false;
        }
        clear(cube);
        generate(world, cube, structureSettings);
        return true;
    }

    private static boolean matchesVersion11(
            ServerWorld world, LoadedCube cube, StructureGenerationSettings structureSettings) {
        LoadedCube expected = new LoadedCube(
                cube.pos(), new net.minecraft.world.chunk.ChunkSection(world.getPalettesFactory()));
        if (!VanillaCubeTerrainGenerator.generate(world, expected)) {
            generateVanillaNoiseTerrain(world, expected);
        }
        VanillaStructureGenerator.generate(world, expected, structureSettings);
        VanillaPlacedFeatureGenerator.generate(world, expected);
        for (int y = 0; y < CubePos.SIZE; y++) {
            for (int z = 0; z < CubePos.SIZE; z++) {
                for (int x = 0; x < CubePos.SIZE; x++) {
                    BlockState actual = cube.section().getBlockState(x, y, z);
                    BlockState generated = expected.section().getBlockState(x, y, z);
                    if (actual.equals(generated)) {
                        continue;
                    }
                    boolean naturalFluidChange = !actual.getFluidState().isEmpty()
                            && (generated.isAir()
                                    || (!generated.getFluidState().isEmpty()
                                            && actual.getFluidState().getFluid()
                                                    == generated.getFluidState().getFluid()));
                    if (!naturalFluidChange) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void clear(LoadedCube cube) {
        for (int y = 0; y < CubePos.SIZE; y++) {
            for (int z = 0; z < CubePos.SIZE; z++) {
                for (int x = 0; x < CubePos.SIZE; x++) {
                    cube.setGeneratedBlockState(x, y, z, AIR);
                }
            }
        }
    }

    private static boolean matchesVersion6(
            ServerWorld world, LoadedCube cube, StructureGenerationSettings structureSettings) {
        LoadedCube expected = new LoadedCube(
                cube.pos(), new net.minecraft.world.chunk.ChunkSection(world.getPalettesFactory()));
        generateVersion6(world, expected, structureSettings);
        for (int y = 0; y < CubePos.SIZE; y++) {
            for (int z = 0; z < CubePos.SIZE; z++) {
                for (int x = 0; x < CubePos.SIZE; x++) {
                    if (!cube.section().getBlockState(x, y, z)
                            .equals(expected.section().getBlockState(x, y, z))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean matchesVersion7(
            ServerWorld world, LoadedCube cube, StructureGenerationSettings structureSettings) {
        LoadedCube expected = new LoadedCube(
                cube.pos(), new net.minecraft.world.chunk.ChunkSection(world.getPalettesFactory()));
        generateVersion7(world, expected, structureSettings);
        for (int y = 0; y < CubePos.SIZE; y++) {
            for (int z = 0; z < CubePos.SIZE; z++) {
                for (int x = 0; x < CubePos.SIZE; x++) {
                    if (!cube.section().getBlockState(x, y, z)
                            .equals(expected.section().getBlockState(x, y, z))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void generateVersion7(
            ServerWorld world, LoadedCube cube, StructureGenerationSettings structureSettings) {
        CubePos pos = cube.pos();
        double[] density = createLegacyCaveDensityGrid(
                world, pos.minBlockX(), pos.minBlockY(), pos.minBlockZ());
        BoundaryField boundary = createBoundaryField(
                world, pos.minBlockX(), pos.minBlockY(), pos.minBlockZ());
        for (int localY = 0; localY < CubePos.SIZE; localY++) {
            int worldY = pos.minBlockY() + localY;
            for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
                for (int localX = 0; localX < CubePos.SIZE; localX++) {
                    if (!isVersion7Cave(world, density, boundary,
                            localX, localY, localZ, worldY)) {
                        cube.setGeneratedBlockState(localX, localY, localZ, DEEPSLATE);
                    }
                }
            }
        }
        LegacyStructureGenerator.generate(world.getSeed(), cube, structureSettings);
    }

    private static void generateVersion6(
            ServerWorld world, LoadedCube cube, StructureGenerationSettings structureSettings) {
        CubePos pos = cube.pos();
        double[] density = createLegacyCaveDensityGrid(
                world, pos.minBlockX(), pos.minBlockY(), pos.minBlockZ());
        boolean[] boundaryAir = createLegacyBoundaryMask(
                world, pos.minBlockX(), pos.minBlockY(), pos.minBlockZ());
        for (int localY = 0; localY < CubePos.SIZE; localY++) {
            int worldY = pos.minBlockY() + localY;
            for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
                for (int localX = 0; localX < CubePos.SIZE; localX++) {
                    if (!isVersion6Cave(world, density, boundaryAir,
                            localX, localY, localZ, worldY)) {
                        cube.setGeneratedBlockState(localX, localY, localZ, DEEPSLATE);
                    }
                }
            }
        }
        LegacyStructureGenerator.generateVersion6(world.getSeed(), cube, structureSettings);
    }

    private static double[] createLegacyCaveDensityGrid(
            ServerWorld world, int baseX, int baseY, int baseZ) {
        DensityFunction finalDensity = LEGACY_CAVE_DENSITIES.computeIfAbsent(world, ignored ->
                NoiseConfig.create(world.getRegistryManager(), ChunkGeneratorSettings.CAVES, world.getSeed())
                        .getNoiseRouter().finalDensity());
        double[] density = new double[NOISE_GRID_SIZE * NOISE_GRID_SIZE * NOISE_GRID_SIZE];
        for (int gridY = 0; gridY < NOISE_GRID_SIZE; gridY++) {
            int y = baseY + gridY * NOISE_CELL_SIZE;
            for (int gridZ = 0; gridZ < NOISE_GRID_SIZE; gridZ++) {
                int z = baseZ + gridZ * NOISE_CELL_SIZE;
                for (int gridX = 0; gridX < NOISE_GRID_SIZE; gridX++) {
                    int x = baseX + gridX * NOISE_CELL_SIZE;
                    // Positive values are cave scores; vanilla final density uses
                    // the opposite sign (positive means a solid block).
                    density[index(gridX, gridY, gridZ)] = -sampleCaveDensity(
                            finalDensity, world.getSeed(), x, y, z);
                }
            }
        }
        return density;
    }

    private static double[] createLegacyDensityGrid(long seed, int baseX, int baseY, int baseZ) {
        double[] density = new double[NOISE_GRID_SIZE * NOISE_GRID_SIZE * NOISE_GRID_SIZE];
        for (int gridY = 0; gridY < NOISE_GRID_SIZE; gridY++) {
            int y = baseY + gridY * NOISE_CELL_SIZE;
            for (int gridZ = 0; gridZ < NOISE_GRID_SIZE; gridZ++) {
                int z = baseZ + gridZ * NOISE_CELL_SIZE;
                for (int gridX = 0; gridX < NOISE_GRID_SIZE; gridX++) {
                    int x = baseX + gridX * NOISE_CELL_SIZE;
                    double broad = valueNoise(seed, x / 34.0, y / 26.0, z / 34.0);
                    double detail = valueNoise(
                            seed ^ 0x6A09E667F3BCC909L, x / 15.0, y / 12.0, z / 15.0);
                    density[index(gridX, gridY, gridZ)] = broad * 0.72 + detail * 0.28;
                }
            }
        }
        return density;
    }

    private static BoundaryField createBoundaryField(
            ServerWorld world, int baseX, int baseY, int baseZ) {
        int bottomY = world.getBottomY();
        if (baseY + CubePos.SIZE - 1 < bottomY - VANILLA_TRANSITION_DEPTH) {
            return null;
        }
        int radius = BOUNDARY_DISTANCE_RADIUS;
        int sampleSize = CubePos.SIZE + radius * 2;
        boolean[] samples = new boolean[sampleSize * sampleSize];
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int z = 0; z < sampleSize; z++) {
            for (int x = 0; x < sampleSize; x++) {
                mutable.set(baseX + x - radius, bottomY + 5, baseZ + z - radius);
                samples[z * sampleSize + x] = isOpenTerrain(world.getBlockState(mutable));
            }
        }
        double[] signedDensity = new double[CubePos.SIZE * CubePos.SIZE];
        for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
            for (int localX = 0; localX < CubePos.SIZE; localX++) {
                int sampleX = localX + radius;
                int sampleZ = localZ + radius;
                boolean open = samples[sampleZ * sampleSize + sampleX];
                double nearestOpposite = radius + 1.0;
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        if (samples[(sampleZ + dz) * sampleSize + sampleX + dx] != open) {
                            nearestOpposite = Math.min(nearestOpposite, Math.sqrt(dx * dx + dz * dz));
                        }
                    }
                }
                double magnitude = 0.28 + Math.min(radius, nearestOpposite) * 0.12;
                signedDensity[localZ * CubePos.SIZE + localX] = open ? magnitude : -magnitude;
            }
        }
        return new BoundaryField(signedDensity);
    }

    private static boolean isVersion7Cave(
            ServerWorld world, double[] density, BoundaryField boundary,
            int x, int y, int z, int worldY) {
        double generatedDensity = interpolateDensity(density, x, y, z);
        if (boundary == null) {
            return generatedDensity > 0.0;
        }

        // Preserve the sign of the vanilla opening at the seam, but use its
        // horizontal distance to the nearest wall as the strength. The weaker
        // edge values and short blend prevent a two-cube-long vertical extrusion.
        int depth = world.getBottomY() - worldY;
        double transition = fade(Math.clamp((depth - 1.0) / (VANILLA_TRANSITION_DEPTH - 1.0), 0.0, 1.0));
        double boundaryDensity = boundary.density[z * CubePos.SIZE + x];
        return lerp(boundaryDensity, generatedDensity, transition) > 0.0;
    }

    private static boolean[] createLegacyBoundaryMask(
            ServerWorld world, int baseX, int baseY, int baseZ) {
        int bottomY = world.getBottomY();
        if (baseY + CubePos.SIZE - 1 < bottomY - LEGACY_V6_TRANSITION_DEPTH) {
            return null;
        }
        boolean[] boundaryAir = new boolean[CubePos.SIZE * CubePos.SIZE];
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
            for (int localX = 0; localX < CubePos.SIZE; localX++) {
                mutable.set(baseX + localX, bottomY + 5, baseZ + localZ);
                boundaryAir[localZ * CubePos.SIZE + localX] =
                        isOpenTerrain(world.getBlockState(mutable));
            }
        }
        return boundaryAir;
    }

    private static boolean isVersion6Cave(
            ServerWorld world, double[] density, boolean[] boundaryAir,
            int x, int y, int z, int worldY) {
        double generatedDensity = interpolateDensity(density, x, y, z);
        if (boundaryAir == null) {
            return generatedDensity > 0.0;
        }
        int depth = world.getBottomY() - worldY;
        double transition = fade(Math.clamp(
                (depth - 1.0) / (LEGACY_V6_TRANSITION_DEPTH - 1.0), 0.0, 1.0));
        double boundaryDensity = boundaryAir[z * CubePos.SIZE + x] ? 1.0 : -1.0;
        return lerp(boundaryDensity, generatedDensity, transition) > 0.0;
    }

    private static boolean matchesLegacyGrid(ServerWorld world, LoadedCube cube) {
        int baseX = cube.pos().minBlockX();
        int baseY = cube.pos().minBlockY();
        int baseZ = cube.pos().minBlockZ();
        double[] density = createLegacyDensityGrid(world.getSeed(), baseX, baseY, baseZ);
        for (int y = 0; y < CubePos.SIZE; y++) {
            int worldY = baseY + y;
            for (int z = 0; z < CubePos.SIZE; z++) {
                for (int x = 0; x < CubePos.SIZE; x++) {
                    boolean cave = worldY < -72 && interpolateDensity(density, x, y, z) > 0.69;
                    if (!matchesGeneratedState(cube, x, y, z, cave)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean matchesLegacyPerBlock(ServerWorld world, LoadedCube cube) {
        int baseX = cube.pos().minBlockX();
        int baseY = cube.pos().minBlockY();
        int baseZ = cube.pos().minBlockZ();
        long seed = world.getSeed();
        for (int y = 0; y < CubePos.SIZE; y++) {
            int worldY = baseY + y;
            for (int z = 0; z < CubePos.SIZE; z++) {
                int worldZ = baseZ + z;
                for (int x = 0; x < CubePos.SIZE; x++) {
                    int worldX = baseX + x;
                    double broad = valueNoise(seed, worldX / 34.0, worldY / 26.0, worldZ / 34.0);
                    double detail = valueNoise(
                            seed ^ 0x6A09E667F3BCC909L,
                            worldX / 15.0, worldY / 12.0, worldZ / 15.0);
                    boolean cave = worldY < -72 && broad * 0.72 + detail * 0.28 > 0.69;
                    if (!matchesGeneratedState(cube, x, y, z, cave)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean matchesPreviousTransition(ServerWorld world, LoadedCube cube) {
        int baseX = cube.pos().minBlockX();
        int baseY = cube.pos().minBlockY();
        int baseZ = cube.pos().minBlockZ();
        int bottomY = world.getBottomY();
        if (baseY + CubePos.SIZE - 1 < bottomY - VANILLA_TRANSITION_DEPTH) {
            return false;
        }
        double[] density = createLegacyDensityGrid(world.getSeed(), baseX, baseY, baseZ);
        for (int y = 0; y < CubePos.SIZE; y++) {
            int depth = bottomY - (baseY + y);
            double transition = fade(Math.clamp(
                    (depth - 1.0) / (VANILLA_TRANSITION_DEPTH - 1.0), 0.0, 1.0));
            for (int z = 0; z < CubePos.SIZE; z++) {
                for (int x = 0; x < CubePos.SIZE; x++) {
                    // The previous implementation sampled the already-replaced
                    // Y=-64 floor, which was solid in every column.
                    boolean cave = lerp(0.0, interpolateDensity(density, x, y, z), transition) > 0.69;
                    if (!matchesGeneratedState(cube, x, y, z, cave)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean matchesSurfaceRouterTerrain(ServerWorld world, LoadedCube cube) {
        int baseX = cube.pos().minBlockX();
        int baseY = cube.pos().minBlockY();
        int baseZ = cube.pos().minBlockZ();
        DensityFunction surfaceDensity = world.getChunkManager().getNoiseConfig()
                .getNoiseRouter().finalDensity();
        double[] density = new double[NOISE_GRID_SIZE * NOISE_GRID_SIZE * NOISE_GRID_SIZE];
        for (int gridY = 0; gridY < NOISE_GRID_SIZE; gridY++) {
            int worldY = baseY + gridY * NOISE_CELL_SIZE;
            for (int gridZ = 0; gridZ < NOISE_GRID_SIZE; gridZ++) {
                int worldZ = baseZ + gridZ * NOISE_CELL_SIZE;
                for (int gridX = 0; gridX < NOISE_GRID_SIZE; gridX++) {
                    int worldX = baseX + gridX * NOISE_CELL_SIZE;
                    density[index(gridX, gridY, gridZ)] = -sampleBandedDensity(
                            surfaceDensity, world.getSeed(), worldX, worldY, worldZ,
                            LEGACY_SURFACE_BAND_HEIGHT, LEGACY_SURFACE_BLEND_HEIGHT);
                }
            }
        }
        boolean[] boundaryAir = createLegacyBoundaryMask(world, baseX, baseY, baseZ);
        for (int y = 0; y < CubePos.SIZE; y++) {
            int worldY = baseY + y;
            for (int z = 0; z < CubePos.SIZE; z++) {
                for (int x = 0; x < CubePos.SIZE; x++) {
                    if (!matchesGeneratedState(
                            cube, x, y, z,
                            isVersion6Cave(world, density, boundaryAir, x, y, z, worldY))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean matchesGeneratedState(LoadedCube cube, int x, int y, int z, boolean cave) {
        BlockState actual = cube.section().getBlockState(x, y, z);
        // Fluids can naturally flow from the vanilla band into a generated cave
        // after first load. Treat them as the original air for migration so a
        // lava cascade does not pin an entire obsolete terrain cube forever.
        return cave ? actual.isAir() || !actual.getFluidState().isEmpty()
                : actual.isOf(Blocks.DEEPSLATE);
    }

    private static double sampleCaveDensity(
            DensityFunction density, long seed, int x, int y, int z) {
        return sampleBandedDensity(
                density, seed, x, y, z, CAVE_NOISE_BAND_HEIGHT, CAVE_NOISE_BLEND_HEIGHT);
    }

    private static double sampleBandedDensity(
            DensityFunction density, long seed, int x, int y, int z,
            int bandHeight, int blendHeight) {
        long depth = Math.max(0L, -65L - y);
        long band = Math.floorDiv(depth, bandHeight);
        int localY = (int) Math.floorMod(depth, bandHeight);
        double current = sampleNoiseBand(density, seed, band, x, -40 + localY, z);
        if (band <= 0 || localY >= blendHeight) {
            return current;
        }

        // Blend the beginning of each new, seed-shifted band with the continued
        // end of the preceding band. No horizontal or vertical plane is created
        // when the wrapped vanilla sampling Y returns from 15 to -40.
        double previous = sampleNoiseBand(
                density, seed, band - 1, x,
                -40 + bandHeight + localY, z);
        double delta = fade(localY / (double) blendHeight);
        return lerp(previous, current, delta);
    }

    private static double sampleNoiseBand(
            DensityFunction density, long seed, long band, int x, int sampleY, int z) {
        int offsetX = band == 0 ? 0 : bandOffset(seed, band, 0x632BE59BD9B4E019L);
        int offsetZ = band == 0 ? 0 : bandOffset(seed, band, 0x85157AF5D66D3E27L);
        return density.sample(new DensityFunction.UnblendedNoisePos(
                x + offsetX, sampleY, z + offsetZ));
    }

    private static int bandOffset(long seed, long band, long salt) {
        long value = seed ^ band * 0x9E3779B97F4A7C15L ^ salt;
        value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
        value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (int) Math.floorMod(value, 2_000_001L) - 1_000_000;
    }

    private static double interpolateDensity(double[] density, int x, int y, int z) {
        int gridX = x >> 2;
        int gridY = y >> 2;
        int gridZ = z >> 2;
        double tx = NOISE_DELTAS[x & 3];
        double ty = NOISE_DELTAS[y & 3];
        double tz = NOISE_DELTAS[z & 3];
        double x00 = lerp(density[index(gridX, gridY, gridZ)], density[index(gridX + 1, gridY, gridZ)], tx);
        double x10 = lerp(
                density[index(gridX, gridY + 1, gridZ)], density[index(gridX + 1, gridY + 1, gridZ)], tx);
        double x01 = lerp(
                density[index(gridX, gridY, gridZ + 1)], density[index(gridX + 1, gridY, gridZ + 1)], tx);
        double x11 = lerp(
                density[index(gridX, gridY + 1, gridZ + 1)],
                density[index(gridX + 1, gridY + 1, gridZ + 1)], tx);
        return lerp(lerp(x00, x10, ty), lerp(x01, x11, ty), tz);
    }

    private static int index(int x, int y, int z) {
        return (y * NOISE_GRID_SIZE + z) * NOISE_GRID_SIZE + x;
    }

    private static double valueNoise(long seed, double x, double y, double z) {
        int x0 = fastFloor(x);
        int y0 = fastFloor(y);
        int z0 = fastFloor(z);
        double tx = fade(x - x0);
        double ty = fade(y - y0);
        double tz = fade(z - z0);

        double x00 = lerp(random(seed, x0, y0, z0), random(seed, x0 + 1, y0, z0), tx);
        double x10 = lerp(random(seed, x0, y0 + 1, z0), random(seed, x0 + 1, y0 + 1, z0), tx);
        double x01 = lerp(random(seed, x0, y0, z0 + 1), random(seed, x0 + 1, y0, z0 + 1), tx);
        double x11 = lerp(random(seed, x0, y0 + 1, z0 + 1), random(seed, x0 + 1, y0 + 1, z0 + 1), tx);
        return lerp(lerp(x00, x10, ty), lerp(x01, x11, ty), tz);
    }

    private static double random(long seed, int x, int y, int z) {
        long value = seed;
        value ^= (long) x * 0x632BE59BD9B4E019L;
        value ^= (long) y * 0x9E3779B97F4A7C15L;
        value ^= (long) z * 0x85157AF5D66D3E27L;
        value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
        value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-53;
    }

    private static int fastFloor(double value) {
        int integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    private static double fade(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private static double lerp(double a, double b, double delta) {
        return a + (b - a) * delta;
    }

    private static boolean isOpenTerrain(BlockState state) {
        return state.isAir() || !state.getFluidState().isEmpty();
    }

    /** Replaces only the lower slide from vanilla overworld.json with a constant 1. */
    private static final class BottomSlideRemover
            implements DensityFunction.DensityFunctionVisitor {
        @Override
        public DensityFunction apply(DensityFunction function) {
            if (!"YClampedGradient".equals(function.getClass().getSimpleName())) {
                return function;
            }
            try {
                int fromY = (int) accessor(function, "fromY");
                int toY = (int) accessor(function, "toY");
                double fromValue = (double) accessor(function, "fromValue");
                double toValue = (double) accessor(function, "toValue");
                if (fromY == -64 && toY == -40
                        && fromValue == 0.0 && toValue == 1.0) {
                    return DensityFunctionTypes.constant(1.0);
                }
                return function;
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Cannot inspect vanilla bottom-slide density", exception);
            }
        }

        private static Object accessor(DensityFunction function, String name)
                throws ReflectiveOperationException {
            java.lang.reflect.Method method = function.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            return method.invoke(function);
        }
    }

    private record BoundaryField(double[] density) {
    }

    private static DensityFunction extendedOverworldDensity(ServerWorld world) {
        return EXTENDED_OVERWORLD_DENSITIES.computeIfAbsent(world, ignored ->
                world.getChunkManager().getNoiseConfig().getNoiseRouter().finalDensity()
                        .apply(new BottomSlideRemover()));
    }

    static net.minecraft.world.gen.noise.NoiseRouter removeVanillaBottomSlide(
            net.minecraft.world.gen.noise.NoiseRouter router) {
        return router.apply(new BottomSlideRemover());
    }
}
