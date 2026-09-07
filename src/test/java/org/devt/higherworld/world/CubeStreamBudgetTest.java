package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CubeStreamBudgetTest {
    private static CubeStreamFeedbackPayload feedback(long id, long processed, long bytes,
            int count, int age, int renders, int lights) {
        return new CubeStreamFeedbackPayload(id, processed, bytes, count, age, renders, lights);
    }

    @Test
    void aNewStreamResetsTheWindowAndIgnoresPreviousConnectionFeedback() {
        CubeStreamBudget budget = new CubeStreamBudget();
        budget.start(1, true, 0);
        budget.recordSent((int) CubeStreamBudget.WINDOW_BYTES);
        budget.start(2, true, 100);
        assertTrue(budget.canSend(2 * 1024 * 1024, 100));
        budget.recordSent((int) CubeStreamBudget.WINDOW_BYTES);
        budget.feedback(feedback(1, CubeStreamBudget.WINDOW_BYTES, 0, 0, 0, 0, 0), 100);
        assertFalse(budget.canSend(1, 100));
        budget.feedback(feedback(2, CubeStreamBudget.WINDOW_BYTES, 0, 0, 0, 0, 0), 100);
        assertTrue(budget.canSend(1024, 100));
    }

    @Test
    void byteWindowOnlyReopensForProcessedBytesAndDuplicateReportsGiveNoCredit() {
        CubeStreamBudget budget = new CubeStreamBudget();
        budget.start(1, true, 0);
        budget.recordSent((int) CubeStreamBudget.WINDOW_BYTES);
        assertFalse(budget.canSend(1, 1));
        budget.feedback(feedback(1, 1024, 0, 0, 0, 0, 0), 1);
        assertTrue(budget.canSend(1024, 1));
        assertFalse(budget.canSend(1025, 1));
        budget.recordSent(1024);
        budget.feedback(feedback(1, 1024, 0, 0, 0, 0, 0), 2);
        assertFalse(budget.canSend(1, 2));
    }

    @Test
    void staleInvalidAndPriorWorldFeedbackCannotReleaseCredit() {
        CubeStreamBudget budget = new CubeStreamBudget();
        budget.start(2, true, 0);
        budget.recordSent((int) CubeStreamBudget.WINDOW_BYTES);
        budget.feedback(feedback(1, CubeStreamBudget.WINDOW_BYTES, 0, 0, 0, 0, 0), 1);
        budget.feedback(feedback(2, CubeStreamBudget.WINDOW_BYTES + 1, 0, 0, 0, 0, 0), 1);
        budget.feedback(feedback(2, 100, -1, 0, 0, 0, 0), 1);
        assertFalse(budget.canSend(1, 1));
        budget.feedback(feedback(2, 100, 0, 0, 0, 0, 0), 2);
        budget.feedback(feedback(2, 50, 0, 0, 0, 0, 0), 3);
        assertTrue(budget.canSend(100, 3));
        assertFalse(budget.canSend(101, 3));
        assertFalse(budget.canSend(1, 43));
        budget.feedback(feedback(2, 100, 0, 0, 0, 0, 0), 44);
        assertTrue(budget.canSend(100, 44));
    }

    @Test
    void decodeAgeRenderAndLightingPressurePauseThenResumeStreaming() {
        CubeStreamBudget budget = new CubeStreamBudget();
        budget.start(1, true, 0);
        for (CubeStreamFeedbackPayload pressure : new CubeStreamFeedbackPayload[] {
                feedback(1, 0, 2 * 1024 * 1024, 0, 0, 0, 0),
                feedback(1, 0, 0, 512, 0, 0, 0), feedback(1, 0, 0, 0, 250, 0, 0),
                feedback(1, 0, 0, 0, 0, 768, 0), feedback(1, 0, 0, 0, 0, 0, 512)}) {
            budget.feedback(pressure, 1);
            assertFalse(budget.canSend(0, 1));
            budget.feedback(feedback(1, 0, 0, 0, 0, 0, 0), 2);
            assertTrue(budget.canSend(2 * 1024 * 1024, 2));
        }
    }

    @Test
    void legacyClientsKeepStreamingWithoutFeedbackAndPlayersHaveSeparateWindows() {
        CubeStreamBudget legacy = new CubeStreamBudget();
        legacy.start(1, false, 0);
        legacy.recordSent(Integer.MAX_VALUE);
        assertTrue(legacy.canSend(1024, 100));
        CubeStreamBudget slow = new CubeStreamBudget();
        slow.start(2, true, 0);
        slow.recordSent((int) CubeStreamBudget.WINDOW_BYTES);
        CubeStreamBudget fast = new CubeStreamBudget();
        fast.start(3, true, 0);
        assertFalse(slow.canSend(1, 1));
        assertTrue(fast.canSend(1024, 1));
    }

    @Test
    void slowConsumerStaysWithinWindowAcrossManyTicksAndEventuallyDrains() {
        CubeStreamBudget budget = new CubeStreamBudget();
        CubeStreamQueue<Integer> queue = new CubeStreamQueue<>();
        budget.start(1, true, 0);
        queue.begin(1);
        int sent = 0;
        int processed = 0;
        for (int tick = 0; tick < 2000 && processed < 500; tick++) {
            budget.feedback(queue.feedback(tick * 50_000_000L, 0, 0), tick);
            for (int offer = 0; offer < 96 && sent < 500 && budget.canSend(65536, tick); offer++) {
                budget.recordSent(65536);
                queue.offer(sent++, 65536, tick * 50_000_000L);
            }
            assertTrue(queue.feedback(tick * 50_000_000L, 0, 0).pendingBytes() <= CubeStreamBudget.WINDOW_BYTES);
            Integer next = queue.poll();
            if (next != null) assertEquals(processed++, next.intValue());
        }
        assertEquals(500, processed);
    }
}
