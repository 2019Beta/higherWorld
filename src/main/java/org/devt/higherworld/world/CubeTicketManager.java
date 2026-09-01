package org.devt.higherworld.world;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import org.devt.higherworld.storage.CubePos;

/** Maintains reference-counted 3D tickets without rebuilding unrelated owners. */
final class CubeTicketManager {
    private final Map<Object, TicketPlacement> tickets = new HashMap<>();
    private final Map<CubePos, Map<Object, CubeTicket>> byCube = new HashMap<>();

    synchronized Set<CubePos> replace(CubeTicket ticket) {
        Set<CubePos> changed = removeInternal(ticket.key());
        Set<CubePos> positions = positions(ticket);
        tickets.put(ticket.key(), new TicketPlacement(ticket, positions));
        for (CubePos pos : positions) {
            byCube.computeIfAbsent(pos, ignored -> new HashMap<>()).put(ticket.key(), ticket);
        }
        changed.addAll(positions);
        return Set.copyOf(changed);
    }

    synchronized Set<CubePos> remove(Object key) {
        return Set.copyOf(removeInternal(key));
    }

    synchronized Set<CubePos> activePositions() {
        return Set.copyOf(byCube.keySet());
    }

    synchronized int priority(CubePos pos) {
        Map<Object, CubeTicket> owners = byCube.get(pos);
        return owners == null ? Integer.MAX_VALUE : owners.values().stream()
                .mapToInt(ticket -> positionalPriority(ticket, pos)).min().orElse(Integer.MAX_VALUE);
    }

    synchronized CubeStatus targetStatus(CubePos pos) {
        Map<Object, CubeTicket> owners = byCube.get(pos);
        return owners == null ? CubeStatus.EMPTY : owners.values().stream()
                .map(CubeTicket::targetStatus)
                .max(Comparator.naturalOrder())
                .orElse(CubeStatus.EMPTY);
    }

    synchronized boolean isActive(CubePos pos) {
        return byCube.containsKey(pos);
    }

    private Set<CubePos> removeInternal(Object key) {
        TicketPlacement previous = tickets.remove(key);
        if (previous == null) return new HashSet<>();
        Set<CubePos> changed = new HashSet<>(previous.positions());
        for (CubePos pos : previous.positions()) {
            Map<Object, CubeTicket> owners = byCube.get(pos);
            if (owners != null) {
                owners.remove(key);
                if (owners.isEmpty()) byCube.remove(pos);
            }
        }
        return changed;
    }

    private static Set<CubePos> positions(CubeTicket ticket) {
        Set<CubePos> positions = new HashSet<>(ticket.radius().volume());
        ticket.radius().forEach(ticket.center(), positions::add);
        return positions;
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

    private record TicketPlacement(CubeTicket ticket, Set<CubePos> positions) {}
}
