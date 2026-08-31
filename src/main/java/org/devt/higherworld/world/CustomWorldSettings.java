package org.devt.higherworld.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.annotations.SerializedName;
import com.mojang.serialization.Codec;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Immutable custom-generator preset shared by the Create World screen and the
 * sparse server-side generator.
 *
 * <p>The field names intentionally follow CubicWorldGen 1.12.2.  A preset is
 * therefore useful as a migration artifact as well as a modern config.  The
 * implementation does not resolve blocks or biomes while loading: that is
 * deliberately deferred to the generator's active registries, so modded
 * blocks can be used in a preset without making the client depend on them.</p>
 */
public final class CustomWorldSettings {
    private static final String FILE_NAME = "custom_world_settings.json";
    private static final int UNLIMITED_DEPTH = -1;
    private static final int DEFAULT_CUSTOM_DEPTH = 256;
    private static final int CURRENT_VERSION = 2;
    private static final List<Integer> DEPTH_OPTIONS = List.of(128, 256, 512, 1024, UNLIMITED_DEPTH);
    private static final Set<Integer> VALID_SAMPLE_SIZES = Set.of(1, 2, 4, 8, 16);

    /* The values below are ConversionUtils' 1.12 constants written out. */
    private static final double VANILLA_DEPTH_NOISE_FACTOR = 1024.0 / 125.0 / 8.0;
    private static final double VANILLA_SELECTOR_NOISE_FACTOR = 12.75;
    private static final double VANILLA_SELECTOR_NOISE_OFFSET = 0.5;
    private static final double VANILLA_DEPTH_NOISE_FREQUENCY = 200.0 / 131072.0;
    private static final double VANILLA_SELECTOR_NOISE_FREQUENCY_XZ = 684.412 / 40960.0;
    private static final double VANILLA_SELECTOR_NOISE_FREQUENCY_Y = 684.412 / 81920.0;
    private static final double VANILLA_LOWHIGH_NOISE_FREQUENCY_XZ = 684.412 / 131072.0;
    private static final double VANILLA_LOWHIGH_NOISE_FREQUENCY_Y = 684.412 / 262144.0;

    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .serializeSpecialFloatingPointValues()
            .setPrettyPrinting()
            .create();

    /** Codec bridge for modern worldgen/preset APIs. It carries the same JSON. */
    public static final Codec<CustomWorldSettings> CODEC = Codec.STRING.xmap(
            CustomWorldSettings::fromJson,
            CustomWorldSettings::toJson);

    private static CustomWorldSettings clientSelection = customDefaults();
    private static CustomWorldSettings pendingWorldSelection;

    private final int generationDepth;
    private final boolean strongholds;
    private final boolean alternateStrongholdsPositions;
    private final boolean villages;
    private final boolean mineshafts;
    private final boolean temples;
    private final boolean oceanMonuments;
    private final boolean woodlandMansions;
    private final boolean ravines;
    private final boolean dungeons;
    private final int dungeonCount;
    private final String biome;
    private final int biomeSize;
    private final int riverSize;

    private final double expectedBaseHeight;
    private final double expectedHeightVariation;
    private final double actualHeight;
    private final double heightVariationFactor;
    private final double specialHeightVariationFactorBelowAverageY;
    private final double heightVariationOffset;
    private final double heightFactor;
    private final double heightOffset;
    private final double depthNoiseFactor;
    private final double depthNoiseOffset;
    private final double depthNoiseFrequencyX;
    private final double depthNoiseFrequencyZ;
    private final int depthNoiseOctaves;
    private final double selectorNoiseFactor;
    private final double selectorNoiseOffset;
    private final double selectorNoiseFrequencyX;
    private final double selectorNoiseFrequencyY;
    private final double selectorNoiseFrequencyZ;
    private final int selectorNoiseOctaves;
    private final double lowNoiseFactor;
    private final double lowNoiseOffset;
    private final double lowNoiseFrequencyX;
    private final double lowNoiseFrequencyY;
    private final double lowNoiseFrequencyZ;
    private final int lowNoiseOctaves;
    private final double highNoiseFactor;
    private final double highNoiseOffset;
    private final double highNoiseFrequencyX;
    private final double highNoiseFrequencyY;
    private final double highNoiseFrequencyZ;
    private final int highNoiseOctaves;
    private final int noiseSampleSizeX;
    private final int noiseSampleSizeY;
    private final int noiseSampleSizeZ;

    private final List<CaveSettings> caves;
    private final List<LakeSettings> lakes;
    private final List<OreSettings> standardOres;
    private final List<OreSettings> periodicGaussianOres;

    private CustomWorldSettings(Builder builder) {
        this.generationDepth = builder.generationDepth;
        this.strongholds = builder.strongholds;
        this.alternateStrongholdsPositions = builder.alternateStrongholdsPositions;
        this.villages = builder.villages;
        this.mineshafts = builder.mineshafts;
        this.temples = builder.temples;
        this.oceanMonuments = builder.oceanMonuments;
        this.woodlandMansions = builder.woodlandMansions;
        this.ravines = builder.ravines;
        this.dungeons = builder.dungeons;
        this.dungeonCount = builder.dungeonCount;
        this.biome = builder.biome;
        this.biomeSize = builder.biomeSize;
        this.riverSize = builder.riverSize;
        this.expectedBaseHeight = builder.expectedBaseHeight;
        this.expectedHeightVariation = builder.expectedHeightVariation;
        this.actualHeight = builder.actualHeight;
        this.heightVariationFactor = builder.heightVariationFactor;
        this.specialHeightVariationFactorBelowAverageY = builder.specialHeightVariationFactorBelowAverageY;
        this.heightVariationOffset = builder.heightVariationOffset;
        this.heightFactor = builder.heightFactor;
        this.heightOffset = builder.heightOffset;
        this.depthNoiseFactor = builder.depthNoiseFactor;
        this.depthNoiseOffset = builder.depthNoiseOffset;
        this.depthNoiseFrequencyX = builder.depthNoiseFrequencyX;
        this.depthNoiseFrequencyZ = builder.depthNoiseFrequencyZ;
        this.depthNoiseOctaves = builder.depthNoiseOctaves;
        this.selectorNoiseFactor = builder.selectorNoiseFactor;
        this.selectorNoiseOffset = builder.selectorNoiseOffset;
        this.selectorNoiseFrequencyX = builder.selectorNoiseFrequencyX;
        this.selectorNoiseFrequencyY = builder.selectorNoiseFrequencyY;
        this.selectorNoiseFrequencyZ = builder.selectorNoiseFrequencyZ;
        this.selectorNoiseOctaves = builder.selectorNoiseOctaves;
        this.lowNoiseFactor = builder.lowNoiseFactor;
        this.lowNoiseOffset = builder.lowNoiseOffset;
        this.lowNoiseFrequencyX = builder.lowNoiseFrequencyX;
        this.lowNoiseFrequencyY = builder.lowNoiseFrequencyY;
        this.lowNoiseFrequencyZ = builder.lowNoiseFrequencyZ;
        this.lowNoiseOctaves = builder.lowNoiseOctaves;
        this.highNoiseFactor = builder.highNoiseFactor;
        this.highNoiseOffset = builder.highNoiseOffset;
        this.highNoiseFrequencyX = builder.highNoiseFrequencyX;
        this.highNoiseFrequencyY = builder.highNoiseFrequencyY;
        this.highNoiseFrequencyZ = builder.highNoiseFrequencyZ;
        this.highNoiseOctaves = builder.highNoiseOctaves;
        this.noiseSampleSizeX = builder.noiseSampleSizeX;
        this.noiseSampleSizeY = builder.noiseSampleSizeY;
        this.noiseSampleSizeZ = builder.noiseSampleSizeZ;
        this.caves = List.copyOf(builder.caves == null ? List.of() : builder.caves);
        this.lakes = List.copyOf(builder.lakes == null ? List.of() : builder.lakes);
        this.standardOres = List.copyOf(builder.standardOres == null ? List.of() : builder.standardOres);
        this.periodicGaussianOres = List.copyOf(builder.periodicGaussianOres == null ? List.of() : builder.periodicGaussianOres);
        validate();
    }

    /** Defaults used by the Infinite Downward preset. */
    public static CustomWorldSettings defaults() {
        return defaultBuilder(UNLIMITED_DEPTH).build();
    }

