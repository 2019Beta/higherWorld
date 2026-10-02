package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FeatureBatchSlicingTest {
    @Test
    void fixedTerrainViewCoversVirtualBandAndHaloSections() {
        int offsetY = -96;

        assertEquals(-12, VanillaPlacedFeatureGenerator.fixedTerrainMinSection(offsetY));
        assertEquals(-5, VanillaPlacedFeatureGenerator.fixedTerrainMaxSection(offsetY));
    }

    @Test
    void slicingStopsWhenVirtualHaloIntersectsVanillaHeight() {
        assertTrue(VanillaPlacedFeatureGenerator.isPureDeepBatch(-96, -64));
        assertFalse(VanillaPlacedFeatureGenerator.isPureDeepBatch(-95, -64));
    }

    @Test
    void onlyNamespacedVanillaOreFeaturesAreEligible() {
        assertTrue(VanillaPlacedFeatureGenerator.isKnownSafeVanillaOre(
                "minecraft", "ore_iron_middle"));
        assertFalse(VanillaPlacedFeatureGenerator.isKnownSafeVanillaOre(
                "minecraft", "dripstone_cluster"));
        assertFalse(VanillaPlacedFeatureGenerator.isKnownSafeVanillaOre(
                "examplemod", "ore_iron_middle"));
    }

    @Test
    void jobCursorRequiresEveryCallBeforePublication() {
        VanillaPlacedFeatureGenerator.FeatureBatchJobState state =
                new VanillaPlacedFeatureGenerator.FeatureBatchJobState(3);

        assertFalse(state.isComplete());
        assertEquals(0, state.take());
        assertEquals(1, state.cursor());
        assertFalse(state.isComplete());
        assertEquals(1, state.take());
        assertFalse(state.isComplete());
        assertThrows(IllegalStateException.class, state::finish);

        assertEquals(2, state.take());
        state.finish();
        assertTrue(state.isComplete());
        assertFalse(state.hasNext());
        assertThrows(IllegalStateException.class, state::take);
    }
}
