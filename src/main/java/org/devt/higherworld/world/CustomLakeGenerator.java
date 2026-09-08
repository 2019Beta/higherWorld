package org.devt.higherworld.world;

import java.util.Random;
import java.util.Set;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import org.devt.higherworld.storage.CubePos;

/** Configured, cube-local lake carving equivalent to the old WorldGenLakes pass. */
final class CustomLakeGenerator {
    private static final long SALT = 0x4C414B455F47454EL;

    private CustomLakeGenerator() {
    }

    static void generate(ServerWorld world, LoadedCube cube, CustomWorldSettings settings) {
        for (int index = 0; index < settings.lakes().size(); index++) generate(world, cube, settings, index);
    }

    static void generate(ServerWorld world, LoadedCube cube, CustomWorldSettings settings, int index) {
        CustomWorldSettings.LakeSettings lake = settings.lakes().get(index);
        BlockState fluid = CustomBlockStateResolver.resolve(world, lake.block());
        if (fluid == null || !knownBiomes(world, lake.biomes())) {
            return;
        }
        CubePos pos = cube.pos();
        Random random = new Random(seed(world.getSeed(), pos, index));
        int surfaceY = highestSurfaceY(cube);
        int actualY;
        double probability;
        if (surfaceY != Integer.MIN_VALUE) {
            actualY = surfaceY;
            probability = lake.surfaceProbability().getValue(actualY);
        } else {
            actualY = pos.minBlockY() + random.nextInt(CubePos.SIZE);
            probability = lake.mainProbability().getValue(actualY);
        }
        BlockPos biomePos = new BlockPos(pos.minBlockX() + CubePos.SIZE / 2, actualY,
                pos.minBlockZ() + CubePos.SIZE / 2);
        String biome = CustomGenerationSupport.biomeId(world, biomePos, settings.biome());
        boolean listed = containsBiome(lake.biomes(), biome);
        boolean allowed = lake.biomeSelect() == CustomWorldSettings.FilterType.INCLUDE
                ? listed : !listed;
        if (!allowed || random.nextDouble() >= probability) {
            return;
        }
        carveLake(cube, fluid, random, actualY);
    }

    private static void carveLake(LoadedCube cube, BlockState fluid, Random random, int worldY) {
        CubePos pos = cube.pos();
        double centerX = pos.minBlockX() + 1.0 + random.nextInt(CubePos.SIZE - 2);
        double centerZ = pos.minBlockZ() + 1.0 + random.nextInt(CubePos.SIZE - 2);
        double centerY = worldY + random.nextDouble() - 0.5;
        int lobes = 4 + random.nextInt(5);
        for (int lobe = 0; lobe < lobes; lobe++) {
            double radiusX = 1.5 + random.nextDouble() * 2.5;
            double radiusZ = 1.5 + random.nextDouble() * 2.5;
            double radiusY = 1.0 + random.nextDouble() * 1.5;
            double lobeX = centerX + (random.nextDouble() - 0.5) * 3.0;
            double lobeZ = centerZ + (random.nextDouble() - 0.5) * 3.0;
            int minX = Math.max(0, (int) Math.floor(lobeX - radiusX) - pos.minBlockX());
            int maxX = Math.min(CubePos.SIZE - 1, (int) Math.ceil(lobeX + radiusX) - pos.minBlockX());
            int minY = Math.max(0, (int) Math.floor(centerY - radiusY) - pos.minBlockY());
            int maxY = Math.min(CubePos.SIZE - 1, (int) Math.ceil(centerY + radiusY) - pos.minBlockY());
            int minZ = Math.max(0, (int) Math.floor(lobeZ - radiusZ) - pos.minBlockZ());
            int maxZ = Math.min(CubePos.SIZE - 1, (int) Math.ceil(lobeZ + radiusZ) - pos.minBlockZ());
            for (int y = minY; y <= maxY; y++) {
                double dy = (pos.minBlockY() + y + 0.5 - centerY) / radiusY;
                for (int z = minZ; z <= maxZ; z++) {
                    double dz = (pos.minBlockZ() + z + 0.5 - lobeZ) / radiusZ;
                    for (int x = minX; x <= maxX; x++) {
                        double dx = (pos.minBlockX() + x + 0.5 - lobeX) / radiusX;
                        if (dx * dx + dy * dy + dz * dz >= 1.0) {
                            continue;
                        }
                        BlockState current = cube.section().getBlockState(x, y, z);
                        if (!isNatural(current)) {
                            continue;
                        }
                        cube.setGeneratedBlockState(x, y, z,
                                pos.minBlockY() + y <= centerY
                                        ? fluid : Blocks.AIR.getDefaultState());
                    }
                }
            }
        }
    }

    /** Finds the highest solid/air interface present in this cube. */
    static int highestSurfaceY(LoadedCube cube) {
        CubePos pos = cube.pos();
        if (cube.section().isEmpty()) return Integer.MIN_VALUE;
        // Descending layers let the first interface answer the global maximum.
        // Test air above first: solid underground cubes need only one read per cell.
        for (int y = CubePos.SIZE - 2; y >= 0; y--) {
            for (int z = 0; z < CubePos.SIZE; z++) {
                for (int x = 0; x < CubePos.SIZE; x++) {
                    BlockState above = cube.section().getBlockState(x, y + 1, z);
                    if (!above.isAir()) continue;
                    BlockState current = cube.section().getBlockState(x, y, z);
                    if (!current.isAir() && current.getFluidState().isEmpty()) {
                        return pos.minBlockY() + y;
                    }
                }
            }
        }
        return Integer.MIN_VALUE;
    }

    private static boolean knownBiomes(ServerWorld world, Set<String> values) {
        Registry<net.minecraft.world.biome.Biome> registry =
                world.getRegistryManager().getOrThrow(RegistryKeys.BIOME);
        for (String value : values) {
            try {
                String normalized = CustomGenerationSupport.normalizeBiomeId(value);
                Identifier id = Identifier.of(normalized);
                if (registry.getOptionalValue(id).isEmpty()) {
                    CustomGenerationSupport.warnOnce("lake-biome:" + value,
                            "unknown configured lake biome " + value);
                    return false;
                }
            } catch (RuntimeException exception) {
                CustomGenerationSupport.warnOnce("lake-biome:" + value,
                        "invalid configured lake biome " + value);
                return false;
            }
        }
        return true;
    }

    private static boolean containsBiome(Set<String> values, String actual) {
        for (String value : values) {
            String normalized = CustomGenerationSupport.normalizeBiomeId(value);
            if (normalized.equals(actual)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNatural(BlockState state) {
        return state.isOf(Blocks.STONE) || state.isOf(Blocks.DEEPSLATE)
                || state.isOf(Blocks.TUFF) || state.isOf(Blocks.DIRT)
                || state.isOf(Blocks.GRASS_BLOCK);
    }

    private static long seed(long worldSeed, CubePos pos, int index) {
        long value = worldSeed ^ SALT
                ^ (long) pos.x() * 0x9E3779B97F4A7C15L
                ^ (long) pos.y() * 0xC2B2AE3D27D4EB4FL
                ^ (long) pos.z() * 0x165667B19E3779F9L
                ^ (long) index * 0xD6E8FEB86659FD93L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
