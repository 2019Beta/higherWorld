package org.devt.higherworld.world;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.devt.higherworld.storage.CubePos;

/** Server-thread root updates, with concurrent read-only position queries. */
final class CubeDemandIndex {
    private static final CubeStatus[] STAGES = CubeStatus.values();
    private final Map<Object, Map<CubePos, CubeStatus>> roots = new HashMap<>();
    private final Map<CubePos, int[]> counts = new HashMap<>();
    private final Map<CubePos, CubeStatus> targets = new ConcurrentHashMap<>();

    Set<CubePos> replace(Object root, Map<CubePos, CubeStatus> next) {
        Map<CubePos, CubeStatus> previous = roots.getOrDefault(root, Map.of());
        if (previous.equals(next)) return Set.of();
        if (next.isEmpty()) roots.remove(root);
        else roots.put(root, Map.copyOf(next));
        Set<CubePos> affected = new HashSet<>(previous.keySet());
        affected.addAll(next.keySet());
        Set<CubePos> changed = new HashSet<>();
        for (CubePos pos : affected) {
            CubeStatus oldContribution = previous.get(pos);
            CubeStatus newContribution = next.get(pos);
            if (oldContribution == newContribution) continue;
            CubeStatus oldTarget = target(pos);
            int[] refs = counts.computeIfAbsent(pos, ignored -> new int[STAGES.length]);
            if (oldContribution != null && oldContribution != CubeStatus.EMPTY) refs[oldContribution.ordinal()]--;
            if (newContribution != null && newContribution != CubeStatus.EMPTY) refs[newContribution.ordinal()]++;
            CubeStatus newTarget = CubeStatus.EMPTY;
            for (int stage = refs.length - 1; stage > 0; stage--) {
                if (refs[stage] > 0) {
                    newTarget = STAGES[stage];
                    break;
                }
            }
            if (newTarget == CubeStatus.EMPTY) {
                counts.remove(pos);
                targets.remove(pos);
            } else targets.put(pos, newTarget);
            if (oldTarget != newTarget) changed.add(pos);
        }
        return changed;
    }

    CubeStatus target(CubePos pos) {
        return targets.getOrDefault(pos, CubeStatus.EMPTY);
    }

    Set<CubePos> positions() {
        return java.util.Collections.unmodifiableSet(targets.keySet());
    }

    void clear() {
        roots.clear();
        counts.clear();
        targets.clear();
    }
}
