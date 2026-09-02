package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.devt.higherworld.storage.CubePos;
import org.junit.jupiter.api.Test;

/** Pure scheduling checks for the watcher state machine. */
class CubeWatchManagerQueueTest {
    @Test
    void phaseTransitionsCannotLeaveDuplicateActiveQueueEntries() {
        CubeWatchManager.WatchState state = new CubeWatchManager.WatchState();
        CubeWatchManager.Watch watch = state.addForTest(new CubePos(0, -1, 0), 0);
        state.setCenterForTest(new CubePos(0, 0, 0));

        assertEquals(1, state.pendingStartCountForTest());
        assertSame(watch, state.pollPendingStart());

        watch.phase = CubeWatchManager.WatchPhase.IN_FLIGHT;
        watch.version++;
        state.markReady(watch);
        state.markReady(watch);

        assertEquals(1, state.readySendCountForTest());
        assertSame(watch, state.pollReadySend());
        assertEquals(0, state.readySendCountForTest());
    }

    @Test
    void retryPromotionUsesDueTickBeforeDistance() {
        CubeWatchManager.WatchState state = new CubeWatchManager.WatchState();
        CubeWatchManager.Watch near = state.addForTest(new CubePos(0, -1, 0), 0);
        CubeWatchManager.Watch far = state.addForTest(new CubePos(8, -1, 8), 100);
        state.setCenterForTest(new CubePos(0, 0, 0));
        state.pollPendingStart();
        state.pollPendingStart();
        near.phase = CubeWatchManager.WatchPhase.IN_FLIGHT;
        near.version++;
        far.phase = CubeWatchManager.WatchPhase.IN_FLIGHT;
        far.version++;

        state.scheduleRetry(near, 100, CubeWatchManager.RetryTarget.SEND, 10);
        state.scheduleRetry(far, 100, CubeWatchManager.RetryTarget.SEND, 1);

        state.promoteDueRetries(101);
        assertSame(far, state.pollReadySend());
        assertEquals(0, state.readySendCountForTest());
        assertEquals(1, state.retryCountForTest());
        state.promoteDueRetries(110);
        assertSame(near, state.pollReadySend());
    }

    @Test
    void staleRetryEntryIsLazilyDiscardedByVersion() {
        CubeWatchManager.WatchState state = new CubeWatchManager.WatchState();
        CubeWatchManager.Watch watch = state.addForTest(new CubePos(0, -1, 0), 0);
        state.setCenterForTest(new CubePos(0, 0, 0));
        state.pollPendingStart();
        watch.phase = CubeWatchManager.WatchPhase.IN_FLIGHT;
        watch.version++;

        state.scheduleRetry(watch, 0, CubeWatchManager.RetryTarget.SEND, 5);
        // Replacing the retry leaves the old priority-queue entry in place.
        state.scheduleRetry(watch, 0, CubeWatchManager.RetryTarget.SEND, 10);

        state.promoteDueRetries(5);
        assertEquals(0, state.readySendCountForTest());
        assertEquals(1, state.retryCountForTest());
        state.promoteDueRetries(10);
        assertSame(watch, state.pollReadySend());
    }

    @Test
    void equalRanksUseYThenZThenXTieBreak() {
        CubeWatchManager.WatchState state = new CubeWatchManager.WatchState();
        CubeWatchManager.Watch xFirst = state.addForTest(new CubePos(1, -1, 0), 1);
        state.addForTest(new CubePos(0, -1, 1), 1);
        state.setCenterForTest(new CubePos(0, 0, 0));

        assertSame(xFirst, state.pollPendingStart());
    }
}
