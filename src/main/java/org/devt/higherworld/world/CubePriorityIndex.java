package org.devt.higherworld.world;

import java.util.HashMap;
import java.util.Map;
import org.devt.higherworld.storage.CubePos;

/** Replaceable owner snapshots; removing an owner also removes its inherited urgency. */
final class CubePriorityIndex {
    private final Map<Object, Map<CubePos, Integer>> owners = new HashMap<>();

    void clear() { owners.clear(); }

    void replace(Object owner, Map<CubePos, Integer> priorities) {
        if (priorities.isEmpty()) owners.remove(owner);
        else owners.put(owner, Map.copyOf(priorities));
    }

    Map<CubePos, Integer> snapshot() {
        Map<CubePos, Integer> result = new HashMap<>();
        owners.values().forEach(values -> values.forEach((pos, rank) -> result.merge(pos, rank, Math::min)));
        return result;
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
