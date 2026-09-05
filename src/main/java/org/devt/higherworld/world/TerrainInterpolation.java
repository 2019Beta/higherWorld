package org.devt.higherworld.world;

/**
 * Allocation-free trilinear rasterization used by the sparse terrain paths.
 *
 * <p>The density grid is deliberately kept small (the largest supported
 * grid is 17^3).  The expensive part of a custom generator is evaluating the
 * density function, so this class keeps the raster pass cheap enough that it
 * does not become the next bottleneck when OpenCL is disabled or unavailable.
 * The coordinate tables are shared by all generation workers because the
 * cube size and the allowed sample strides are fixed.</p>
 */
final class TerrainInterpolation {
    static final int SIZE = 16;
    static final int VOXEL_COUNT = SIZE * SIZE * SIZE;
    private static final AxisTable[] AXES = new AxisTable[SIZE + 1];

    static {
        for (int step = 1; step <= SIZE; step++) {
            AXES[step] = new AxisTable(step);
        }
    }

    private TerrainInterpolation() {
    }

    /** Fills {@code solid} with the sign of every voxel in one cube. */
    static void fillSolid(
            double[] samples, int stepX, int stepY, int stepZ,
            int cellsX, int cellsY, int cellsZ, boolean[] solid) {
        if (stepZ == 1) {
            fillDenseZ(samples, stepX, stepY, cellsX, solid);
            return;
        }
        AxisTable xAxis = AXES[stepX];
        AxisTable yAxis = AXES[stepY];
        AxisTable zAxis = AXES[stepZ];
        int widthX = cellsX + 1;
        int widthZ = cellsZ + 1;
        int plane = widthX * widthZ;

        for (int y = 0; y < SIZE; y++) {
            int gridY = yAxis.grid[y];
            double ty = yAxis.weight[y];
            int y0 = gridY * plane;
            int y1 = y0 + plane;
            int outputY = y * SIZE * SIZE;
            // X/Y interpolation is identical for all Z positions in a cell.
            // Preserve the reference arithmetic order while reusing that work.
            for (int gridZ = 0; gridZ < cellsZ; gridZ++) {
                int row00 = y0 + gridZ * widthX;
                int row10 = y1 + gridZ * widthX;
                int row01 = row00 + widthX;
                int row11 = row10 + widthX;
                int startZ = gridZ * stepZ;
                int endZ = Math.min(SIZE, startZ + stepZ);
                for (int x = 0; x < SIZE; x++) {
                    int gridX = xAxis.grid[x];
                    double tx = xAxis.weight[x];
                    double first = lerp(
                            samples[row00 + gridX], samples[row00 + gridX + 1], tx);
                    double second = lerp(
                            samples[row10 + gridX], samples[row10 + gridX + 1], tx);
                    double third = lerp(
                            samples[row01 + gridX], samples[row01 + gridX + 1], tx);
                    double fourth = lerp(
                            samples[row11 + gridX], samples[row11 + gridX + 1], tx);
                    double near = lerp(first, second, ty);
                    double far = lerp(third, fourth, ty);
                    for (int z = startZ; z < endZ; z++) {
                        solid[outputY + z * SIZE + x] = lerp(near, far, zAxis.weight[z]) > 0.0;
                    }
                }
            }
        }
    }

    /** No Z work can be shared at stride one; retain contiguous output writes. */
    private static void fillDenseZ(double[] samples, int stepX, int stepY, int cellsX, boolean[] solid) {
        AxisTable xAxis = AXES[stepX];
        AxisTable yAxis = AXES[stepY];
        int width = cellsX + 1;
        int plane = width * (SIZE + 1);
        for (int y = 0; y < SIZE; y++) {
            int y0 = yAxis.grid[y] * plane;
            double ty = yAxis.weight[y];
            for (int z = 0; z < SIZE; z++) {
                int row00 = y0 + z * width;
                int row10 = row00 + plane;
                int row01 = row00 + width;
                int row11 = row10 + width;
                int output = (y * SIZE + z) * SIZE;
                for (int x = 0; x < SIZE; x++) {
                    int gx = xAxis.grid[x];
                    double tx = xAxis.weight[x];
                    double first = lerp(samples[row00 + gx], samples[row00 + gx + 1], tx);
                    double second = lerp(samples[row10 + gx], samples[row10 + gx + 1], tx);
                    double third = lerp(samples[row01 + gx], samples[row01 + gx + 1], tx);
                    double fourth = lerp(samples[row11 + gx], samples[row11 + gx + 1], tx);
                    // Retain the last lerp even at zero weight for NaN/infinity parity.
                    solid[output + x] = lerp(lerp(first, second, ty), lerp(third, fourth, ty), 0.0) > 0;
                }
            }
        }
    }

    /** A conservative proof that the whole interpolated grid is positive. */
    static boolean isAllSolid(double[] samples) {
        for (double sample : samples) {
            if (!(sample > 0.0)) return false;
        }
        return true;
    }

    private static double lerp(double first, double second, double amount) {
        return first + (second - first) * amount;
    }

    private static final class AxisTable {
        private final int[] grid = new int[SIZE];
        private final double[] weight = new double[SIZE];

        private AxisTable(int step) {
            for (int coordinate = 0; coordinate < SIZE; coordinate++) {
                grid[coordinate] = coordinate / step;
                weight[coordinate] = (coordinate % step) / (double) step;
            }
        }
    }
}
