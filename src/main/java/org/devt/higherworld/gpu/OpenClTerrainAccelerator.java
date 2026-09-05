package org.devt.higherworld.gpu;

import org.devt.higherworld.Higherworld;
import org.devt.higherworld.storage.CubePos;
import org.devt.higherworld.world.CustomWorldSettings;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL;
import org.lwjgl.opencl.CL10;
import org.lwjgl.opencl.CL12;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * OpenCL backend for HigherWorld's terrain density and voxel-raster stages.
 * Device/context/program ownership is intentionally isolated here so the rest
 * of the mod remains usable when a machine has no OpenCL runtime.
 */
final class OpenClTerrainAccelerator implements AutoCloseable {
    private static final String SAMPLE_KERNEL_NAME = "higherworld_custom_terrain";
    private static final String SAMPLE_BATCH_KERNEL_NAME = "higherworld_custom_terrain_batch";
    private static final String SURFACE_BATCH_KERNEL_NAME = "higherworld_custom_surface_batch";
    private static final String RASTER_KERNEL_NAME = "higherworld_rasterize_solid";
    private static final String RASTER_BATCH_KERNEL_NAME = "higherworld_rasterize_solid_batch";
    private static final int PARAMETER_COUNT = 30;
    private static final int MAX_SAMPLE_COUNT = 17 * 17 * 17;
    private static final int VOXEL_COUNT = CubePos.SIZE * CubePos.SIZE * CubePos.SIZE;
    /** Deep vanilla work is submitted as four adjacent 16-block sections. */
    private static final int MAX_BATCH_HEIGHT = CubePos.SIZE * 4;
    /** Matches the small 2x2/4x4 chunk batches used by C2ME's OpenCL path. */
    private static final int MAX_BATCH_CUBES = 16;
    private static final int MAX_BATCH_SAMPLE_COUNT =
            (CubePos.SIZE + 1) * (MAX_BATCH_HEIGHT + 1) * (CubePos.SIZE + 1)
                    * MAX_BATCH_CUBES;
    private static final int MAX_SURFACE_VALUE_COUNT = 3 * 17 * 17;
    private static final int MAX_BATCH_SURFACE_VALUE_COUNT = MAX_SURFACE_VALUE_COUNT * MAX_BATCH_CUBES;
    private static final int MAX_BATCH_VOXEL_COUNT =
            VOXEL_COUNT * (MAX_BATCH_HEIGHT / CubePos.SIZE) * MAX_BATCH_CUBES;
    private static final String[] OPENCL_LIBRARY_CANDIDATES = {
            "/usr/lib/x86_64-linux-gnu/libOpenCL.so.1",
            "/usr/lib64/libOpenCL.so.1",
            "/usr/lib/libOpenCL.so.1",
            "/run/opengl-driver/lib/libOpenCL.so",
            "/vendor/lib64/libOpenCL.so"
    };

    private final long device;
    private final long context;
    private final long queue;
    private final long program;
    private final long sampleKernel;
    private final long sampleBatchKernel;
    private final long surfaceBatchKernel;
    private final long rasterKernel;
    private final long rasterBatchKernel;
    private final long parametersBuffer;
    private final long surfaceBuffer;
    private final long outputBuffer;
    private final long solidBuffer;
    private final long batchOriginsBuffer;
    private final long batchOutputBuffer;
    private final long batchSolidBuffer;
    private final String description;
    private final Object queueLock = new Object();
    /** Reused host-side staging buffers; all access is guarded by queueLock. */
    private final DoubleBuffer hostSamples;
    private final DoubleBuffer hostBatchSamples;
    private final ByteBuffer hostSolid;
    private final IntBuffer hostBatchOrigins;
    private final ByteBuffer hostBatchSolid;
    private final double[] parameterValues = new double[PARAMETER_COUNT];
    private CustomWorldSettings uploadedSettings;
    private boolean parametersUploaded;
    // Kernel arguments persist between dispatches. Buffer identities are fixed
    // for this backend; changing their contents does not require rebinding them.
    // These caches, like the kernels themselves, are guarded by queueLock.
    private SurfaceArguments surfaceArguments;
    private SampleBatchArguments sampleBatchArguments;
    private RasterArguments rasterArguments;
    private RasterArguments rasterBatchArguments;
    private volatile boolean closed;

    private OpenClTerrainAccelerator(
            long device, long context, long queue, long program,
            long sampleKernel, long sampleBatchKernel,
            long surfaceBatchKernel,
            long rasterKernel, long rasterBatchKernel,
            long parametersBuffer, long surfaceBuffer, long outputBuffer, long solidBuffer,
            long batchOriginsBuffer, long batchOutputBuffer, long batchSolidBuffer,
            String description) {
        this.device = device;
        this.context = context;
        this.queue = queue;
        this.program = program;
        this.sampleKernel = sampleKernel;
        this.sampleBatchKernel = sampleBatchKernel;
        this.surfaceBatchKernel = surfaceBatchKernel;
        this.rasterKernel = rasterKernel;
        this.rasterBatchKernel = rasterBatchKernel;
        this.parametersBuffer = parametersBuffer;
        this.surfaceBuffer = surfaceBuffer;
        this.outputBuffer = outputBuffer;
        this.solidBuffer = solidBuffer;
        this.batchOriginsBuffer = batchOriginsBuffer;
        this.batchOutputBuffer = batchOutputBuffer;
        this.batchSolidBuffer = batchSolidBuffer;
        this.description = description;
        this.hostSamples = MemoryUtil.memAllocDouble(MAX_SAMPLE_COUNT);
        this.hostBatchSamples = MemoryUtil.memAllocDouble(MAX_BATCH_SAMPLE_COUNT);
        this.hostSolid = MemoryUtil.memAlloc(VOXEL_COUNT);
        this.hostBatchOrigins = MemoryUtil.memAllocInt(MAX_BATCH_CUBES * 3);
        this.hostBatchSolid = MemoryUtil.memAlloc(MAX_BATCH_VOXEL_COUNT);
    }

