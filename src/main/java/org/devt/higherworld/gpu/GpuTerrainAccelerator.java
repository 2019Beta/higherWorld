package org.devt.higherworld.gpu;

import org.devt.higherworld.Higherworld;
import org.devt.higherworld.storage.CubePos;
import org.devt.higherworld.world.CustomWorldSettings;

import java.util.concurrent.atomic.AtomicBoolean;

/** Process-wide lifecycle for the optional OpenCL terrain sampler. */
public final class GpuTerrainAccelerator {
    /** Interpolation weights used when a host-provided density grid is rasterized. */
    public enum InterpolationMode {
        LINEAR,
        VANILLA_CELL_4
    }

    private static final Object LOCK = new Object();
    private static final AtomicBoolean INITIALIZATION_ATTEMPTED = new AtomicBoolean();
    private static final int VOXEL_COUNT = CubePos.SIZE * CubePos.SIZE * CubePos.SIZE;
    private static final int MAX_CUSTOM_BATCH = 16;
    private static final int MAX_DENSITY_BATCH = 16;

    private static volatile OpenClTerrainAccelerator accelerator;
    private static volatile boolean unavailable;
    private static volatile boolean stopping;

    private GpuTerrainAccelerator() {
    }

    /** Eagerly initializes OpenCL on server start when the setting is enabled. */
    public static void start() {
        synchronized (LOCK) {
            stopping = false;
        }
        if (!GpuAccelerationConfig.settings().enabled()) return;
        ensureInitialized();
    }

    /** Releases OpenCL resources at server shutdown. */
    public static void stop() {
        synchronized (LOCK) {
            stopping = true;
            OpenClTerrainAccelerator current = accelerator;
            accelerator = null;
            unavailable = false;
            INITIALIZATION_ATTEMPTED.set(false);
            if (current != null) current.close();
        }
    }

    /** Returns whether a GPU dispatch may currently be attempted. */
    public static boolean isEnabled() {
        return GpuAccelerationConfig.settings().enabled() && !stopping && !unavailable;
    }

    /**
     * Returns the backend currently responsible for HigherWorld terrain work.
     * The detail is deliberately short because it is also sent to the client
     * and rendered as one F3 line.
     */
    public static Status status() {
        OpenClTerrainAccelerator current = accelerator;
        if (current != null) {
            return new Status(true, shortDeviceName(current.deviceDescription()));
        }
        if (!GpuAccelerationConfig.settings().enabled()) {
            return new Status(false, "OpenCL disabled");
        }
        if (unavailable) {
            return new Status(false, "OpenCL unavailable");
        }
        if (stopping) {
            return new Status(false, "Server stopping");
        }
        return new Status(false, "OpenCL not initialized");
    }

    private static String shortDeviceName(String description) {
        int separator = description.indexOf(" [");
        return separator > 0 ? description.substring(0, separator) : description;
    }

    /**
     * Tries to fill the exact custom-world noise sample grid.  Returning false
     * asks the caller to use its existing CPU implementation.
     */
    public static boolean trySample(
            long seed, CubePos pos, CustomWorldSettings settings, double[] samples) {
        if (!GpuAccelerationConfig.settings().enabled() || stopping) return false;
        validateCustomSampleRequest(pos, settings, samples);
        OpenClTerrainAccelerator current = ensureInitialized();
        if (current == null) return false;
        try {
            current.sample(seed, pos, settings, samples);
            return true;
        } catch (Throwable throwable) {
            disableAfterFailure(throwable);
            if (!GpuAccelerationConfig.settings().fallbackOnError()) {
                throw new IllegalStateException("HigherWorld OpenCL terrain sampling failed", throwable);
            }
            return false;
        }
    }

    /**
     * Samples and rasterizes a custom terrain cube without copying the
     * intermediate density grid back to Java.
     */
    public static boolean trySampleAndRasterizeCustom(
            long seed, CubePos pos, CustomWorldSettings settings, boolean[] solid) {
        if (!GpuAccelerationConfig.settings().enabled() || stopping) return false;
        validateCustomSettings(pos, settings);
        validateSolidMask(solid);
        OpenClTerrainAccelerator current = ensureInitialized();
        if (current == null) return false;
        try {
            current.sampleCustomSolid(seed, pos, settings, solid);
            return true;
        } catch (Throwable throwable) {
            disableAfterFailure(throwable);
            if (!GpuAccelerationConfig.settings().fallbackOnError()) {
                throw new IllegalStateException("HigherWorld OpenCL terrain rasterization failed", throwable);
            }
            return false;
        }
    }

