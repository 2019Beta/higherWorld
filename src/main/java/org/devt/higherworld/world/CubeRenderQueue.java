package org.devt.higherworld.world;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.TreeSet;
import org.devt.higherworld.storage.CubePos;

/**
 * Deduplicated render invalidations with near-first scheduling and FIFO
 * starvation protection.  The queue is independent from Minecraft client
 * classes so its ordering can be checked without starting a client.
 */
public final class CubeRenderQueue {
    private static final Comparator<Entry> NEAREST_FIRST = Comparator
            .comparingLong(Entry::distanceSquared)
            .thenComparingLong(Entry::sequence);

    private final LinkedHashMap<CubePos, Entry> pending = new LinkedHashMap<>();
    private final TreeSet<Entry> nearest = new TreeSet<>(NEAREST_FIRST);
    private long nextSequence;
    private CubePos center;
    private int roundBudget;
    private int roundServed;
    private int fifoShare;

    /** Adds a cube once without changing the age of an existing invalidation. */
    public synchronized boolean offer(CubePos pos) {
        Objects.requireNonNull(pos, "pos");
        if (pending.containsKey(pos)) return false;
        Entry entry = new Entry(pos, nextSequence++);
        pending.put(pos, entry);
        if (center != null) {
            entry.distanceSquared = squaredDistance(pos, center);
            nearest.add(entry);
        }
        return true;
    }

    /**
     * Starts one bounded scheduling round.  A changed camera cube rebuilds the
     * distance index in place; the FIFO map remains untouched so task age is
     * preserved across camera movement.
     */
    public synchronized void beginRound(CubePos center, int budget) {
        if (budget < 0) throw new IllegalArgumentException("Negative render budget");
        if (!Objects.equals(this.center, center)) {
            this.center = center;
            rebuildDistanceIndex();
        }
        roundBudget = budget;
        roundServed = 0;
        fifoShare = budget == 0 ? 0 : Math.max(1, budget / 8 + (budget % 8 == 0 ? 0 : 1));
    }

    /** Returns the next cube for the current round, or null when the round is done. */
    public synchronized CubePos poll() {
        if (roundServed >= roundBudget || pending.isEmpty()) return null;

        Entry entry;
        if (roundServed < fifoShare) {
            entry = pending.entrySet().iterator().next().getValue();
            nearest.remove(entry);
            pending.remove(entry.pos());
        } else {
            entry = nearest.pollFirst();
            if (entry == null) {
                entry = pending.entrySet().iterator().next().getValue();
            }
            pending.remove(entry.pos());
        }
        roundServed++;
        return entry.pos();
    }

    public synchronized int size() {
        return pending.size();
    }

    public synchronized boolean isEmpty() {
        return pending.isEmpty();
    }

    public synchronized void clear() {
        pending.clear();
        nearest.clear();
        nextSequence = 0L;
        center = null;
        roundBudget = 0;
        roundServed = 0;
        fifoShare = 0;
    }

    private void rebuildDistanceIndex() {
        nearest.clear();
        if (center == null) return;
        for (Entry entry : pending.values()) {
            entry.distanceSquared = squaredDistance(entry.pos(), center);
            nearest.add(entry);
        }
    }

    private static long squaredDistance(CubePos first, CubePos second) {
        long dx = (long) first.x() - second.x();
        long dy = (long) first.y() - second.y();
        long dz = (long) first.z() - second.z();
        return saturatingAdd(saturatingAdd(square(dx), square(dy)), square(dz));
    }

    private static long square(long value) {
        long absolute = Math.abs(value);
        return absolute != 0L && absolute > Long.MAX_VALUE / absolute
                ? Long.MAX_VALUE : absolute * absolute;
    }

    private static long saturatingAdd(long first, long second) {
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }

    private static final class Entry {
        private final CubePos pos;
        private final long sequence;
        private long distanceSquared;

        private Entry(CubePos pos, long sequence) {
            this.pos = pos;
            this.sequence = sequence;
        }

        private CubePos pos() {
            return pos;
        }

        private long sequence() {
            return sequence;
        }

        private long distanceSquared() {
            return distanceSquared;
        }
    }
}
