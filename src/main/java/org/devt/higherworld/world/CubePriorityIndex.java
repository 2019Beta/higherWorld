package org.devt.higherworld.world;

import java.util.HashMap;
import java.util.Map;
import org.devt.higherworld.storage.CubePos;

/** Replaceable owner snapshots; removing an owner also removes its inherited urgency. */
final class CubePriorityIndex {
    private final Map<Object, Map<CubePos, Integer>> owners = new HashMap<>();
    private final Map<CubePos, java.util.TreeMap<Integer, Integer>> ranks = new HashMap<>();
    private final Map<CubePos, Integer> effective = new HashMap<>();
    private final Map<CubePos, Integer> previousEffective = new HashMap<>();

    void clear() {
        owners.clear();
        ranks.clear();
        effective.clear();
        previousEffective.clear();
    }

    void replace(Object owner, Map<CubePos, Integer> priorities) {
        Map<CubePos, Integer> next = Map.copyOf(priorities);
        Map<CubePos, Integer> previous = owners.getOrDefault(owner, Map.of());
        previous.forEach((pos, rank) -> {
            if (java.util.Objects.equals(next.get(pos), rank)) return;
            rememberPriority(pos);
            java.util.TreeMap<Integer, Integer> counts = ranks.get(pos);
            counts.compute(rank, (ignored, count) -> count == 1 ? null : count - 1);
            if (counts.isEmpty()) {
                ranks.remove(pos);
                effective.remove(pos);
            } else effective.put(pos, counts.firstKey());
        });
        next.forEach((pos, rank) -> {
            if (java.util.Objects.equals(previous.get(pos), rank)) return;
            rememberPriority(pos);
            java.util.TreeMap<Integer, Integer> counts = ranks.computeIfAbsent(
                    pos, ignored -> new java.util.TreeMap<>());
            counts.merge(rank, 1, Integer::sum);
            effective.put(pos, counts.firstKey());
        });
        if (next.isEmpty()) owners.remove(owner);
        else owners.put(owner, next);
    }

    Map<CubePos, Integer> snapshot() {
        return new HashMap<>(effective);
    }

    /** Null values explicitly remove an old effective rank. */
    Map<CubePos, Integer> drainChanges() {
        Map<CubePos, Integer> result = new HashMap<>();
        previousEffective.forEach((pos, previous) -> {
            Integer current = effective.get(pos);
            if (!java.util.Objects.equals(previous, current)) result.put(pos, current);
        });
        previousEffective.clear();
        return result;
    }

    private void rememberPriority(CubePos pos) {
        // putIfAbsent would overwrite a remembered null during a second edit.
        if (!previousEffective.containsKey(pos)) previousEffective.put(pos, effective.get(pos));
    }

    Integer effectivePriority(CubePos pos) {
        return effective.get(pos);
    }

    static Map<CubePos, Integer> prefetch(CubePos root, int priority) {
        Map<CubePos, Integer> result = new HashMap<>();
        CubeStatus.FEATURES.dependencyRadius().forEach(root,
                pos -> result.put(pos, increment(priority)));
        result.put(root, priority);
        return result;
    }

    static int increment(int priority) {
        return priority == Integer.MAX_VALUE ? priority : priority + 1;
    }

    static Map<CubePos, Integer> ticket(CubeTicket ticket) {
        Map<CubePos, Integer> result = new HashMap<>();
        collect(ticket, ticket.targetStatus(), CubeDependencyRadius.NONE, 0, result);
        return result;
    }

    // Expand stage cuboids, not one graph per root. The nearest root to the
    // ticket centre within this halo gives the minimum inherited distance.
    private static void collect(CubeTicket ticket, CubeStatus target,
            CubeDependencyRadius halo, int depth, Map<CubePos, Integer> result) {
        CubeDependencyRadius radius = ticket.radius();
        new CubeDependencyRadius(radius.x() + halo.x(), radius.y() + halo.y(),
                radius.z() + halo.z()).forEach(ticket.center(), pos -> {
            long dx = Math.max(0L, Math.abs((long) pos.x() - ticket.center().x()) - halo.x());
            long dy = Math.max(0L, Math.abs((long) pos.y() - ticket.center().y()) - halo.y());
            long dz = Math.max(0L, Math.abs((long) pos.z() - ticket.center().z()) - halo.z());
            long horizontal = Math.max(dx, dz);
            long rank = Math.max(0L, ticket.effectivePriority()
                    + horizontal * horizontal * (radius.y() + 1L) + dy * dy) + depth;
            result.merge(pos, (int) Math.min(Integer.MAX_VALUE, rank), Math::min);
        });
        for (CubeStatus stage : CubeStatus.values()) {
            if (stage.ordinal() > target.ordinal()) break;
            if (stage.neighbourPrerequisite() == null) continue;
            CubeDependencyRadius next = stage.dependencyRadius();
            collect(ticket, stage.neighbourPrerequisite(), new CubeDependencyRadius(
                    halo.x() + next.x(), halo.y() + next.y(), halo.z() + next.z()), depth + 1, result);
        }
    }
}
