package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.devt.higherworld.storage.CubePos;
import org.junit.jupiter.api.Test;

class CubeRenderQueueTest {
    @Test
    void missingCameraDrainsEachEntryExactlyOnce() {
        CubeRenderQueue queue = new CubeRenderQueue();
        CubePos first = new CubePos(0, 0, 0);
        CubePos second = new CubePos(1, 0, 0);
        queue.offer(first);
        queue.offer(second);
        queue.beginRound(null, 8);
        assertEquals(first, queue.poll());
        assertEquals(second, queue.poll());
        assertTrue(queue.isEmpty());
    }

    @Test
    void deduplicatesWithoutRefreshingAgeAndReservesFifoShare() {
        CubeRenderQueue queue = new CubeRenderQueue();
        CubePos old = new CubePos(100, 0, 0);
        CubePos near = new CubePos(1, 0, 0);

        assertTrue(queue.offer(old));
        assertTrue(queue.offer(near));
        assertFalse(queue.offer(old));

        queue.beginRound(new CubePos(0, 0, 0), 8);
        assertEquals(old, queue.poll());
        assertEquals(near, queue.poll());
        assertTrue(queue.isEmpty());
    }

    @Test
    void cameraMovementRebuildsDistanceIndexWithoutChangingFifoOrder() {
        CubeRenderQueue queue = new CubeRenderQueue();
        CubePos old = new CubePos(2, 0, 0);
        CubePos nearNewCenter = new CubePos(8, 0, 0);
        CubePos nearOldCenter = new CubePos(-4, 0, 0);
        queue.offer(old);
        queue.offer(nearNewCenter);
        queue.offer(nearOldCenter);

        queue.beginRound(new CubePos(0, 0, 0), 8);
        queue.beginRound(new CubePos(8, 0, 0), 8);
        assertEquals(old, queue.poll());
        assertEquals(nearNewCenter, queue.poll());
    }

    @Test
    void largeCoordinateDifferencesUseSaturatingLongDistance() {
        CubeRenderQueue queue = new CubeRenderQueue();
        CubePos old = new CubePos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        CubePos near = new CubePos(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        queue.offer(old);
        queue.offer(near);

        queue.beginRound(new CubePos(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE), 8);
        assertEquals(old, queue.poll());
        assertEquals(near, queue.poll());
    }
}
