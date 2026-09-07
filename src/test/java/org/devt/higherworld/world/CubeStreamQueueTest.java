package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CubeStreamQueueTest {
    @Test
    void preservesDataLightUnloadOrderAndReportsBytesAndOldestAge() {
        CubeStreamQueue<String> queue = new CubeStreamQueue<>();
        queue.begin(4);
        queue.offer("data rev10", 100, 0);
        queue.offer("light rev11", 20, 10_000_000);
        queue.offer("unload rev12", 0, 20_000_000);
        assertEquals(120, queue.feedback(30_000_000, 0, 0).pendingBytes());
        assertEquals(30, queue.feedback(30_000_000, 0, 0).oldestMillis());
        assertEquals("data rev10", queue.poll());
        assertEquals(100, queue.feedback(30_000_000, 0, 0).processedBytes());
        assertEquals(20, queue.feedback(30_000_000, 0, 0).oldestMillis());
        assertEquals("light rev11", queue.poll());
        assertEquals("unload rev12", queue.poll());
        assertTrue(queue.isEmpty());
        assertEquals(0, queue.feedback(40_000_000, 0, 0).pendingBytes());
    }

    @Test
    void worldSwitchKeepsOldQueueOrderButDoesNotAcknowledgeOldBytesInNewStream() {
        CubeStreamQueue<String> queue = new CubeStreamQueue<>();
        queue.begin(1);
        queue.offer("old world", 100, 0);
        queue.begin(2);
        queue.offer("new world", 20, 1);
        assertEquals("old world", queue.poll());
        assertEquals(0, queue.feedback(2, 0, 0).processedBytes());
        assertEquals("new world", queue.poll());
        assertEquals(20, queue.feedback(2, 0, 0).processedBytes());
        queue.clear();
        assertEquals(0, queue.feedback(3, 0, 0).streamId());
    }
}
