package org.devt.higherworld.world;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CubeReadyQueueTest {
    private record Entry(int id, int rank) {}
    private static final Comparator<Entry> ORDER = Comparator.comparingInt(Entry::rank)
            .thenComparingInt(Entry::id);

    @Test void randomUpdatesAndRemovalsMatchReference() {
        var queue = new CubeReadyQueue<Integer, Entry>(Entry::id, ORDER);
        var reference = new HashMap<Integer, Entry>();
        var random = new Random(89123);
        for (int i = 0; i < 30000; i++) {
            int id = random.nextInt(300);
            switch (random.nextInt(3)) {
                case 0 -> {
                    var entry = new Entry(id, random.nextInt(1000));
                    reference.put(id, entry);
                    queue.offer(entry);
                }
                case 1 -> {
                    var old = reference.remove(id);
                    if (old != null) queue.remove(old);
                }
                default -> {
                    var expected = reference.values().stream().min(ORDER).orElse(null);
                    assertEquals(expected, queue.poll());
                    if (expected != null) reference.remove(expected.id());
                }
            }
            assertEquals(reference.size(), queue.size());
        }
    }

    @Test void staleRemovalCannotRemoveReplacement() {
        var queue = new CubeReadyQueue<Integer, Entry>(Entry::id, ORDER);
        var old = new Entry(1, 0);
        var next = new Entry(1, 99);
        queue.offer(old);
        queue.offer(next);
        queue.remove(old);
        assertSame(next, queue.poll());
        assertNull(queue.poll());
    }
}
