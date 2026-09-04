package org.devt.higherworld.gpu;

import net.fabricmc.loader.api.FabricLoader;
import org.devt.higherworld.Higherworld;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Small dependency-free configuration for the optional OpenCL terrain path.
 * The file is deliberately plain properties so a server owner can edit it
 * without installing a configuration library.
 */
public final class GpuAccelerationConfig {
    private static final String FILE_NAME = "higherworld.properties";
    private static final String ENABLED = "higherworld.gpu.enabled";
    private static final String ALLOW_CPU_DEVICES = "higherworld.gpu.allow_cpu_devices";
    private static final String DEVICE_INDEX = "higherworld.gpu.device_index";
    private static final String FALLBACK_ON_ERROR = "higherworld.gpu.fallback_on_error";

    private static volatile Settings settings = Settings.defaults();

    private GpuAccelerationConfig() {
    }

    /** Loads (and, on first run, creates) the global HigherWorld config. */
    public static void initialize() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        Properties properties = new Properties();
        boolean created = false;
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
            } else {
                created = true;
            }
        } catch (IOException | RuntimeException exception) {
            Higherworld.LOGGER.warn("Cannot read HigherWorld config {}; using defaults", file,
                    exception);
        }

        Settings loaded = Settings.from(properties);
        settings = loaded;
        if (created) {
            try {
                writeDefaults(file);
            } catch (IOException exception) {
                Higherworld.LOGGER.warn("Cannot create HigherWorld config {}", file, exception);
            }
        }
        Higherworld.LOGGER.info(
                "HigherWorld GPU terrain acceleration: {}",
                loaded.enabled() ? "enabled (OpenCL initializes when the server starts)" : "disabled");
    }

    public static Settings settings() {
        return settings;
    }

    private static void writeDefaults(Path file) throws IOException {
        Properties defaults = new Properties();
        defaults.setProperty(ENABLED, Boolean.toString(Settings.defaults().enabled()));
        defaults.setProperty(ALLOW_CPU_DEVICES,
                Boolean.toString(Settings.defaults().allowCpuDevices()));
        defaults.setProperty(DEVICE_INDEX, Integer.toString(Settings.defaults().deviceIndex()));
        defaults.setProperty(FALLBACK_ON_ERROR,
                Boolean.toString(Settings.defaults().fallbackOnError()));

        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
            defaults.store(writer, "HigherWorld optional OpenCL terrain acceleration");
        }
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record Settings(
            boolean enabled,
            boolean allowCpuDevices,
            int deviceIndex,
            boolean fallbackOnError) {
        private static Settings defaults() {
            return new Settings(false, false, -1, true);
        }

        private static Settings from(Properties properties) {
            boolean enabled = readBoolean(properties, ENABLED, false);
            boolean allowCpuDevices = readBoolean(properties, ALLOW_CPU_DEVICES, false);
            int deviceIndex = readInt(properties, DEVICE_INDEX, -1, -1, 256);
            boolean fallbackOnError = readBoolean(properties, FALLBACK_ON_ERROR, true);
            return new Settings(enabled, allowCpuDevices, deviceIndex, fallbackOnError);
        }

        private static boolean readBoolean(Properties properties, String key, boolean fallback) {
            String value = overrideOr(properties, key);
            if (value == null || value.isBlank()) return fallback;
            if ("true".equalsIgnoreCase(value.trim())) return true;
            if ("false".equalsIgnoreCase(value.trim())) return false;
            Higherworld.LOGGER.warn("Invalid boolean '{}' for {}; using {}", value, key, fallback);
            return fallback;
        }

        private static int readInt(
                Properties properties, String key, int fallback, int minimum, int maximum) {
            String value = overrideOr(properties, key);
            if (value == null || value.isBlank()) return fallback;
            try {
                int parsed = Integer.parseInt(value.trim());
                if (parsed < minimum || parsed > maximum) throw new NumberFormatException();
                return parsed;
            } catch (NumberFormatException exception) {
                Higherworld.LOGGER.warn(
                        "Invalid integer '{}' for {}; using {}", value, key, fallback);
                return fallback;
            }
        }

        private static String overrideOr(Properties properties, String key) {
            String override = System.getProperty(key);
            return override == null ? properties.getProperty(key) : override;
        }
    }
}
