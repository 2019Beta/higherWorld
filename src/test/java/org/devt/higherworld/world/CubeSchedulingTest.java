package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.devt.higherworld.storage.CubePos;
import org.devt.higherworld.storage.CubeIoScheduler;
import org.devt.higherworld.storage.CubeStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CubeSchedulingTest {
    @TempDir
    Path directory;

    @Test
    void dependencyRadiusIsAnisotropic() {
        CubeDependencyRadius radius = new CubeDependencyRadius(2, 0, 1);
        java.util.HashSet<CubePos> positions = new java.util.HashSet<>();
        radius.forEach(new CubePos(10, 20, 30), positions::add);

        assertEquals(15, radius.volume());
        assertEquals(15, positions.size());
        assertTrue(positions.stream().allMatch(pos -> pos.y() == 20));
        assertThrows(IllegalArgumentException.class, () -> new CubeDependencyRadius(1, -1, 1));
    }

    @Test
    void ticketReplacementAndReferenceCountingKeepSharedCubes() {
        CubeTicketManager manager = new CubeTicketManager();
        CubePos center = new CubePos(0, -10, 0);
        manager.replace(new CubeTicket("player-a", CubeTicketType.PLAYER, center,
                new CubeDependencyRadius(1, 0, 0), CubeStatus.FULL, 5));
        manager.replace(new CubeTicket("portal", CubeTicketType.PORTAL, center,
                CubeDependencyRadius.NONE, CubeStatus.TERRAIN, 0));

        assertEquals(3, manager.activePositions().size());
        assertEquals(CubeStatus.FULL, manager.targetStatus(center));
        manager.remove("player-a");
        assertTrue(manager.isActive(center));
        assertEquals(Set.of(center), manager.activePositions());
        assertEquals(CubeStatus.TERRAIN, manager.targetStatus(center));
        manager.remove("portal");
        assertFalse(manager.isActive(center));
    }

    @Test
    void spatialLocksSerializeOverlappingCommitRegions() throws Exception {
        CubeSpatialLock locks = new CubeSpatialLock(64);
        CubePos center = new CubePos(4, -20, 9);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean secondEnteredEarly = new AtomicBoolean();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                try (var ignored = locks.lock(center, new CubeDependencyRadius(1, 1, 1))) {
                    firstEntered.countDown();
                    releaseFirst.await(5, TimeUnit.SECONDS);
                }
                return null;
            });
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> {
                try (var ignored = locks.lock(center, CubeDependencyRadius.NONE)) {
                    secondEnteredEarly.set(releaseFirst.getCount() != 0);
                }
                return null;
            });
            Thread.yield();
            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }
        assertFalse(secondEnteredEarly.get());
    }

    @Test
    void lifecycleFuturesAdvanceIndependentlyAndRestartAfterCancellation() {
        CubeHolder holder = new CubeHolder(new CubePos(1, 2, 3));
        holder.request(CubeStatus.FULL);
        CompletableFuture<?> firstFull = holder.fullFuture();

        holder.advance(CubeStatus.TERRAIN);
        assertTrue(holder.terrainFuture().isDone());
        assertFalse(holder.featureFuture().isDone());
        assertFalse(holder.lightFuture().isDone());
        assertFalse(firstFull.isDone());
        CompletableFuture<Void> save = new CompletableFuture<>();
        holder.trackSave(save);
        assertEquals(save, holder.saveFuture());

        long firstEpoch = holder.epoch();
        holder.cancel();
        assertTrue(firstFull.isCompletedExceptionally());
        assertFalse(save.isCancelled());
        holder.request(CubeStatus.FEATURES);

        assertEquals(firstEpoch + 1L, holder.epoch());
        assertEquals(CubeStatus.EMPTY, holder.status());
        assertNotSame(firstFull, holder.fullFuture());
        assertFalse(holder.terrainFuture().isDone());
    }

    @Test
    void staleIoCompletionCannotAdvanceRestartedLifecycle() {
        CubeHolder holder = new CubeHolder(new CubePos(4, 5, 6));
        holder.request(CubeStatus.IO_READY);
        CompletableFuture<Optional<byte[]>> oldIo = new CompletableFuture<>();
        holder.startIo(() -> oldIo);
        holder.cancel();
        holder.request(CubeStatus.IO_READY);

        assertEquals(CubeStatus.EMPTY, holder.status());
        holder.startIo(() -> CompletableFuture.completedFuture(Optional.empty()));
        assertEquals(CubeStatus.IO_READY, holder.status());
    }

    @Test
    void failedLifecycleStaysTerminalUntilCancellation() {
        CubeHolder holder = new CubeHolder(new CubePos(7, 8, 9));
        holder.request(CubeStatus.FULL);
        long failedEpoch = holder.epoch();
        holder.fail(new IllegalStateException("generation failed"));

        assertTrue(holder.failed());
        assertTrue(holder.fullFuture().isCompletedExceptionally());
        holder.request(CubeStatus.TERRAIN);
        assertEquals(failedEpoch, holder.epoch());
        assertTrue(holder.failed());

        holder.cancel();
        holder.request(CubeStatus.TERRAIN);
        assertEquals(failedEpoch + 1L, holder.epoch());
        assertFalse(holder.failed());
    }

    @Test
    void featureAndLightStagesWaitForLocalAndNeighbourPrerequisites() throws Exception {
        CubePos center = new CubePos(0, -10, 0);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io)) {
            CubeHolder holder = scheduler.request(center, CubeStatus.FULL, 0);
            holder.ioFuture().join();
            CompletableFuture<Void> features = scheduler.dependenciesFuture(
                    holder, CubeStatus.FEATURES, 0);
            assertFalse(features.isDone());
            holder.advance(CubeStatus.TERRAIN);
            assertFalse(features.isDone());
            CubeDependencyRadius radius = CubeStatus.FEATURES.dependencyRadius();
            radius.forEach(center, pos -> scheduler.holder(pos).advance(CubeStatus.TERRAIN));
            features.join();
            assertTrue(features.isDone());

            CompletableFuture<Void> light = scheduler.dependenciesFuture(holder, CubeStatus.LIGHT, 0);
            assertFalse(light.isDone());
            CubeStatus.LIGHT.dependencyRadius().forEach(
                    center, pos -> scheduler.holder(pos).advance(CubeStatus.FEATURES));
            light.join();
            assertTrue(light.isDone());
            assertEquals(CubeStatus.FEATURES,
                    scheduler.holder(new CubePos(1, -10, 0)).target());
        }
    }

    @Test
    void statusMetadataUsesStrictlyLowerNeighbourStages() {
        assertEquals(CubeStatus.IO_READY, CubeStatus.TERRAIN.localPrerequisite());
        assertEquals(CubeStatus.TERRAIN, CubeStatus.FEATURES.localPrerequisite());
        assertEquals(CubeStatus.FEATURES, CubeStatus.LIGHT.localPrerequisite());
        assertEquals(CubeStatus.TERRAIN, CubeStatus.FEATURES.neighbourPrerequisite());
        assertEquals(CubeStatus.FEATURES, CubeStatus.LIGHT.neighbourPrerequisite());
        assertTrue(CubeStatus.FEATURES.neighbourPrerequisite().ordinal()
                < CubeStatus.FEATURES.ordinal());
        assertTrue(CubeStatus.LIGHT.neighbourPrerequisite().ordinal()
                < CubeStatus.LIGHT.ordinal());
        assertEquals(CubeDependencyRadius.NONE, CubeStatus.TERRAIN.dependencyRadius());
    }
}
