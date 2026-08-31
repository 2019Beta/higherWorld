package org.devt.higherworld.world;

import java.util.Random;
import java.util.Set;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import org.devt.higherworld.Higherworld;
import org.devt.higherworld.storage.CubePos;

/** Deterministic configured uniform and periodic ore placement for custom cubes. */
final class CustomOreGenerator {
    private static final long UNIFORM_SALT = 0x554E49464F524DL;
    private static final long PERIODIC_SALT = 0x504552494F4449L;

    private CustomOreGenerator() {
    }

    static void generateUniform(
            ServerWorld world, LoadedCube cube, CustomWorldSettings settings) {
        for (int index = 0; index < settings.standardOres().size(); index++) {
            CustomWorldSettings.OreSettings ore = settings.standardOres().get(index);
            generate(world, cube, settings, ore, index, UNIFORM_SALT, false);
        }
    }

    static void generatePeriodic(
            ServerWorld world, LoadedCube cube, CustomWorldSettings settings) {
        for (int index = 0; index < settings.periodicGaussianOres().size(); index++) {
            CustomWorldSettings.OreSettings ore = settings.periodicGaussianOres().get(index);
            generate(world, cube, settings, ore, index, PERIODIC_SALT, true);
        }
    }

    private static void generate(
            ServerWorld world, LoadedCube cube, CustomWorldSettings settings,
            CustomWorldSettings.OreSettings ore, int index, long salt, boolean periodic) {
        BlockState oreState = CustomBlockStateResolver.resolve(world, ore.blockstate());
        if (oreState == null) {
            return;
        }
        CubePos pos = cube.pos();
        int minY = Math.max(pos.minBlockY(), absoluteHeight(ore.minHeight(), settings));
        int maxY = Math.min(pos.minBlockY() + CubePos.SIZE - 1,
                absoluteHeight(ore.maxHeight(), settings));
        if (minY > maxY || !isKnownBiomeFilter(world, ore.biomes())) {
            return;
        }
        String fixedBiome = settings.biome();
        String biome = CustomGenerationSupport.biomeId(
                world, new BlockPos(pos.minBlockX() + CubePos.SIZE / 2, minY,
                        pos.minBlockZ() + CubePos.SIZE / 2), fixedBiome);
        if (!matchesBiome(ore.biomes(), biome)) {
            return;
        }
        Random random = new Random(seed(world.getSeed(), pos, index, salt));
        for (int attempt = 0; attempt < ore.spawnTries(); attempt++) {
            if (random.nextDouble() >= ore.spawnProbability()) {
                continue;
            }
            int y = minY + random.nextInt(maxY - minY + 1);
            if (periodic) {
                double mean = settings.expectedBaseHeight()
                        + ore.heightMean() * settings.expectedHeightVariation();
                double standardDeviation = ore.heightStdDeviation()
                        * settings.expectedHeightVariation();
                double spacing = ore.heightSpacing() * settings.expectedHeightVariation();
                double cyclicProbability = cyclicBellCurveProbability(
                        y, mean, standardDeviation, spacing);
                if (random.nextDouble() >= cyclicProbability) {
                    continue;
                }
            }
            placeVein(cube, oreState, random, y, ore.spawnSize());
        }
    }

    /**
     * The cyclic bell curve used by PopulatorUtils/MathUtil in the reference
     * generator.  A zero deviation is accepted for the uniform category only;
     * periodic settings reject it only when callers choose to, so this helper
     * still has a well-defined point-mass result for tests and presets.
     */
    static double cyclicBellCurveProbability(
            double value, double mean, double standardDeviation, double spacing) {
        if (!(spacing > 0.0) || !Double.isFinite(value) || !Double.isFinite(mean)
                || !Double.isFinite(standardDeviation) || standardDeviation < 0.0) {
            return 0.0;
        }
        double halfSpace = spacing / 2.0;
        double shiftedLocation = value - halfSpace - mean;
        double factor = Math.abs(shiftedLocation % spacing) - halfSpace;
        if (standardDeviation == 0.0) {
            return Math.abs(factor) <= 1.0e-9 ? 1.0 : 0.0;
        }
        double divisorExponent = 2.0 * standardDeviation * standardDeviation;
        double exponent = (-factor) * factor / divisorExponent;
        return Math.exp(exponent);
    }

    /** PopulatorUtils' normalized-height conversion, with explicit infinities. */
    static int absoluteHeight(double normalizedHeight, CustomWorldSettings settings) {
        if (normalizedHeight == Double.NEGATIVE_INFINITY) {
            return Integer.MIN_VALUE;
        }
        if (normalizedHeight == Double.POSITIVE_INFINITY) {
            return Integer.MAX_VALUE;
        }
        double value = Math.round(normalizedHeight * settings.expectedHeightVariation()
                + settings.expectedBaseHeight());
        if (value <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (value >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) value;
    }

    private static void placeVein(
            LoadedCube cube, BlockState oreState, Random random, int worldY, int size) {
        CubePos pos = cube.pos();
        int x = random.nextInt(CubePos.SIZE);
        int y = worldY - pos.minBlockY();
        int z = random.nextInt(CubePos.SIZE);
        for (int block = 0; block < size; block++) {
            if (x >= 0 && x < CubePos.SIZE && y >= 0 && y < CubePos.SIZE
                    && z >= 0 && z < CubePos.SIZE) {
                BlockState current = cube.section().getBlockState(x, y, z);
                if (isNatural(current)) {
                    cube.setGeneratedBlockState(x, y, z, oreState);
                }
            }
            x = Math.max(0, Math.min(CubePos.SIZE - 1, x + random.nextInt(3) - 1));
            y = Math.max(0, Math.min(CubePos.SIZE - 1, y + random.nextInt(3) - 1));
            z = Math.max(0, Math.min(CubePos.SIZE - 1, z + random.nextInt(3) - 1));
        }
    }

    private static boolean isNatural(BlockState state) {
        return state.isOf(Blocks.STONE) || state.isOf(Blocks.DEEPSLATE)
                || state.isOf(Blocks.TUFF);
    }

    private static boolean isKnownBiomeFilter(ServerWorld world, Set<String> biomes) {
        if (biomes == null) {
            return true;
        }
        Registry<net.minecraft.world.biome.Biome> registry =
                world.getRegistryManager().getOrThrow(RegistryKeys.BIOME);
        for (String value : biomes) {
            try {
                Identifier id = Identifier.of(CustomGenerationSupport.normalizeBiomeId(value));
                if (registry.getOptionalValue(id).isEmpty()) {
                    CustomGenerationSupport.warnOnce("biome:" + value,
                            "unknown configured ore biome " + value);
                    return false;
                }
            } catch (RuntimeException exception) {
                CustomGenerationSupport.warnOnce("biome:" + value,
                        "invalid configured ore biome " + value);
                return false;
            }
        }
        return true;
    }

    private static boolean matchesBiome(Set<String> configured, String actual) {
        if (configured == null) {
            return true;
        }
        for (String value : configured) {
            String normalized = CustomGenerationSupport.normalizeBiomeId(value);
            if (normalized.equals(actual)) {
                return true;
            }
        }
        return false;
    }

    private static long seed(long worldSeed, CubePos pos, int index, long salt) {
        long value = worldSeed ^ salt
                ^ (long) pos.x() * 0x9E3779B97F4A7C15L
                ^ (long) pos.y() * 0xC2B2AE3D27D4EB4FL
                ^ (long) pos.z() * 0x165667B19E3779F9L
                ^ (long) index * 0xD6E8FEB86659FD93L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
