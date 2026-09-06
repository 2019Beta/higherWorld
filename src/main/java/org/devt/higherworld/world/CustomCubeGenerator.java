package org.devt.higherworld.world;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import org.devt.higherworld.gpu.GpuTerrainAccelerator;
import org.devt.higherworld.storage.CubePos;

/** Complete server-side generation pipeline for the CUSTOM_OVERWORLD preset. */
final class CustomCubeGenerator {
    /** Deliberately independent from InfiniteDownwardGenerator.GENERATION_VERSION. */
    static final int GENERATION_VERSION = 100;
    private static final long TERRAIN_SALT = 0x5445525241494EL;
    private static final long SELECTOR_SALT = 0x53454C454354L;
    private static final long LOW_SALT = 0x4C4F575F4E4F49L;
    private static final long HIGH_SALT = 0x484947485F4E4FL;
    private static final long BASE_HEIGHT_SALT = 0x424153455F4844L;
    private static final long VOLATILITY_SALT = 0x564F4C4154494CL;
    private static final BlockState AIR = Blocks.AIR.getDefaultState();
    private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.getDefaultState();
    private static final ThreadLocal<CpuScratch> CPU_SCRATCH =
            ThreadLocal.withInitial(CpuScratch::new);

    private CustomCubeGenerator() {
    }

    static void generate(
            ServerWorld world, LoadedCube cube, CustomWorldSettings settings,
            StructureGenerationSettings structureSettings, boolean generateStructures) {
        applyTerrain(cube, prepareTerrain(world.getSeed(), cube.pos(), settings));
        finishGeneration(world, cube, settings, structureSettings, generateStructures);
    }

    /** Terrain preparation phase; it is safe to run on a generation worker. */
    static TerrainSnapshot prepareTerrain(long seed, CubePos pos, CustomWorldSettings settings) {
        boolean[] solid = new boolean[TerrainInterpolation.VOXEL_COUNT];
        if (GpuTerrainAccelerator.trySampleAndRasterizeCustom(seed, pos, settings, solid)) {
            return new TerrainSnapshot(solid);
        }
        fillCpuTerrain(seed, pos, settings, solid);
        return new TerrainSnapshot(solid);
    }

    /** CPU-only path used when a GPU batch falls back after device probing. */
    static TerrainSnapshot prepareTerrainCpu(long seed, CubePos pos, CustomWorldSettings settings) {
        boolean[] solid = new boolean[TerrainInterpolation.VOXEL_COUNT];
        fillCpuTerrain(seed, pos, settings, solid);
        return new TerrainSnapshot(solid);
    }

