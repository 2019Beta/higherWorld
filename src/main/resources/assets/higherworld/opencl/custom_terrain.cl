#pragma OPENCL EXTENSION cl_khr_fp64 : enable
#pragma OPENCL FP_CONTRACT OFF

// This kernel mirrors org.devt.higherworld.world.CustomNoise and the pure
// terrainDensity overload.  It evaluates the sparse sample grid only; Java
// keeps the existing interpolation and cube materialization path.

inline ulong higherworld_mix(ulong value) {
    value = (value ^ (value >> 30)) * 0xBF58476D1CE4E5B9UL;
    value = (value ^ (value >> 27)) * 0x94D049BB133111EBUL;
    return value ^ (value >> 31);
}

inline double higherworld_fade(double value) {
    return value * value * value * (value * (value * 6.0 - 15.0) + 10.0);
}

inline double higherworld_lerp(double first, double second, double amount) {
    return first + amount * (second - first);
}

inline double higherworld_gradient(
        ulong seed, int x, int y, int z, double dx, double dy, double dz) {
    ulong hash = higherworld_mix(seed
            ^ ((ulong)((long)x) * 0x632BE59BD9B4E019UL)
            ^ ((ulong)((long)y) * 0x9E3779B97F4A7C15UL)
            ^ ((ulong)((long)z) * 0xC2B2AE3D27D4EB4FUL));
    int direction = (int)(hash & 15UL);
    double gx = ((direction & 1) == 0 ? 1.0 : -1.0)
            * ((direction & 2) == 0 ? 1.0 : 0.0);
    double gy = ((direction & 4) == 0 ? 1.0 : -1.0)
            * ((direction & 8) == 0 ? 1.0 : 0.0);
    double gz = 1.0 - fabs(gx) - fabs(gy);
    if (gz == 0.0) gz = (direction & 1) == 0 ? 1.0 : -1.0;
    return (gx * dx + gy * dy + gz * dz) * 0.7071067811865476;
}

inline double higherworld_lattice_gradient(ulong seed, double x, double y, double z) {
    int x0 = (int)floor(x);
    int y0 = (int)floor(y);
    int z0 = (int)floor(z);
    double tx = x - x0;
    double ty = y - y0;
    double tz = z - z0;
    double x00 = higherworld_lerp(
            higherworld_gradient(seed, x0, y0, z0, tx, ty, tz),
            higherworld_gradient(seed, x0 + 1, y0, z0, tx - 1.0, ty, tz),
            higherworld_fade(tx));
    double x10 = higherworld_lerp(
            higherworld_gradient(seed, x0, y0 + 1, z0, tx, ty - 1.0, tz),
            higherworld_gradient(seed, x0 + 1, y0 + 1, z0,
                    tx - 1.0, ty - 1.0, tz), higherworld_fade(tx));
    double x01 = higherworld_lerp(
            higherworld_gradient(seed, x0, y0, z0 + 1, tx, ty, tz - 1.0),
            higherworld_gradient(seed, x0 + 1, y0, z0 + 1,
                    tx - 1.0, ty, tz - 1.0), higherworld_fade(tx));
    double x11 = higherworld_lerp(
            higherworld_gradient(seed, x0, y0 + 1, z0 + 1,
                    tx, ty - 1.0, tz - 1.0),
            higherworld_gradient(seed, x0 + 1, y0 + 1, z0 + 1,
                    tx - 1.0, ty - 1.0, tz - 1.0), higherworld_fade(tx));
    double y0Value = higherworld_lerp(x00, x10, higherworld_fade(ty));
    double y1Value = higherworld_lerp(x01, x11, higherworld_fade(ty));
    return higherworld_lerp(y0Value, y1Value, higherworld_fade(tz));
}

