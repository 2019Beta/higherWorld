package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CubeWatchManagerBudgetTest {
    @Test
    void overrunRepaysDebtWithoutStoppingCommits() {
        CubeWatchManager.AdaptiveBudget budget = new CubeWatchManager.AdaptiveBudget();

        assertTrue(budget.claimCommitNanos() > 0L);
        budget.recordCommit(12_000_000L);

        assertEquals(6_000_000L, budget.debtNanos());
        assertTrue(budget.cubeAllowance() > 0);
        assertEquals(500_000L, budget.claimCommitNanos());
        assertEquals(500_000L, budget.debtNanos());
        assertEquals(500_000L, budget.claimCommitNanos());
        assertEquals(0L, budget.debtNanos());
        assertTrue(budget.claimCommitNanos() > 0L);
    }

    @Test
    void debtIsCappedAfterPathologicalSingleCommit() {
        CubeWatchManager.AdaptiveBudget budget = new CubeWatchManager.AdaptiveBudget();

        budget.recordCommit(2_000_000_000L);

        assertEquals(300_000_000L, budget.debtNanos());
        for (int tick = 0; tick < 60; tick++) {
            long slice = budget.claimCommitNanos();
            assertTrue(slice > 0L);
            budget.recordCommit(slice);
        }
        assertEquals(0L, budget.debtNanos());
    }

    @Test
    void readyPayloadSendingIsNotReducedToDebtTrickle() {
        CubeWatchManager.AdaptiveBudget budget = new CubeWatchManager.AdaptiveBudget();

        budget.recordCommit(12_000_000L);

        assertTrue(budget.sendAllowance() > budget.cubeAllowance());
    }

    @Test
    void commitAndSendCostsDoNotThrottleEachOther() {
        CubeWatchManager.AdaptiveBudget slowCommit = new CubeWatchManager.AdaptiveBudget();
        int sendAllowance = slowCommit.sendAllowance();
        slowCommit.recordCommit(300_000_000L);
        assertEquals(sendAllowance, slowCommit.sendAllowance());

        CubeWatchManager.AdaptiveBudget slowSend = new CubeWatchManager.AdaptiveBudget();
        long commitAllowance = slowSend.claimCommitNanos();
        slowSend.record(300_000_000L);
        assertEquals(commitAllowance, slowSend.claimCommitNanos());
    }
}
