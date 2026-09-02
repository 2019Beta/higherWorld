package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CubeWatchManagerBudgetTest {
    @Test
    void overrunCreatesDebtAndPausesSubsequentCommits() {
        CubeWatchManager.AdaptiveBudget budget = new CubeWatchManager.AdaptiveBudget();

        assertTrue(budget.claimCommitNanos() > 0L);
        budget.recordCommit(12_000_000L);

        assertEquals(10_000_000L, budget.debtNanos());
        assertEquals(0, budget.cubeAllowance());
        for (int tick = 0; tick < 5; tick++) {
            assertEquals(0L, budget.claimCommitNanos());
        }
        assertEquals(0L, budget.debtNanos());
        assertTrue(budget.claimCommitNanos() > 0L);
    }

    @Test
    void debtIsCappedAfterPathologicalSingleCommit() {
        CubeWatchManager.AdaptiveBudget budget = new CubeWatchManager.AdaptiveBudget();

        budget.recordCommit(2_000_000_000L);

        assertEquals(200_000_000L, budget.debtNanos());
    }
}
