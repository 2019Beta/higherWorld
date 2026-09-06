package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
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
    void unchangedPrefetchRootsStillReconcileAdHocRequestsAndTicketChanges() throws Exception {
        CubePos root = new CubePos(0, -20, 0);
        CubePos transientPos = new CubePos(100, -20, 100);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io)) {
            scheduler.retainPrefetches(Set.of(root));
            CubeHolder transientHolder = scheduler.holder(transientPos);
            transientHolder.request(CubeStatus.TERRAIN);
            scheduler.retainPrefetches(new java.util.HashSet<>(Set.of(root)));
            assertFalse(scheduler.isRequired(transientPos));
            assertEquals(CubeStatus.EMPTY, transientHolder.target());
            assertTrue(scheduler.isRequired(new CubePos(1, -20, 0)));
            scheduler.replaceTicket(new CubeTicket("test", CubeTicketType.PLAYER, transientPos,
                    CubeDependencyRadius.NONE, CubeStatus.FULL, 0));
            scheduler.retainPrefetches(Set.of(root));
            assertTrue(scheduler.isRequired(transientPos));
            scheduler.removeTicket("test");
            assertFalse(scheduler.isRequired(transientPos));
            scheduler.retainPrefetches(Set.of());
            assertFalse(scheduler.isRequired(root));
            assertFalse(scheduler.isRequired(new CubePos(1, -20, 0)));
        }
    }

    @Test
    void tickingFrontierTracksFullPromotionDowngradeFailureAndCancellation() throws Exception {
        CubePos pos = new CubePos(0, -20, 0);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io)) {
            CubeHolder holder = scheduler.holder(pos);
            holder.request(CubeStatus.FULL);
            holder.advance(CubeStatus.PAYLOAD);
            assertFalse(scheduler.fullTickingHolders().iterator().hasNext());
            holder.complete(null);
            assertSame(holder, scheduler.fullTickingHolders().iterator().next());
            holder.lowerTarget(CubeStatus.PAYLOAD);
            assertFalse(scheduler.fullTickingHolders().iterator().hasNext());
            holder.request(CubeStatus.FULL);
            assertSame(holder, scheduler.fullTickingHolders().iterator().next());
            holder.fail(new IllegalStateException("test"));
            assertFalse(scheduler.fullTickingHolders().iterator().hasNext());
            holder.cancel();
            holder.request(CubeStatus.FULL);
            holder.complete(null);
            assertTrue(scheduler.fullTickingHolders().iterator().hasNext());
            scheduler.release(pos);
            assertFalse(scheduler.fullTickingHolders().iterator().hasNext());
            assertEquals(0, scheduler.holderCount());
        }
    }

    @Test
    void cachedTicketClosureDoesNotRetainFinishedStreamingRoots() throws Exception {
        CubePos center = new CubePos(12, -20, -7);
        CubePos streaming = new CubePos(100, -40, 100);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io)) {
            scheduler.replaceTicket(CubeTicket.playerSimulation("player", center, 1, 0));
            scheduler.retainPrefetches(Set.of(streaming));
            assertTrue(scheduler.isRequired(streaming));
            assertTrue(scheduler.isRequired(new CubePos(101, -40, 100)));

            scheduler.retainPrefetches(Set.of());
            assertFalse(scheduler.isRequired(streaming));
            assertFalse(scheduler.isRequired(new CubePos(101, -40, 100)));
            assertTrue(scheduler.isRequired(center));
            assertTrue(scheduler.isRequired(new CubePos(15, -20, -7)));
            assertEquals(0, scheduler.holderCount());

            scheduler.removeTicket(CubeTicket.playerSimulationKey("player"));
            assertFalse(scheduler.isRequired(center));
        }
    }

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
    void simulationTicketPromotesRootWithoutUpgradingItsRecursiveHalo() throws Exception {
        CubePos center = new CubePos(12, -20, -7);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io)) {
            scheduler.replaceTicket(CubeTicket.playerSimulation("player", center, 1, 0));

            CubeHolder root = scheduler.request(center, CubeStatus.PAYLOAD, 0);

            assertEquals(CubeStatus.FULL, root.target());
            // The adjacent cube is reached as a LIGHT prerequisite.  It must
            // stop at FEATURES; upgrading it to FULL would recursively build
            // the whole lighting graph for every streaming root in range.
            assertEquals(CubeStatus.FEATURES,
                    scheduler.holder(new CubePos(center.x() + 1, center.y(), center.z())).target());
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
            blockedHighPriority.advance(CubeStatus.TERRAIN);

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
    void repeatedRequeueKeepsOneReadyEntryPerHolder() throws Exception {
        CubePos pos = new CubePos(0, -20, 0);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io)) {
            CubeHolder holder = scheduler.holder(pos);
            holder.request(CubeStatus.TERRAIN);
            holder.advance(CubeStatus.IO_READY);

            for (int index = 0; index < 32; index++) scheduler.requeue(holder);

            assertSame(holder, scheduler.readyForCommit(1).get(0));
            assertTrue(scheduler.readyForCommit(32).isEmpty());
        }
    }

    @Test
    void staleReadySnapshotsAreCompactedAgainstLiveEntries() throws Exception {
        CubePos pos = new CubePos(3, -20, -4);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io)) {
            CubeHolder holder = scheduler.holder(pos);
            for (int index = 0; index < 1_200; index++) {
                holder.request(CubeStatus.TERRAIN);
                holder.advance(CubeStatus.IO_READY);
                holder.cancel();
            }
            holder.request(CubeStatus.TERRAIN);
            holder.advance(CubeStatus.IO_READY);

            assertTrue(scheduler.readyQueueSizeForTest()
                    <= 4L * scheduler.queuedReadySizeForTest() + 1024L);
            assertSame(holder, scheduler.readyForCommit(1).get(0));
            assertEquals(0, scheduler.queuedReadySizeForTest());
            assertTrue(scheduler.readyQueueSizeForTest() <= 1024);
        }
    }

    @Test
    void refreshingPriorityEpochDoesNotStarveLowPriorityReadyWork() throws Exception {
        CubePos center = new CubePos(0, -20, 0);
        CubePos lowPriorityPos = new CubePos(100, -20, 100);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io)) {
            scheduler.replaceTicket(new CubeTicket(
                    "epoch-high", CubeTicketType.COLLISION, center,
                    new CubeDependencyRadius(8, 0, 8), CubeStatus.PAYLOAD, 0));
            scheduler.replaceTicket(new CubeTicket(
                    "epoch-low", CubeTicketType.FORCED, lowPriorityPos,
                    CubeDependencyRadius.NONE, CubeStatus.TERRAIN, 100_000));

            for (int x = -8; x < 8; x++) {
                for (int z = -8; z < 8; z++) {
                    CubeHolder blocked = scheduler.holder(new CubePos(x, -20, z));
                    blocked.request(CubeStatus.PAYLOAD);
                    blocked.advance(CubeStatus.TERRAIN);
                }
            }
            CubeHolder lowPriority = scheduler.holder(lowPriorityPos);
            lowPriority.request(CubeStatus.TERRAIN);
            lowPriority.advance(CubeStatus.IO_READY);

            // Target refresh changes the ordering metadata without changing
            // any holder lifecycle state. The stale entries must be refreshed
            // and scanned in the same bounded pass.
            scheduler.retainPrefetches(Set.of());

            assertSame(lowPriority, scheduler.readyForCommit(1).get(0));
        }
    }

    @Test
    void cancellingAndRestartingAHolderSkipsItsOldReadySnapshots() throws Exception {
        CubePos pos = new CubePos(2, -20, -3);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io)) {
            CubeHolder holder = scheduler.holder(pos);
            holder.request(CubeStatus.TERRAIN);
            holder.advance(CubeStatus.IO_READY);

            holder.cancel();
            holder.request(CubeStatus.TERRAIN);
            holder.advance(CubeStatus.IO_READY);

            // The cancelled lifecycle left physical queue records behind. A
            // restart must still be woken by its current identity-owned entry.
            assertSame(holder, scheduler.readyForCommit(1).get(0));
        }
    }

    @Test
    void featureTerrainPollingDoesNotReRequestAnExistingTerrainHolder() throws Exception {
        CubePos dependency = new CubePos(4, -20, 7);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io)) {
            CubeHolder holder = scheduler.requestDependency(dependency, CubeStatus.TERRAIN, 50);
            assertEquals(50, scheduler.priority(dependency));

            // A blocked FEATURES owner can inspect this same terrain holder on
            // every commit slice. The hot path must not repeat requestGraph or
            // lower the request priority on each poll.
            for (int index = 0; index < 2_048; index++) {
                assertSame(holder, scheduler.featureTerrainDependencyForTest(dependency, 1));
            }

            assertEquals(0L, scheduler.featureTerrainRequestCountForTest());
            assertEquals(50, scheduler.priority(dependency));
            assertEquals(1, scheduler.holderCount());
        }
    }

    @Test
    void readyCommitSkipsBlockedFeatureHalosBeyondItsBatchLimit() throws Exception {
        CubePos center = new CubePos(0, -20, 0);
        CubePos haloPos = new CubePos(100, -20, 100);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io)) {
            scheduler.replaceTicket(new CubeTicket(
                    "feature-owners", CubeTicketType.COLLISION, center,
                    new CubeDependencyRadius(8, 0, 8), CubeStatus.PAYLOAD, 0));
            scheduler.replaceTicket(new CubeTicket(
                    "feature-halo", CubeTicketType.COLLISION, haloPos,
                    CubeDependencyRadius.NONE, CubeStatus.TERRAIN, 100_000));

            CubeHolder halo = scheduler.holder(haloPos);
            halo.advance(CubeStatus.IO_READY);
            halo.request(CubeStatus.TERRAIN);

            int ownerCount = 0;
            for (int x = -8; x < 8; x++) {
                for (int z = -8; z < 8; z++) {
                    CubePos ownerPos = new CubePos(x, -20, z);
                    CubeHolder owner = scheduler.holder(ownerPos);
                    owner.advance(CubeStatus.TERRAIN);
                    owner.request(CubeStatus.PAYLOAD);

                    for (int offsetY = -1; offsetY <= 1; offsetY++) {
                        for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                                CubeHolder dependency = scheduler.holder(new CubePos(
                                        x + offsetX, -20 + offsetY, z + offsetZ));
                                dependency.advance(CubeStatus.TERRAIN);
                                dependency.request(CubeStatus.TERRAIN);
                            }
                        }
                    }
                    // This simulates the wider list already registered by a
                    // previous feature poll. The shared halo is still IO_READY.
                    scheduler.registerFeatureTerrainDependenciesForTest(
                            ownerPos, List.of(haloPos));
                    ownerCount++;
                }
            }
            assertEquals(256, ownerCount);

            List<CubeHolder> ready = scheduler.readyForCommit(256);

            // The old predicate returned all 256 owners and repeatedly
            // requeued them from tryAdvance(), starving this low-priority halo.
            assertEquals(1, ready.size());
            assertSame(halo, ready.get(0));

            halo.advance(CubeStatus.TERRAIN);

            List<CubeHolder> nextReady = scheduler.readyForCommit(1);
            assertEquals(1, nextReady.size());
            assertTrue(nextReady.get(0) != halo);
            assertEquals(CubeStatus.TERRAIN, nextReady.get(0).status());
            assertEquals(CubeStatus.PAYLOAD, nextReady.get(0).target());
        }
    }

    @Test
    void blockedHoldersWaitOutsideTheReadyQueueUntilADependencyAdvances() throws Exception {
        CubePos haloPos = new CubePos(100, -20, 100);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io)) {
            scheduler.replaceTicket(new CubeTicket(
                    "feature-owners", CubeTicketType.COLLISION, new CubePos(0, -20, 0),
                    new CubeDependencyRadius(8, 0, 8), CubeStatus.PAYLOAD, 0));
            scheduler.replaceTicket(new CubeTicket(
                    "feature-halo", CubeTicketType.COLLISION, haloPos,
                    CubeDependencyRadius.NONE, CubeStatus.TERRAIN, 100_000));

            CubeHolder halo = scheduler.holder(haloPos);
            halo.advance(CubeStatus.IO_READY);
            halo.request(CubeStatus.TERRAIN);

            for (int x = -8; x < 8; x++) {
                for (int z = -8; z < 8; z++) {
                    CubePos ownerPos = new CubePos(x, -20, z);
                    CubeHolder owner = scheduler.holder(ownerPos);
                    owner.advance(CubeStatus.TERRAIN);
                    owner.request(CubeStatus.PAYLOAD);
                    for (int offsetY = -1; offsetY <= 1; offsetY++) {
                        for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                                CubeHolder dependency = scheduler.holder(new CubePos(
                                        x + offsetX, -20 + offsetY, z + offsetZ));
                                dependency.advance(CubeStatus.TERRAIN);
                                dependency.request(CubeStatus.TERRAIN);
                            }
                        }
                    }
                    scheduler.registerFeatureTerrainDependenciesForTest(
                            ownerPos, List.of(haloPos));
                }
            }

            List<CubeHolder> ready = scheduler.readyForCommit(256);
            assertEquals(1, ready.size());
            assertSame(halo, ready.get(0));

            // Blocked owners must stay parked in the waiting set instead of
            // bouncing through the physical queue: repeated scans leave the
            // queue empty rather than re-offering thousands of stale entries.
            for (int scan = 0; scan < 8; scan++) {
                assertTrue(scheduler.readyForCommit(256).isEmpty());
            }
            assertTrue(scheduler.readyQueueSizeForTest()
                    <= 4L * scheduler.queuedReadySizeForTest() + 1024L);

            // A dependency advance wakes an owner within the next scan.
            halo.advance(CubeStatus.TERRAIN);
            List<CubeHolder> woken = scheduler.readyForCommit(1);
            assertEquals(1, woken.size());
            assertEquals(CubeStatus.TERRAIN, woken.get(0).status());
            assertEquals(CubeStatus.PAYLOAD, woken.get(0).target());
        }
    }

    @Test
    void newlyRegisteredFeatureHaloIsRequiredUntilItsOwnerRefreshes() throws Exception {
        CubePos firstOwner = new CubePos(0, -20, 0);
        CubePos secondOwner = new CubePos(2, -20, 0);
        CubePos dependency = new CubePos(100, -20, 100);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io)) {
            scheduler.replaceTicket(new CubeTicket(
                    "feature-owner-one", CubeTicketType.COLLISION, firstOwner,
                    CubeDependencyRadius.NONE, CubeStatus.FEATURES, 0));
            scheduler.replaceTicket(new CubeTicket(
                    "feature-owner-two", CubeTicketType.COLLISION, secondOwner,
                    CubeDependencyRadius.NONE, CubeStatus.FEATURES, 0));
            scheduler.registerFeatureTerrainDependenciesForTest(firstOwner, List.of(dependency));
            scheduler.registerFeatureTerrainDependenciesForTest(secondOwner, List.of(dependency));

            assertTrue(scheduler.isRequired(dependency));

            scheduler.removeTicket("feature-owner-one");

            assertTrue(scheduler.isRequired(dependency));

            scheduler.removeTicket("feature-owner-two");

            assertFalse(scheduler.isRequired(dependency));
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
