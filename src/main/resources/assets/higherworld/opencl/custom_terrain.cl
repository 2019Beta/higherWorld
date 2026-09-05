#pragma OPENCL EXTENSION cl_khr_fp64 : enable
#pragma OPENCL FP_CONTRACT OFF

// The first kernel mirrors org.devt.higherworld.world.CustomNoise and the pure
// terrainDensity overload. The second kernel is a generator-neutral density
// rasterizer, so vanilla and modded density functions can share the same GPU
// voxel stage without moving Minecraft objects across the JNI boundary.

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

inline double higherworld_base_height(
        ulong seed, __global const double *p, double x, double z) {
    return higherworld_octave_gradient(
            seed ^ 0x424153455F4844UL, x, 0.0, z, p[23], 0.0, p[23], 2);
}

inline double higherworld_volatility_base(
        ulong seed, __global const double *p, double x, double z) {
    return clamp(0.5 + 0.5 * higherworld_octave_gradient(
            seed ^ 0x564F4C4154494CUL, x, 0.0, z, p[24], 0.0, p[24], 2), 0.0, 1.0);
}

inline double higherworld_depth_surface(
        ulong seed, __global const double *p, double x, double z) {
    return higherworld_depth_noise(seed ^ 0x5445525241494EUL, x, z, p);
}

inline double higherworld_terrain_density_with_surface(
        ulong seed, __global const double *p, double x, double y, double z,
        double depthNoise, double base, double volatilityBase) {
    double selector = higherworld_octave_gradient(
            seed ^ 0x53454C454354UL, x, y, z, p[7], p[8], p[9], (int)p[10]);
    selector = clamp(selector * p[5] + p[6], 0.0, 1.0);
    double low = higherworld_octave_gradient(
            seed ^ 0x4C4F575F4E4F49UL, x, y, z,
            p[13], p[14], p[15], (int)p[16]) * p[11] + p[12];
    double high = higherworld_octave_gradient(
            seed ^ 0x484947485F4E4FUL, x, y, z,
            p[19], p[20], p[21], (int)p[22]) * p[17] + p[18];
    double terrainNoise = low + (high - low) * selector + depthNoise;
    double height = base * p[28] + p[29];
    if (height > y) volatilityBase *= p[26];
    double volatility = volatilityBase * p[25] + p[27];
    double sign = volatility > 0.0 ? 1.0 : (volatility < 0.0 ? -1.0 : 0.0);
    return terrainNoise * volatility + height - y * sign;
}

inline double higherworld_terrain_density(
        ulong seed, __global const double *p, double x, double y, double z) {
    return higherworld_terrain_density_with_surface(
            seed, p, x, y, z,
            higherworld_depth_surface(seed, p, x, z),
            higherworld_base_height(seed, p, x, z),
            higherworld_volatility_base(seed, p, x, z));
}