    /** Defaults used by a newly selected Custom World. */
    public static CustomWorldSettings customDefaults() {
        return defaultBuilder(DEFAULT_CUSTOM_DEPTH).build();
    }

    public int generationDepth() { return generationDepth; }
    public boolean isUnlimited() { return generationDepth == UNLIMITED_DEPTH; }
    public boolean strongholds() { return strongholds; }
    public boolean alternateStrongholdsPositions() { return alternateStrongholdsPositions; }
    public boolean villages() { return villages; }
    public boolean mineshafts() { return mineshafts; }
    public boolean temples() { return temples; }
    public boolean oceanMonuments() { return oceanMonuments; }
    public boolean woodlandMansions() { return woodlandMansions; }
    public boolean ravines() { return ravines; }
    public boolean dungeons() { return dungeons; }
    public int dungeonCount() { return dungeonCount; }
    public String biome() { return biome; }
    public int biomeSize() { return biomeSize; }
    public int riverSize() { return riverSize; }
    public double expectedBaseHeight() { return expectedBaseHeight; }
    public double expectedHeightVariation() { return expectedHeightVariation; }
    public double actualHeight() { return actualHeight; }
    public double heightVariationFactor() { return heightVariationFactor; }
    public double specialHeightVariationFactorBelowAverageY() { return specialHeightVariationFactorBelowAverageY; }
    public double heightVariationOffset() { return heightVariationOffset; }
    public double heightFactor() { return heightFactor; }
    public double heightOffset() { return heightOffset; }
    public double depthNoiseFactor() { return depthNoiseFactor; }
    public double depthNoiseOffset() { return depthNoiseOffset; }
    public double depthNoiseFrequencyX() { return depthNoiseFrequencyX; }
    public double depthNoiseFrequencyZ() { return depthNoiseFrequencyZ; }
    public int depthNoiseOctaves() { return depthNoiseOctaves; }
    public double selectorNoiseFactor() { return selectorNoiseFactor; }
    public double selectorNoiseOffset() { return selectorNoiseOffset; }
    public double selectorNoiseFrequencyX() { return selectorNoiseFrequencyX; }
    public double selectorNoiseFrequencyY() { return selectorNoiseFrequencyY; }
    public double selectorNoiseFrequencyZ() { return selectorNoiseFrequencyZ; }
    public int selectorNoiseOctaves() { return selectorNoiseOctaves; }
    public double lowNoiseFactor() { return lowNoiseFactor; }
    public double lowNoiseOffset() { return lowNoiseOffset; }
    public double lowNoiseFrequencyX() { return lowNoiseFrequencyX; }
    public double lowNoiseFrequencyY() { return lowNoiseFrequencyY; }
    public double lowNoiseFrequencyZ() { return lowNoiseFrequencyZ; }
    public int lowNoiseOctaves() { return lowNoiseOctaves; }
    public double highNoiseFactor() { return highNoiseFactor; }
    public double highNoiseOffset() { return highNoiseOffset; }
    public double highNoiseFrequencyX() { return highNoiseFrequencyX; }
    public double highNoiseFrequencyY() { return highNoiseFrequencyY; }
    public double highNoiseFrequencyZ() { return highNoiseFrequencyZ; }
    public int highNoiseOctaves() { return highNoiseOctaves; }
    public int noiseSampleSizeX() { return noiseSampleSizeX; }
    public int noiseSampleSizeY() { return noiseSampleSizeY; }
    public int noiseSampleSizeZ() { return noiseSampleSizeZ; }
    public List<CaveSettings> caves() { return caves; }
    public List<LakeSettings> lakes() { return lakes; }
    public List<OreSettings> standardOres() { return standardOres; }
    public List<OreSettings> periodicGaussianOres() { return periodicGaussianOres; }

    public CustomWorldSettings withGenerationDepth(int depth) {
        return toBuilder().generationDepth(depth).build();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder(customDefaults());
    }

    public static List<Integer> depthOptions() { return DEPTH_OPTIONS; }

    public static Text depthText(int depth) {
        return depth == UNLIMITED_DEPTH
                ? Text.translatable("option.higherworld.generation_depth.unlimited")
                : Text.translatable("option.higherworld.generation_depth.blocks", depth);
    }

    /** The object itself is immutable; returning it is safe and avoids a lossy copy. */
    public static synchronized CustomWorldSettings clientSelection() { return clientSelection; }

    public static synchronized void setClientSelection(CustomWorldSettings selection) {
        clientSelection = Objects.requireNonNull(selection).copy();
    }

    /** Called when the Create World button starts creating a level. */
    public static synchronized void markWorldCreationStarted() {
        pendingWorldSelection = clientSelection.copy();
    }

    /** Used by the server after a custom world has been opened. */
    static CustomWorldSettings load(Path cubicRoot, boolean acceptPendingSelection) throws IOException {
        Path file = cubicRoot.resolve(FILE_NAME);
        if (Files.exists(file)) return read(file);

        CustomWorldSettings selected = customDefaults();
        synchronized (CustomWorldSettings.class) {
            if (acceptPendingSelection && pendingWorldSelection != null) {
                selected = pendingWorldSelection;
                pendingWorldSelection = null;
            }
        }
        if (acceptPendingSelection) selected.write(file);
        return selected;
    }