    /**
     * Dispatches several independent custom cubes as one OpenCL batch.  The
     * caller owns the output arrays; this method only fills them after the
     * device has completed the sample and raster stages.
     */
    public static boolean trySampleAndRasterizeCustomBatch(
            long seed, CubePos[] positions, CustomWorldSettings settings, boolean[][] solid) {
        if (!GpuAccelerationConfig.settings().enabled() || stopping) return false;
        validateCustomBatch(positions, settings, solid);
        OpenClTerrainAccelerator current = ensureInitialized();
        if (current == null) return false;
        try {
            current.sampleCustomSolidBatch(seed, positions, settings, solid);
            return true;
        } catch (Throwable throwable) {
            disableAfterFailure(throwable);
            if (!GpuAccelerationConfig.settings().fallbackOnError()) {
                throw new IllegalStateException(
                        "HigherWorld OpenCL custom terrain batch failed", throwable);
            }
            return false;
        }
    }

    /**
     * Rasterizes any host-sampled density grid.  This is the common bridge for
     * vanilla and modded generators whose density function cannot be serialized
     * into an OpenCL kernel safely.
     */
    public static boolean tryRasterizeDensity(
            double[] samples, int stepX, int stepY, int stepZ,
            int cellsX, int cellsY, int cellsZ,
            InterpolationMode interpolationMode, boolean[] solid) {
        if (!GpuAccelerationConfig.settings().enabled() || stopping) return false;
        validateRasterRequest(
                samples, stepX, stepY, stepZ, cellsX, cellsY, cellsZ,
                interpolationMode, solid);
        OpenClTerrainAccelerator current = ensureInitialized();
        if (current == null) return false;
        try {
            current.rasterize(samples, stepX, stepY, stepZ, cellsX, cellsY, cellsZ,
                    interpolationMode.ordinal(), solid);
            return true;
        } catch (Throwable throwable) {
            disableAfterFailure(throwable);
            if (!GpuAccelerationConfig.settings().fallbackOnError()) {
                throw new IllegalStateException("HigherWorld OpenCL density rasterization failed", throwable);
            }
            return false;
        }
    }

    /**
     * Rasterizes several host-sampled density grids in one OpenCL dispatch.
     * This keeps the vanilla deep path on the same batched device stage as
     * custom terrain while preserving a CPU fallback for unsupported graphs or
     * devices.
     */
    public static boolean tryRasterizeDensityBatch(
            double[][] samples, int stepX, int stepY, int stepZ,
            int cellsX, int cellsY, int cellsZ,
            InterpolationMode interpolationMode, boolean[][] solid) {
        if (!GpuAccelerationConfig.settings().enabled() || stopping) return false;
        validateRasterBatchRequest(
                samples, stepX, stepY, stepZ, cellsX, cellsY, cellsZ,
                interpolationMode, solid);
        OpenClTerrainAccelerator current = ensureInitialized();
        if (current == null) return false;
        try {
            current.rasterizeBatch(samples, stepX, stepY, stepZ, cellsX, cellsY, cellsZ,
                    interpolationMode.ordinal(), solid);
            return true;
        } catch (Throwable throwable) {
            disableAfterFailure(throwable);
            if (!GpuAccelerationConfig.settings().fallbackOnError()) {
                throw new IllegalStateException(
                        "HigherWorld OpenCL density batch rasterization failed", throwable);
            }
            return false;
        }
    }

    private static void validateCustomSampleRequest(
            CubePos pos, CustomWorldSettings settings, double[] samples) {
        validateCustomSettings(pos, settings);
        if (samples == null) throw new IllegalArgumentException("Terrain samples cannot be null");
        int sampleCount = customSampleCount(settings);
        if (samples.length != sampleCount) {
            throw new IllegalArgumentException("Unexpected terrain sample array length " + samples.length);
        }
    }

    private static void validateCustomSettings(CubePos pos, CustomWorldSettings settings) {
        if (pos == null) throw new IllegalArgumentException("Cube position cannot be null");
        if (settings == null) throw new IllegalArgumentException("Custom world settings cannot be null");
        validateStep(settings.noiseSampleSizeX(), "X");
        validateStep(settings.noiseSampleSizeY(), "Y");
        validateStep(settings.noiseSampleSizeZ(), "Z");
    }

    private static void validateCustomBatch(
            CubePos[] positions, CustomWorldSettings settings, boolean[][] solid) {
        if (positions == null || positions.length == 0 || positions.length > MAX_CUSTOM_BATCH) {
            throw new IllegalArgumentException(
                    "Custom terrain batch must contain 1-" + MAX_CUSTOM_BATCH + " cubes");
        }
        if (settings == null) throw new IllegalArgumentException("Custom world settings cannot be null");
        if (solid == null || solid.length != positions.length) {
            throw new IllegalArgumentException("Solid mask batch length does not match cube batch");
        }
        for (int index = 0; index < positions.length; index++) {
            validateCustomSettings(positions[index], settings);
            validateSolidMask(solid[index]);
        }
    }

