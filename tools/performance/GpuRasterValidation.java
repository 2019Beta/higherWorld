import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL;
import org.lwjgl.opencl.CL10;
import org.lwjgl.opencl.CL12;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Out-of-process validation and raster-stage benchmark for a real OpenCL GPU.
 * The source is intentionally independent of Minecraft classes so it can run
 * while the game is open and cannot be hidden by a CPU fallback.
 */
public final class GpuRasterValidation {
    private static final int SIZE = 16;
    private static final int VOXELS_16 = SIZE * SIZE * SIZE;
    private static final int BATCH_COUNT = 2;
    private static final int BATCH_HEIGHT = SIZE * 4;
    private static final int SEA_LEVEL = -64;
    private static final int MINIMUM_Y = -320;
    private static final int[] STEPS = {1, 2, 4, 8, 16};
    private static final int DEFAULT_WARMUPS = 10;
    private static final int DEFAULT_ITERATIONS = 30;

    private GpuRasterValidation() {
    }

    public static void main(String[] args) throws Exception {
        Configuration.OPENCL_EXPLICIT_INIT.set(true);
        CL.create();
        long context = 0L;
        long queue = 0L;
        long program = 0L;
        long kernel = 0L;
        long samplesBuffer = 0L;
        long solidBuffer = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer error = stack.callocInt(1);
            PointerBuffer platforms = stack.mallocPointer(platformCount(stack));
            check(CL12.clGetPlatformIDs(platforms, (IntBuffer) null));
            DeviceSelection selection = findGpu(platforms, stack);
            if (selection == null) throw new IllegalStateException("No OpenCL GPU found");
            System.out.println("device=" + deviceString(selection.device(), CL12.CL_DEVICE_NAME, stack));

            PointerBuffer properties = stack.mallocPointer(3)
                    .put(CL12.CL_CONTEXT_PLATFORM).put(selection.platform()).put(0L).flip();
            context = CL12.clCreateContext(properties, selection.device(), null, 0L, error);
            check(error);
            queue = CL12.clCreateCommandQueue(context, selection.device(), 0L, error);
            check(error);

            String source = Files.readString(Path.of(args.length == 0
                    ? "src/main/resources/assets/higherworld/opencl/custom_terrain.cl" : args[0]),
                    StandardCharsets.UTF_8);
            ByteBuffer sourceBuffer = MemoryUtil.memUTF8(source, true);
            try {
                PointerBuffer sources = stack.mallocPointer(1)
                        .put(0, MemoryUtil.memAddress(sourceBuffer));
                program = CL12.clCreateProgramWithSource(context, sources, null, error);
                check(error);
                int build = CL12.clBuildProgram(program, selection.device(), "-cl-std=CL1.2", null, 0L);
                if (build != CL10.CL_SUCCESS) {
                    throw new IllegalStateException("OpenCL program build failed: " + build);
                }
                kernel = CL12.clCreateKernel(program, "higherworld_rasterize_solid_batch", error);
                check(error);

                int maxSampleCount = (SIZE + 1) * (BATCH_HEIGHT + 1) * (SIZE + 1) * BATCH_COUNT;
                int maxVoxelCount = SIZE * BATCH_HEIGHT * SIZE * BATCH_COUNT;
                samplesBuffer = CL12.clCreateBuffer(
                        context, CL12.CL_MEM_READ_ONLY,
                        (long) maxSampleCount * Double.BYTES, error);
                check(error);
                solidBuffer = CL12.clCreateBuffer(
                        context, CL12.CL_MEM_WRITE_ONLY, maxVoxelCount, error);
                check(error);

                int validationCases = 0;
                for (int height : new int[]{SIZE, BATCH_HEIGHT}) {
                    for (int stepX : STEPS) {
                        for (int stepY : STEPS) {
                            for (int stepZ : STEPS) {
                                validateCase(queue, kernel, samplesBuffer, solidBuffer,
                                        stepX, stepY, stepZ, height, stack);
                                validationCases++;
                            }
                        }
                    }
                }
                System.out.println("SWEEP PASS: " + validationCases
                        + " legal step combinations (16-high and 64-high, "
                        + BATCH_COUNT + " batches), CPU sign reference matched");

                int warmups = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_WARMUPS;
                int iterations = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_ITERATIONS;
                BenchmarkResult benchmark = benchmark(
                        queue, kernel, samplesBuffer, solidBuffer, stack, warmups, iterations);
                System.out.printf(
                        "BENCH PASS: step=4/8/4 height=64 batches=%d warmups=%d iterations=%d "
                                + "old_4x16_median_ms=%.3f new_1x64_median_ms=%.3f speedup=%.2fx%n",
                        BATCH_COUNT, warmups, iterations,
                        benchmark.oldMedianNanos() / 1_000_000.0,
                        benchmark.newMedianNanos() / 1_000_000.0,
                        (double) benchmark.oldMedianNanos() / benchmark.newMedianNanos());
            } finally {
                MemoryUtil.memFree(sourceBuffer);
            }
        } finally {
            if (solidBuffer != 0L) CL12.clReleaseMemObject(solidBuffer);
            if (samplesBuffer != 0L) CL12.clReleaseMemObject(samplesBuffer);
            if (kernel != 0L) CL12.clReleaseKernel(kernel);
            if (program != 0L) CL12.clReleaseProgram(program);
            if (queue != 0L) CL12.clReleaseCommandQueue(queue);
            if (context != 0L) CL12.clReleaseContext(context);
            CL.destroy();
        }
    }

    private static void validateCase(
            long queue, long kernel, long samplesBuffer, long solidBuffer,
            int stepX, int stepY, int stepZ, int height, MemoryStack stack) {
        int cellsX = SIZE / stepX;
        int cellsY = height / stepY;
        int cellsZ = SIZE / stepZ;
        int sampleCount = (cellsX + 1) * (cellsY + 1) * (cellsZ + 1);
        int outputCount = SIZE * height * SIZE;
        double[][] samples = makeSamples(sampleCount, cellsX, cellsY, cellsZ,
                stepX, stepY, stepZ);
        DoubleBuffer hostSamples = MemoryUtil.memAllocDouble(BATCH_COUNT * sampleCount);
        ByteBuffer hostSolid = MemoryUtil.memAlloc(BATCH_COUNT * outputCount);
        try {
            putSamples(hostSamples, samples);
            hostSolid.clear();
            hostSolid.limit(BATCH_COUNT * outputCount);
            enqueueRaster(queue, kernel, samplesBuffer, solidBuffer,
                    hostSamples, hostSolid, stepX, stepY, stepZ,
                    cellsX, cellsY, cellsZ, BATCH_COUNT, sampleCount,
                    outputCount, stack);
            for (int batch = 0; batch < BATCH_COUNT; batch++) {
                for (int y = 0; y < height; y++) {
                    for (int z = 0; z < SIZE; z++) {
                        for (int x = 0; x < SIZE; x++) {
                            int index = (y * SIZE + z) * SIZE + x;
                            boolean expected = interpolate(
                                    samples[batch], x, y, z,
                                    stepX, stepY, stepZ, cellsX, cellsY, cellsZ) > 0.0;
                            boolean actual = hostSolid.get(batch * outputCount + index) != 0;
                            if (expected != actual) {
                                throw new AssertionError("Raster mismatch for step="
                                        + stepX + "/" + stepY + "/" + stepZ
                                        + " height=" + height + " batch=" + batch
                                        + " voxel=" + x + "/" + y + "/" + z);
                            }
                        }
                    }
                }
            }
        } finally {
            MemoryUtil.memFree(hostSolid);
            MemoryUtil.memFree(hostSamples);
        }
    }

    private static BenchmarkResult benchmark(
            long queue, long kernel, long samplesBuffer, long solidBuffer,
            MemoryStack stack, int warmups, int iterations) {
        if (warmups < 0 || iterations <= 0) {
            throw new IllegalArgumentException("warmups must be >= 0 and iterations must be > 0");
        }
        int stepX = 4;
        int stepY = 8;
        int stepZ = 4;
        int height = BATCH_HEIGHT;
        int cellsX = SIZE / stepX;
        int cellsY = height / stepY;
        int cellsZ = SIZE / stepZ;
        int fullSampleCount = (cellsX + 1) * (cellsY + 1) * (cellsZ + 1);
        int fullOutputCount = SIZE * height * SIZE;
        double[][] fullSamples = makeSamples(
                fullSampleCount, cellsX, cellsY, cellsZ, stepX, stepY, stepZ);
        int sectionCellsY = SIZE / stepY;
        int sectionSampleCount = (cellsX + 1) * (sectionCellsY + 1) * (cellsZ + 1);
        double[][][] sectionSamples = sliceSamples(
                fullSamples, sampleRows(cellsX, cellsZ), sectionCellsY, sectionSampleCount);
        DoubleBuffer fullHostSamples = MemoryUtil.memAllocDouble(BATCH_COUNT * fullSampleCount);
        ByteBuffer fullHostSolid = MemoryUtil.memAlloc(BATCH_COUNT * fullOutputCount);
        DoubleBuffer[] sectionHostSamples = new DoubleBuffer[4];
        ByteBuffer sectionHostSolid = MemoryUtil.memAlloc(BATCH_COUNT * VOXELS_16);
        try {
            putSamples(fullHostSamples, fullSamples);
            for (int section = 0; section < sectionHostSamples.length; section++) {
                sectionHostSamples[section] = MemoryUtil.memAllocDouble(
                        BATCH_COUNT * sectionSampleCount);
                putSamples(sectionHostSamples[section], sectionSamples[section]);
            }

            // Verify that the old four-dispatch path and the new single-dispatch
            // path produce byte-for-byte identical masks before timing either.
            byte[] oldMask = oldRaster(
                    queue, kernel, samplesBuffer, solidBuffer, sectionHostSamples,
                    sectionHostSolid, stepX, stepY, stepZ, cellsX, sectionCellsY,
                    cellsZ, sectionSampleCount, stack);
            byte[] newMask = newRaster(
                    queue, kernel, samplesBuffer, solidBuffer, fullHostSamples,
                    fullHostSolid, stepX, stepY, stepZ, cellsX, cellsY, cellsZ,
                    fullSampleCount, fullOutputCount, stack);
            if (!Arrays.equals(oldMask, newMask)) {
                throw new AssertionError("Old 4x16 and new 1x64 masks differ");
            }

            for (int i = 0; i < warmups; i++) {
                oldRaster(queue, kernel, samplesBuffer, solidBuffer, sectionHostSamples,
                        sectionHostSolid, stepX, stepY, stepZ, cellsX, sectionCellsY,
                        cellsZ, sectionSampleCount, stack);
                newRaster(queue, kernel, samplesBuffer, solidBuffer, fullHostSamples,
                        fullHostSolid, stepX, stepY, stepZ, cellsX, cellsY, cellsZ,
                        fullSampleCount, fullOutputCount, stack);
            }
            long[] oldTimes = new long[iterations];
            long[] newTimes = new long[iterations];
            for (int i = 0; i < iterations; i++) {
                long start = System.nanoTime();
                oldRaster(queue, kernel, samplesBuffer, solidBuffer, sectionHostSamples,
                        sectionHostSolid, stepX, stepY, stepZ, cellsX, sectionCellsY,
                        cellsZ, sectionSampleCount, stack);
                oldTimes[i] = System.nanoTime() - start;

                start = System.nanoTime();
                newRaster(queue, kernel, samplesBuffer, solidBuffer, fullHostSamples,
                        fullHostSolid, stepX, stepY, stepZ, cellsX, cellsY, cellsZ,
                        fullSampleCount, fullOutputCount, stack);
                newTimes[i] = System.nanoTime() - start;
            }
            Arrays.sort(oldTimes);
            Arrays.sort(newTimes);
            return new BenchmarkResult(oldTimes[iterations / 2], newTimes[iterations / 2]);
        } finally {
            MemoryUtil.memFree(sectionHostSolid);
            MemoryUtil.memFree(fullHostSolid);
            MemoryUtil.memFree(fullHostSamples);
            for (DoubleBuffer sectionHostSample : sectionHostSamples) {
                if (sectionHostSample != null) MemoryUtil.memFree(sectionHostSample);
            }
        }
    }

    private static byte[] oldRaster(
            long queue, long kernel, long samplesBuffer, long solidBuffer,
            DoubleBuffer[] sectionHostSamples, ByteBuffer hostSolid,
            int stepX, int stepY, int stepZ, int cellsX, int sectionCellsY,
            int cellsZ, int sectionSampleCount, MemoryStack stack) {
        byte[] combined = new byte[BATCH_COUNT * BATCH_HEIGHT * SIZE * SIZE];
        for (int section = 0; section < sectionHostSamples.length; section++) {
            DoubleBuffer hostSamples = sectionHostSamples[section];
            hostSamples.rewind();
            int outputCount = BATCH_COUNT * VOXELS_16;
            hostSolid.clear();
            hostSolid.limit(outputCount);
            enqueueRaster(queue, kernel, samplesBuffer, solidBuffer,
                    hostSamples, hostSolid, stepX, stepY, stepZ,
                    cellsX, sectionCellsY, cellsZ, BATCH_COUNT,
                    sectionSampleCount, VOXELS_16, stack);
            for (int batch = 0; batch < BATCH_COUNT; batch++) {
                int sourceOffset = batch * VOXELS_16;
                int targetOffset = (batch * BATCH_HEIGHT + section * SIZE) * SIZE * SIZE;
                for (int voxel = 0; voxel < VOXELS_16; voxel++) {
                    combined[targetOffset + voxel] = hostSolid.get(sourceOffset + voxel);
                }
            }
        }
        return combined;
    }

    private static byte[] newRaster(
            long queue, long kernel, long samplesBuffer, long solidBuffer,
            DoubleBuffer hostSamples, ByteBuffer hostSolid,
            int stepX, int stepY, int stepZ, int cellsX, int cellsY,
            int cellsZ, int sampleCount, int outputCount, MemoryStack stack) {
        hostSamples.rewind();
        hostSolid.clear();
        hostSolid.limit(BATCH_COUNT * outputCount);
        enqueueRaster(queue, kernel, samplesBuffer, solidBuffer,
                hostSamples, hostSolid, stepX, stepY, stepZ,
                cellsX, cellsY, cellsZ, BATCH_COUNT, sampleCount,
                outputCount, stack);
        byte[] result = new byte[BATCH_COUNT * outputCount];
        for (int index = 0; index < result.length; index++) {
            result[index] = hostSolid.get(index);
        }
        return result;
    }

    private static void enqueueRaster(
            long queue, long kernel, long samplesBuffer, long solidBuffer,
            DoubleBuffer hostSamples, ByteBuffer hostSolid,
            int stepX, int stepY, int stepZ,
            int cellsX, int cellsY, int cellsZ, int batchCount,
            int sampleCount, int outputCount, MemoryStack stack) {
        check(CL12.clEnqueueWriteBuffer(
                queue, samplesBuffer, true, 0, hostSamples, null, null));
        check(CL12.clSetKernelArg1p(kernel, 0, samplesBuffer));
        check(CL12.clSetKernelArg1i(kernel, 1, batchCount));
        check(CL12.clSetKernelArg1i(kernel, 2, sampleCount));
        check(CL12.clSetKernelArg1i(kernel, 3, stepX));
        check(CL12.clSetKernelArg1i(kernel, 4, stepY));
        check(CL12.clSetKernelArg1i(kernel, 5, stepZ));
        check(CL12.clSetKernelArg1i(kernel, 6, cellsX));
        check(CL12.clSetKernelArg1i(kernel, 7, cellsY));
        check(CL12.clSetKernelArg1i(kernel, 8, cellsZ));
        check(CL12.clSetKernelArg1i(kernel, 9, 0));
        check(CL12.clSetKernelArg1p(kernel, 10, solidBuffer));
        try (MemoryStack ignored = stack.push()) {
            PointerBuffer global = stack.mallocPointer(1)
                    .put(0, (long) batchCount * outputCount);
            check(CL12.clEnqueueNDRangeKernel(
                    queue, kernel, 1, null, global, null, null, null));
        }
        hostSolid.rewind();
        check(CL12.clEnqueueReadBuffer(
                queue, solidBuffer, true, 0, hostSolid, null, null));
    }

    private static double[][] makeSamples(
            int sampleCount, int cellsX, int cellsY, int cellsZ,
            int stepX, int stepY, int stepZ) {
        double[][] result = new double[BATCH_COUNT][sampleCount];
        for (int batch = 0; batch < BATCH_COUNT; batch++) {
            for (int gridY = 0; gridY <= cellsY; gridY++) {
                int worldY = MINIMUM_Y + gridY * stepY;
                for (int gridZ = 0; gridZ <= cellsZ; gridZ++) {
                    int z = gridZ * stepZ;
                    for (int gridX = 0; gridX <= cellsX; gridX++) {
                        int x = gridX * stepX;
                        double value = (worldY - SEA_LEVEL) * 0.0375
                                + Math.sin((x + batch * 3) * 0.173 + z * 0.119)
                                - Math.cos((x - z - batch * 5) * 0.071)
                                + Math.sin((worldY + x * 2 - z) * 0.031) * 0.55
                                + batch * 0.37;
                        int index = (gridY * (cellsZ + 1) + gridZ) * (cellsX + 1) + gridX;
                        result[batch][index] = value;
                    }
                }
            }
        }
        return result;
    }

    private static int sampleRows(int cellsX, int cellsZ) {
        return (cellsX + 1) * (cellsZ + 1);
    }

    private static double[][][] sliceSamples(
            double[][] fullSamples, int rowLength, int sectionCellsY, int sectionSampleCount) {
        double[][][] result = new double[4][BATCH_COUNT][];
        int fullCellsZ = SIZE / 4;
        for (int section = 0; section < result.length; section++) {
            for (int batch = 0; batch < BATCH_COUNT; batch++) {
                result[section][batch] = new double[sectionSampleCount];
                int sourceOffset = section * sectionCellsY * rowLength;
                for (int row = 0; row <= sectionCellsY; row++) {
                    System.arraycopy(fullSamples[batch], sourceOffset + row * rowLength,
                            result[section][batch], row * rowLength, rowLength);
                }
            }
        }
        double[][][] transposed = new double[4][BATCH_COUNT][];
        for (int section = 0; section < 4; section++) {
            transposed[section] = new double[BATCH_COUNT][];
            for (int batch = 0; batch < BATCH_COUNT; batch++) {
                transposed[section][batch] = result[section][batch];
            }
        }
        return transposed;
    }

    private static void putSamples(DoubleBuffer target, double[][] samples) {
        target.clear();
        for (double[] sample : samples) target.put(sample);
        target.flip();
    }

    private static double interpolate(
            double[] samples, int x, int y, int z,
            int stepX, int stepY, int stepZ,
            int cellsX, int cellsY, int cellsZ) {
        int gridX = Math.min(cellsX - 1, x / stepX);
        int gridY = Math.min(cellsY - 1, y / stepY);
        int gridZ = Math.min(cellsZ - 1, z / stepZ);
        double tx = (x - gridX * stepX) / (double) stepX;
        double ty = (y - gridY * stepY) / (double) stepY;
        double tz = (z - gridZ * stepZ) / (double) stepZ;
        int x00 = index(gridX, gridY, gridZ, cellsX, cellsZ);
        int x10 = index(gridX, gridY + 1, gridZ, cellsX, cellsZ);
        int x01 = index(gridX, gridY, gridZ + 1, cellsX, cellsZ);
        int x11 = index(gridX, gridY + 1, gridZ + 1, cellsX, cellsZ);
        double first = lerp(samples[x00], samples[x00 + 1], tx);
        double second = lerp(samples[x10], samples[x10 + 1], tx);
        double third = lerp(samples[x01], samples[x01 + 1], tx);
        double fourth = lerp(samples[x11], samples[x11 + 1], tx);
        return lerp(lerp(first, second, ty), lerp(third, fourth, ty), tz);
    }

    private static int index(int x, int y, int z, int cellsX, int cellsZ) {
        return (y * (cellsZ + 1) + z) * (cellsX + 1) + x;
    }

    private static double lerp(double first, double second, double amount) {
        return first + (second - first) * amount;
    }

    private static DeviceSelection findGpu(PointerBuffer platforms, MemoryStack stack) {
        for (int platformIndex = 0; platformIndex < platforms.capacity(); platformIndex++) {
            long platform = platforms.get(platformIndex);
            IntBuffer deviceCount = stack.callocInt(1);
            int result = CL12.clGetDeviceIDs(
                    platform, CL12.CL_DEVICE_TYPE_GPU, null, deviceCount);
            if (result == CL10.CL_DEVICE_NOT_FOUND || deviceCount.get(0) == 0) continue;
            check(result);
            PointerBuffer devices = stack.mallocPointer(deviceCount.get(0));
            check(CL12.clGetDeviceIDs(
                    platform, CL12.CL_DEVICE_TYPE_GPU, devices, (IntBuffer) null));
            return new DeviceSelection(platform, devices.get(0));
        }
        return null;
    }

    private static int platformCount(MemoryStack stack) {
        IntBuffer count = stack.callocInt(1);
        check(CL12.clGetPlatformIDs(null, count));
        return count.get(0);
    }

    private static String deviceString(long device, int parameter, MemoryStack stack) {
        PointerBuffer size = stack.mallocPointer(1);
        check(CL12.clGetDeviceInfo(device, parameter, (ByteBuffer) null, size));
        ByteBuffer value = stack.malloc(Math.toIntExact(size.get(0)));
        check(CL12.clGetDeviceInfo(device, parameter, value, null));
        return MemoryUtil.memUTF8(value, Math.max(0, value.remaining() - 1));
    }

    private static void check(IntBuffer error) {
        check(error.get(error.position()));
    }

    private static void check(int error) {
        if (error != CL10.CL_SUCCESS) throw new IllegalStateException("OpenCL error " + error);
    }

    private record DeviceSelection(long platform, long device) {
    }

    private record BenchmarkResult(long oldMedianNanos, long newMedianNanos) {
    }
}