    private static void fillCpuTerrain(
            long seed, CubePos pos, CustomWorldSettings settings, boolean[] solid) {
        int stepX = settings.noiseSampleSizeX();
        int stepY = settings.noiseSampleSizeY();
        int stepZ = settings.noiseSampleSizeZ();
        int cellsX = CubePos.SIZE / stepX;
        int cellsY = CubePos.SIZE / stepY;
        int cellsZ = CubePos.SIZE / stepZ;
        CpuScratch scratch = CPU_SCRATCH.get();
        double[] samples = scratch.samples(
                (cellsX + 1) * (cellsY + 1) * (cellsZ + 1));

        // Base height, depth noise, and the unmodified volatility are all
        // functions of X/Z only.  Computing them once per column removes the
        // same expensive octave-gradient calls from every vertical sample.
        int surfaceWidth = cellsX + 1;
        int surfaceDepth = cellsZ + 1;
        double[] surface = scratch.surface(surfaceWidth * surfaceDepth * 3);
        double scaleBase = Math.pow(2.0, -Math.min(1022, Math.max(0, settings.biomeSize())));
        double scaleRiver = Math.pow(2.0, -Math.min(1022, Math.max(0, settings.riverSize())));
        for (int gridZ = 0; gridZ <= cellsZ; gridZ++) {
            for (int gridX = 0; gridX <= cellsX; gridX++) {
                int x = pos.minBlockX() + gridX * stepX;
                int z = pos.minBlockZ() + gridZ * stepZ;
                int surfaceIndex = (gridZ * surfaceWidth + gridX) * 3;
                surface[surfaceIndex] = CustomNoise.depthNoise(
                        seed ^ TERRAIN_SALT, x, z, settings);
                surface[surfaceIndex + 1] = CustomNoise.baseHeight(
                        seed ^ BASE_HEIGHT_SALT, x, z, scaleBase);
                surface[surfaceIndex + 2] = CustomNoise.volatilityBase(
                        seed ^ VOLATILITY_SALT, x, z, scaleRiver);
            }
        }

        for (int gridY = 0; gridY <= cellsY; gridY++) {
            for (int gridZ = 0; gridZ <= cellsZ; gridZ++) {
                for (int gridX = 0; gridX <= cellsX; gridX++) {
                    int x = pos.minBlockX() + gridX * stepX;
                    int y = pos.minBlockY() + gridY * stepY;
                    int z = pos.minBlockZ() + gridZ * stepZ;
                    int surfaceIndex = (gridZ * surfaceWidth + gridX) * 3;
                    double selector = CustomNoise.octaveGradient(
                            seed ^ SELECTOR_SALT, x, y, z,
                            settings.selectorNoiseFrequencyX(),
                            settings.selectorNoiseFrequencyY(),
                            settings.selectorNoiseFrequencyZ(),
                            settings.selectorNoiseOctaves());
                    selector = CustomNoise.clamp(
                            selector * settings.selectorNoiseFactor()
                                    + settings.selectorNoiseOffset(), 0.0, 1.0);
                    double low = CustomNoise.octaveGradient(
                            seed ^ LOW_SALT, x, y, z,
                            settings.lowNoiseFrequencyX(), settings.lowNoiseFrequencyY(),
                            settings.lowNoiseFrequencyZ(), settings.lowNoiseOctaves())
                            * settings.lowNoiseFactor() + settings.lowNoiseOffset();
                    double high = CustomNoise.octaveGradient(
                            seed ^ HIGH_SALT, x, y, z,
                            settings.highNoiseFrequencyX(), settings.highNoiseFrequencyY(),
                            settings.highNoiseFrequencyZ(), settings.highNoiseOctaves())
                            * settings.highNoiseFactor() + settings.highNoiseOffset();
                    double terrainNoise = low + (high - low) * selector + surface[surfaceIndex];
                    double height = surface[surfaceIndex + 1] * settings.heightFactor()
                            + settings.heightOffset();
                    double volatilityBase = surface[surfaceIndex + 2];
                    if (height > y) {
                        volatilityBase *= settings.specialHeightVariationFactorBelowAverageY();
                    }
                    double volatility = volatilityBase * settings.heightVariationFactor()
                            + settings.heightVariationOffset();
                    samples[index(gridX, gridY, gridZ, cellsX, cellsZ)] =
                            terrainNoise * volatility + height - y * Math.signum(volatility);
                }
            }
        }

        TerrainInterpolation.fillSolid(
                samples, stepX, stepY, stepZ, cellsX, cellsY, cellsZ, solid);
    }

