package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CustomWorldSettingsTest {
    @Test
    void defaultsContainModernCompletePreset() {
        CustomWorldSettings defaults = CustomWorldSettings.defaults();
        assertEquals(-1, defaults.generationDepth());
        assertEquals(64.0, defaults.expectedBaseHeight());
        assertEquals(256.0, defaults.actualHeight());
        assertEquals(1.024, defaults.depthNoiseFactor(), 1.0e-12);
        assertEquals(12.75, defaults.selectorNoiseFactor(), 1.0e-12);
        assertEquals(4, defaults.noiseSampleSizeX());
        assertEquals(8, defaults.noiseSampleSizeY());
        assertEquals(4, defaults.noiseSampleSizeZ());
        assertEquals(1, defaults.caves().size());
        assertEquals(2, defaults.lakes().size());
        assertTrue(defaults.standardOres().size() >= 13);
        assertEquals(1, defaults.periodicGaussianOres().size());
        assertEquals("minecraft:lapis_ore", defaults.periodicGaussianOres().get(0).blockstate());
        assertEquals(256, CustomWorldSettings.customDefaults().generationDepth());
    }

    @Test
    void allCategoriesRoundTrip() {
        CustomWorldSettings original = CustomWorldSettings.builder()
                .generationDepth(768)
                .biome("minecraft:plains")
                .biomeSize(7)
                .riverSize(2)
                .actualHeight(512)
                .heightFactor(93.5)
                .selectorNoiseFrequencyY(.03125)
                .noiseSampleSizeX(16)
                .noiseSampleSizeY(2)
                .noiseSampleSizeZ(8)
                .build();

        CustomWorldSettings copy = CustomWorldSettings.fromJson(original.toJson());
        assertEquals(original.generationDepth(), copy.generationDepth());
        assertEquals(original.biome(), copy.biome());
        assertEquals(original.biomeSize(), copy.biomeSize());
        assertEquals(original.riverSize(), copy.riverSize());
        assertEquals(original.actualHeight(), copy.actualHeight());
        assertEquals(original.heightFactor(), copy.heightFactor());
        assertEquals(original.selectorNoiseFrequencyY(), copy.selectorNoiseFrequencyY());
        assertEquals(original.noiseSampleSizeX(), copy.noiseSampleSizeX());
        assertEquals(original.caves().get(0).caveFloorDepth(), copy.caves().get(0).caveFloorDepth());
        assertEquals(original.lakes().get(1).surfaceProbability().points().size(), copy.lakes().get(1).surfaceProbability().points().size());
        assertEquals(original.standardOres().get(0).blockstate(), copy.standardOres().get(0).blockstate());
        assertEquals(original.periodicGaussianOres().get(0).heightMean(), copy.periodicGaussianOres().get(0).heightMean());
        assertNull(copy.standardOres().get(0).minHeight() == Double.NEGATIVE_INFINITY ? null : copy.standardOres().get(0).minHeight());
    }

    @Test
    void versionOneAndLegacyDepthAliasAreMigrated() {
        CustomWorldSettings migrated = CustomWorldSettings.fromJson("{\"version\":1,\"generation_depth\":512}");
        assertEquals(512, migrated.generationDepth());
        assertEquals(64.0, migrated.expectedBaseHeight());
        assertEquals(2, migrated.lakes().size());
        CustomWorldSettings alias = CustomWorldSettings.fromJson("{\"version\":2,\"generationDepth\":384}");
        assertEquals(384, alias.generationDepth());
    }

    @Test
    void selectionsAndCollectionsAreDefensive() {
        CustomWorldSettings original = CustomWorldSettings.customDefaults();
        CustomWorldSettings.setClientSelection(original.withGenerationDepth(512));
        assertThrows(UnsupportedOperationException.class, () -> CustomWorldSettings.clientSelection().lakes().clear());
        assertThrows(UnsupportedOperationException.class, () -> CustomWorldSettings.clientSelection().caves().get(0).isBlockReplaceable().clear());
        CustomWorldSettings copy = CustomWorldSettings.clientSelection().copy();
        assertEquals(512, copy.generationDepth());
        assertEquals(256, original.generationDepth());
    }

    @Test
    void invalidValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> CustomWorldSettings.fromJson(
                "{\"standardOres\":[{\"blockstate\":\"minecraft:stone\",\"spawnProbability\":2}]}"));
        assertThrows(IllegalArgumentException.class, () -> CustomWorldSettings.fromJson(
                "{\"noiseSampleSizeX\":3}"));
        assertThrows(IllegalArgumentException.class, () -> CustomWorldSettings.fromJson(
                "{\"lakes\":[{\"mainProbability\":[{\"y\":0,\"v\":0.1},{\"y\":0,\"v\":0.2}],\"surfaceProbability\":[{\"y\":0,\"v\":0.1}]}]}"));
        assertThrows(IllegalArgumentException.class, () -> CustomWorldSettings.fromJson(
                "{\"standardOres\":[{\"blockstate\":\"not valid id\"}]}"));
        assertThrows(IllegalArgumentException.class, () -> CustomWorldSettings.fromJson(
                "{\"standardOres\":[{\"blockstate\":\"minecraft:stone\",\"spawnProbability\":\"NaN\"}]}"));
    }

    @Test
    void pendingSelectionIsConsumedOnlyOnce() throws Exception {
        Path first = Files.createTempDirectory("higherworld-settings-one");
        Path second = Files.createTempDirectory("higherworld-settings-two");
        try {
            CustomWorldSettings.setClientSelection(CustomWorldSettings.customDefaults().withGenerationDepth(1024));
            CustomWorldSettings.markWorldCreationStarted();
            assertEquals(1024, CustomWorldSettings.load(first, true).generationDepth());
            assertEquals(256, CustomWorldSettings.load(second, true).generationDepth());
        } finally {
            deleteTree(first);
            deleteTree(second);
        }
    }

    private static void deleteTree(Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(value -> {
                try { Files.deleteIfExists(value); } catch (Exception exception) { throw new RuntimeException(exception); }
            });
        }
    }
}
