package org.devt.higherworld.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CubeIoSchedulerTest {
    @TempDir
    Path directory;

    @Test
    void deduplicatesPrefetchAndReturnsCompletedPayload() throws Exception {
        CubePos pos = new CubePos(3, -300, 7);
        byte[] payload = "scheduled cube".getBytes(StandardCharsets.UTF_8);
        try (CubeStorage storage = new CubeStorage(directory)) {
            storage.write(pos, payload);
            try (CubeIoScheduler scheduler = new CubeIoScheduler(storage, 1)) {
                scheduler.prefetch(pos, 100);
                scheduler.prefetch(pos, 0);
                assertEquals(1, scheduler.pendingReadCount());

                CubeIoScheduler.ReadResult result = awaitReady(scheduler, pos);
                assertTrue(result.ready());
                assertArrayEquals(payload, result.payload().orElseThrow());
                assertEquals(0, scheduler.pendingReadCount());
            }
        }
    }

    @Test
    void distinguishesPendingFromACompletedMissingCube() throws Exception {
        CubePos pos = new CubePos(30, 40, 50);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler scheduler = new CubeIoScheduler(storage, 1)) {
            CubeIoScheduler.ReadResult result = awaitReady(scheduler, pos);
            assertTrue(result.ready());
            assertFalse(result.payload().isPresent());
        }
    }

    @Test
    void dropsReadAheadAfterItsWatcherTicketDisappears() throws Exception {
        CubePos retained = new CubePos(1, 2, 3);
        CubePos abandoned = new CubePos(4, 5, 6);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler scheduler = new CubeIoScheduler(storage, 1)) {
            scheduler.prefetch(retained, 1);
            scheduler.prefetch(abandoned, 2);
            scheduler.retainPrefetches(Set.of(retained));

            assertEquals(1, scheduler.pendingReadCount());
            assertTrue(awaitReady(scheduler, retained).ready());
        }
    }

    private static CubeIoScheduler.ReadResult awaitReady(CubeIoScheduler scheduler, CubePos pos)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        CubeIoScheduler.ReadResult result;
        do {
            result = scheduler.poll(pos, 0);
            if (result.ready()) {
                return result;
            }
            Thread.yield();
        } while (System.nanoTime() < deadline);
        return result;
    }
}
