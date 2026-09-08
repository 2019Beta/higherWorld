package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.devt.higherworld.storage.CubeIoScheduler;
import org.devt.higherworld.storage.CubePos;
import org.devt.higherworld.storage.CubeStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CubeMovementRegressionTest {
    @TempDir Path directory;

    @Test void movingWatcherRaisesRearPriorityAndCommitsFrontFirst() throws Exception {
        CubePos rear = new CubePos(0, -20, 0), front = new CubePos(9, -20, 0);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io)) {
            scheduler.retainPrefetches(Map.of(rear, 0, front, 405));
            CubeHolder old = scheduler.requestDependency(rear, CubeStatus.TERRAIN, 0);
            CubeHolder next = scheduler.requestDependency(front, CubeStatus.TERRAIN, 405);
            old.localStageFuture(CubeStatus.IO_READY).get(5, TimeUnit.SECONDS);
            next.localStageFuture(CubeStatus.IO_READY).get(5, TimeUnit.SECONDS);
            scheduler.retainPrefetches(Map.of(rear, 320, front, 5));
            assertEquals(320, scheduler.priority(rear));
            assertEquals(5, scheduler.priority(front));
            assertSame(next, scheduler.readyForCommit(1).getFirst());
        }
    }

    @Test void sharedDependencyDropsDepartedOwnersUrgency() {
        CubePriorityIndex index = new CubePriorityIndex();
        CubePos a = new CubePos(0, -20, 0), b = new CubePos(2, -20, 0);
        CubePos shared = new CubePos(1, -20, 0);
        index.replace("a", CubePriorityIndex.prefetch(a, 0));
        index.replace("b", CubePriorityIndex.prefetch(b, 50));
        assertEquals(1, index.snapshot().get(shared));
        index.replace("a", CubePriorityIndex.prefetch(a, 100));
        assertEquals(51, index.snapshot().get(shared));
        index.replace("b", Map.of());
        assertEquals(101, index.snapshot().get(shared));
        index.replace("a", Map.of());
        assertTrue(index.snapshot().isEmpty());
    }

    @Test void ticketMovementReplacesHistoricalRanksIncludingWideReads() throws Exception {
        CubePos root = new CubePos(0, -20, 0), wide = new CubePos(-10, -20, 0);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io)) {
            scheduler.replaceTicket(CubeTicket.playerSimulation("player", root, 12, 4));
            scheduler.requestDependency(root, CubeStatus.TERRAIN, 0);
            scheduler.registerFeatureTerrainDependenciesForTest(root, List.of(wide));
            assertEquals(scheduler.priority(root) + 1, scheduler.priority(wide));
            scheduler.replaceTicket(CubeTicket.playerSimulation("player", new CubePos(8, -20, 0), 12, 4));
            assertTrue(scheduler.priority(root) > 5);
            assertEquals(scheduler.priority(root) + 1, scheduler.priority(wide));
        }
    }

    @Test void unchangedGraphIsNotExpandedAgainButUpgradeAndRestartAre() throws Exception {
        CubePos root = new CubePos(0, -20, 0);
        try (CubeStorage storage = new CubeStorage(directory);
                CubeIoScheduler io = new CubeIoScheduler(storage);
                CubeTaskScheduler scheduler = new CubeTaskScheduler(io)) {
            CubeHolder holder = scheduler.requestDependency(root, CubeStatus.PAYLOAD, 20);
            long first = scheduler.graphExpansionCountForTest();
            for (int i = 0; i < 100; i++) scheduler.requestDependency(root, CubeStatus.PAYLOAD, 20);
            assertEquals(first, scheduler.graphExpansionCountForTest());
            scheduler.requestDependency(root, CubeStatus.FULL, 20);
            assertTrue(scheduler.graphExpansionCountForTest() > first);
            long upgraded = scheduler.graphExpansionCountForTest();
            scheduler.requestDependency(root, CubeStatus.FULL, 0);
            assertTrue(scheduler.graphExpansionCountForTest() > upgraded);
            holder.cancel();
            long beforeRestart = scheduler.graphExpansionCountForTest();
            scheduler.requestDependency(root, CubeStatus.PAYLOAD, 20);
            assertTrue(scheduler.graphExpansionCountForTest() > beforeRestart);
            assertEquals(CubeStatus.PAYLOAD, holder.target());
        }
    }

    @Test void ticketCuboidPrioritiesMatchExplicitDependencyWalk() {
        CubeTicket ticket = CubeTicket.player("player", new CubePos(3, -20, 7), 2, 1);
        Map<CubePos, Integer> expected = new HashMap<>();
        ticket.radius().forEach(ticket.center(), root -> {
            long dx = root.x() - ticket.center().x(), dy = root.y() - ticket.center().y();
            long dz = root.z() - ticket.center().z(), h = Math.max(Math.abs(dx), Math.abs(dz));
            walk(root, ticket.targetStatus(), (int) (ticket.effectivePriority() + h * h * 2 + dy * dy), expected);
        });
        assertEquals(expected, CubePriorityIndex.ticket(ticket));
    }

    private static void walk(CubePos root, CubeStatus target, int rank, Map<CubePos, Integer> ranks) {
        ranks.merge(root, rank, Math::min);
        for (CubeStatus stage : CubeStatus.values()) {
            if (stage.ordinal() > target.ordinal()) break;
            if (stage.neighbourPrerequisite() != null) stage.dependencyRadius().forEach(root,
                    pos -> walk(pos, stage.neighbourPrerequisite(), rank + 1, ranks));
        }
    }

    @Test void preparationRemainsBoundedWhileSendPressureIsActive() {
        CubeStreamBudget stream = new CubeStreamBudget();
        stream.start(7, true, 0);
        stream.feedback(new CubeStreamFeedbackPayload(7, 0, 0, 0, 0, 768, 0), 1);
        assertFalse(stream.canSend(0, 1));
        assertTrue(CubeWatchManager.canPrepare(511));
        assertFalse(CubeWatchManager.canPrepare(512));
        assertFalse(CubeWatchManager.canPrepare(513));
    }
}
