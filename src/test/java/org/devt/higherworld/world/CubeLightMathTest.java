package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Regression coverage for block emission in cubes outside vanilla height. */
class CubeLightMathTest {
    @Test
    void externalCubeBlockEmissionSurvivesDarkSkyAndAmbientDarkness() {
        assertEquals(14, CubeLightMath.externalBaseLightLevel(0, 4, 14));
    }

    @Test
    void externalCubeSkyLightStillWinsWhenItIsBrighter() {
        assertEquals(12, CubeLightMath.externalBaseLightLevel(15, 3, 8));
    }
}
