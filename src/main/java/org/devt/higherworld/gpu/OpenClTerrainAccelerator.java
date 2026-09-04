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
 * Minimal OpenCL backend for HigherWorld's pure custom-terrain equation.
 * Device/context/program ownership is intentionally isolated here so the rest
 * of the mod remains usable when a machine has no OpenCL runtime.
 */
final class OpenClTerrainAccelerator implements AutoCloseable {
    private static final String KERNEL_NAME = "higherworld_custom_terrain";
    private static final int PARAMETER_COUNT = 30;
    private static final int MAX_SAMPLE_COUNT = 17 * 17 * 17;
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
    private final long kernel;
    private final long parametersBuffer;
    private final long outputBuffer;
    private final String description;
    private final Object queueLock = new Object();
    private volatile boolean closed;

    private OpenClTerrainAccelerator(
            long device, long context, long queue, long program, long kernel,
            long parametersBuffer, long outputBuffer, String description) {
        this.device = device;
        this.context = context;
        this.queue = queue;
        this.program = program;
        this.kernel = kernel;
        this.parametersBuffer = parametersBuffer;
        this.outputBuffer = outputBuffer;
        this.description = description;
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
        int stepX = settings.noiseSampleSizeX();
        int stepY = settings.noiseSampleSizeY();
        int stepZ = settings.noiseSampleSizeZ();
        int cellsX = CubePos.SIZE / stepX;
        int cellsY = CubePos.SIZE / stepY;
        int cellsZ = CubePos.SIZE / stepZ;
        int sampleCount = (cellsX + 1) * (cellsY + 1) * (cellsZ + 1);
        if (output.length != sampleCount) {
            throw new IllegalArgumentException("Unexpected terrain sample array length " + output.length);
        }
        if (sampleCount > MAX_SAMPLE_COUNT) {
            throw new IllegalArgumentException("Terrain sample array is larger than the OpenCL buffer");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            DoubleBuffer parameters = stack.mallocDouble(PARAMETER_COUNT);
            putParameters(parameters, settings);
            parameters.flip();

            PointerBuffer globalSize = stack.mallocPointer(1).put(0, sampleCount);
            DoubleBuffer result = MemoryUtil.memAllocDouble(sampleCount);
            try {
                synchronized (queueLock) {
                    if (closed) throw new IllegalStateException("OpenCL terrain accelerator is closed");
                    check(CL12.clEnqueueWriteBuffer(
                            queue, parametersBuffer, true, 0, parameters, null, null));
                    setKernelArguments(seed, pos, stepX, stepY, stepZ,
                            cellsX, cellsY, cellsZ);
                    check(CL12.clEnqueueNDRangeKernel(
                            queue, kernel, 1, null, globalSize, null, null, null));
                    check(CL12.clFinish(queue));
                    check(CL12.clEnqueueReadBuffer(
                            queue, outputBuffer, true, 0, result, null, null));
                }
                result.rewind();
                result.get(output);
            } finally {
                MemoryUtil.memFree(result);
            }
        }
    }

    private void setKernelArguments(
            long seed, CubePos pos, int stepX, int stepY, int stepZ,
            int cellsX, int cellsY, int cellsZ) {
        check(CL12.clSetKernelArg1p(kernel, 0, parametersBuffer));
        check(CL12.clSetKernelArg1l(kernel, 1, seed));
        check(CL12.clSetKernelArg1i(kernel, 2, pos.minBlockX()));
        check(CL12.clSetKernelArg1i(kernel, 3, pos.minBlockY()));
        check(CL12.clSetKernelArg1i(kernel, 4, pos.minBlockZ()));
        check(CL12.clSetKernelArg1i(kernel, 5, stepX));
        check(CL12.clSetKernelArg1i(kernel, 6, stepY));
        check(CL12.clSetKernelArg1i(kernel, 7, stepZ));
        check(CL12.clSetKernelArg1i(kernel, 8, cellsX));
        check(CL12.clSetKernelArg1i(kernel, 9, cellsY));
        check(CL12.clSetKernelArg1i(kernel, 10, cellsZ));
        check(CL12.clSetKernelArg1p(kernel, 11, outputBuffer));
    }

    private static void putParameters(DoubleBuffer buffer, CustomWorldSettings settings) {
        buffer.put(settings.depthNoiseFactor());
        buffer.put(settings.depthNoiseOffset());
        buffer.put(settings.depthNoiseFrequencyX());
        buffer.put(settings.depthNoiseFrequencyZ());
        buffer.put(settings.depthNoiseOctaves());
        buffer.put(settings.selectorNoiseFactor());
        buffer.put(settings.selectorNoiseOffset());
        buffer.put(settings.selectorNoiseFrequencyX());
        buffer.put(settings.selectorNoiseFrequencyY());
        buffer.put(settings.selectorNoiseFrequencyZ());
        buffer.put(settings.selectorNoiseOctaves());
        buffer.put(settings.lowNoiseFactor());
        buffer.put(settings.lowNoiseOffset());
        buffer.put(settings.lowNoiseFrequencyX());
        buffer.put(settings.lowNoiseFrequencyY());
        buffer.put(settings.lowNoiseFrequencyZ());
        buffer.put(settings.lowNoiseOctaves());
        buffer.put(settings.highNoiseFactor());
        buffer.put(settings.highNoiseOffset());
        buffer.put(settings.highNoiseFrequencyX());
        buffer.put(settings.highNoiseFrequencyY());
        buffer.put(settings.highNoiseFrequencyZ());
        buffer.put(settings.highNoiseOctaves());
        buffer.put(settings.biomeSize());
        buffer.put(settings.riverSize());
        buffer.put(settings.heightVariationFactor());
        buffer.put(settings.specialHeightVariationFactorBelowAverageY());
        buffer.put(settings.heightVariationOffset());
        buffer.put(settings.heightFactor());
        buffer.put(settings.heightOffset());
    }

    private static OpenClTerrainAccelerator createFor(DeviceInfo info) {
        long context = 0L;
        long queue = 0L;
        long program = 0L;
        long kernel = 0L;
        long parametersBuffer = 0L;
        long outputBuffer = 0L;
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

            kernel = CL12.clCreateKernel(program, KERNEL_NAME, error);
            check(error);
            parametersBuffer = CL12.clCreateBuffer(
                    context, CL12.CL_MEM_READ_ONLY, (long) PARAMETER_COUNT * Double.BYTES, error);
            check(error);
            outputBuffer = CL12.clCreateBuffer(
                    context, CL12.CL_MEM_WRITE_ONLY, (long) MAX_SAMPLE_COUNT * Double.BYTES, error);
            check(error);

            return new OpenClTerrainAccelerator(
                    info.device(), context, queue, program, kernel,
                    parametersBuffer, outputBuffer, info.description());
        } catch (Throwable throwable) {
            releaseQuietly(outputBuffer, parametersBuffer, kernel, program, queue, context);
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
            long outputBuffer, long parametersBuffer, long kernel,
            long program, long queue, long context) {
        release(CL12::clReleaseMemObject, outputBuffer);
        release(CL12::clReleaseMemObject, parametersBuffer);
        release(CL12::clReleaseKernel, kernel);
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
            release(CL12::clReleaseMemObject, outputBuffer);
            release(CL12::clReleaseMemObject, parametersBuffer);
            release(CL12::clReleaseKernel, kernel);
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
