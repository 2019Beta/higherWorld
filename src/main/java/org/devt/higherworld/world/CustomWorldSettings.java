package org.devt.higherworld.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import net.minecraft.text.Text;

/** Settings selected by the custom world editor in the Create World screen. */
public final class CustomWorldSettings {
    private static final String FILE_NAME = "custom_world_settings.json";
    private static final int UNLIMITED_DEPTH = -1;
    private static final int DEFAULT_CUSTOM_DEPTH = 256;
    private static final List<Integer> DEPTH_OPTIONS = List.of(128, 256, 512, 1024, UNLIMITED_DEPTH);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static CustomWorldSettings clientSelection = new CustomWorldSettings(DEFAULT_CUSTOM_DEPTH);
    private static CustomWorldSettings pendingWorldSelection;

    private final int generationDepth;

    private CustomWorldSettings(int generationDepth) {
        if (generationDepth != UNLIMITED_DEPTH && !DEPTH_OPTIONS.contains(generationDepth)) {
            throw new IllegalArgumentException("Unsupported custom world generation depth: " + generationDepth);
        }
        this.generationDepth = generationDepth;
    }

    /** Defaults used by the existing Infinite Downward preset. */
    public static CustomWorldSettings defaults() {
        return new CustomWorldSettings(UNLIMITED_DEPTH);
    }

    /** Defaults used when a new Custom World is created without opening its editor. */
    public static CustomWorldSettings customDefaults() {
        return new CustomWorldSettings(DEFAULT_CUSTOM_DEPTH);
    }

    public int generationDepth() {
        return generationDepth;
    }

    public boolean isUnlimited() {
        return generationDepth == UNLIMITED_DEPTH;
    }

    public CustomWorldSettings withGenerationDepth(int depth) {
        return new CustomWorldSettings(depth);
    }

    public static List<Integer> depthOptions() {
        return DEPTH_OPTIONS;
    }

    public static Text depthText(int depth) {
        return depth == UNLIMITED_DEPTH
                ? Text.translatable("option.higherworld.generation_depth.unlimited")
                : Text.translatable("option.higherworld.generation_depth.blocks", depth);
    }

    public static synchronized CustomWorldSettings clientSelection() {
        return clientSelection;
    }

    public static synchronized void setClientSelection(CustomWorldSettings selection) {
        clientSelection = selection;
    }

    /** Called when the Create World button starts creating a level. */
    public static synchronized void markWorldCreationStarted() {
        pendingWorldSelection = clientSelection;
    }

    static CustomWorldSettings load(Path cubicRoot, boolean acceptPendingSelection) throws IOException {
        Path file = cubicRoot.resolve(FILE_NAME);
        if (Files.exists(file)) {
            return read(file);
        }

        CustomWorldSettings selected = customDefaults();
        synchronized (CustomWorldSettings.class) {
            if (acceptPendingSelection && pendingWorldSelection != null) {
                selected = pendingWorldSelection;
                pendingWorldSelection = null;
            }
        }
        if (acceptPendingSelection) {
            selected.write(file);
        }
        return selected;
    }

    private static CustomWorldSettings read(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!root.has("generation_depth")) {
                return customDefaults();
            }
            return new CustomWorldSettings(root.get("generation_depth").getAsInt());
        } catch (RuntimeException exception) {
            throw new IOException("Invalid HigherWorld custom settings in " + file, exception);
        }
    }

    private void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("generation_depth", generationDepth);

        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary)) {
            GSON.toJson(root, writer);
        }
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
