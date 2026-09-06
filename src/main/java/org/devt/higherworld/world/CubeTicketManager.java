package org.devt.higherworld.world;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.devt.higherworld.storage.CubePos;

/** Maintains replaceable 3D tickets without pre-expanding their covered volumes. */
final class CubeTicketManager {
    /**
     * Tickets are keyed by owner only.  Expanding every ticket into a reverse
     * index made moving a large view distance allocate and mutate thousands of
     * map entries on every replacement.  Position queries are infrequent and
     * can scan this small set while holding the monitor.
     */
    private final Map<Object, CubeTicket> tickets = new HashMap<>();
    /** Immutable spatial demand, reused while watcher payload roots change. */
    private Map<CubePos, CubeStatus> requiredStatuses = Map.of();

    synchronized void replace(CubeTicket ticket) {
        CubeTicket previous = tickets.put(ticket.key(), ticket);
        if (previous == null || !previous.center().equals(ticket.center())
                || !previous.radius().equals(ticket.radius())
                || previous.targetStatus() != ticket.targetStatus()) {
            requiredStatuses = null;
        }
    }

    synchronized void remove(Object key) {
        if (tickets.remove(key) != null) requiredStatuses = null;
    }

    /** Ticket movement invalidates the closure; payload completion does not. */
    synchronized Map<CubePos, CubeStatus> requiredStatuses() {
        if (requiredStatuses == null) {
            Map<CubePos, CubeStatus> required = new HashMap<>();
            for (CubeTicket ticket : tickets.values()) {
                collectRequired(ticket.center(), ticket.radius(), ticket.targetStatus(), required);
            }
            requiredStatuses = Map.copyOf(required);
        }
        return requiredStatuses;
    }

    /** Expands cuboids through the decreasing stage DAG without starting IO. */
    static void collectRequired(
            CubePos center, CubeDependencyRadius radius, CubeStatus target,
            Map<CubePos, CubeStatus> required) {
        radius.forEach(center, pos -> required.merge(pos, target,
                (first, second) -> first.ordinal() >= second.ordinal() ? first : second));
        for (CubeStatus stage : CubeStatus.values()) {
            if (stage.ordinal() > target.ordinal()) break;
            CubeStatus neighbour = stage.neighbourPrerequisite();
            if (neighbour == null) continue;
            CubeDependencyRadius dependency = stage.dependencyRadius();
            CubeDependencyRadius expanded = new CubeDependencyRadius(
                    Math.addExact(radius.x(), dependency.x()),
                    Math.addExact(radius.y(), dependency.y()),
                    Math.addExact(radius.z(), dependency.z()));
            collectRequired(center, expanded, neighbour, required);
        }
    }

    synchronized Set<CubePos> activePositions() {
        Set<CubePos> positions = new HashSet<>();
        for (CubeTicket ticket : tickets.values()) {
            ticket.radius().forEach(ticket.center(), positions::add);
        }
        return Set.copyOf(positions);
    }

    /**
     * Returns a stable snapshot of the ticket roots.  The scheduler uses the
     * roots to derive the required dependency closure without iterating every
     * position in every ticket and recursively walking the same graph again.
     */
    synchronized List<CubeTicket> activeTickets() {
        return List.copyOf(tickets.values());
    }

    synchronized int priority(CubePos pos) {
        int best = Integer.MAX_VALUE;
        for (CubeTicket ticket : tickets.values()) {
            if (contains(ticket, pos)) {
                best = Math.min(best, positionalPriority(ticket, pos));
            }
        }
        return best;
    }

    synchronized CubeStatus targetStatus(CubePos pos) {
        CubeStatus best = CubeStatus.EMPTY;
        for (CubeTicket ticket : tickets.values()) {
            if (contains(ticket, pos) && ticket.targetStatus().ordinal() > best.ordinal()) {
                best = ticket.targetStatus();
            }
        }
        return best;
    }

    synchronized boolean isActive(CubePos pos) {
        for (CubeTicket ticket : tickets.values()) {
            if (contains(ticket, pos)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(CubeTicket ticket, CubePos pos) {
        CubePos center = ticket.center();
        CubeDependencyRadius radius = ticket.radius();
        return Math.abs((long) pos.x() - center.x()) <= radius.x()
                && Math.abs((long) pos.y() - center.y()) <= radius.y()
                && Math.abs((long) pos.z() - center.z()) <= radius.z();
    }

    private static int positionalPriority(CubeTicket ticket, CubePos pos) {
        long dx = Math.abs((long) pos.x() - ticket.center().x());
        long dy = Math.abs((long) pos.y() - ticket.center().y());
        long dz = Math.abs((long) pos.z() - ticket.center().z());
        long horizontal = Math.max(dx, dz);
        long distance = horizontal * horizontal * (ticket.radius().y() + 1L) + dy * dy;
        return (int) Math.min(Integer.MAX_VALUE,
                Math.max(0L, (long) ticket.effectivePriority() + distance));
    }

}