    private static int customSampleCount(CustomWorldSettings settings) {
        int cellsX = CubePos.SIZE / settings.noiseSampleSizeX();
        int cellsY = CubePos.SIZE / settings.noiseSampleSizeY();
        int cellsZ = CubePos.SIZE / settings.noiseSampleSizeZ();
        return (cellsX + 1) * (cellsY + 1) * (cellsZ + 1);
    }

    private static void validateRasterRequest(
            double[] samples, int stepX, int stepY, int stepZ,
            int cellsX, int cellsY, int cellsZ,
            InterpolationMode interpolationMode, boolean[] solid) {
        if (samples == null) throw new IllegalArgumentException("Density samples cannot be null");
        validateSolidMask(solid);
        if (interpolationMode == null) {
            throw new IllegalArgumentException("Interpolation mode cannot be null");
        }
        validateStep(stepX, "X");
        validateStep(stepY, "Y");
        validateStep(stepZ, "Z");
        if (cellsX <= 0 || cellsY <= 0 || cellsZ <= 0
                || cellsX * stepX != CubePos.SIZE
                || cellsY * stepY != CubePos.SIZE
                || cellsZ * stepZ != CubePos.SIZE) {
            throw new IllegalArgumentException("Density grid must exactly cover one 16^3 cube");
        }
        int sampleCount = (cellsX + 1) * (cellsY + 1) * (cellsZ + 1);
        if (samples.length != sampleCount) {
            throw new IllegalArgumentException("Unexpected density grid length " + samples.length);
        }
    }

    private static void validateRasterBatchRequest(
            double[][] samples, int stepX, int stepY, int stepZ,
            int cellsX, int cellsY, int cellsZ,
            InterpolationMode interpolationMode, boolean[][] solid) {
        if (samples == null || samples.length == 0 || samples.length > MAX_DENSITY_BATCH) {
            throw new IllegalArgumentException(
                    "Density batch must contain 1-" + MAX_DENSITY_BATCH + " grids");
        }
        if (solid == null || solid.length != samples.length) {
            throw new IllegalArgumentException("Solid mask batch length does not match density batch");
        }
        for (int index = 0; index < samples.length; index++) {
            validateRasterRequest(samples[index], stepX, stepY, stepZ,
                    cellsX, cellsY, cellsZ, interpolationMode, solid[index]);
        }
    }

    private static void validateSolidMask(boolean[] solid) {
        if (solid == null) throw new IllegalArgumentException("Solid mask cannot be null");
        if (solid.length != VOXEL_COUNT) {
            throw new IllegalArgumentException("Unexpected solid mask length " + solid.length);
        }
    }

    private static void validateStep(int step, String axis) {
        if (step <= 0 || CubePos.SIZE % step != 0) {
            throw new IllegalArgumentException("Density step " + axis + " must divide cube size");
        }
    }

    private static OpenClTerrainAccelerator ensureInitialized() {
        OpenClTerrainAccelerator current = accelerator;
        if (current != null || unavailable || stopping) return current;
        synchronized (LOCK) {
            current = accelerator;
            if (current != null || unavailable || stopping) return current;
            if (!INITIALIZATION_ATTEMPTED.compareAndSet(false, true)) return accelerator;
            try {
                current = OpenClTerrainAccelerator.create(GpuAccelerationConfig.settings());
                accelerator = current;
                Higherworld.LOGGER.info("HigherWorld OpenCL terrain acceleration is ready on {}",
                        current.deviceDescription());
            } catch (Throwable throwable) {
                unavailable = true;
                if (GpuAccelerationConfig.settings().fallbackOnError()) {
                    Higherworld.LOGGER.warn(
                            "HigherWorld OpenCL acceleration is unavailable; falling back to CPU terrain",
                            throwable);
                    return null;
                }
                throw new IllegalStateException(
                        "HigherWorld OpenCL acceleration could not be initialized", throwable);
            }
            return current;
        }
    }

    private static void disableAfterFailure(Throwable throwable) {
        synchronized (LOCK) {
            OpenClTerrainAccelerator current = accelerator;
            accelerator = null;
            unavailable = true;
            if (current != null) current.close();
            Higherworld.LOGGER.warn(
                    "Disabling HigherWorld OpenCL acceleration after a device error; CPU fallback remains active",
                    throwable);
        }
    }

    public record Status(boolean openCl, String detail) {
    }
}
