package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CubeEntityRuntimeSaveDecisionTest {
    @Test
    void worldSaveIncludesLiveEntitiesEvenWhenPositionWasUnchanged() {
        assertTrue(CubeEntityRuntime.shouldSaveAtWorldSave(false, 1));
        assertTrue(CubeEntityRuntime.shouldSaveAtWorldSave(true, 0));
        assertFalse(CubeEntityRuntime.shouldSaveAtWorldSave(false, 0));
    }

    @Test
    void liveCountCannotBeNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> CubeEntityRuntime.shouldSaveAtWorldSave(false, -1));
    }
}
