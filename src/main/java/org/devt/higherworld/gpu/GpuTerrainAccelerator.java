package org.devt.higherworld.gpu;

import org.devt.higherworld.Higherworld;
import org.devt.higherworld.storage.CubePos;
import org.devt.higherworld.world.CustomWorldSettings;

import java.util.concurrent.atomic.AtomicBoolean;

/** Process-wide lifecycle for the optional OpenCL terrain sampler. */
public final class GpuTerrainAccelerator {
    private static final Object LOCK = new Object();
    private static final AtomicBoolean INITIALIZATION_ATTEMPTED = new AtomicBoolean();

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

    /**
     * Tries to fill the exact custom-world noise sample grid.  Returning false
     * asks the caller to use its existing CPU implementation.
     */
    public static boolean trySample(
            long seed, CubePos pos, CustomWorldSettings settings, double[] samples) {
        if (!GpuAccelerationConfig.settings().enabled() || stopping) return false;
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
}
