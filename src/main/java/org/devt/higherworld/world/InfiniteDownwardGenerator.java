package org.devt.higherworld.world;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import org.devt.higherworld.storage.CubePos;

/** Lazily extends Overworld terrain below the vanilla generation band. */
final class InfiniteDownwardGenerator {
    private static final BlockState AIR = Blocks.AIR.getDefaultState();
    private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.getDefaultState();
    private static final int NOISE_CELL_SIZE = 4;
    private static final int NOISE_GRID_SIZE = CubePos.SIZE / NOISE_CELL_SIZE + 1;
    private static final int VANILLA_TRANSITION_DEPTH = 32;
    private static final double[] NOISE_DELTAS = {0.0, 0.15625, 0.5, 0.84375};

    private InfiniteDownwardGenerator() {
    }

    static void generate(ServerWorld world, LoadedCube cube) {
        CubePos pos = cube.pos();
        int baseX = pos.minBlockX();
        int baseY = pos.minBlockY();
        int baseZ = pos.minBlockZ();
        long seed = world.getSeed();
        double[] density = createDensityGrid(seed, baseX, baseY, baseZ);
        boolean[] boundaryAir = createBoundaryMask(world, baseX, baseY, baseZ);

        for (int localY = 0; localY < CubePos.SIZE; localY++) {
            int y = baseY + localY;
            for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
                for (int localX = 0; localX < CubePos.SIZE; localX++) {
                    if (!isCave(world, density, boundaryAir, localX, localY, localZ, y)) {
                        cube.setGeneratedBlockState(localX, localY, localZ, DEEPSLATE);
                    }
                }
            }
        }
        cube.markDirty();
    }

    /** Upgrades untouched cubes produced by either previous generator revision. */
    static boolean upgradeLegacyTransition(ServerWorld world, LoadedCube cube) {
        int bottomY = world.getBottomY();
        int topY = cube.pos().minBlockY() + CubePos.SIZE - 1;
        if (topY < bottomY - VANILLA_TRANSITION_DEPTH || !cube.blockEntities().isEmpty()) {
            return false;
        }
        if (!matchesLegacyGrid(world, cube) && !matchesLegacyPerBlock(world, cube)) {
            return false;
        }
        for (int y = 0; y < CubePos.SIZE; y++) {
            for (int z = 0; z < CubePos.SIZE; z++) {
                for (int x = 0; x < CubePos.SIZE; x++) {
                    cube.setGeneratedBlockState(x, y, z, AIR);
                }
            }
        }
        generate(world, cube);
        return true;
    }

    private static double[] createDensityGrid(long seed, int baseX, int baseY, int baseZ) {
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

    private static boolean[] createBoundaryMask(
            ServerWorld world, int baseX, int baseY, int baseZ) {
        int bottomY = world.getBottomY();
        if (baseY + CubePos.SIZE - 1 < bottomY - VANILLA_TRANSITION_DEPTH) {
            return null;
        }
        boolean[] boundaryAir = new boolean[CubePos.SIZE * CubePos.SIZE];
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
            for (int localX = 0; localX < CubePos.SIZE; localX++) {
                mutable.set(baseX + localX, bottomY, baseZ + localZ);
                boundaryAir[localZ * CubePos.SIZE + localX] = world.getBlockState(mutable).isAir();
            }
        }
        return boundaryAir;
    }

    private static boolean isCave(
            ServerWorld world, double[] density, boolean[] boundaryAir,
            int x, int y, int z, int worldY) {
        double generatedDensity = interpolateDensity(density, x, y, z);
        if (boundaryAir == null) {
            return generatedDensity > 0.69;
        }

        // Match the exact air/solid state of the vanilla bottom at Y=-64, then
        // fade into HigherWorld noise over two cubes. This removes the planar
        // seam without forcing a new artificial solid band below the old floor.
        int depth = world.getBottomY() - worldY;
        double transition = fade(Math.clamp((depth - 1.0) / (VANILLA_TRANSITION_DEPTH - 1.0), 0.0, 1.0));
        double boundaryDensity = boundaryAir[z * CubePos.SIZE + x] ? 1.0 : 0.0;
        return lerp(boundaryDensity, generatedDensity, transition) > 0.69;
    }

    private static boolean matchesLegacyGrid(ServerWorld world, LoadedCube cube) {
        int baseX = cube.pos().minBlockX();
        int baseY = cube.pos().minBlockY();
        int baseZ = cube.pos().minBlockZ();
        double[] density = createDensityGrid(world.getSeed(), baseX, baseY, baseZ);
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

    private static boolean matchesGeneratedState(LoadedCube cube, int x, int y, int z, boolean cave) {
        BlockState actual = cube.section().getBlockState(x, y, z);
        return cave ? actual.isAir() : actual.isOf(Blocks.DEEPSLATE);
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

    static void openVanillaFloor(ServerWorld world, WorldChunk chunk) {
        int bottomY = world.getBottomY();
        int baseX = chunk.getPos().getStartX();
        int baseZ = chunk.getPos().getStartZ();
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        boolean changed = false;
        for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
            for (int localX = 0; localX < CubePos.SIZE; localX++) {
                for (int y = bottomY; y < bottomY + 5; y++) {
                    mutable.set(baseX + localX, y, baseZ + localZ);
                    if (chunk.getBlockState(mutable).isOf(Blocks.BEDROCK)) {
                        chunk.setBlockState(mutable, DEEPSLATE, 0);
                        changed = true;
                    }
                }
            }
        }
        if (changed) {
            chunk.markNeedsSaving();
        }
    }
}
