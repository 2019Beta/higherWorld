package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Set;
import org.devt.higherworld.storage.CubeIoScheduler;
import org.devt.higherworld.storage.CubePos;
import org.devt.higherworld.storage.CubeStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CubeLoadingOptimizationTest {
    @TempDir Path directory;

    @Test
    void currentStoredPayloadDoesNotStartOrRetainATerrainHalo() throws Exception {
        CubePos pos = new CubePos(0, -20, 0);
        try (CubeStorage storage = new CubeStorage(directory)) {
            storage.write(pos, recordHeader(17));
            try (CubeIoScheduler io = new CubeIoScheduler(storage);
                    CubeTaskScheduler scheduler = new CubeTaskScheduler(io, 17)) {
                scheduler.retainPrefetches(Set.of(pos));
                CubeHolder holder = scheduler.request(pos, CubeStatus.PAYLOAD, 0);
                holder.ioFuture().join();
                scheduler.readyForCommit(256);
                assertEquals(1, scheduler.holderCount());
                assertFalse(scheduler.isRequired(new CubePos(1, -20, 0)));
                holder.advance(CubeStatus.TERRAIN);
                assertTrue(scheduler.readyForCommit(256).contains(holder));
                scheduler.retainPrefetches(Set.of());
                assertFalse(scheduler.isRequired(pos));
            }
        }
    }

    @Test
    void missingOrLegacyRecordsExpandAndRetainTheirGenerationHalo() throws Exception {
        for (int version : new int[] {-1, 16}) {
            CubePos pos = new CubePos(0, -20, 0);
            try (CubeStorage storage = new CubeStorage(directory.resolve("version-" + version))) {
                if (version >= 0) storage.write(pos, recordHeader(version));
                try (CubeIoScheduler io = new CubeIoScheduler(storage);
                        CubeTaskScheduler scheduler = new CubeTaskScheduler(io, 17)) {
                    scheduler.retainPrefetches(Set.of(pos));
                    CubeHolder holder = scheduler.request(pos, CubeStatus.PAYLOAD, 0);
                    holder.ioFuture().join();
                    // join may return before the completion callback has queued
                    // the server-thread graph expansion. Allow subsequent ticks.
                    long deadline = System.nanoTime() + 2_000_000_000L;
                    while (scheduler.holderCount() < 27 && System.nanoTime() < deadline) {
                        scheduler.readyForCommit(256);
                        Thread.yield();
                    }
                    assertEquals(27, scheduler.holderCount());
                    assertTrue(scheduler.isRequired(new CubePos(1, -20, 0)));
                    scheduler.retainPrefetches(Set.of(pos));
                    assertEquals(CubeStatus.TERRAIN,
                            scheduler.holder(new CubePos(1, -20, 0)).target());
                }
            }
        }
    }

    @Test
    void cancelledDeferredGraphCannotResurrectRemovedDemand() throws Exception {
        CubePos pos = new CubePos(0, -20, 0);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io, 17)) {
            scheduler.retainPrefetches(Set.of(pos));
            scheduler.request(pos, CubeStatus.PAYLOAD, 0);
            scheduler.retainPrefetches(Set.of());
            scheduler.readyForCommit(256);
            assertEquals(0, scheduler.holderCount());
            assertFalse(scheduler.isRequired(pos));
        }
    }

    @Test
    void dependencyCompletionWakesOwnerWithoutPollingUnrelatedProgress() throws Exception {
        CubePos pos = new CubePos(0, -20, 0);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io)) {
            CubeHolder owner = scheduler.holder(pos);
            owner.request(CubeStatus.FEATURES);
            owner.advance(CubeStatus.TERRAIN);
            CubePos blocked = new CubePos(-1, -21, -1);
            CubeStatus.FEATURES.dependencyRadius().forEach(pos, neighbour -> {
                if (!neighbour.equals(blocked)) scheduler.holder(neighbour).advance(CubeStatus.TERRAIN);
            });
            CubeHolder dependency = scheduler.holder(blocked);
            assertFalse(scheduler.readyForCommit(256).contains(owner));
            assertEquals(1, scheduler.dependencyWaitCountForTest());
            dependency.advance(CubeStatus.TERRAIN);
            assertTrue(scheduler.readyForCommit(256).contains(owner));
            assertEquals(0, scheduler.dependencyWaitCountForTest());
        }
    }

    /** Scheduler metadata only: decoding correctness is covered by codec tests. */
    private static byte[] recordHeader(int version) {
        return ByteBuffer.allocate(8).putInt(0x48574336).putInt(version).array();
    }
}
