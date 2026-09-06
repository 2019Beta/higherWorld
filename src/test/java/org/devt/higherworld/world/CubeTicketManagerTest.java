package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.devt.higherworld.storage.CubePos;
import org.junit.jupiter.api.Test;

class CubeTicketManagerTest {
    @Test
    void stableDemandReusesImmutableSnapshotAndPriorityStillUpdates() {
        CubeTicketManager manager = new CubeTicketManager();
        CubePos center = new CubePos(0, -30, 0);
        CubeTicket ticket = ticket("player", center, CubeDependencyRadius.NONE, CubeStatus.FULL, 0);
        manager.replace(ticket);
        Map<CubePos, CubeStatus> first = manager.requiredStatuses();
        manager.replace(ticket);
        manager.remove("absent");
        assertSame(first, manager.requiredStatuses());
        manager.replace(ticket("player", center, CubeDependencyRadius.NONE, CubeStatus.FULL, 100));
        assertSame(first, manager.requiredStatuses());
        assertEquals(CubeTicketType.PLAYER.basePriority() + 100, manager.priority(center));
        assertThrows(UnsupportedOperationException.class, first::clear);
    }

    @Test
    void movingResizingAndDemotingTicketsReleasesOnlyObsoleteDemand() {
        CubeTicketManager manager = new CubeTicketManager();
        CubePos oldCenter = new CubePos(-10, -30, -10);
        CubePos moved = new CubePos(10, -40, 10);
        manager.replace(ticket("player", oldCenter, CubeDependencyRadius.NONE, CubeStatus.FULL, 0));
        Map<CubePos, CubeStatus> oldSnapshot = manager.requiredStatuses();
        manager.replace(ticket("player", moved, new CubeDependencyRadius(1, 0, 1), CubeStatus.FULL, 0));
        assertFalse(manager.requiredStatuses().containsKey(oldCenter));
        assertEquals(CubeStatus.TERRAIN, manager.requiredStatuses().get(new CubePos(13, -40, 10)));
        assertTrue(oldSnapshot.containsKey(oldCenter));
        manager.replace(ticket("player", moved, CubeDependencyRadius.NONE, CubeStatus.PAYLOAD, 0));
        assertEquals(CubeStatus.PAYLOAD, manager.requiredStatuses().get(moved));
        assertFalse(manager.requiredStatuses().containsKey(new CubePos(12, -40, 10)));
        manager.remove("player");
        assertTrue(manager.requiredStatuses().isEmpty());
    }

    @Test
    void removingOverlappingTicketRestoresSurvivingLowerStatus() {
        CubeTicketManager manager = new CubeTicketManager();
        CubePos center = new CubePos(-3, -40, 2);
        manager.replace(ticket("full", center, CubeDependencyRadius.NONE, CubeStatus.FULL, 0));
        manager.replace(ticket("payload", center, CubeDependencyRadius.NONE, CubeStatus.PAYLOAD, 0));
        assertEquals(CubeStatus.FULL, manager.requiredStatuses().get(center));
        manager.remove("full");
        assertEquals(CubeStatus.PAYLOAD, manager.requiredStatuses().get(center));
        assertEquals(27, manager.requiredStatuses().size());
    }

    @Test
    void cachedCuboidClosureMatchesIndependentPerRootDagAcrossTicketChanges() {
        CubeTicketManager manager = new CubeTicketManager();
        Map<Integer, CubeTicket> active = new HashMap<>();
        Random random = new Random(0x6c0beL);
        for (int change = 0; change < 80; change++) {
            int key = random.nextInt(4);
            if (random.nextInt(4) == 0) {
                active.remove(key);
                manager.remove(key);
            } else {
                CubeTicket ticket = ticket(key,
                        new CubePos(random.nextInt(7) - 3, -30 + random.nextInt(7), random.nextInt(7) - 3),
                        new CubeDependencyRadius(random.nextInt(2), random.nextInt(2), random.nextInt(2)),
                        CubeStatus.values()[random.nextInt(CubeStatus.values().length)], 0);
                active.put(key, ticket);
                manager.replace(ticket);
            }
            Map<CubePos, CubeStatus> expected = new HashMap<>();
            Set<Stage> visited = new HashSet<>();
            for (CubeTicket ticket : active.values()) {
                ticket.radius().forEach(ticket.center(),
                        pos -> visit(pos, ticket.targetStatus(), expected, visited));
            }
            assertEquals(expected, manager.requiredStatuses(), "ticket change " + change);
        }
    }

    private static void visit(CubePos pos, CubeStatus status,
            Map<CubePos, CubeStatus> required, Set<Stage> visited) {
        if (!visited.add(new Stage(pos, status))) return;
        required.merge(pos, status, (a, b) -> a.ordinal() >= b.ordinal() ? a : b);
        CubeStatus local = status.localPrerequisite();
        if (local != null) visit(pos, local, required, visited);
        CubeStatus neighbour = status.neighbourPrerequisite();
        if (neighbour != null) {
            status.dependencyRadius().forEach(pos, dependency -> visit(dependency, neighbour, required, visited));
        }
    }

    private record Stage(CubePos pos, CubeStatus status) {}

    private static CubeTicket ticket(Object key, CubePos center,
            CubeDependencyRadius radius, CubeStatus status, int priority) {
        return new CubeTicket(key, CubeTicketType.PLAYER, center, radius, status, priority);
    }
}
