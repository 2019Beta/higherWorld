package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Pure checks for the custom generation math; no Minecraft client bootstrap is required. */
class CustomGenerationTest {
    @Test
    void terrainNoiseIsDeterministicAndParametersMatter() {
        CustomWorldSettings defaults = CustomWorldSettings.customDefaults();
        double first = CustomCubeGenerator.terrainDensity(12345L, defaults, 18.0, -96.0, -7.0);
        double second = CustomCubeGenerator.terrainDensity(12345L, defaults, 18.0, -96.0, -7.0);
        assertEquals(first, second, 0.0);
        CustomWorldSettings changed = defaults.toBuilder()
                .heightFactor(defaults.heightFactor() + 23.0).build();
        assertNotEquals(first,
                CustomCubeGenerator.terrainDensity(12345L, changed, 18.0, -96.0, -7.0));
    }

    @Test
    void interpolationUsesConfiguredSampleSpacing() {
        double[] samples = {0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0};
        assertEquals(5.0, CustomCubeGenerator.interpolate(
                samples, 1, 1, 0, 2, 2, 2, 1, 1, 1), 1.0e-9);
        assertEquals(0.0, CustomCubeGenerator.interpolate(
                samples, 0, 0, 0, 1, 1, 1, 1, 1, 1), 1.0e-9);
    }

    @Test
    void oreHeightAndCyclicCurveFollowReferenceMath() {
        CustomWorldSettings settings = CustomWorldSettings.customDefaults();
        assertEquals(32, CustomOreGenerator.absoluteHeight(-.5, settings));
        double peak = CustomOreGenerator.cyclicBellCurveProbability(16.0, 16.0, 7.0, 192.0);
        assertEquals(1.0, peak, 1.0e-12);
        assertEquals(peak, CustomOreGenerator.cyclicBellCurveProbability(208.0, 16.0, 7.0, 192.0), 1.0e-12);
        assertTrue(CustomOreGenerator.cyclicBellCurveProbability(80.0, 16.0, 7.0, 192.0) < peak);
    }

    @Test
    void structureIdentifierFamiliesAreStable() {
        assertTrue(VanillaStructureGenerator.isMineshaft("mineshaft_mesa"));
        assertTrue(VanillaStructureGenerator.isVillage("village_taiga"));
        assertTrue(VanillaStructureGenerator.isTemple("jungle_pyramid"));
        assertTrue(VanillaStructureGenerator.isTemple("ocean_ruin_warm"));
    }

    @Test
    void modelRejectsZeroRandomDivisors() {
        CustomWorldSettings defaults = CustomWorldSettings.customDefaults();
        CustomWorldSettings.CaveSettings cave = defaults.caves().get(0);
        assertThrows(IllegalArgumentException.class, () -> new CustomWorldSettings.CaveSettings(
                cave.caveBlock(), cave.caveMinHeight(), cave.caveMaxHeight(), 0,
                cave.maxInitNodes(), cave.largeNodeRarity(), cave.largeNodeMaxBranches(),
                cave.bigCaveRarity(), cave.caveSizeAdd(), cave.steepStepRarity(),
                cave.flattenFactor(), cave.steeperFlattenFactor(), cave.directionChangeFactor(),
                cave.prevHorizDirectionChangeWeight(), cave.prevVertDirectionChangeWeight(),
                cave.maxAddDirectionChangeHoriz(), cave.maxAddDirectionChangeVert(),
                cave.carveStepRarity(), cave.caveFloorDepth(), cave.isBlockReplaceable()));
    }
}