    private static CustomWorldSettings read(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return fromJson(JsonParser.parseReader(reader).toString());
        } catch (RuntimeException exception) {
            throw new IOException("Invalid HigherWorld custom settings in " + file, exception);
        }
    }

    private void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary)) {
            GSON.toJson(toDto(), writer);
        }
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Pretty JSON representation used by preset import/export. */
    public String toJson() {
        return GSON.toJson(toDto());
    }

    /** Alias retained for callers that used the previous object-oriented API. */
    public JsonObject toJsonObject() {
        return JsonParser.parseString(toJson()).getAsJsonObject();
    }

    /** Reads version 1 (generation_depth only) and version 2 camelCase presets. */
    public static CustomWorldSettings fromJson(String json) {
        if (json == null || json.isBlank()) return customDefaults();
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("Custom world settings must be a JSON object");
        JsonObject root = parsed.getAsJsonObject();
        normalizeLegacy(root);
        SettingsDto dto = GSON.fromJson(root, SettingsDto.class);
        dto.biomePresent = root.has("biome");
        Builder builder = defaultBuilder(DEFAULT_CUSTOM_DEPTH);
        if (dto.generationDepth != null) builder.generationDepth(dto.generationDepth);
        if (dto.strongholds != null) builder.strongholds(dto.strongholds);
        if (dto.alternateStrongholdsPositions != null) builder.alternateStrongholdsPositions(dto.alternateStrongholdsPositions);
        if (dto.villages != null) builder.villages(dto.villages);
        if (dto.mineshafts != null) builder.mineshafts(dto.mineshafts);
        if (dto.temples != null) builder.temples(dto.temples);
        if (dto.oceanMonuments != null) builder.oceanMonuments(dto.oceanMonuments);
        if (dto.woodlandMansions != null) builder.woodlandMansions(dto.woodlandMansions);
        if (dto.ravines != null) builder.ravines(dto.ravines);
        if (dto.dungeons != null) builder.dungeons(dto.dungeons);
        if (dto.dungeonCount != null) builder.dungeonCount(dto.dungeonCount);
        if (dto.biomePresent) builder.biome(dto.biome);
        if (dto.biomeSize != null) builder.biomeSize(dto.biomeSize);
        if (dto.riverSize != null) builder.riverSize(dto.riverSize);

        if (dto.expectedBaseHeight != null) builder.expectedBaseHeight(dto.expectedBaseHeight);
        if (dto.expectedHeightVariation != null) builder.expectedHeightVariation(dto.expectedHeightVariation);
        if (dto.actualHeight != null) builder.actualHeight(dto.actualHeight);
        if (dto.heightVariationFactor != null) builder.heightVariationFactor(dto.heightVariationFactor);
        if (dto.specialHeightVariationFactorBelowAverageY != null) builder.specialHeightVariationFactorBelowAverageY(dto.specialHeightVariationFactorBelowAverageY);
        if (dto.heightVariationOffset != null) builder.heightVariationOffset(dto.heightVariationOffset);
        if (dto.heightFactor != null) builder.heightFactor(dto.heightFactor);
        if (dto.heightOffset != null) builder.heightOffset(dto.heightOffset);
        if (dto.depthNoiseFactor != null) builder.depthNoiseFactor(dto.depthNoiseFactor);
        if (dto.depthNoiseOffset != null) builder.depthNoiseOffset(dto.depthNoiseOffset);
        if (dto.depthNoiseFrequencyX != null) builder.depthNoiseFrequencyX(dto.depthNoiseFrequencyX);
        if (dto.depthNoiseFrequencyZ != null) builder.depthNoiseFrequencyZ(dto.depthNoiseFrequencyZ);
        if (dto.depthNoiseOctaves != null) builder.depthNoiseOctaves(dto.depthNoiseOctaves);
        if (dto.selectorNoiseFactor != null) builder.selectorNoiseFactor(dto.selectorNoiseFactor);
        if (dto.selectorNoiseOffset != null) builder.selectorNoiseOffset(dto.selectorNoiseOffset);
        if (dto.selectorNoiseFrequencyX != null) builder.selectorNoiseFrequencyX(dto.selectorNoiseFrequencyX);
        if (dto.selectorNoiseFrequencyY != null) builder.selectorNoiseFrequencyY(dto.selectorNoiseFrequencyY);
        if (dto.selectorNoiseFrequencyZ != null) builder.selectorNoiseFrequencyZ(dto.selectorNoiseFrequencyZ);
        if (dto.selectorNoiseOctaves != null) builder.selectorNoiseOctaves(dto.selectorNoiseOctaves);
        if (dto.lowNoiseFactor != null) builder.lowNoiseFactor(dto.lowNoiseFactor);
        if (dto.lowNoiseOffset != null) builder.lowNoiseOffset(dto.lowNoiseOffset);
        if (dto.lowNoiseFrequencyX != null) builder.lowNoiseFrequencyX(dto.lowNoiseFrequencyX);
        if (dto.lowNoiseFrequencyY != null) builder.lowNoiseFrequencyY(dto.lowNoiseFrequencyY);
        if (dto.lowNoiseFrequencyZ != null) builder.lowNoiseFrequencyZ(dto.lowNoiseFrequencyZ);
        if (dto.lowNoiseOctaves != null) builder.lowNoiseOctaves(dto.lowNoiseOctaves);
        if (dto.highNoiseFactor != null) builder.highNoiseFactor(dto.highNoiseFactor);
        if (dto.highNoiseOffset != null) builder.highNoiseOffset(dto.highNoiseOffset);
        if (dto.highNoiseFrequencyX != null) builder.highNoiseFrequencyX(dto.highNoiseFrequencyX);
        if (dto.highNoiseFrequencyY != null) builder.highNoiseFrequencyY(dto.highNoiseFrequencyY);
        if (dto.highNoiseFrequencyZ != null) builder.highNoiseFrequencyZ(dto.highNoiseFrequencyZ);
        if (dto.highNoiseOctaves != null) builder.highNoiseOctaves(dto.highNoiseOctaves);
        if (dto.noiseSampleSizeX != null) builder.noiseSampleSizeX(dto.noiseSampleSizeX);
        if (dto.noiseSampleSizeY != null) builder.noiseSampleSizeY(dto.noiseSampleSizeY);
        if (dto.noiseSampleSizeZ != null) builder.noiseSampleSizeZ(dto.noiseSampleSizeZ);
        if (dto.caves != null) builder.caves(parseCaves(dto.caves));
        if (dto.lakes != null) builder.lakes(parseLakes(dto.lakes));
        if (dto.standardOres != null) builder.standardOres(parseOres(dto.standardOres, false));
        if (dto.periodicGaussianOres != null) builder.periodicGaussianOres(parseOres(dto.periodicGaussianOres, true));
        return builder.build();
    }

    /** Compatibility helper for the old preset editor. */
    public static JsonObject asJsonObject(String json) { return fromJson(json).toJsonObject(); }

    /** Validates this immutable instance and returns it for fluent callers. */
    public CustomWorldSettings validate() {
        if (generationDepth == 0 || generationDepth < UNLIMITED_DEPTH) fail("generationDepth must be -1 or positive");
        if (!VALID_SAMPLE_SIZES.contains(noiseSampleSizeX)
                || !VALID_SAMPLE_SIZES.contains(noiseSampleSizeY)
                || !VALID_SAMPLE_SIZES.contains(noiseSampleSizeZ)) {
            fail("noiseSampleSizeX/Y/Z must be one of 1, 2, 4, 8, 16");
        }
        if (dungeonCount < 0 || biomeSize < 0 || riverSize < 0) fail("counts and sizes cannot be negative");
        validateDoubles(expectedBaseHeight, expectedHeightVariation, actualHeight, heightVariationFactor,
                specialHeightVariationFactorBelowAverageY, heightVariationOffset, heightFactor, heightOffset,
                depthNoiseFactor, depthNoiseOffset, depthNoiseFrequencyX, depthNoiseFrequencyZ,
                selectorNoiseFactor, selectorNoiseOffset, selectorNoiseFrequencyX, selectorNoiseFrequencyY,
                selectorNoiseFrequencyZ, lowNoiseFactor, lowNoiseOffset, lowNoiseFrequencyX, lowNoiseFrequencyY,
                lowNoiseFrequencyZ, highNoiseFactor, highNoiseOffset, highNoiseFrequencyX, highNoiseFrequencyY,
                highNoiseFrequencyZ);
        if (biome != null) validateIdentifier(biome, "biome");
        for (CaveSettings cave : caves) cave.validate();
        for (LakeSettings lake : lakes) lake.validate();
        for (OreSettings ore : standardOres) ore.validate(false);
        for (OreSettings ore : periodicGaussianOres) ore.validate(true);
        return this;
    }

    public CustomWorldSettings copy() { return fromJson(toJson()); }

    private static void fail(String message) { throw new IllegalArgumentException(message); }

    private static void validateDoubles(double... values) {
        for (double value : values) if (!Double.isFinite(value)) fail("NaN and Infinity are not valid custom settings");
    }

    private static void validateIdentifier(String value, String field) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isWhitespace)) fail(field + " must be an Identifier");
        String id = value;
        int bracket = value.indexOf('[');
        if (bracket >= 0) {
            if (!value.endsWith("]") || bracket == 0) fail(field + " has an invalid block state");
            id = value.substring(0, bracket);
            String properties = value.substring(bracket + 1, value.length() - 1);
            if (properties.isBlank()) fail(field + " has an invalid block state");
            for (String property : properties.split(",")) {
                if (!property.matches("[A-Za-z0-9_.-]+=[A-Za-z0-9_.-]+")) fail(field + " has an invalid block state");
            }
        }
        try {
            Identifier.of(id.contains(":") ? id : "minecraft:" + id);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(field + " must be an Identifier: " + value, exception);
        }
    }

    private static void validateStringList(List<String> values, String field) {
        if (values == null) return;
        for (String value : values) validateIdentifier(value, field);
    }

    private static Builder defaultBuilder(int depth) {
        Builder builder = new Builder();
        builder.generationDepth = depth;
        builder.depthNoiseFactor = VANILLA_DEPTH_NOISE_FACTOR;
        builder.depthNoiseOffset = 0.0;
        builder.depthNoiseFrequencyX = VANILLA_DEPTH_NOISE_FREQUENCY;
        builder.depthNoiseFrequencyZ = VANILLA_DEPTH_NOISE_FREQUENCY;
        builder.depthNoiseOctaves = 16;
        builder.selectorNoiseFactor = VANILLA_SELECTOR_NOISE_FACTOR;
        builder.selectorNoiseOffset = VANILLA_SELECTOR_NOISE_OFFSET;
        builder.selectorNoiseFrequencyX = VANILLA_SELECTOR_NOISE_FREQUENCY_XZ;
        builder.selectorNoiseFrequencyY = VANILLA_SELECTOR_NOISE_FREQUENCY_Y;
        builder.selectorNoiseFrequencyZ = VANILLA_SELECTOR_NOISE_FREQUENCY_XZ;
        builder.selectorNoiseOctaves = 8;
        builder.lowNoiseFrequencyX = VANILLA_LOWHIGH_NOISE_FREQUENCY_XZ;
        builder.lowNoiseFrequencyY = VANILLA_LOWHIGH_NOISE_FREQUENCY_Y;
        builder.lowNoiseFrequencyZ = VANILLA_LOWHIGH_NOISE_FREQUENCY_XZ;
        builder.lowNoiseOctaves = 16;
        builder.highNoiseFrequencyX = VANILLA_LOWHIGH_NOISE_FREQUENCY_XZ;
        builder.highNoiseFrequencyY = VANILLA_LOWHIGH_NOISE_FREQUENCY_Y;
        builder.highNoiseFrequencyZ = VANILLA_LOWHIGH_NOISE_FREQUENCY_XZ;
        builder.highNoiseOctaves = 16;
        builder.caves = List.of(CaveSettings.standardDefault());
        builder.lakes = List.of(LakeSettings.standardLava(), LakeSettings.standardWater());
        builder.standardOres = standardDefaults();
        builder.periodicGaussianOres = List.of(ore("minecraft:lapis_ore", null, 7, 1, .933307775,
                Double.NEGATIVE_INFINITY, -.5, -.75, .11231704455, 3.0));
        return builder;
    }

    private static List<OreSettings> standardDefaults() {
        List<OreSettings> ores = new ArrayList<>();
        ores.add(ore("minecraft:dirt", null, 33, 10, 1.0 / (256.0 / 16.0), Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0, 0, 1));
        ores.add(ore("minecraft:gravel", null, 33, 8, 1.0 / (256.0 / 16.0), Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0, 0, 1));
        ores.add(ore("minecraft:granite", null, 33, 10, 256.0 / 80.0 / (256.0 / 16.0), Double.NEGATIVE_INFINITY, (80 - 64) / 64.0, 0, 0, 1));
        ores.add(ore("minecraft:diorite", null, 33, 10, 256.0 / 80.0 / (256.0 / 16.0), Double.NEGATIVE_INFINITY, (80 - 64) / 64.0, 0, 0, 1));
        ores.add(ore("minecraft:andesite", null, 33, 10, 256.0 / 80.0 / (256.0 / 16.0), Double.NEGATIVE_INFINITY, (80 - 64) / 64.0, 0, 0, 1));
        ores.add(ore("minecraft:coal_ore", null, 17, 20, 256.0 / 128.0 / (256.0 / 16.0), Double.NEGATIVE_INFINITY, 1, 0, 0, 1));
        ores.add(ore("minecraft:iron_ore", null, 9, 20, 256.0 / 64.0 / (256.0 / 16.0), Double.NEGATIVE_INFINITY, 0, 0, 0, 1));
        ores.add(ore("minecraft:gold_ore", null, 9, 2, 256.0 / 32.0 / (256.0 / 16.0), Double.NEGATIVE_INFINITY, -.5, 0, 0, 1));
        ores.add(ore("minecraft:redstone_ore", null, 8, 8, 256.0 / 16.0 / (256.0 / 16.0), Double.NEGATIVE_INFINITY, -.75, 0, 0, 1));
        ores.add(ore("minecraft:diamond_ore", null, 8, 1, 256.0 / 16.0 / (256.0 / 16.0), Double.NEGATIVE_INFINITY, -.75, 0, 0, 1));
        List<String> mountains = List.of("minecraft:windswept_hills", "minecraft:windswept_gravelly_hills", "minecraft:windswept_forest");
        ores.add(ore("minecraft:emerald_ore", mountains, 1, 11, .5 * 256.0 / 28.0 / (256.0 / 16.0), Double.NEGATIVE_INFINITY, 0, 0, 0, 1));
        ores.add(ore("minecraft:infested_stone", mountains, 7, 7, 256.0 / 64.0 / (256.0 / 16.0), Double.NEGATIVE_INFINITY, -.5, 0, 0, 1));
        ores.add(ore("minecraft:gold_ore", List.of("minecraft:badlands", "minecraft:eroded_badlands", "minecraft:wooded_badlands"),
                20, 2, 256.0 / 32.0 / (256.0 / 16.0), -.5, .25, 0, 0, 1));
        return List.copyOf(ores);
    }

    private static OreSettings ore(String blockstate, List<String> biomes, int size, int tries, double probability,
                                    double min, double max, double mean, double std, double spacing) {
        return new OreSettings(blockstate, biomes, size, tries, probability, min, max, mean, std, spacing);
    }

    private static UserFunction curve(double... values) {
        List<UserFunctionPoint> points = new ArrayList<>();
        for (int i = 0; i < values.length; i += 2) points.add(new UserFunctionPoint(values[i], values[i + 1]));
        return new UserFunction(points);
    }

    private static final class SettingsDto {
        @SerializedName(value = "generationDepth", alternate = {"generation_depth"}) Integer generationDepth;
        Integer version;
        Boolean strongholds, alternateStrongholdsPositions, villages, mineshafts, temples, oceanMonuments, woodlandMansions, ravines, dungeons;
        Integer dungeonCount;
        String biome;
        Integer biomeSize, riverSize;
        Double expectedBaseHeight, expectedHeightVariation, actualHeight, heightVariationFactor,
                specialHeightVariationFactorBelowAverageY, heightVariationOffset, heightFactor, heightOffset;
        Double depthNoiseFactor, depthNoiseOffset, depthNoiseFrequencyX, depthNoiseFrequencyZ;
        Integer depthNoiseOctaves;
        Double selectorNoiseFactor, selectorNoiseOffset, selectorNoiseFrequencyX, selectorNoiseFrequencyY, selectorNoiseFrequencyZ;
        Integer selectorNoiseOctaves;
        Double lowNoiseFactor, lowNoiseOffset, lowNoiseFrequencyX, lowNoiseFrequencyY, lowNoiseFrequencyZ;
        Integer lowNoiseOctaves;
        Double highNoiseFactor, highNoiseOffset, highNoiseFrequencyX, highNoiseFrequencyY, highNoiseFrequencyZ;
        Integer highNoiseOctaves, noiseSampleSizeX, noiseSampleSizeY, noiseSampleSizeZ;
        List<CaveDto> caves;
        List<LakeDto> lakes;
        List<OreDto> standardOres, periodicGaussianOres;
        transient boolean biomePresent;
    }

    private static final class CaveDto {
        String caveBlock;
        Integer caveMinHeight, caveMaxHeight, caveRarity, maxInitNodes, largeNodeRarity, largeNodeMaxBranches,
                bigCaveRarity, steepStepRarity, carveStepRarity;
        Double caveSizeAdd, flattenFactor, steeperFlattenFactor, directionChangeFactor,
                prevHorizDirectionChangeWeight, prevVertDirectionChangeWeight, maxAddDirectionChangeHoriz,
                maxAddDirectionChangeVert, caveFloorDepth;
        List<String> isBlockReplaceable;
    }

    private static final class LakeDto {
        String block;
        FilterType biomeSelect;
        List<String> biomes;
        List<UserFunctionPoint> surfaceProbability, mainProbability;
    }

    private static final class OreDto {
        String blockstate;
        List<String> biomes;
        Integer spawnSize, spawnTries;
        Double spawnProbability, minHeight, maxHeight, heightMean, heightStdDeviation, heightSpacing;
    }

    private SettingsDto toDto() {
        SettingsDto dto = new SettingsDto();
        dto.generationDepth = generationDepth;
        dto.version = CURRENT_VERSION;
        dto.strongholds = strongholds;
        dto.alternateStrongholdsPositions = alternateStrongholdsPositions;
        dto.villages = villages;
        dto.mineshafts = mineshafts;
        dto.temples = temples;
        dto.oceanMonuments = oceanMonuments;
        dto.woodlandMansions = woodlandMansions;
        dto.ravines = ravines;
        dto.dungeons = dungeons;
        dto.dungeonCount = dungeonCount;
        dto.biome = biome;
        dto.biomeSize = biomeSize;
        dto.riverSize = riverSize;
        dto.expectedBaseHeight = expectedBaseHeight;
        dto.expectedHeightVariation = expectedHeightVariation;
        dto.actualHeight = actualHeight;
        dto.heightVariationFactor = heightVariationFactor;
        dto.specialHeightVariationFactorBelowAverageY = specialHeightVariationFactorBelowAverageY;
        dto.heightVariationOffset = heightVariationOffset;
        dto.heightFactor = heightFactor;
        dto.heightOffset = heightOffset;
        dto.depthNoiseFactor = depthNoiseFactor;
        dto.depthNoiseOffset = depthNoiseOffset;
        dto.depthNoiseFrequencyX = depthNoiseFrequencyX;
        dto.depthNoiseFrequencyZ = depthNoiseFrequencyZ;
        dto.depthNoiseOctaves = depthNoiseOctaves;
        dto.selectorNoiseFactor = selectorNoiseFactor;
        dto.selectorNoiseOffset = selectorNoiseOffset;
        dto.selectorNoiseFrequencyX = selectorNoiseFrequencyX;
        dto.selectorNoiseFrequencyY = selectorNoiseFrequencyY;
        dto.selectorNoiseFrequencyZ = selectorNoiseFrequencyZ;
        dto.selectorNoiseOctaves = selectorNoiseOctaves;
        dto.lowNoiseFactor = lowNoiseFactor;
        dto.lowNoiseOffset = lowNoiseOffset;
        dto.lowNoiseFrequencyX = lowNoiseFrequencyX;
        dto.lowNoiseFrequencyY = lowNoiseFrequencyY;
        dto.lowNoiseFrequencyZ = lowNoiseFrequencyZ;
        dto.lowNoiseOctaves = lowNoiseOctaves;
        dto.highNoiseFactor = highNoiseFactor;
        dto.highNoiseOffset = highNoiseOffset;
        dto.highNoiseFrequencyX = highNoiseFrequencyX;
        dto.highNoiseFrequencyY = highNoiseFrequencyY;
        dto.highNoiseFrequencyZ = highNoiseFrequencyZ;
        dto.highNoiseOctaves = highNoiseOctaves;
        dto.noiseSampleSizeX = noiseSampleSizeX;
        dto.noiseSampleSizeY = noiseSampleSizeY;
        dto.noiseSampleSizeZ = noiseSampleSizeZ;
        dto.caves = caves.stream().map(CaveSettings::toDto).toList();
        dto.lakes = lakes.stream().map(LakeSettings::toDto).toList();
        dto.standardOres = standardOres.stream().map(OreSettings::toDto).toList();
        dto.periodicGaussianOres = periodicGaussianOres.stream().map(OreSettings::toDto).toList();
        return dto;
    }

    private static List<CaveSettings> parseCaves(List<CaveDto> values) {
        return values.stream().map(CaveSettings::fromDto).toList();
    }

    private static List<LakeSettings> parseLakes(List<LakeDto> values) {
        return values.stream().map(LakeSettings::fromDto).toList();
    }

    private static List<OreSettings> parseOres(List<OreDto> values, boolean periodic) {
        return values.stream().map(value -> OreSettings.fromDto(value, periodic)).toList();
    }

    private static void normalizeLegacy(JsonObject root) {
        /* The 1.12 serializer emitted blockstate objects; modern config uses IDs. */
        normalizeBlocks(root);
        JsonElement biome = root.get("biome");
        if (biome != null && biome.isJsonPrimitive() && biome.getAsJsonPrimitive().isNumber()) {
            /* -1 was the old "generated biome" sentinel. */
            root.add("biome", JsonNullHolder.NULL);
        }
        if (root.has("cubeAreas") && root.get("cubeAreas").isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement value : root.getAsJsonArray("cubeAreas")) {
                if (value.isJsonArray() && value.getAsJsonArray().size() == 2) {
                    JsonObject box = value.getAsJsonArray().get(0).getAsJsonObject();
                    JsonObject replacement = box.deepCopy();
                    replacement.add("settings", value.getAsJsonArray().get(1));
                    result.add(replacement);
                } else result.add(value);
            }
            root.add("cubeAreas", result);
        }
    }

    private static void normalizeBlocks(JsonElement element) {
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                JsonElement child = array.get(i);
                if (child.isJsonObject() && child.getAsJsonObject().has("Name")) {
                    array.set(i, new JsonPrimitive(blockStateString(child.getAsJsonObject())));
                } else {
                    normalizeBlocks(child);
                }
            }
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        for (String key : new ArrayList<>(object.keySet())) {
            JsonElement value = object.get(key);
            if (isBlockKey(key) && value.isJsonObject() && value.getAsJsonObject().has("Name")) {
                object.addProperty(key, blockStateString(value.getAsJsonObject()));
            } else normalizeBlocks(value);
        }
    }

    private static boolean isBlockKey(String key) {
        return key.equals("blockstate") || key.equals("block") || key.equals("caveBlock")
                || key.equals("filterBlocks") || key.equals("isBlockReplaceable");
    }

    private static String blockStateString(JsonObject value) {
        String name = value.get("Name").getAsString();
        if (!name.contains(":")) name = "minecraft:" + name;
        JsonObject properties = value.getAsJsonObject("Properties");
        if (properties == null || properties.isEmpty()) return name;
        StringBuilder result = new StringBuilder(name).append('[');
        boolean first = true;
        for (var entry : properties.entrySet()) {
            if (!first) result.append(',');
            first = false;
            result.append(entry.getKey()).append('=').append(entry.getValue().getAsString());
        }
        return result.append(']').toString();
    }

    /** Gson cannot represent a null JsonElement without a singleton holder in older versions. */
    private static final class JsonNullHolder {
        private static final JsonElement NULL = com.google.gson.JsonNull.INSTANCE;
    }

    public enum FilterType { INCLUDE, EXCLUDE }

    public static final class UserFunctionPoint {
        private final double y;
        private final double v;

        public UserFunctionPoint(double y, double v) {
            if (!Double.isFinite(y) || !Double.isFinite(v)) fail("UserFunctionPoint cannot contain NaN or Infinity");
            this.y = y;
            this.v = v;
        }

        public double y() { return y; }
        public double v() { return v; }
    }

    public static final class UserFunction {
        private final List<UserFunctionPoint> points;

        public UserFunction(List<UserFunctionPoint> points) {
            if (points == null || points.isEmpty()) fail("UserFunction must contain at least one point");
            List<UserFunctionPoint> sorted = new ArrayList<>(points);
            sorted.sort(Comparator.comparingDouble(UserFunctionPoint::y));
            for (int i = 1; i < sorted.size(); i++) {
                if (Double.compare(sorted.get(i - 1).y(), sorted.get(i).y()) == 0) fail("UserFunction points cannot repeat y");
            }
            for (UserFunctionPoint point : sorted) if (point.v() < 0 || point.v() > 1) fail("probability must be between 0 and 1");
            this.points = List.copyOf(sorted);
        }

        public List<UserFunctionPoint> points() { return points; }

        public double getValue(double y) {
            if (y <= points.get(0).y()) return points.get(0).v();
            for (int i = 1; i < points.size(); i++) {
                UserFunctionPoint high = points.get(i);
                UserFunctionPoint low = points.get(i - 1);
                if (y <= high.y()) {
                    double fraction = (y - low.y()) / (high.y() - low.y());
                    return low.v() + (high.v() - low.v()) * fraction;
                }
            }
            return points.get(points.size() - 1).v();
        }
    }

    public static final class CaveSettings {
        private final String caveBlock;
        private final int caveMinHeight, caveMaxHeight, caveRarity, maxInitNodes, largeNodeRarity, largeNodeMaxBranches,
                bigCaveRarity, steepStepRarity, carveStepRarity;
        private final double caveSizeAdd, flattenFactor, steeperFlattenFactor, directionChangeFactor,
                prevHorizDirectionChangeWeight, prevVertDirectionChangeWeight, maxAddDirectionChangeHoriz,
                maxAddDirectionChangeVert, caveFloorDepth;
        private final List<String> isBlockReplaceable;

        public CaveSettings(String caveBlock, int caveMinHeight, int caveMaxHeight, int caveRarity, int maxInitNodes,
                            int largeNodeRarity, int largeNodeMaxBranches, int bigCaveRarity, double caveSizeAdd,
                            int steepStepRarity, double flattenFactor, double steeperFlattenFactor,
                            double directionChangeFactor, double prevHorizDirectionChangeWeight,
                            double prevVertDirectionChangeWeight, double maxAddDirectionChangeHoriz,
                            double maxAddDirectionChangeVert, int carveStepRarity, double caveFloorDepth,
                            List<String> isBlockReplaceable) {
            this.caveBlock = Objects.requireNonNull(caveBlock);
            this.caveMinHeight = caveMinHeight;
            this.caveMaxHeight = caveMaxHeight;
            this.caveRarity = caveRarity;
            this.maxInitNodes = maxInitNodes;
            this.largeNodeRarity = largeNodeRarity;
            this.largeNodeMaxBranches = largeNodeMaxBranches;
            this.bigCaveRarity = bigCaveRarity;
            this.caveSizeAdd = caveSizeAdd;
            this.steepStepRarity = steepStepRarity;
            this.flattenFactor = flattenFactor;
            this.steeperFlattenFactor = steeperFlattenFactor;
            this.directionChangeFactor = directionChangeFactor;
            this.prevHorizDirectionChangeWeight = prevHorizDirectionChangeWeight;
            this.prevVertDirectionChangeWeight = prevVertDirectionChangeWeight;
            this.maxAddDirectionChangeHoriz = maxAddDirectionChangeHoriz;
            this.maxAddDirectionChangeVert = maxAddDirectionChangeVert;
            this.carveStepRarity = carveStepRarity;
            this.caveFloorDepth = caveFloorDepth;
            this.isBlockReplaceable = List.copyOf(isBlockReplaceable == null ? List.of() : isBlockReplaceable);
        }

        static CaveSettings standardDefault() {
            return new CaveSettings("minecraft:air", Integer.MIN_VALUE / 16, Integer.MAX_VALUE / 16, 14, 14, 4, 4, 10,
                    1.5, 6, .7, .92, .1, .75, .9, 4, 2, 4, -.7,
                    List.of("minecraft:grass_block", "minecraft:dirt", "minecraft:stone", "minecraft:deepslate"));
        }
        public String caveBlock() { return caveBlock; }
        public int caveMinHeight() { return caveMinHeight; }
        public int caveMaxHeight() { return caveMaxHeight; }
        public int caveRarity() { return caveRarity; }
        public int maxInitNodes() { return maxInitNodes; }
        public int largeNodeRarity() { return largeNodeRarity; }
        public int largeNodeMaxBranches() { return largeNodeMaxBranches; }
        public int bigCaveRarity() { return bigCaveRarity; }
        public double caveSizeAdd() { return caveSizeAdd; }
        public int steepStepRarity() { return steepStepRarity; }
        public double flattenFactor() { return flattenFactor; }
        public double steeperFlattenFactor() { return steeperFlattenFactor; }
        public double directionChangeFactor() { return directionChangeFactor; }
        public double prevHorizDirectionChangeWeight() { return prevHorizDirectionChangeWeight; }
        public double prevVertDirectionChangeWeight() { return prevVertDirectionChangeWeight; }
        public double maxAddDirectionChangeHoriz() { return maxAddDirectionChangeHoriz; }
        public double maxAddDirectionChangeVert() { return maxAddDirectionChangeVert; }
        public int carveStepRarity() { return carveStepRarity; }
        public double caveFloorDepth() { return caveFloorDepth; }
        public List<String> isBlockReplaceable() { return isBlockReplaceable; }
        private void validate() {
            validateIdentifier(caveBlock, "caveBlock");
            validateStringList(isBlockReplaceable, "isBlockReplaceable");
            if (caveMinHeight > caveMaxHeight) fail("caveMinHeight must be <= caveMaxHeight");
            if (caveRarity <= 0 || maxInitNodes <= 0 || largeNodeRarity <= 0 || largeNodeMaxBranches <= 0 || bigCaveRarity <= 0
                    || steepStepRarity <= 0 || carveStepRarity <= 0) fail("cave rarity/node settings must be positive");
            validateDoubles(caveSizeAdd, flattenFactor, steeperFlattenFactor, directionChangeFactor,
                    prevHorizDirectionChangeWeight, prevVertDirectionChangeWeight, maxAddDirectionChangeHoriz,
                    maxAddDirectionChangeVert, caveFloorDepth);
            if (caveSizeAdd < 0 || flattenFactor < 0 || flattenFactor > 1 || steeperFlattenFactor < 0 || steeperFlattenFactor > 1
                    || directionChangeFactor < 0 || directionChangeFactor > 1 || prevHorizDirectionChangeWeight < 0
                    || prevHorizDirectionChangeWeight > 1 || prevVertDirectionChangeWeight < 0 || prevVertDirectionChangeWeight > 1
                    || maxAddDirectionChangeHoriz < 0 || maxAddDirectionChangeVert < 0 || caveFloorDepth < -1 || caveFloorDepth > 1) {
                fail("cave numeric setting is outside its valid range");
            }
        }
        private CaveDto toDto() {
            CaveDto dto = new CaveDto();
            dto.caveBlock = caveBlock; dto.caveMinHeight = caveMinHeight; dto.caveMaxHeight = caveMaxHeight;
            dto.caveRarity = caveRarity; dto.maxInitNodes = maxInitNodes; dto.largeNodeRarity = largeNodeRarity;
            dto.largeNodeMaxBranches = largeNodeMaxBranches; dto.bigCaveRarity = bigCaveRarity; dto.caveSizeAdd = caveSizeAdd;
            dto.steepStepRarity = steepStepRarity; dto.flattenFactor = flattenFactor; dto.steeperFlattenFactor = steeperFlattenFactor;
            dto.directionChangeFactor = directionChangeFactor; dto.prevHorizDirectionChangeWeight = prevHorizDirectionChangeWeight;
            dto.prevVertDirectionChangeWeight = prevVertDirectionChangeWeight; dto.maxAddDirectionChangeHoriz = maxAddDirectionChangeHoriz;
            dto.maxAddDirectionChangeVert = maxAddDirectionChangeVert; dto.carveStepRarity = carveStepRarity;
            dto.caveFloorDepth = caveFloorDepth; dto.isBlockReplaceable = isBlockReplaceable; return dto;
        }
        private static CaveSettings fromDto(CaveDto dto) {
            CaveSettings defaults = standardDefault();
            return new CaveSettings(value(dto.caveBlock, defaults.caveBlock), value(dto.caveMinHeight, defaults.caveMinHeight),
                    value(dto.caveMaxHeight, defaults.caveMaxHeight), value(dto.caveRarity, defaults.caveRarity), value(dto.maxInitNodes, defaults.maxInitNodes),
                    value(dto.largeNodeRarity, defaults.largeNodeRarity), value(dto.largeNodeMaxBranches, defaults.largeNodeMaxBranches), value(dto.bigCaveRarity, defaults.bigCaveRarity),
                    value(dto.caveSizeAdd, defaults.caveSizeAdd), value(dto.steepStepRarity, defaults.steepStepRarity), value(dto.flattenFactor, defaults.flattenFactor),
                    value(dto.steeperFlattenFactor, defaults.steeperFlattenFactor), value(dto.directionChangeFactor, defaults.directionChangeFactor),
                    value(dto.prevHorizDirectionChangeWeight, defaults.prevHorizDirectionChangeWeight), value(dto.prevVertDirectionChangeWeight, defaults.prevVertDirectionChangeWeight),
                    value(dto.maxAddDirectionChangeHoriz, defaults.maxAddDirectionChangeHoriz), value(dto.maxAddDirectionChangeVert, defaults.maxAddDirectionChangeVert),
                    value(dto.carveStepRarity, defaults.carveStepRarity), value(dto.caveFloorDepth, defaults.caveFloorDepth),
                    dto.isBlockReplaceable == null ? defaults.isBlockReplaceable : dto.isBlockReplaceable);
        }
    }

    public static final class LakeSettings {
        private final String block;
        private final FilterType biomeSelect;
        private final Set<String> biomes;
        private final UserFunction surfaceProbability;
        private final UserFunction mainProbability;

        public LakeSettings(String block, FilterType biomeSelect, Set<String> biomes,
                            UserFunction surfaceProbability, UserFunction mainProbability) {
            this.block = Objects.requireNonNull(block);
            this.biomeSelect = Objects.requireNonNull(biomeSelect);
            this.biomes = Set.copyOf(biomes == null ? Set.of() : biomes);
            this.surfaceProbability = Objects.requireNonNull(surfaceProbability);
            this.mainProbability = Objects.requireNonNull(mainProbability);
        }
        static LakeSettings standardLava() {
            return new LakeSettings("minecraft:lava", FilterType.EXCLUDE, Set.of(),
                    curve(-1, 19921 / 326120.0, 0, 19921 / 326120.0, 31, 1332 / 40765.0, 63, 579 / 81530.0,
                            95, 161 / 32612.0, 127, 129 / 40765.0, 128, 129 / 40765.0),
                    curve(0, 4 / 263.0, 7, 4 / 263.0, 8, 247 / 16306.0, 62, 193 / 16306.0,
                            63, 48 / 40765.0, 127, 32 / 40765.0, 128, 32 / 40765.0));
        }
        static LakeSettings standardWater() {
            return new LakeSettings("minecraft:water", FilterType.EXCLUDE,
                    Set.of("minecraft:desert", "minecraft:desert_hills"), curve(-1, .25, 0, .25, 128, .125, 129, .125), curve(0, 1 / 64.0));
        }
        public String block() { return block; }
        public FilterType biomeSelect() { return biomeSelect; }
        public Set<String> biomes() { return biomes; }
        public UserFunction surfaceProbability() { return surfaceProbability; }
        public UserFunction mainProbability() { return mainProbability; }
        private void validate() {
            validateIdentifier(block, "lake.block"); validateStringList(new ArrayList<>(biomes), "lake.biomes");
            surfaceProbability.getClass(); mainProbability.getClass();
        }
        private LakeDto toDto() {
            LakeDto dto = new LakeDto(); dto.block = block; dto.biomeSelect = biomeSelect; dto.biomes = new ArrayList<>(biomes);
            dto.surfaceProbability = surfaceProbability.points; dto.mainProbability = mainProbability.points; return dto;
        }
        private static LakeSettings fromDto(LakeDto dto) {
            LakeSettings defaults = standardWater();
            return new LakeSettings(value(dto.block, defaults.block), dto.biomeSelect == null ? defaults.biomeSelect : dto.biomeSelect,
                    dto.biomes == null ? defaults.biomes : new LinkedHashSet<>(dto.biomes),
                    new UserFunction(dto.surfaceProbability == null ? defaults.surfaceProbability.points : dto.surfaceProbability),
                    new UserFunction(dto.mainProbability == null ? defaults.mainProbability.points : dto.mainProbability));
        }
    }

    public static final class OreSettings {
        private final String blockstate;
        private final List<String> biomes;
        private final int spawnSize, spawnTries;
        private final double spawnProbability, minHeight, maxHeight, heightMean, heightStdDeviation, heightSpacing;

        public OreSettings(String blockstate, List<String> biomes, int spawnSize, int spawnTries, double spawnProbability,
                           double minHeight, double maxHeight, double heightMean, double heightStdDeviation, double heightSpacing) {
            this.blockstate = Objects.requireNonNull(blockstate);
            this.biomes = biomes == null ? null : List.copyOf(new LinkedHashSet<>(biomes));
            this.spawnSize = spawnSize; this.spawnTries = spawnTries; this.spawnProbability = spawnProbability;
            this.minHeight = minHeight; this.maxHeight = maxHeight; this.heightMean = heightMean;
            this.heightStdDeviation = heightStdDeviation; this.heightSpacing = heightSpacing;
        }
        public String blockstate() { return blockstate; }
        /** null means all biomes; the returned set is defensive and immutable. */
        public Set<String> biomes() { return biomes == null ? null : Set.copyOf(biomes); }
        public int spawnSize() { return spawnSize; }
        public int spawnTries() { return spawnTries; }
        public double spawnProbability() { return spawnProbability; }
        public double minHeight() { return minHeight; }
        public double maxHeight() { return maxHeight; }
        public double heightMean() { return heightMean; }
        public double heightStdDeviation() { return heightStdDeviation; }
        public double heightSpacing() { return heightSpacing; }
        private void validate(boolean periodic) {
            validateIdentifier(blockstate, "ore.blockstate"); validateStringList(biomes, "ore.biomes");
            if (spawnSize <= 0 || spawnTries < 0) fail("ore spawnSize must be positive and spawnTries cannot be negative");
            validateDoubles(spawnProbability, heightMean, heightStdDeviation, heightSpacing);
            if (Double.isNaN(minHeight) || Double.isNaN(maxHeight)
                    || minHeight == Double.POSITIVE_INFINITY
                    || maxHeight == Double.NEGATIVE_INFINITY) {
                fail("ore height bounds must be finite or use the matching unbounded sentinel");
            }
            if (spawnProbability < 0 || spawnProbability > 1) fail("ore probability must be between 0 and 1");
            if (minHeight > maxHeight) fail("ore minHeight must be <= maxHeight");
            if (periodic && (heightStdDeviation <= 0 || heightStdDeviation > 1 || heightSpacing <= 0)) fail("invalid periodic ore settings");
        }
        private OreDto toDto() {
            OreDto dto = new OreDto(); dto.blockstate = blockstate; dto.biomes = biomes == null ? null : new ArrayList<>(biomes);
            dto.spawnSize = spawnSize; dto.spawnTries = spawnTries; dto.spawnProbability = spawnProbability;
            dto.minHeight = finiteOrNull(minHeight); dto.maxHeight = finiteOrNull(maxHeight); dto.heightMean = heightMean;
            dto.heightStdDeviation = heightStdDeviation; dto.heightSpacing = heightSpacing; return dto;
        }
        private static OreSettings fromDto(OreDto dto, boolean periodic) {
            OreSettings defaults = periodic ? new OreSettings("minecraft:lapis_ore", null, 7, 1, .933307775, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -.75, .11231704455, 3.0)
                    : ore("minecraft:stone", null, 8, 4, 1, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0, 0, 1);
            return new OreSettings(value(dto.blockstate, defaults.blockstate), dto.biomes == null ? defaults.biomes : dto.biomes,
                    value(dto.spawnSize, defaults.spawnSize), value(dto.spawnTries, defaults.spawnTries), value(dto.spawnProbability, defaults.spawnProbability),
                    unbounded(dto.minHeight, defaults.minHeight), unbounded(dto.maxHeight, defaults.maxHeight), value(dto.heightMean, defaults.heightMean),
                    value(dto.heightStdDeviation, defaults.heightStdDeviation), value(dto.heightSpacing, defaults.heightSpacing));
        }
        private static Double finiteOrNull(double value) { return Double.isInfinite(value) ? null : value; }
        private static double unbounded(Double value, double fallback) { return value == null ? fallback : value; }
    }

    public static final class Builder {
        private int generationDepth = DEFAULT_CUSTOM_DEPTH;
        private boolean strongholds = true, alternateStrongholdsPositions = false, villages = true, mineshafts = true,
                temples = true, oceanMonuments = true, woodlandMansions = true, ravines = true, dungeons = true;
        private int dungeonCount = 7; private String biome; private int biomeSize = 4, riverSize = 4;
        private double expectedBaseHeight = 64, expectedHeightVariation = 64, actualHeight = 256, heightVariationFactor = 64,
                specialHeightVariationFactorBelowAverageY = .25, heightVariationOffset, heightFactor = 64, heightOffset = 64;
        private double depthNoiseFactor = VANILLA_DEPTH_NOISE_FACTOR, depthNoiseOffset,
                depthNoiseFrequencyX = VANILLA_DEPTH_NOISE_FREQUENCY, depthNoiseFrequencyZ = VANILLA_DEPTH_NOISE_FREQUENCY;
        private int depthNoiseOctaves = 16;
        private double selectorNoiseFactor = VANILLA_SELECTOR_NOISE_FACTOR, selectorNoiseOffset = VANILLA_SELECTOR_NOISE_OFFSET,
                selectorNoiseFrequencyX = VANILLA_SELECTOR_NOISE_FREQUENCY_XZ, selectorNoiseFrequencyY = VANILLA_SELECTOR_NOISE_FREQUENCY_Y,
                selectorNoiseFrequencyZ = VANILLA_SELECTOR_NOISE_FREQUENCY_XZ;
        private int selectorNoiseOctaves = 8;
        private double lowNoiseFactor = 1, lowNoiseOffset,
                lowNoiseFrequencyX = VANILLA_LOWHIGH_NOISE_FREQUENCY_XZ, lowNoiseFrequencyY = VANILLA_LOWHIGH_NOISE_FREQUENCY_Y,
                lowNoiseFrequencyZ = VANILLA_LOWHIGH_NOISE_FREQUENCY_XZ;
        private int lowNoiseOctaves = 16;
        private double highNoiseFactor = 1, highNoiseOffset,
                highNoiseFrequencyX = VANILLA_LOWHIGH_NOISE_FREQUENCY_XZ, highNoiseFrequencyY = VANILLA_LOWHIGH_NOISE_FREQUENCY_Y,
                highNoiseFrequencyZ = VANILLA_LOWHIGH_NOISE_FREQUENCY_XZ;
        private int highNoiseOctaves = 16, noiseSampleSizeX = 4, noiseSampleSizeY = 8, noiseSampleSizeZ = 4;
        private List<CaveSettings> caves = List.of(CaveSettings.standardDefault());
        private List<LakeSettings> lakes = List.of(LakeSettings.standardLava(), LakeSettings.standardWater());
        private List<OreSettings> standardOres = standardDefaults();
        private List<OreSettings> periodicGaussianOres = List.of(ore("minecraft:lapis_ore", null, 7, 1, .933307775, Double.NEGATIVE_INFINITY, -.5, -.75, .11231704455, 3));

        private Builder() { }
        private Builder(CustomWorldSettings source) {
            generationDepth = source.generationDepth; strongholds = source.strongholds; alternateStrongholdsPositions = source.alternateStrongholdsPositions;
            villages = source.villages; mineshafts = source.mineshafts; temples = source.temples; oceanMonuments = source.oceanMonuments; woodlandMansions = source.woodlandMansions;
            ravines = source.ravines; dungeons = source.dungeons; dungeonCount = source.dungeonCount; biome = source.biome; biomeSize = source.biomeSize; riverSize = source.riverSize;
            expectedBaseHeight = source.expectedBaseHeight; expectedHeightVariation = source.expectedHeightVariation; actualHeight = source.actualHeight; heightVariationFactor = source.heightVariationFactor;
            specialHeightVariationFactorBelowAverageY = source.specialHeightVariationFactorBelowAverageY; heightVariationOffset = source.heightVariationOffset; heightFactor = source.heightFactor; heightOffset = source.heightOffset;
            depthNoiseFactor = source.depthNoiseFactor; depthNoiseOffset = source.depthNoiseOffset; depthNoiseFrequencyX = source.depthNoiseFrequencyX; depthNoiseFrequencyZ = source.depthNoiseFrequencyZ; depthNoiseOctaves = source.depthNoiseOctaves;
            selectorNoiseFactor = source.selectorNoiseFactor; selectorNoiseOffset = source.selectorNoiseOffset; selectorNoiseFrequencyX = source.selectorNoiseFrequencyX; selectorNoiseFrequencyY = source.selectorNoiseFrequencyY; selectorNoiseFrequencyZ = source.selectorNoiseFrequencyZ; selectorNoiseOctaves = source.selectorNoiseOctaves;
            lowNoiseFactor = source.lowNoiseFactor; lowNoiseOffset = source.lowNoiseOffset; lowNoiseFrequencyX = source.lowNoiseFrequencyX; lowNoiseFrequencyY = source.lowNoiseFrequencyY; lowNoiseFrequencyZ = source.lowNoiseFrequencyZ; lowNoiseOctaves = source.lowNoiseOctaves;
            highNoiseFactor = source.highNoiseFactor; highNoiseOffset = source.highNoiseOffset; highNoiseFrequencyX = source.highNoiseFrequencyX; highNoiseFrequencyY = source.highNoiseFrequencyY; highNoiseFrequencyZ = source.highNoiseFrequencyZ; highNoiseOctaves = source.highNoiseOctaves;
            noiseSampleSizeX = source.noiseSampleSizeX; noiseSampleSizeY = source.noiseSampleSizeY; noiseSampleSizeZ = source.noiseSampleSizeZ;
            caves = source.caves; lakes = source.lakes; standardOres = source.standardOres; periodicGaussianOres = source.periodicGaussianOres;
        }
        public Builder generationDepth(int value) { generationDepth = value; return this; }
        public Builder strongholds(boolean value) { strongholds = value; return this; }
        public Builder alternateStrongholdsPositions(boolean value) { alternateStrongholdsPositions = value; return this; }
        public Builder villages(boolean value) { villages = value; return this; }
        public Builder mineshafts(boolean value) { mineshafts = value; return this; }
        public Builder temples(boolean value) { temples = value; return this; }
        public Builder oceanMonuments(boolean value) { oceanMonuments = value; return this; }
        public Builder woodlandMansions(boolean value) { woodlandMansions = value; return this; }
        public Builder ravines(boolean value) { ravines = value; return this; }
        public Builder dungeons(boolean value) { dungeons = value; return this; }
        public Builder dungeonCount(int value) { dungeonCount = value; return this; }
        public Builder biome(String value) { biome = value; return this; }
        public Builder biomeSize(int value) { biomeSize = value; return this; }
        public Builder riverSize(int value) { riverSize = value; return this; }
        public Builder expectedBaseHeight(double value) { expectedBaseHeight = value; return this; }
        public Builder expectedHeightVariation(double value) { expectedHeightVariation = value; return this; }
        public Builder actualHeight(double value) { actualHeight = value; return this; }
        public Builder heightVariationFactor(double value) { heightVariationFactor = value; return this; }
        public Builder specialHeightVariationFactorBelowAverageY(double value) { specialHeightVariationFactorBelowAverageY = value; return this; }
        public Builder heightVariationOffset(double value) { heightVariationOffset = value; return this; }
        public Builder heightFactor(double value) { heightFactor = value; return this; }
        public Builder heightOffset(double value) { heightOffset = value; return this; }
        public Builder depthNoiseFactor(double value) { depthNoiseFactor = value; return this; }
        public Builder depthNoiseOffset(double value) { depthNoiseOffset = value; return this; }
        public Builder depthNoiseFrequencyX(double value) { depthNoiseFrequencyX = value; return this; }
        public Builder depthNoiseFrequencyZ(double value) { depthNoiseFrequencyZ = value; return this; }
        public Builder depthNoiseOctaves(int value) { depthNoiseOctaves = value; return this; }
        public Builder selectorNoiseFactor(double value) { selectorNoiseFactor = value; return this; }
        public Builder selectorNoiseOffset(double value) { selectorNoiseOffset = value; return this; }
        public Builder selectorNoiseFrequencyX(double value) { selectorNoiseFrequencyX = value; return this; }
        public Builder selectorNoiseFrequencyY(double value) { selectorNoiseFrequencyY = value; return this; }
        public Builder selectorNoiseFrequencyZ(double value) { selectorNoiseFrequencyZ = value; return this; }
        public Builder selectorNoiseOctaves(int value) { selectorNoiseOctaves = value; return this; }
        public Builder lowNoiseFactor(double value) { lowNoiseFactor = value; return this; }
        public Builder lowNoiseOffset(double value) { lowNoiseOffset = value; return this; }
        public Builder lowNoiseFrequencyX(double value) { lowNoiseFrequencyX = value; return this; }
        public Builder lowNoiseFrequencyY(double value) { lowNoiseFrequencyY = value; return this; }
        public Builder lowNoiseFrequencyZ(double value) { lowNoiseFrequencyZ = value; return this; }
        public Builder lowNoiseOctaves(int value) { lowNoiseOctaves = value; return this; }
        public Builder highNoiseFactor(double value) { highNoiseFactor = value; return this; }
        public Builder highNoiseOffset(double value) { highNoiseOffset = value; return this; }
        public Builder highNoiseFrequencyX(double value) { highNoiseFrequencyX = value; return this; }
        public Builder highNoiseFrequencyY(double value) { highNoiseFrequencyY = value; return this; }
        public Builder highNoiseFrequencyZ(double value) { highNoiseFrequencyZ = value; return this; }
        public Builder highNoiseOctaves(int value) { highNoiseOctaves = value; return this; }
        public Builder noiseSampleSizeX(int value) { noiseSampleSizeX = value; return this; }
        public Builder noiseSampleSizeY(int value) { noiseSampleSizeY = value; return this; }
        public Builder noiseSampleSizeZ(int value) { noiseSampleSizeZ = value; return this; }
        public Builder caves(List<CaveSettings> value) { caves = List.copyOf(value); return this; }
        public Builder lakes(List<LakeSettings> value) { lakes = List.copyOf(value); return this; }
        public Builder standardOres(List<OreSettings> value) { standardOres = List.copyOf(value); return this; }
        public Builder periodicGaussianOres(List<OreSettings> value) { periodicGaussianOres = List.copyOf(value); return this; }
        public CustomWorldSettings build() { return new CustomWorldSettings(this); }
    }

    private static <T> T value(T value, T fallback) { return value == null ? fallback : value; }
    private static Double finiteOrNull(Double value) { return value != null && Double.isFinite(value) ? value : null; }
}