    static OpenClTerrainAccelerator create(GpuAccelerationConfig.Settings settings) {
        initializeOpenCl();
        List<DeviceInfo> devices = enumerateDevices(settings.allowCpuDevices());
        if (devices.isEmpty()) {
            throw new IllegalStateException(
                    "No OpenCL device with cl_khr_fp64 was found (GPU acceleration requires double precision)");
        }
        int index = settings.deviceIndex() < 0 ? 0 : settings.deviceIndex();
        if (index >= devices.size()) {
            throw new IllegalArgumentException(
                    "higherworld.gpu.device_index=" + settings.deviceIndex()
                            + " but only " + devices.size() + " compatible OpenCL device(s) exist");
        }
        DeviceInfo selected = devices.get(index);
        return createFor(selected);
    }

    String deviceDescription() {
        return description;
    }

    /** Synchronously dispatches the sample grid and copies it back to Java. */
    void sample(long seed, CubePos pos, CustomWorldSettings settings, double[] output) {
        if (closed) throw new IllegalStateException("OpenCL terrain accelerator is closed");
        if (pos == null) throw new IllegalArgumentException("Cube position cannot be null");
        if (settings == null) throw new IllegalArgumentException("Custom world settings cannot be null");
        if (output == null) throw new IllegalArgumentException("Terrain samples cannot be null");
        int stepX = settings.noiseSampleSizeX();
        int stepY = settings.noiseSampleSizeY();
        int stepZ = settings.noiseSampleSizeZ();
        int cellsX = CubePos.SIZE / stepX;
        int cellsY = CubePos.SIZE / stepY;
        int cellsZ = CubePos.SIZE / stepZ;
        int sampleCount = sampleCount(stepX, stepY, stepZ, cellsX, cellsY, cellsZ);
        if (output.length != sampleCount) {
            throw new IllegalArgumentException("Unexpected terrain sample array length " + output.length);
        }
        if (sampleCount > MAX_SAMPLE_COUNT) {
            throw new IllegalArgumentException("Terrain sample array is larger than the OpenCL buffer");
        }

        synchronized (queueLock) {
            if (closed) throw new IllegalStateException("OpenCL terrain accelerator is closed");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                writeParametersIfChanged(settings, stack);

                PointerBuffer globalSize = stack.mallocPointer(1).put(0, sampleCount);
                setSampleKernelArguments(seed, pos, stepX, stepY, stepZ,
                        cellsX, cellsY, cellsZ);
                check(CL12.clEnqueueNDRangeKernel(
                        queue, sampleKernel, 1, null, globalSize, null, null, null));
                readSamples(output, sampleCount);
            }
        }
    }

    /**
     * Samples the custom terrain equation and rasterizes it directly into the
     * 4096-voxel solid mask.  Keeping the intermediate density grid on-device
     * removes one device-to-host copy and the hot per-voxel interpolation loop.
     */
    void sampleCustomSolid(
            long seed, CubePos pos, CustomWorldSettings settings, boolean[] solid) {
        if (closed) throw new IllegalStateException("OpenCL terrain accelerator is closed");
        if (pos == null) throw new IllegalArgumentException("Cube position cannot be null");
        if (settings == null) throw new IllegalArgumentException("Custom world settings cannot be null");
        if (solid == null) throw new IllegalArgumentException("Solid mask cannot be null");
        if (solid.length != VOXEL_COUNT) {
            throw new IllegalArgumentException("Unexpected solid mask length " + solid.length);
        }
        int stepX = settings.noiseSampleSizeX();
        int stepY = settings.noiseSampleSizeY();
        int stepZ = settings.noiseSampleSizeZ();
        int cellsX = CubePos.SIZE / stepX;
        int cellsY = CubePos.SIZE / stepY;
        int cellsZ = CubePos.SIZE / stepZ;
        int sampleCount = sampleCount(stepX, stepY, stepZ, cellsX, cellsY, cellsZ);
        if (sampleCount > MAX_SAMPLE_COUNT) {
            throw new IllegalArgumentException("Terrain sample array is larger than the OpenCL buffer");
        }

        synchronized (queueLock) {
            if (closed) throw new IllegalStateException("OpenCL terrain accelerator is closed");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                writeParametersIfChanged(settings, stack);
                PointerBuffer globalSize = stack.mallocPointer(1).put(0, sampleCount);
                setSampleKernelArguments(seed, pos, stepX, stepY, stepZ,
                        cellsX, cellsY, cellsZ);
                check(CL12.clEnqueueNDRangeKernel(
                        queue, sampleKernel, 1, null, globalSize, null, null, null));
                rasterizeDeviceSamples(stepX, stepY, stepZ, cellsX, cellsY, cellsZ, 0, stack);
                readSolid(solid);
            }
        }
    }

    /**
     * Samples and rasterizes a group of independent custom cubes in one
     * device submission.  Every cube in a batch must use the same immutable
     * settings and seed; only its origin changes.  This is the hot path for
     * streamed non-vanilla cubes.
     */
    void sampleCustomSolidBatch(
            long seed, CubePos[] positions, CustomWorldSettings settings, boolean[][] solid) {
        if (closed) throw new IllegalStateException("OpenCL terrain accelerator is closed");
        if (positions == null || positions.length == 0 || positions.length > MAX_BATCH_CUBES) {
            throw new IllegalArgumentException("Custom terrain batch must contain 1-" + MAX_BATCH_CUBES + " cubes");
        }
        if (settings == null) throw new IllegalArgumentException("Custom world settings cannot be null");
        if (solid == null || solid.length != positions.length) {
            throw new IllegalArgumentException("Solid mask batch length does not match cube batch");
        }
        for (int index = 0; index < positions.length; index++) {
            if (positions[index] == null) throw new IllegalArgumentException("Cube position cannot be null");
            if (solid[index] == null || solid[index].length != VOXEL_COUNT) {
                throw new IllegalArgumentException("Unexpected solid mask length at batch index " + index);
            }
        }
        // A one-cube batch is common at low load.  Keep its low-latency fused
        // path instead of paying for the separate column-precompute kernel.
        if (positions.length == 1) {
            sampleCustomSolid(seed, positions[0], settings, solid[0]);
            return;
        }

        int stepX = settings.noiseSampleSizeX();
        int stepY = settings.noiseSampleSizeY();
        int stepZ = settings.noiseSampleSizeZ();
        int cellsX = CubePos.SIZE / stepX;
        int cellsY = CubePos.SIZE / stepY;
        int cellsZ = CubePos.SIZE / stepZ;
        int sampleCount = sampleCount(stepX, stepY, stepZ, cellsX, cellsY, cellsZ);
        int surfaceCount = (cellsX + 1) * (cellsZ + 1);
        long totalSamples = (long) sampleCount * positions.length;
        long totalSurfaceValues = (long) surfaceCount * positions.length * 3L;
        if (totalSamples > MAX_BATCH_SAMPLE_COUNT) {
            throw new IllegalArgumentException("Custom terrain batch exceeds the OpenCL sample buffer");
        }
        if (totalSurfaceValues > MAX_BATCH_SURFACE_VALUE_COUNT) {
            throw new IllegalArgumentException("Custom terrain batch exceeds the OpenCL surface buffer");
        }

        synchronized (queueLock) {
            if (closed) throw new IllegalStateException("OpenCL terrain accelerator is closed");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                writeParametersIfChanged(settings, stack);
                hostBatchOrigins.clear();
                for (CubePos position : positions) {
                    hostBatchOrigins.put(position.minBlockX())
                            .put(position.minBlockY())
                            .put(position.minBlockZ());
                }
                hostBatchOrigins.flip();
                check(CL12.clEnqueueWriteBuffer(
                        queue, batchOriginsBuffer, false, 0, hostBatchOrigins, null, null));

                setSurfaceBatchKernelArguments(
                        seed, positions.length, surfaceCount,
                        stepX, stepZ, cellsX, cellsZ);
                PointerBuffer surfaceGlobalSize = stack.mallocPointer(1)
                        .put(0, (long) positions.length * surfaceCount);
                check(CL12.clEnqueueNDRangeKernel(
                        queue, surfaceBatchKernel, 1, null, surfaceGlobalSize, null, null, null));

                setSampleBatchKernelArguments(
                        seed, positions.length, sampleCount, surfaceCount,
                        stepX, stepY, stepZ, cellsX, cellsY, cellsZ);
                PointerBuffer sampleGlobalSize = stack.mallocPointer(1)
                        .put(0, totalSamples);
                check(CL12.clEnqueueNDRangeKernel(
                        queue, sampleBatchKernel, 1, null, sampleGlobalSize, null, null, null));

                setRasterBatchKernelArguments(
                        positions.length, sampleCount,
                        stepX, stepY, stepZ, cellsX, cellsY, cellsZ, 0);
                PointerBuffer rasterGlobalSize = stack.mallocPointer(1)
                        .put(0, (long) positions.length * VOXEL_COUNT);
                check(CL12.clEnqueueNDRangeKernel(
                        queue, rasterBatchKernel, 1, null, rasterGlobalSize, null, null, null));

                int solidCount = positions.length * VOXEL_COUNT;
                hostBatchSolid.clear();
                hostBatchSolid.limit(solidCount);
                check(CL12.clEnqueueReadBuffer(
                        queue, batchSolidBuffer, true, 0, hostBatchSolid, null, null));
                hostBatchSolid.rewind();
                for (boolean[] output : solid) {
                    for (int voxel = 0; voxel < VOXEL_COUNT; voxel++) {
                        output[voxel] = hostBatchSolid.get() != 0;
                    }
                }
            }
        }
    }

    /** Rasterizes a host-provided density grid using the requested interpolation mode. */
    void rasterize(
            double[] samples, int stepX, int stepY, int stepZ,
            int cellsX, int cellsY, int cellsZ, int interpolationMode, boolean[] solid) {
        if (closed) throw new IllegalStateException("OpenCL terrain accelerator is closed");
        if (samples == null) throw new IllegalArgumentException("Density samples cannot be null");
        if (solid == null) throw new IllegalArgumentException("Solid mask cannot be null");
        if (solid.length != VOXEL_COUNT) {
            throw new IllegalArgumentException("Unexpected solid mask length " + solid.length);
        }
        int sampleCount = sampleCount(stepX, stepY, stepZ, cellsX, cellsY, cellsZ);
        if (samples.length != sampleCount) {
            throw new IllegalArgumentException("Unexpected density grid length " + samples.length);
        }
        if (sampleCount > MAX_SAMPLE_COUNT) {
            throw new IllegalArgumentException("Terrain sample array is larger than the OpenCL buffer");
        }

        synchronized (queueLock) {
            if (closed) throw new IllegalStateException("OpenCL terrain accelerator is closed");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                hostSamples.clear();
                hostSamples.limit(sampleCount);
                hostSamples.put(samples).flip();
                check(CL12.clEnqueueWriteBuffer(
                        queue, outputBuffer, true, 0, hostSamples, null, null));
                rasterizeDeviceSamples(stepX, stepY, stepZ, cellsX, cellsY, cellsZ,
                        interpolationMode, stack);
                readSolid(solid);
            }
        }
    }

    /** Rasterizes several host-provided density grids in one device dispatch. */
    void rasterizeBatch(
            double[][] samples, int stepX, int stepY, int stepZ,
            int cellsX, int cellsY, int cellsZ, int interpolationMode,
            boolean[][] solid) {
        if (closed) throw new IllegalStateException("OpenCL terrain accelerator is closed");
        if (samples == null || samples.length == 0 || samples.length > MAX_BATCH_CUBES) {
            throw new IllegalArgumentException("Density batch size is outside the OpenCL limit");
        }
        if (solid == null || solid.length != samples.length) {
            throw new IllegalArgumentException("Solid mask batch length does not match density batch");
        }
        int outputHeight = Math.multiplyExact(cellsY, stepY);
        int sampleCount = sampleCount(
                stepX, stepY, stepZ, cellsX, cellsY, cellsZ, outputHeight);
        long totalSamples = (long) sampleCount * samples.length;
        if (totalSamples > MAX_BATCH_SAMPLE_COUNT) {
            throw new IllegalArgumentException("Density batch exceeds the OpenCL sample buffer");
        }
        long totalVoxels = (long) samples.length * CubePos.SIZE * outputHeight * CubePos.SIZE;
        if (outputHeight <= 0 || outputHeight > MAX_BATCH_HEIGHT
                || outputHeight % CubePos.SIZE != 0
                || totalVoxels > MAX_BATCH_VOXEL_COUNT) {
            throw new IllegalArgumentException("Density batch output height is outside the OpenCL buffer");
        }
        for (int index = 0; index < samples.length; index++) {
            if (samples[index] == null || samples[index].length != sampleCount) {
                throw new IllegalArgumentException(
                        "Unexpected density grid length at batch index " + index);
            }
            long expectedSolidCount = (long) CubePos.SIZE * outputHeight * CubePos.SIZE;
            if (solid[index] == null || solid[index].length != expectedSolidCount) {
                throw new IllegalArgumentException(
                        "Unexpected solid mask length at batch index " + index);
            }
        }
        if (samples.length == 1 && outputHeight == CubePos.SIZE) {
            rasterize(samples[0], stepX, stepY, stepZ, cellsX, cellsY, cellsZ,
                    interpolationMode, solid[0]);
            return;
        }

        synchronized (queueLock) {
            if (closed) throw new IllegalStateException("OpenCL terrain accelerator is closed");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                hostBatchSamples.clear();
                hostBatchSamples.limit((int) totalSamples);
                for (double[] batch : samples) hostBatchSamples.put(batch);
                hostBatchSamples.flip();
                check(CL12.clEnqueueWriteBuffer(
                        queue, batchOutputBuffer, true, 0, hostBatchSamples, null, null));

                setRasterBatchKernelArguments(
                        samples.length, sampleCount,
                        stepX, stepY, stepZ, cellsX, cellsY, cellsZ, interpolationMode);
                PointerBuffer globalSize = stack.mallocPointer(1)
                        .put(0, totalVoxels);
                check(CL12.clEnqueueNDRangeKernel(
                        queue, rasterBatchKernel, 1, null, globalSize, null, null, null));

                int solidCount = Math.toIntExact(totalVoxels);
                hostBatchSolid.clear();
                hostBatchSolid.limit(solidCount);
                check(CL12.clEnqueueReadBuffer(
                        queue, batchSolidBuffer, true, 0, hostBatchSolid, null, null));
                hostBatchSolid.rewind();
                for (boolean[] output : solid) {
                    for (int voxel = 0; voxel < output.length; voxel++) {
                        output[voxel] = hostBatchSolid.get() != 0;
                    }
                }
            }
        }
    }

    private void writeParametersIfChanged(CustomWorldSettings settings, MemoryStack stack) {
        if (parametersUploaded && uploadedSettings == settings) return;
        putParameters(parameterValues, settings);
        DoubleBuffer parameters = stack.mallocDouble(PARAMETER_COUNT);
        parameters.put(parameterValues).flip();
        check(CL12.clEnqueueWriteBuffer(
                queue, parametersBuffer, true, 0, parameters, null, null));
        uploadedSettings = settings;
        parametersUploaded = true;
    }

    private static int sampleCount(
            int stepX, int stepY, int stepZ,
            int cellsX, int cellsY, int cellsZ) {
        return sampleCount(stepX, stepY, stepZ, cellsX, cellsY, cellsZ, CubePos.SIZE);
    }

    private static int sampleCount(
            int stepX, int stepY, int stepZ,
            int cellsX, int cellsY, int cellsZ, int outputHeight) {
        if (stepX <= 0 || stepY <= 0 || stepZ <= 0
                || cellsX <= 0 || cellsY <= 0 || cellsZ <= 0
                || CubePos.SIZE % stepX != 0
                || CubePos.SIZE % stepY != 0
                || CubePos.SIZE % stepZ != 0
                || cellsX * stepX != CubePos.SIZE
                || cellsY * stepY != outputHeight
                || cellsZ * stepZ != CubePos.SIZE) {
            throw new IllegalArgumentException("Density grid must exactly cover one 16^3 cube");
        }
        return (cellsX + 1) * (cellsY + 1) * (cellsZ + 1);
    }

    private void rasterizeDeviceSamples(
            int stepX, int stepY, int stepZ,
            int cellsX, int cellsY, int cellsZ,
            int interpolationMode, MemoryStack stack) {
        setRasterKernelArguments(stepX, stepY, stepZ, cellsX, cellsY, cellsZ, interpolationMode);
        PointerBuffer globalSize = stack.mallocPointer(1).put(0, VOXEL_COUNT);
        check(CL12.clEnqueueNDRangeKernel(
                queue, rasterKernel, 1, null, globalSize, null, null, null));
    }

    private void readSamples(double[] output, int sampleCount) {
        hostSamples.clear();
        hostSamples.limit(sampleCount);
        check(CL12.clEnqueueReadBuffer(
                queue, outputBuffer, true, 0, hostSamples, null, null));
        hostSamples.rewind();
        hostSamples.get(output);
    }

    private void readSolid(boolean[] output) {
        hostSolid.clear();
        hostSolid.limit(VOXEL_COUNT);
        check(CL12.clEnqueueReadBuffer(
                queue, solidBuffer, true, 0, hostSolid, null, null));
        hostSolid.rewind();
        for (int i = 0; i < output.length; i++) output[i] = hostSolid.get() != 0;
    }

    private void setSampleKernelArguments(
            long seed, CubePos pos, int stepX, int stepY, int stepZ,
            int cellsX, int cellsY, int cellsZ) {
        check(CL12.clSetKernelArg1p(sampleKernel, 0, parametersBuffer));
        check(CL12.clSetKernelArg1l(sampleKernel, 1, seed));
        check(CL12.clSetKernelArg1i(sampleKernel, 2, pos.minBlockX()));
        check(CL12.clSetKernelArg1i(sampleKernel, 3, pos.minBlockY()));
        check(CL12.clSetKernelArg1i(sampleKernel, 4, pos.minBlockZ()));
        check(CL12.clSetKernelArg1i(sampleKernel, 5, stepX));
        check(CL12.clSetKernelArg1i(sampleKernel, 6, stepY));
        check(CL12.clSetKernelArg1i(sampleKernel, 7, stepZ));
        check(CL12.clSetKernelArg1i(sampleKernel, 8, cellsX));
        check(CL12.clSetKernelArg1i(sampleKernel, 9, cellsY));
        check(CL12.clSetKernelArg1i(sampleKernel, 10, cellsZ));
        check(CL12.clSetKernelArg1p(sampleKernel, 11, outputBuffer));
    }

    private void setSurfaceBatchKernelArguments(
            long seed, int batchCount, int surfaceCount,
            int stepX, int stepZ, int cellsX, int cellsZ) {
        SurfaceArguments arguments = new SurfaceArguments(
                seed, batchCount, surfaceCount, stepX, stepZ, cellsX, cellsZ);
        if (arguments.equals(surfaceArguments)) return;
        surfaceArguments = null;
        check(CL12.clSetKernelArg1p(surfaceBatchKernel, 0, parametersBuffer));
        check(CL12.clSetKernelArg1l(surfaceBatchKernel, 1, seed));
        check(CL12.clSetKernelArg1p(surfaceBatchKernel, 2, batchOriginsBuffer));
        check(CL12.clSetKernelArg1i(surfaceBatchKernel, 3, batchCount));
        check(CL12.clSetKernelArg1i(surfaceBatchKernel, 4, surfaceCount));
        check(CL12.clSetKernelArg1i(surfaceBatchKernel, 5, stepX));
        check(CL12.clSetKernelArg1i(surfaceBatchKernel, 6, stepZ));
        check(CL12.clSetKernelArg1i(surfaceBatchKernel, 7, cellsX));
        check(CL12.clSetKernelArg1i(surfaceBatchKernel, 8, cellsZ));
        check(CL12.clSetKernelArg1p(surfaceBatchKernel, 9, surfaceBuffer));
        surfaceArguments = arguments;
    }

    private void setSampleBatchKernelArguments(
            long seed, int batchCount, int sampleCount, int surfaceCount,
            int stepX, int stepY, int stepZ,
            int cellsX, int cellsY, int cellsZ) {
        SampleBatchArguments arguments = new SampleBatchArguments(
                seed, batchCount, sampleCount, surfaceCount,
                stepX, stepY, stepZ, cellsX, cellsY, cellsZ);
        if (arguments.equals(sampleBatchArguments)) return;
        sampleBatchArguments = null;
        check(CL12.clSetKernelArg1p(sampleBatchKernel, 0, parametersBuffer));
        check(CL12.clSetKernelArg1l(sampleBatchKernel, 1, seed));
        check(CL12.clSetKernelArg1p(sampleBatchKernel, 2, batchOriginsBuffer));
        check(CL12.clSetKernelArg1i(sampleBatchKernel, 3, batchCount));
        check(CL12.clSetKernelArg1i(sampleBatchKernel, 4, sampleCount));
        check(CL12.clSetKernelArg1i(sampleBatchKernel, 5, surfaceCount));
        check(CL12.clSetKernelArg1i(sampleBatchKernel, 6, stepX));
        check(CL12.clSetKernelArg1i(sampleBatchKernel, 7, stepY));
        check(CL12.clSetKernelArg1i(sampleBatchKernel, 8, stepZ));
        check(CL12.clSetKernelArg1i(sampleBatchKernel, 9, cellsX));
        check(CL12.clSetKernelArg1i(sampleBatchKernel, 10, cellsY));
        check(CL12.clSetKernelArg1i(sampleBatchKernel, 11, cellsZ));
        check(CL12.clSetKernelArg1p(sampleBatchKernel, 12, surfaceBuffer));
        check(CL12.clSetKernelArg1p(sampleBatchKernel, 13, batchOutputBuffer));
        sampleBatchArguments = arguments;
    }

    private void setRasterBatchKernelArguments(
            int batchCount, int sampleCount,
            int stepX, int stepY, int stepZ,
            int cellsX, int cellsY, int cellsZ, int interpolationMode) {
        RasterArguments arguments = new RasterArguments(
                batchCount, sampleCount, stepX, stepY, stepZ, cellsX, cellsY, cellsZ, interpolationMode);
        if (arguments.equals(rasterBatchArguments)) return;
        rasterBatchArguments = null;
        check(CL12.clSetKernelArg1p(rasterBatchKernel, 0, batchOutputBuffer));
        check(CL12.clSetKernelArg1i(rasterBatchKernel, 1, batchCount));
        check(CL12.clSetKernelArg1i(rasterBatchKernel, 2, sampleCount));
        check(CL12.clSetKernelArg1i(rasterBatchKernel, 3, stepX));
        check(CL12.clSetKernelArg1i(rasterBatchKernel, 4, stepY));
        check(CL12.clSetKernelArg1i(rasterBatchKernel, 5, stepZ));
        check(CL12.clSetKernelArg1i(rasterBatchKernel, 6, cellsX));
        check(CL12.clSetKernelArg1i(rasterBatchKernel, 7, cellsY));
        check(CL12.clSetKernelArg1i(rasterBatchKernel, 8, cellsZ));
        check(CL12.clSetKernelArg1i(rasterBatchKernel, 9, interpolationMode));
        check(CL12.clSetKernelArg1p(rasterBatchKernel, 10, batchSolidBuffer));
        rasterBatchArguments = arguments;
    }

    private void setRasterKernelArguments(
            int stepX, int stepY, int stepZ,
            int cellsX, int cellsY, int cellsZ, int interpolationMode) {
        RasterArguments arguments = new RasterArguments(
                1, 0, stepX, stepY, stepZ, cellsX, cellsY, cellsZ, interpolationMode);
        if (arguments.equals(rasterArguments)) return;
        rasterArguments = null;
        check(CL12.clSetKernelArg1p(rasterKernel, 0, outputBuffer));
        check(CL12.clSetKernelArg1i(rasterKernel, 1, stepX));
        check(CL12.clSetKernelArg1i(rasterKernel, 2, stepY));
        check(CL12.clSetKernelArg1i(rasterKernel, 3, stepZ));
        check(CL12.clSetKernelArg1i(rasterKernel, 4, cellsX));
        check(CL12.clSetKernelArg1i(rasterKernel, 5, cellsY));
        check(CL12.clSetKernelArg1i(rasterKernel, 6, cellsZ));
        check(CL12.clSetKernelArg1i(rasterKernel, 7, interpolationMode));
        check(CL12.clSetKernelArg1p(rasterKernel, 8, solidBuffer));
        rasterArguments = arguments;
    }

    private record SurfaceArguments(
            long seed, int batchCount, int surfaceCount,
            int stepX, int stepZ, int cellsX, int cellsZ) {}

    private record SampleBatchArguments(
            long seed, int batchCount, int sampleCount, int surfaceCount,
            int stepX, int stepY, int stepZ, int cellsX, int cellsY, int cellsZ) {}

    private record RasterArguments(
            int batchCount, int sampleCount, int stepX, int stepY, int stepZ,
            int cellsX, int cellsY, int cellsZ, int interpolationMode) {}

    private static void putParameters(double[] values, CustomWorldSettings settings) {
        int i = 0;
        values[i++] = settings.depthNoiseFactor();
        values[i++] = settings.depthNoiseOffset();
        values[i++] = settings.depthNoiseFrequencyX();
        values[i++] = settings.depthNoiseFrequencyZ();
        values[i++] = settings.depthNoiseOctaves();
        values[i++] = settings.selectorNoiseFactor();
        values[i++] = settings.selectorNoiseOffset();
        values[i++] = settings.selectorNoiseFrequencyX();
        values[i++] = settings.selectorNoiseFrequencyY();
        values[i++] = settings.selectorNoiseFrequencyZ();
        values[i++] = settings.selectorNoiseOctaves();
        values[i++] = settings.lowNoiseFactor();
        values[i++] = settings.lowNoiseOffset();
        values[i++] = settings.lowNoiseFrequencyX();
        values[i++] = settings.lowNoiseFrequencyY();
        values[i++] = settings.lowNoiseFrequencyZ();
        values[i++] = settings.lowNoiseOctaves();
        values[i++] = settings.highNoiseFactor();
        values[i++] = settings.highNoiseOffset();
        values[i++] = settings.highNoiseFrequencyX();
        values[i++] = settings.highNoiseFrequencyY();
        values[i++] = settings.highNoiseFrequencyZ();
        values[i++] = settings.highNoiseOctaves();
        // These values are used at every density sample.  Upload the already
        // clamped powers once per immutable settings object instead of making
        // the device execute two integer casts and two pow() calls per sample.
        values[i++] = Math.scalb(1.0,
                -Math.min(1022, Math.max(0, settings.biomeSize())));
        values[i++] = Math.scalb(1.0,
                -Math.min(1022, Math.max(0, settings.riverSize())));
        values[i++] = settings.heightVariationFactor();
        values[i++] = settings.specialHeightVariationFactorBelowAverageY();
        values[i++] = settings.heightVariationOffset();
        values[i++] = settings.heightFactor();
        values[i] = settings.heightOffset();
    }

    private static OpenClTerrainAccelerator createFor(DeviceInfo info) {
        long context = 0L;
        long queue = 0L;
        long program = 0L;
        long sampleKernel = 0L;
        long sampleBatchKernel = 0L;
        long surfaceBatchKernel = 0L;
        long rasterKernel = 0L;
        long rasterBatchKernel = 0L;
        long parametersBuffer = 0L;
        long surfaceBuffer = 0L;
        long outputBuffer = 0L;
        long solidBuffer = 0L;
        long batchOriginsBuffer = 0L;
        long batchOutputBuffer = 0L;
        long batchSolidBuffer = 0L;
        ByteBuffer sourceBuffer = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer error = stack.callocInt(1);
            PointerBuffer properties = stack.mallocPointer(3)
                    .put(CL12.CL_CONTEXT_PLATFORM)
                    .put(info.platform())
                    .put(0L)
                    .flip();
            context = CL12.clCreateContext(properties, info.device(), null, 0L, error);
            check(error);

            queue = CL12.clCreateCommandQueue(context, info.device(), 0L, error);
            check(error);

            String source = loadKernelSource();
            sourceBuffer = MemoryUtil.memUTF8(source, true);
            PointerBuffer sources = stack.mallocPointer(1).put(0, MemoryUtil.memAddress(sourceBuffer));
            program = CL12.clCreateProgramWithSource(context, sources, null, error);
            check(error);

            int buildResult = CL12.clBuildProgram(program, info.device(), "-cl-std=CL1.2", null, 0L);
            if (buildResult != CL10.CL_SUCCESS) {
                throw new IllegalStateException(
                        "OpenCL terrain kernel build failed on " + info.name()
                                + ": " + getBuildLog(program, info.device()));
            }

            sampleKernel = CL12.clCreateKernel(program, SAMPLE_KERNEL_NAME, error);
            check(error);
            sampleBatchKernel = CL12.clCreateKernel(program, SAMPLE_BATCH_KERNEL_NAME, error);
            check(error);
            surfaceBatchKernel = CL12.clCreateKernel(program, SURFACE_BATCH_KERNEL_NAME, error);
            check(error);
            rasterKernel = CL12.clCreateKernel(program, RASTER_KERNEL_NAME, error);
            check(error);
            rasterBatchKernel = CL12.clCreateKernel(program, RASTER_BATCH_KERNEL_NAME, error);
            check(error);
            parametersBuffer = CL12.clCreateBuffer(
                    context, CL12.CL_MEM_READ_ONLY, (long) PARAMETER_COUNT * Double.BYTES, error);
            check(error);
            surfaceBuffer = CL12.clCreateBuffer(
                    context, CL12.CL_MEM_READ_WRITE,
                    (long) MAX_BATCH_SURFACE_VALUE_COUNT * Double.BYTES, error);
            check(error);
            outputBuffer = CL12.clCreateBuffer(
                    context, CL12.CL_MEM_READ_WRITE, (long) MAX_SAMPLE_COUNT * Double.BYTES, error);
            check(error);
            solidBuffer = CL12.clCreateBuffer(
                    context, CL12.CL_MEM_WRITE_ONLY, VOXEL_COUNT, error);
            check(error);
            batchOriginsBuffer = CL12.clCreateBuffer(
                    context, CL12.CL_MEM_READ_ONLY,
                    (long) MAX_BATCH_CUBES * 3 * Integer.BYTES, error);
            check(error);
            batchOutputBuffer = CL12.clCreateBuffer(
                    context, CL12.CL_MEM_READ_WRITE,
                    (long) MAX_BATCH_SAMPLE_COUNT * Double.BYTES, error);
            check(error);
            batchSolidBuffer = CL12.clCreateBuffer(
                    context, CL12.CL_MEM_WRITE_ONLY, MAX_BATCH_VOXEL_COUNT, error);
            check(error);

            return new OpenClTerrainAccelerator(
                    info.device(), context, queue, program,
                    sampleKernel, sampleBatchKernel,
                    surfaceBatchKernel,
                    rasterKernel, rasterBatchKernel,
                    parametersBuffer, surfaceBuffer, outputBuffer, solidBuffer,
                    batchOriginsBuffer, batchOutputBuffer, batchSolidBuffer,
                    info.description());
        } catch (Throwable throwable) {
            releaseQuietly(
                    batchSolidBuffer, batchOutputBuffer, batchOriginsBuffer,
                    solidBuffer, outputBuffer, surfaceBuffer, parametersBuffer,
                    rasterBatchKernel, rasterKernel, sampleBatchKernel, sampleKernel,
                    surfaceBatchKernel,
                    program, queue, context);
            throw throwable instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException("Cannot create OpenCL terrain context", throwable);
        } finally {
            if (sourceBuffer != null) MemoryUtil.memFree(sourceBuffer);
        }
    }

    private static String getBuildLog(long program, long device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer size = stack.mallocPointer(1);
            int result = CL12.clGetProgramBuildInfo(
                    program, device, CL12.CL_PROGRAM_BUILD_LOG, (ByteBuffer) null, size);
            if (result != CL10.CL_SUCCESS || size.get(0) == 0L) return "no build log";
            ByteBuffer log = MemoryUtil.memAlloc((int) size.get(0));
            try {
                check(CL12.clGetProgramBuildInfo(
                        program, device, CL12.CL_PROGRAM_BUILD_LOG, log, null));
                return MemoryUtil.memUTF8(log, Math.max(0, log.remaining() - 1));
            } finally {
                MemoryUtil.memFree(log);
            }
        }
    }

    private static void initializeOpenCl() {
        Configuration.OPENCL_EXPLICIT_INIT.set(true);
        try {
            CL.getFunctionProvider();
            return;
        } catch (Throwable ignored) {
        }
        Throwable failure;
        try {
            CL.create();
            return;
        } catch (Throwable throwable) {
            failure = throwable;
        }
        for (String library : OPENCL_LIBRARY_CANDIDATES) {
            try {
                CL.create(library);
                return;
            } catch (Throwable throwable) {
                failure.addSuppressed(throwable);
            }
        }
        throw new IllegalStateException("Failed to load the OpenCL runtime", failure);
    }

    private static List<DeviceInfo> enumerateDevices(boolean allowCpuDevices) {
        List<DeviceInfo> result = new ArrayList<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.callocInt(1);
            check(CL12.clGetPlatformIDs(null, count));
            PointerBuffer platforms = stack.mallocPointer(count.get(0));
            check(CL12.clGetPlatformIDs(platforms, (IntBuffer) null));
            for (int platformIndex = 0; platformIndex < platforms.capacity(); platformIndex++) {
                long platform = platforms.get(platformIndex);
                long deviceTypes = CL12.CL_DEVICE_TYPE_GPU | CL12.CL_DEVICE_TYPE_ACCELERATOR;
                if (allowCpuDevices) deviceTypes |= CL12.CL_DEVICE_TYPE_CPU;
                IntBuffer deviceCount = stack.callocInt(1);
                int devicesResult = CL12.clGetDeviceIDs(platform, deviceTypes, null, deviceCount);
                if (devicesResult == CL10.CL_DEVICE_NOT_FOUND) continue;
                check(devicesResult);
                PointerBuffer devices = stack.mallocPointer(deviceCount.get(0));
                check(CL12.clGetDeviceIDs(platform, deviceTypes, devices, (IntBuffer) null));
                for (int deviceIndex = 0; deviceIndex < devices.capacity(); deviceIndex++) {
                    long device = devices.get(deviceIndex);
                    String name = getDeviceString(device, CL12.CL_DEVICE_NAME);
                    String version = getDeviceString(device, CL12.CL_DEVICE_VERSION);
                    if (!supportsOpenCl12(version)) {
                        Higherworld.LOGGER.warn(
                                "Skipping OpenCL device {} because OpenCL 1.2 or newer is required (reported {})",
                                name, version);
                        continue;
                    }
                    String extensions = getDeviceString(device, CL12.CL_DEVICE_EXTENSIONS);
                    if (!hasExtension(extensions, "cl_khr_fp64")) {
                        Higherworld.LOGGER.warn(
                                "Skipping OpenCL device {} because cl_khr_fp64 is unavailable",
                                name);
                        continue;
                    }
                    long type = getDeviceLong(device, CL12.CL_DEVICE_TYPE);
                    String vendor = getDeviceString(device, CL12.CL_DEVICE_VENDOR);
                    result.add(new DeviceInfo(platform, device, type, name, vendor, version));
                }
            }
        }
        result.sort(Comparator.comparingInt(DeviceInfo::priority));
        for (int i = 0; i < result.size(); i++) {
            DeviceInfo info = result.get(i);
            Higherworld.LOGGER.info("OpenCL device {}: {}", i, info.description());
        }
        return result;
    }

    private static boolean hasExtension(String extensions, String expected) {
        for (String extension : extensions.split("\\s+")) {
            if (extension.equals(expected)) return true;
        }
        return false;
    }

    private static boolean supportsOpenCl12(String version) {
        int start = version.indexOf("OpenCL ");
        if (start < 0) return false;
        start += "OpenCL ".length();
        int separator = version.indexOf('.', start);
        if (separator < 0) return false;
        int end = separator + 1;
        while (end < version.length() && Character.isDigit(version.charAt(end))) end++;
        try {
            int major = Integer.parseInt(version.substring(start, separator));
            int minor = Integer.parseInt(version.substring(separator + 1, end));
            return major > 1 || major == 1 && minor >= 2;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static String getDeviceString(long device, int parameter) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer size = stack.mallocPointer(1);
            check(CL12.clGetDeviceInfo(device, parameter, (ByteBuffer) null, size));
            int length = Math.toIntExact(size.get(0));
            ByteBuffer value = stack.malloc(length);
            check(CL12.clGetDeviceInfo(device, parameter, value, null));
            return MemoryUtil.memUTF8(value, Math.max(0, length - 1));
        }
    }

    private static long getDeviceLong(long device, int parameter) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer value = stack.mallocLong(1);
            check(CL12.clGetDeviceInfo(device, parameter, value, null));
            return value.get(0);
        }
    }

    private static String loadKernelSource() {
        try (InputStream input = OpenClTerrainAccelerator.class.getClassLoader()
                .getResourceAsStream("assets/higherworld/opencl/custom_terrain.cl")) {
            if (input == null) throw new IllegalStateException("OpenCL terrain kernel resource is missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read OpenCL terrain kernel resource", exception);
        }
    }

    private static void releaseQuietly(
            long batchSolidBuffer, long batchOutputBuffer, long batchOriginsBuffer,
            long solidBuffer, long outputBuffer, long surfaceBuffer, long parametersBuffer,
            long rasterBatchKernel, long rasterKernel,
            long sampleBatchKernel, long sampleKernel,
            long surfaceBatchKernel,
            long program, long queue, long context) {
        release(CL12::clReleaseMemObject, batchSolidBuffer);
        release(CL12::clReleaseMemObject, batchOutputBuffer);
        release(CL12::clReleaseMemObject, batchOriginsBuffer);
        release(CL12::clReleaseMemObject, solidBuffer);
        release(CL12::clReleaseMemObject, outputBuffer);
        release(CL12::clReleaseMemObject, surfaceBuffer);
        release(CL12::clReleaseMemObject, parametersBuffer);
        release(CL12::clReleaseKernel, rasterBatchKernel);
        release(CL12::clReleaseKernel, rasterKernel);
        release(CL12::clReleaseKernel, sampleBatchKernel);
        release(CL12::clReleaseKernel, sampleKernel);
        release(CL12::clReleaseKernel, surfaceBatchKernel);
        release(CL12::clReleaseProgram, program);
        release(CL12::clReleaseCommandQueue, queue);
        release(CL12::clReleaseContext, context);
    }

    @FunctionalInterface
    private interface ReleaseAction {
        int release(long handle);
    }

    private static void release(ReleaseAction action, long handle) {
        if (handle == 0L) return;
        try {
            check(action.release(handle));
        } catch (Throwable throwable) {
            Higherworld.LOGGER.debug("Unable to release OpenCL handle {}", handle, throwable);
        }
    }

    private static void check(IntBuffer error) {
        check(error.get(error.position()));
    }

    private static void check(int error) {
        if (error != CL10.CL_SUCCESS) {
            throw new IllegalStateException("OpenCL error " + error);
        }
    }

    @Override
    public void close() {
        synchronized (queueLock) {
            if (closed) return;
            closed = true;
            MemoryUtil.memFree(hostBatchSolid);
            MemoryUtil.memFree(hostBatchOrigins);
            MemoryUtil.memFree(hostSolid);
            MemoryUtil.memFree(hostBatchSamples);
            MemoryUtil.memFree(hostSamples);
            release(CL12::clReleaseMemObject, batchSolidBuffer);
            release(CL12::clReleaseMemObject, batchOutputBuffer);
            release(CL12::clReleaseMemObject, batchOriginsBuffer);
            release(CL12::clReleaseMemObject, solidBuffer);
            release(CL12::clReleaseMemObject, outputBuffer);
            release(CL12::clReleaseMemObject, surfaceBuffer);
            release(CL12::clReleaseMemObject, parametersBuffer);
            release(CL12::clReleaseKernel, rasterBatchKernel);
            release(CL12::clReleaseKernel, rasterKernel);
            release(CL12::clReleaseKernel, sampleBatchKernel);
            release(CL12::clReleaseKernel, sampleKernel);
            release(CL12::clReleaseKernel, surfaceBatchKernel);
            release(CL12::clReleaseProgram, program);
            release(CL12::clReleaseCommandQueue, queue);
            release(CL12::clReleaseContext, context);
        }
    }

    private record DeviceInfo(
            long platform, long device, long type,
            String name, String vendor, String version) {
        private int priority() {
            if ((type & CL12.CL_DEVICE_TYPE_GPU) != 0) return 0;
            if ((type & CL12.CL_DEVICE_TYPE_ACCELERATOR) != 0) return 1;
            return 2;
        }

        private String description() {
            return name + " [" + vendor + ", " + version + "]";
        }
    }
}
