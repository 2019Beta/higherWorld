package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.devt.higherworld.storage.CubePos;
import org.junit.jupiter.api.Test;

class CubeSchedulingTest {
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
}
