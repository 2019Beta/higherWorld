package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CubicWorldStateBudgetTest {
    @Test
    void scanTimeoutStillAllowsTheFirstReadyStage() {
        long deadline = 100L;

        assertFalse(CubicWorldState.commitBudgetExhausted(101L, deadline, 0));
        assertTrue(CubicWorldState.commitBudgetExhausted(101L, deadline, 1));
    }
}
