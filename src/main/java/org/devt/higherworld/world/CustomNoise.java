package org.devt.higherworld.world;

/** Deterministic, allocation-free value/gradient noise helpers for custom cubes. */
final class CustomNoise {
    private CustomNoise() {
    }

    /**
     * Samples a normalized multi-octave gradient field.  The hash is based only
     * on seed and lattice coordinates, so cube loading order cannot affect the
     * result.  Frequencies are independent on all three axes, matching the
     * old selector/low/high settings.
     */
    static double octaveGradient(long seed, double x, double y, double z,
                                 double frequencyX, double frequencyY, double frequencyZ,
                                 int octaves) {
        int count = Math.max(1, octaves);
        double amplitude = 1.0;
        double amplitudeSum = 0.0;
        double result = 0.0;
        double fx = frequencyX;
        double fy = frequencyY;
        double fz = frequencyZ;
        for (int octave = 0; octave < count; octave++) {
            result += latticeGradient(seed + octave * 0x9E3779B97F4A7C15L,
                    x * fx, y * fy, z * fz) * amplitude;
            amplitudeSum += amplitude;
            amplitude *= 0.5;
            fx *= 2.0;
            fy *= 2.0;
            fz *= 2.0;
        }
        return amplitudeSum == 0.0 ? 0.0 : clamp(result / amplitudeSum, -1.0, 1.0);
    }

    static double octaveGradient2D(long seed, double x, double z,
                                   double frequencyX, double frequencyZ,
                                   int octaves) {
        return octaveGradient(seed, x, 0.0, z, frequencyX, 0.0, frequencyZ, octaves);
    }

    /** The exact 1.12 depth-noise post-processing from CustomTerrainGenerator. */
    static double depthNoise(long seed, double x, double z, CustomWorldSettings settings) {
        double value = octaveGradient2D(seed, x, z,
                settings.depthNoiseFrequencyX(), settings.depthNoiseFrequencyZ(),
                settings.depthNoiseOctaves());
        value = value * settings.depthNoiseFactor() + settings.depthNoiseOffset();
        if (value < 0.0) value *= -0.3;
        value = value * 3.0 - 2.0;
        value = clamp(value, -2.0, 1.0);
        value = value < 0.0 ? value / 5.6 : value / 8.0;
        return value * 0.2 * 17.0 / 64.0;
    }

    static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double latticeGradient(long seed, double x, double y, double z) {
        int x0 = floor(x);
        int y0 = floor(y);
        int z0 = floor(z);
        double tx = x - x0;
        double ty = y - y0;
        double tz = z - z0;
        double x00 = lerp(gradient(seed, x0, y0, z0, tx, ty, tz),
                gradient(seed, x0 + 1, y0, z0, tx - 1.0, ty, tz), fade(tx));
        double x10 = lerp(gradient(seed, x0, y0 + 1, z0, tx, ty - 1.0, tz),
                gradient(seed, x0 + 1, y0 + 1, z0, tx - 1.0, ty - 1.0, tz), fade(tx));
        double x01 = lerp(gradient(seed, x0, y0, z0 + 1, tx, ty, tz - 1.0),
                gradient(seed, x0 + 1, y0, z0 + 1, tx - 1.0, ty, tz - 1.0), fade(tx));
        double x11 = lerp(gradient(seed, x0, y0 + 1, z0 + 1, tx, ty - 1.0, tz - 1.0),
                gradient(seed, x0 + 1, y0 + 1, z0 + 1, tx - 1.0, ty - 1.0, tz - 1.0), fade(tx));
        double y0Value = lerp(x00, x10, fade(ty));
        double y1Value = lerp(x01, x11, fade(ty));
        return lerp(y0Value, y1Value, fade(tz));
    }

    private static double gradient(long seed, int x, int y, int z, double dx, double dy, double dz) {
        long hash = mix(seed ^ ((long) x * 0x632BE59BD9B4E019L)
                ^ ((long) y * 0x9E3779B97F4A7C15L)
                ^ ((long) z * 0xC2B2AE3D27D4EB4FL));
        int direction = (int) (hash & 15L);
        double gx = ((direction & 1) == 0 ? 1.0 : -1.0) * ((direction & 2) == 0 ? 1.0 : 0.0);
        double gy = ((direction & 4) == 0 ? 1.0 : -1.0) * ((direction & 8) == 0 ? 1.0 : 0.0);
        double gz = 1.0 - Math.abs(gx) - Math.abs(gy);
        if (gz == 0.0) gz = (direction & 1) == 0 ? 1.0 : -1.0;
        return (gx * dx + gy * dy + gz * dz) * 0.7071067811865476;
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static int floor(double value) {
        int result = (int) value;
        return value < result ? result - 1 : result;
    }

    private static double fade(double value) {
        return value * value * value * (value * (value * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double first, double second, double amount) {
        return first + amount * (second - first);
    }
}
