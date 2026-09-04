package org.devt.higherworld.world;

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
        int stepX = settings.noiseSampleSizeX();
        int stepY = settings.noiseSampleSizeY();
        int stepZ = settings.noiseSampleSizeZ();
        int cellsX = CubePos.SIZE / stepX;
        int cellsY = CubePos.SIZE / stepY;
        int cellsZ = CubePos.SIZE / stepZ;
        double[] samples = new double[(cellsX + 1) * (cellsY + 1) * (cellsZ + 1)];
        if (!GpuTerrainAccelerator.trySample(seed, pos, settings, samples)) {
            for (int gridY = 0; gridY <= cellsY; gridY++) {
                for (int gridZ = 0; gridZ <= cellsZ; gridZ++) {
                    for (int gridX = 0; gridX <= cellsX; gridX++) {
                        int x = pos.minBlockX() + gridX * stepX;
                        int y = pos.minBlockY() + gridY * stepY;
                        int z = pos.minBlockZ() + gridZ * stepZ;
                        samples[index(gridX, gridY, gridZ, cellsX, cellsZ)] =
                                terrainDensity(seed, settings, x, y, z);
                    }
                }
            }
        }
        boolean[] solid = new boolean[CubePos.SIZE * CubePos.SIZE * CubePos.SIZE];
        for (int localY = 0; localY < CubePos.SIZE; localY++) {
            for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
                for (int localX = 0; localX < CubePos.SIZE; localX++) {
                    double density = interpolate(samples, localX, localY, localZ,
                            stepX, stepY, stepZ, cellsX, cellsY, cellsZ);
                    solid[blockIndex(localX, localY, localZ)] = density > 0.0;
                }
            }
        }
        return new TerrainSnapshot(solid);
    }

    /** Main-thread commit of the immutable worker result. */
    static void applyTerrain(LoadedCube cube, TerrainSnapshot snapshot) {
        boolean[] solid = snapshot.solid();
        for (int localY = 0; localY < CubePos.SIZE; localY++) {
            for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
                for (int localX = 0; localX < CubePos.SIZE; localX++) {
                    cube.setGeneratedBlockState(localX, localY, localZ,
                            solid[blockIndex(localX, localY, localZ)]
                                    ? Blocks.DEEPSLATE.getDefaultState()
                                    : Blocks.AIR.getDefaultState());
                }
            }
        }
    }

    /** World-aware feature phase; invoked only by a server-thread commit. */
    static void finishGeneration(
            ServerWorld world, LoadedCube cube, CustomWorldSettings settings,
            StructureGenerationSettings structureSettings, boolean generateStructures) {
        CustomCaveGenerator.generate(world, cube, settings.caves());
        if (settings.ravines()) {
            CustomCaveGenerator.generateRavine(world, cube);
        }
        if (generateStructures) {
            VanillaStructureGenerator.generate(world, cube, structureSettings, settings);
        }
        CustomLakeGenerator.generate(world, cube, settings);
        CustomDungeonGenerator.generate(world, cube, settings);
        CustomOreGenerator.generateUniform(world, cube, settings);
        CustomOreGenerator.generatePeriodic(world, cube, settings);
        cube.setGenerationVersion(GENERATION_VERSION);
        cube.markDirty();
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

        @Override
        public void applyTo(LoadedCube cube) {
            CustomCubeGenerator.applyTerrain(cube, this);
        }
    }
}