// This kernel calculates the X/Z-only terms once per column for a whole batch.
// The sample kernel below then reuses them for every Y sample, matching the CPU
// path and avoiding three octave-gradient evaluations per vertical sample.
__kernel void higherworld_custom_surface_batch(
        __global const double *parameters,
        long seed,
        __global const int *origins,
        int batchCount,
        int surfaceCount,
        int stepX, int stepZ,
        int cellsX, int cellsZ,
        __global double *output) {
    int index = (int)get_global_id(0);
    int batch = index / surfaceCount;
    if (batch >= batchCount) return;
    int localIndex = index - batch * surfaceCount;
    int width = cellsX + 1;
    int gridX = localIndex % width;
    int gridZ = localIndex / width;
    int origin = batch * 3;
    double x = (double)(origins[origin] + gridX * stepX);
    double z = (double)(origins[origin + 2] + gridZ * stepZ);
    int outputIndex = index * 3;
    output[outputIndex] = higherworld_depth_surface((ulong)seed, parameters, x, z);
    output[outputIndex + 1] = higherworld_base_height((ulong)seed, parameters, x, z);
    output[outputIndex + 2] = higherworld_volatility_base((ulong)seed, parameters, x, z);
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

// The scheduler submits several independent sparse cubes together.  Keeping
// their origins in one small buffer lets the device amortize JNI calls,
// command-queue synchronization, and the sample/raster readback over a
// whole view slice instead of paying that cost once per cube.
__kernel void higherworld_custom_terrain_batch(
        __global const double *parameters,
        long seed,
        __global const int *origins,
        int batchCount,
        int sampleCount,
        int surfaceCount,
        int stepX, int stepY, int stepZ,
        int cellsX, int cellsY, int cellsZ,
        __global const double *surface,
        __global double *output) {
    int index = (int)get_global_id(0);
    int batch = index / sampleCount;
    if (batch >= batchCount) return;
    int localIndex = index - batch * sampleCount;
    int width = cellsX + 1;
    int depth = cellsZ + 1;
    int gridX = localIndex % width;
    int remainder = localIndex / width;
    int gridZ = remainder % depth;
    int gridY = remainder / depth;
    int origin = batch * 3;
    int surfaceIndex = batch * surfaceCount * 3 + (gridZ * width + gridX) * 3;
    output[index] = higherworld_terrain_density_with_surface(
            (ulong)seed, parameters,
            (double)(origins[origin] + gridX * stepX),
            (double)(origins[origin + 1] + gridY * stepY),
            (double)(origins[origin + 2] + gridZ * stepZ),
            surface[surfaceIndex], surface[surfaceIndex + 1],
            surface[surfaceIndex + 2]);
}

inline int higherworld_density_index(int x, int y, int z, int cellsX, int cellsZ) {
    return (y * (cellsZ + 1) + z) * (cellsX + 1) + x;
}

inline double higherworld_vanilla_delta(int coordinate) {
    // Matches the legacy interpolated noise weights used by the deep-world
    // fallback. The linear path remains the default for custom settings.
    switch (coordinate & 3) {
        case 0: return 0.0;
        case 1: return 0.15625;
        case 2: return 0.5;
        default: return 0.84375;
    }
}

inline double higherworld_density_weight(int coordinate, int grid, int step, int mode) {
    int localCoordinate = coordinate - grid * step;
    return mode == 1 && step == 4
            ? higherworld_vanilla_delta(localCoordinate)
            : (double)localCoordinate / (double)step;
}

inline double higherworld_interpolated_density(
        __global const double *samples,
        int x, int y, int z,
        int stepX, int stepY, int stepZ,
        int cellsX, int cellsY, int cellsZ,
        int mode) {
    int gridX = min(cellsX - 1, x / stepX);
    int gridY = min(cellsY - 1, y / stepY);
    int gridZ = min(cellsZ - 1, z / stepZ);
    double tx = higherworld_density_weight(x, gridX, stepX, mode);
    double ty = higherworld_density_weight(y, gridY, stepY, mode);
    double tz = higherworld_density_weight(z, gridZ, stepZ, mode);

    int x00 = higherworld_density_index(gridX, gridY, gridZ, cellsX, cellsZ);
    int x10 = higherworld_density_index(gridX, gridY + 1, gridZ, cellsX, cellsZ);
    int x01 = higherworld_density_index(gridX, gridY, gridZ + 1, cellsX, cellsZ);
    int x11 = higherworld_density_index(gridX, gridY + 1, gridZ + 1, cellsX, cellsZ);
    double first = higherworld_lerp(
            samples[x00], samples[x00 + 1], tx);
    double second = higherworld_lerp(
            samples[x10], samples[x10 + 1], tx);
    double third = higherworld_lerp(
            samples[x01], samples[x01 + 1], tx);
    double fourth = higherworld_lerp(
            samples[x11], samples[x11 + 1], tx);
    return higherworld_lerp(
            higherworld_lerp(first, second, ty),
            higherworld_lerp(third, fourth, ty), tz);
}

// Converts a density grid into the compact 4096-voxel mask consumed by the
// server-thread commit. Keeping this separate from the sampler lets vanilla,
// custom, and modded density functions share the same GPU raster stage.
__kernel void higherworld_rasterize_solid(
        __global const double *samples,
        int stepX, int stepY, int stepZ,
        int cellsX, int cellsY, int cellsZ,
        int interpolationMode,
        __global uchar *solid) {
    int index = (int)get_global_id(0);
    int x = index & 15;
    int remainder = index >> 4;
    int z = remainder & 15;
    int y = remainder >> 4;
    double density = higherworld_interpolated_density(
            samples, x, y, z, stepX, stepY, stepZ,
            cellsX, cellsY, cellsZ, interpolationMode);
    solid[index] = density > 0.0 ? (uchar)1 : (uchar)0;
}

__kernel void higherworld_rasterize_solid_batch(
        __global const double *samples,
        int batchCount,
        int sampleCount,
        int stepX, int stepY, int stepZ,
        int cellsX, int cellsY, int cellsZ,
        int interpolationMode,
        __global uchar *solid) {
    int index = (int)get_global_id(0);
    int batch = index >> 12;
    if (batch >= batchCount) return;
    int localIndex = index & 4095;
    int x = localIndex & 15;
    int remainder = localIndex >> 4;
    int z = remainder & 15;
    int y = remainder >> 4;
    double density = higherworld_interpolated_density(
            samples + batch * sampleCount, x, y, z,
            stepX, stepY, stepZ, cellsX, cellsY, cellsZ, interpolationMode);
    solid[index] = density > 0.0 ? (uchar)1 : (uchar)0;
}
