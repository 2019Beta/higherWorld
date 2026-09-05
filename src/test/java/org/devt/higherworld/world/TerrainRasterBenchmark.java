package org.devt.higherworld.world;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/** Standalone CPU raster correctness check and comparative microbenchmark. */
public final class TerrainRasterBenchmark {
    private static volatile int checksum;
    private static final int[][] GRID = new int[17][16];
    private static final double[][] WEIGHT = new double[17][16];

    static {
        for (int step = 1; step <= 16; step++) {
            for (int x = 0; x < 16; x++) {
                GRID[step][x] = x / step;
                WEIGHT[step][x] = (x % step) / (double) step;
            }
        }
    }

    public static void main(String[] args) {
        Random random = new Random(42);
        int[] steps = {1, 2, 4, 8, 16};
        for (int sx : steps) for (int sy : steps) for (int sz : steps) {
            double[] samples = random.doubles((16 / sx + 1) * (16 / sy + 1) * (16 / sz + 1), -1, 1).toArray();
            verify(samples, sx, sy, sz);
            for (double value : new double[]{0, -0.0, Double.MIN_VALUE, -Double.MIN_VALUE,
                    Double.MAX_VALUE, -Double.MAX_VALUE, Double.NaN, Double.POSITIVE_INFINITY}) {
                Arrays.fill(samples, value);
                verify(samples, sx, sy, sz);
            }
        }
        System.out.println("PASS: 125 grid layouts, random densities and 8 edge-value grids each");
        System.out.println("step,baseline_ns_per_cube,optimized_ns_per_cube,speedup");
        for (int step : steps) {
            double[][] samples = new double[32][];
            for (int i = 0; i < samples.length; i++) {
                samples[i] = random.doubles((int) Math.pow(16 / step + 1, 3), -1, 1).toArray();
            }
            measure(samples, step, false, 10_000);
            measure(samples, step, true, 10_000);
            long baseline = Long.MAX_VALUE;
            long optimized = Long.MAX_VALUE;
            for (int trial = 0; trial < 4; trial++) {
                // Alternate ordering to limit warmup and thermal bias.
                if ((trial & 1) == 0) {
                    baseline = Math.min(baseline, measure(samples, step, false, 20_000));
                    optimized = Math.min(optimized, measure(samples, step, true, 20_000));
                } else {
                    optimized = Math.min(optimized, measure(samples, step, true, 20_000));
                    baseline = Math.min(baseline, measure(samples, step, false, 20_000));
                }
            }
            System.out.printf(Locale.ROOT, "%d,%.1f,%.1f,%.2f%n",
                    step, baseline / 20_000.0, optimized / 20_000.0, baseline / (double) optimized);
        }
    }

    private static void verify(double[] samples, int sx, int sy, int sz) {
        boolean[] expected = new boolean[4096];
        boolean[] actual = new boolean[4096];
        baseline(samples, sx, sy, sz, expected);
        TerrainInterpolation.fillSolid(samples, sx, sy, sz, 16 / sx, 16 / sy, 16 / sz, actual);
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError("Raster mismatch: " + sx + "/" + sy + "/" + sz);
        }
    }

    private static long measure(double[][] samples, int step, boolean optimized, int iterations) {
        boolean[] solid = new boolean[4096];
        int sum = 0;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            double[] input = samples[i & 31];
            if (optimized) {
                TerrainInterpolation.fillSolid(input, step, step, step, 16 / step, 16 / step, 16 / step, solid);
            } else {
                baseline(input, step, step, step, solid);
            }
            if (solid[i & 4095]) sum++;
        }
        long elapsed = System.nanoTime() - start;
        checksum = sum;
        return elapsed;
    }

    /** Original per-voxel algorithm, including the precomputed axis tables. */
    private static void baseline(double[] samples, int sx, int sy, int sz, boolean[] solid) {
        int width = 16 / sx + 1;
        int plane = width * (16 / sz + 1);
        for (int y = 0; y < 16; y++) {
            int y0 = GRID[sy][y] * plane;
            int y1 = y0 + plane;
            double ty = WEIGHT[sy][y];
            for (int z = 0; z < 16; z++) {
                int row00 = y0 + GRID[sz][z] * width;
                int row10 = y1 + GRID[sz][z] * width;
                int row01 = row00 + width;
                int row11 = row10 + width;
                double tz = WEIGHT[sz][z];
                for (int x = 0; x < 16; x++) {
                    int gx = GRID[sx][x];
                    double tx = WEIGHT[sx][x];
                    double first = lerp(samples[row00 + gx], samples[row00 + gx + 1], tx);
                    double second = lerp(samples[row10 + gx], samples[row10 + gx + 1], tx);
                    double third = lerp(samples[row01 + gx], samples[row01 + gx + 1], tx);
                    double fourth = lerp(samples[row11 + gx], samples[row11 + gx + 1], tx);
                    solid[(y * 16 + z) * 16 + x] = lerp(lerp(first, second, ty), lerp(third, fourth, ty), tz) > 0;
                }
            }
        }
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
