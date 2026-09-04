package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;
import org.devt.higherworld.storage.CubeIoScheduler;
import org.devt.higherworld.storage.CubePos;
import org.devt.higherworld.storage.CubeStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CubeTaskSchedulerLifecycleTest {
    @TempDir
    Path directory;

    @Test
    void ticketClosureRetainsDependencyHaloUntilTicketRemoval() throws Exception {
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
    void largeTicketClosureDoesNotMaterializeHolders() throws Exception {
        CubePos center = new CubePos(12, -20, -7);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io)) {
            scheduler.replaceTicket(new CubeTicket(
                    "large", CubeTicketType.PLAYER, center,
                    new CubeDependencyRadius(32, 4, 32), CubeStatus.FULL, 0));

            assertEquals(0, scheduler.holderCount());
        }
    }

    @Test
    void fullTicketClosureExpandsToTwoDependencyCubes() throws Exception {
        CubePos center = new CubePos(12, -20, -7);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io)) {
            scheduler.replaceTicket(new CubeTicket(
                    "full", CubeTicketType.PLAYER, center,
                    CubeDependencyRadius.NONE, CubeStatus.FULL, 0));

            assertTrue(scheduler.isRequired(new CubePos(center.x() + 2, center.y(), center.z())));
            assertFalse(scheduler.isRequired(new CubePos(center.x() + 3, center.y(), center.z())));
        }
    }

    @Test
    void explicitRequestMaterializesHolderAfterTicketRefresh() throws Exception {
        CubePos center = new CubePos(12, -20, -7);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io)) {
            scheduler.replaceTicket(new CubeTicket(
                    "explicit", CubeTicketType.PLAYER, center,
                    new CubeDependencyRadius(32, 4, 32), CubeStatus.FULL, 0));
            assertEquals(0, scheduler.holderCount());

            scheduler.request(center, CubeStatus.FULL, 0);

            assertTrue(scheduler.holderCount() > 0);
        }
    }

    @Test
    void payloadFirstRequestDefersTheOuterLightingDependencyHalo() throws Exception {
        CubePos center = new CubePos(12, -20, -7);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io)) {
            CubeHolder payload = scheduler.request(center, CubeStatus.PAYLOAD, 0);

            assertEquals(CubeStatus.PAYLOAD, payload.target());
            assertEquals(27, scheduler.holderCount());

            scheduler.request(center, CubeStatus.FULL, 0);

            assertEquals(CubeStatus.FULL, payload.target());
            assertEquals(125, scheduler.holderCount());
        }
    }

    @Test
    void retainingWatcherPayloadKeepsItsDependencyClosureWithoutATicket() throws Exception {
        CubePos center = new CubePos(12, -20, -7);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io)) {
            CubeHolder payload = scheduler.request(center, CubeStatus.PAYLOAD, 0);

            scheduler.retainPrefetches(Set.of(center));

            assertTrue(scheduler.isRequired(center));
            assertTrue(scheduler.isRequired(new CubePos(center.x() + 1, center.y(), center.z())));
            assertFalse(payload.ioFuture().isCancelled());

            scheduler.retainPrefetches(Set.of());

            assertFalse(scheduler.isRequired(center));
            assertEquals(0, scheduler.holderCount());
        }
    }

    @Test
    void readyCommitSkipsHighPriorityStageWhoseDependenciesAreBlocked() throws Exception {
        CubePos blockedHighPriorityPos = new CubePos(100, -20, 100);
        CubePos lowPriorityTerrainPos = new CubePos(-100, -20, -100);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io)) {
            scheduler.replaceTicket(new CubeTicket(
                    "blocked-high", CubeTicketType.COLLISION, blockedHighPriorityPos,
                    CubeDependencyRadius.NONE, CubeStatus.FULL, 0));
            scheduler.replaceTicket(new CubeTicket(
                    "terrain-low", CubeTicketType.FORCED, lowPriorityTerrainPos,
                    CubeDependencyRadius.NONE, CubeStatus.TERRAIN, 100));

            CubeHolder blockedHighPriority = scheduler.holder(blockedHighPriorityPos);
            blockedHighPriority.request(CubeStatus.FULL);
            blockedHighPriority.advance(CubeStatus.FEATURES);

            CubeHolder lowPriorityTerrain = scheduler.holder(lowPriorityTerrainPos);
            lowPriorityTerrain.request(CubeStatus.TERRAIN);
            lowPriorityTerrain.advance(CubeStatus.IO_READY);

            assertSame(lowPriorityTerrain, scheduler.readyForCommit(1).get(0));
        }
    }

    @Test
    void readyCommitDoesNotLetDistantIoReadyWorkStarveNearbyFeatures() throws Exception {
        CubePos center = new CubePos(0, -20, 0);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io)) {
            scheduler.replaceTicket(new CubeTicket(
                    "player", CubeTicketType.PLAYER, center,
                    new CubeDependencyRadius(32, 4, 32), CubeStatus.FULL, 0));

            CubeHolder nearbyFeatures = scheduler.holder(center);
            nearbyFeatures.request(CubeStatus.FULL);
            CubeStatus.LIGHT.dependencyRadius().forEach(center, pos -> {
                CubeHolder dependency = scheduler.holder(pos);
                dependency.request(CubeStatus.FULL);
                dependency.advance(CubeStatus.FEATURES);
            });

            // Model a continuing stream of newly IO-ready cubes farther from
            // the player. The old status-first ordering selected every one of
            // these ahead of the nearby cube, regardless of ticket distance.
            for (int distance = 8; distance <= 24; distance++) {
                CubeHolder distant = scheduler.holder(new CubePos(distance, -20, 0));
                distant.request(CubeStatus.FULL);
                distant.advance(CubeStatus.IO_READY);
            }

            assertSame(nearbyFeatures, scheduler.readyForCommit(1).get(0));
        }
    }

    @Test
    void releasingUnticketedHolderCancelsItsLifecycle() throws Exception {
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