    /** Main-thread commit of the immutable worker result. */
    static void applyTerrain(LoadedCube cube, TerrainSnapshot snapshot) {
        boolean[] solid = snapshot.rawSolid();
        // Missing sparse cubes start as air.  Write only solid voxels in that
        // common case; preserve the old clearing behavior if a caller applies
        // a snapshot to a pre-populated section (for example during a repair).
        boolean clearAir = !cube.section().isEmpty();

        for (int localY = 0; localY < CubePos.SIZE; localY++) {
            for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
                for (int localX = 0; localX < CubePos.SIZE; localX++) {
                    if (solid[blockIndex(localX, localY, localZ)]) {
                        cube.setGeneratedBlockState(localX, localY, localZ,
                                DEEPSLATE);
                    } else if (clearAir) {
                        cube.setGeneratedBlockState(localX, localY, localZ,
                                AIR);
                    }
                }
            }
        }
    }

    /** World-aware feature phase; invoked only by a server-thread commit. */
    static void finishGeneration(
            ServerWorld world, LoadedCube cube, CustomWorldSettings settings,
            StructureGenerationSettings structureSettings, boolean generateStructures) {
        finishGeneration(world, cube, settings, structureSettings,
                generateStructures, 0, Long.MAX_VALUE);
    }

    /**
     * Deadline-sliced variant.  {@code progress} indexes the finish stages
     * below; a negative result means the cube is complete, otherwise the
     * returned value is the next resume cursor.  The first fresh stage of a
     * slice always runs, so a streaming commit keeps making progress without
     * holding the server thread past its budget.
     */
    static int finishGeneration(
            ServerWorld world, LoadedCube cube, CustomWorldSettings settings,
            StructureGenerationSettings structureSettings, boolean generateStructures,
            int progress, long deadlineNanos) {
        switch (progress) {
            case 0:
                CustomCaveGenerator.generate(world, cube, settings.caves());
                if (System.nanoTime() >= deadlineNanos) return 1;
                // fallthrough
            case 1:
                if (settings.ravines()) {
                    CustomCaveGenerator.generateRavine(world, cube);
                }
                if (System.nanoTime() >= deadlineNanos) return 2;
                // fallthrough
            case 2:
                if (generateStructures) {
                    VanillaStructureGenerator.generate(world, cube, structureSettings, settings);
                }
                if (System.nanoTime() >= deadlineNanos) return 3;
                // fallthrough
            case 3:
                CustomLakeGenerator.generate(world, cube, settings);
                if (System.nanoTime() >= deadlineNanos) return 4;
                // fallthrough
            case 4:
                CustomDungeonGenerator.generate(world, cube, settings);
                if (System.nanoTime() >= deadlineNanos) return 5;
                // fallthrough
            case 5:
                CustomOreGenerator.generateUniform(world, cube, settings);
                if (System.nanoTime() >= deadlineNanos) return 6;
                // fallthrough
            case 6:
                CustomOreGenerator.generatePeriodic(world, cube, settings);
                // fallthrough
            default:
                cube.setGenerationVersion(GENERATION_VERSION);
                cube.markDirty();
                return -1;
        }
    }

    /** Samples the migrated CustomTerrainGenerator equation at an exact point. */
    static double terrainDensity(
            ServerWorld world, CustomWorldSettings settings, int x, int y, int z) {
        if (settings.biome() == null) {
            // 1.21 no longer exposes baseHeight/heightVariation on Biome.  We
            // still resolve the active biome at every sample so the terrain
            // path uses the same world biome source as the feature filters;
            // the replacement numeric fields below remain stable 2-D noise.
            CustomGenerationSupport.biomeId(world, new BlockPos(x, y, z), null);
        }
        return terrainDensity(world.getSeed(), settings, x, y, z);
    }

    /** Pure overload used by deterministic tests and by headless preset tooling. */
    static double terrainDensity(
            long seed, CustomWorldSettings settings, double x, double y, double z) {
        double selector = CustomNoise.octaveGradient(seed ^ SELECTOR_SALT, x, y, z,
                settings.selectorNoiseFrequencyX(), settings.selectorNoiseFrequencyY(),
                settings.selectorNoiseFrequencyZ(), settings.selectorNoiseOctaves());
        selector = CustomNoise.clamp(
                selector * settings.selectorNoiseFactor() + settings.selectorNoiseOffset(), 0.0, 1.0);
        double low = CustomNoise.octaveGradient(seed ^ LOW_SALT, x, y, z,
                settings.lowNoiseFrequencyX(), settings.lowNoiseFrequencyY(),
                settings.lowNoiseFrequencyZ(), settings.lowNoiseOctaves())
                * settings.lowNoiseFactor() + settings.lowNoiseOffset();
        double high = CustomNoise.octaveGradient(seed ^ HIGH_SALT, x, y, z,
                settings.highNoiseFrequencyX(), settings.highNoiseFrequencyY(),
                settings.highNoiseFrequencyZ(), settings.highNoiseOctaves())
                * settings.highNoiseFactor() + settings.highNoiseOffset();
        // CustomTerrainGenerator adds the processed 2-D depth noise to the
        // selector blend before applying biome volatility.  Keeping it in
        // this term is important: depthNoise is a terrain perturbation, not
        // an absolute height offset.
        double terrainNoise = low + (high - low) * selector
                + CustomNoise.depthNoise(seed ^ TERRAIN_SALT, x, z, settings);

        double scaleBase = Math.pow(2.0, -Math.min(1022, Math.max(0, settings.biomeSize())));
        double scaleRiver = Math.pow(2.0, -Math.min(1022, Math.max(0, settings.riverSize())));
        // Modern Biome has no 1.12 baseHeight/heightVariation.  These stable
        // fields are the equivalent numeric source; a fixed biome therefore
        // affects filtering only, while a non-fixed world biome is queried by
        // the feature passes using CustomGenerationSupport.biomeId.
        double base = CustomNoise.octaveGradient2D(seed ^ BASE_HEIGHT_SALT, x, z,
                scaleBase, scaleBase, 2);
        double volatilityBase = CustomNoise.clamp(0.5 + 0.5 * CustomNoise.octaveGradient2D(
                seed ^ VOLATILITY_SALT, x, z, scaleRiver, scaleRiver, 2), 0.0, 1.0);
        // actualHeight is legacy preview/metadata, not a second density limit.
        // expectedBaseHeight/expectedHeightVariation are intentionally consumed
        // by ore conversion instead of silently changing this terrain equation.
        double height = base * settings.heightFactor() + settings.heightOffset();
        if (height > y) {
            volatilityBase *= settings.specialHeightVariationFactorBelowAverageY();
        }
        double volatility = volatilityBase * settings.heightVariationFactor()
                + settings.heightVariationOffset();
        return terrainNoise * volatility + height - y * Math.signum(volatility);
    }

    static double interpolate(
            double[] samples, int x, int y, int z,
            int stepX, int stepY, int stepZ,
            int cellsX, int cellsY, int cellsZ) {
        int gridX = Math.min(cellsX - 1, x / stepX);
        int gridY = Math.min(cellsY - 1, y / stepY);
        int gridZ = Math.min(cellsZ - 1, z / stepZ);
        double tx = (x - gridX * stepX) / (double) stepX;
        double ty = (y - gridY * stepY) / (double) stepY;
        double tz = (z - gridZ * stepZ) / (double) stepZ;
        double x00 = lerp(samples[index(gridX, gridY, gridZ, cellsX, cellsZ)],
                samples[index(gridX + 1, gridY, gridZ, cellsX, cellsZ)], tx);
        double x10 = lerp(samples[index(gridX, gridY + 1, gridZ, cellsX, cellsZ)],
                samples[index(gridX + 1, gridY + 1, gridZ, cellsX, cellsZ)], tx);
        double x01 = lerp(samples[index(gridX, gridY, gridZ + 1, cellsX, cellsZ)],
                samples[index(gridX + 1, gridY, gridZ + 1, cellsX, cellsZ)], tx);
        double x11 = lerp(samples[index(gridX, gridY + 1, gridZ + 1, cellsX, cellsZ)],
                samples[index(gridX + 1, gridY + 1, gridZ + 1, cellsX, cellsZ)], tx);
        return lerp(lerp(x00, x10, ty), lerp(x01, x11, ty), tz);
    }

    private static int index(int x, int y, int z, int cellsX, int cellsZ) {
        return (y * (cellsZ + 1) + z) * (cellsX + 1) + x;
    }

    private static int blockIndex(int x, int y, int z) {
        return (y * CubePos.SIZE + z) * CubePos.SIZE + x;
    }

    private static double lerp(double first, double second, double amount) {
        return first + (second - first) * amount;
    }

    /** Reuses the tiny numeric grids on each generation worker. */
    private static final class CpuScratch {
        private double[] samples = new double[0];
        private double[] surface = new double[0];

        private double[] samples(int size) {
            if (samples.length < size) samples = new double[size];
            return samples;
        }

        private double[] surface(int size) {
            if (surface.length < size) surface = new double[size];
            return surface;
        }
    }

    record TerrainSnapshot(boolean[] solid) implements CubeTerrainSnapshot {
        TerrainSnapshot {
            if (solid.length != CubePos.SIZE * CubePos.SIZE * CubePos.SIZE) {
                throw new IllegalArgumentException("A terrain snapshot must contain exactly 4096 blocks");
            }
            solid = solid.clone();
        }

        @Override
        public boolean[] solid() {
            return solid.clone();
        }

        /** Avoids a second 4096-entry clone on the server-thread commit path. */
        boolean[] rawSolid() {
            return solid;
        }

        @Override
        public void applyTo(LoadedCube cube) {
            CustomCubeGenerator.applyTerrain(cube, this);
        }
    }
}
