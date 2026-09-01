package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.devt.higherworld.storage.CubeIoScheduler;
import org.devt.higherworld.storage.CubePos;
import org.devt.higherworld.storage.CubeStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CubeTaskSchedulerLifecycleTest {
    @TempDir
    Path directory;

    @Test
    void ticketClosureRetainsDependencyHaloUntilTicketRemoval() {
        CubePos center = new CubePos(12, -20, -7);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io)) {
            scheduler.replaceTicket(new CubeTicket(
                    "player", CubeTicketType.PLAYER, center,
                    CubeDependencyRadius.NONE, CubeStatus.FULL, 0));

            // The feature/light dependency halo is wider than the player's
            // sent set and must therefore be protected from cache eviction.
            assertTrue(scheduler.isRequired(center));
            assertTrue(scheduler.isRequired(new CubePos(center.x() + 1, center.y(), center.z())));
            assertTrue(scheduler.isRequired(new CubePos(center.x() + 2, center.y(), center.z())));

            scheduler.removeTicket("player");

            assertFalse(scheduler.isRequired(center));
            assertFalse(scheduler.isRequired(new CubePos(center.x() + 1, center.y(), center.z())));
        }
    }

    @Test
    void releasingUnticketedHolderCancelsItsLifecycle() {
        CubePos pos = new CubePos(1, -30, 4);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io)) {
            CubeHolder holder = scheduler.request(pos, CubeStatus.IO_READY, 0);
            assertFalse(scheduler.isRequired(pos));

            scheduler.release(pos);

            assertTrue(holder.fullFuture().isCompletedExceptionally());
            assertFalse(scheduler.isRequired(pos));
        }
    }
}