inline double higherworld_octave_gradient(
        ulong seed, double x, double y, double z,
        double frequencyX, double frequencyY, double frequencyZ, int octaves) {
    int count = max(1, octaves);
    double amplitude = 1.0;
    double amplitudeSum = 0.0;
    double result = 0.0;
    double fx = frequencyX;
    double fy = frequencyY;
    double fz = frequencyZ;
    for (int octave = 0; octave < count; octave++) {
        result += higherworld_lattice_gradient(
                seed + (ulong)octave * 0x9E3779B97F4A7C15UL,
                x * fx, y * fy, z * fz) * amplitude;
        amplitudeSum += amplitude;
        amplitude *= 0.5;
        fx *= 2.0;
        fy *= 2.0;
        fz *= 2.0;
    }
    return amplitudeSum == 0.0
            ? 0.0 : clamp(result / amplitudeSum, -1.0, 1.0);
}

inline double higherworld_depth_noise(
        ulong seed, double x, double z, __global const double *p) {
    double value = higherworld_octave_gradient(
            seed, x, 0.0, z, p[2], 0.0, p[3], (int)p[4]);
    value = value * p[0] + p[1];
    if (value < 0.0) value *= -0.3;
    value = value * 3.0 - 2.0;
    value = clamp(value, -2.0, 1.0);
    value = value < 0.0 ? value / 5.6 : value / 8.0;
    return value * 0.2 * 17.0 / 64.0;
}

inline double higherworld_terrain_density(
        ulong seed, __global const double *p, double x, double y, double z) {
    double selector = higherworld_octave_gradient(
            seed ^ 0x53454C454354UL, x, y, z, p[7], p[8], p[9], (int)p[10]);
    selector = clamp(selector * p[5] + p[6], 0.0, 1.0);
    double low = higherworld_octave_gradient(
            seed ^ 0x4C4F575F4E4F49UL, x, y, z,
            p[13], p[14], p[15], (int)p[16]) * p[11] + p[12];
    double high = higherworld_octave_gradient(
            seed ^ 0x484947485F4E4FUL, x, y, z,
            p[19], p[20], p[21], (int)p[22]) * p[17] + p[18];
    double terrainNoise = low + (high - low) * selector
            + higherworld_depth_noise(seed ^ 0x5445525241494EUL, x, z, p);

    int biomeSize = max(0, min(1022, (int)p[23]));
    int riverSize = max(0, min(1022, (int)p[24]));
    double scaleBase = pow(2.0, -(double)biomeSize);
    double scaleRiver = pow(2.0, -(double)riverSize);
    double base = higherworld_octave_gradient(
            seed ^ 0x424153455F4844UL, x, 0.0, z,
            scaleBase, 0.0, scaleBase, 2);
    double volatilityBase = clamp(0.5 + 0.5 * higherworld_octave_gradient(
            seed ^ 0x564F4C4154494CUL, x, 0.0, z,
            scaleRiver, 0.0, scaleRiver, 2), 0.0, 1.0);
    double height = base * p[28] + p[29];
    if (height > y) volatilityBase *= p[26];
    double volatility = volatilityBase * p[25] + p[27];
    double sign = volatility > 0.0 ? 1.0 : (volatility < 0.0 ? -1.0 : 0.0);
    return terrainNoise * volatility + height - y * sign;
}

__kernel void higherworld_custom_terrain(
        __global const double *parameters,
        long seed,
        int originX, int originY, int originZ,
        int stepX, int stepY, int stepZ,
        int cellsX, int cellsY, int cellsZ,
        __global double *output) {
    int index = (int)get_global_id(0);
    int width = cellsX + 1;
    int depth = cellsZ + 1;
    int gridX = index % width;
    int remainder = index / width;
    int gridZ = remainder % depth;
    int gridY = remainder / depth;
    output[index] = higherworld_terrain_density(
            (ulong)seed, parameters,
            (double)(originX + gridX * stepX),
            (double)(originY + gridY * stepY),
            (double)(originZ + gridZ * stepZ));
}
