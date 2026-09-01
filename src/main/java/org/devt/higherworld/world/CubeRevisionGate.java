package org.devt.higherworld.world;

/**
 * Revision ordering rules shared by full snapshots, deltas and removals.
 *
 * <p>The returned decision is deliberately immutable so callers can apply it
 * inside a {@code ConcurrentMap.compute} operation.  The map operation, not a
 * separate check followed by a write, is what makes accepting and promoting a
 * delta one atomic state transition.</p>
 */
public final class CubeRevisionGate {
    private CubeRevisionGate() {
    }

    /** Full snapshots never treat revision zero as a wildcard. */
    public static Decision snapshot(long current, long incoming) {
        return incoming < current ? Decision.rejected(current) : Decision.accepted(incoming);
    }

    /** Revision zero (and legacy negative values) is an unversioned delta. */
    public static Decision delta(long current, long incoming) {
        if (incoming <= 0L) return Decision.accepted(current);
        return incoming < current ? Decision.rejected(current) : Decision.accepted(incoming);
    }

    /** Revision zero is an unversioned, force-removal compatibility packet. */
    public static Decision removal(long current, long incoming) {
        if (incoming <= 0L || incoming >= current) return Decision.accepted(current);
        return Decision.rejected(current);
    }

    public record Decision(boolean accepted, long revision) {
        private static Decision accepted(long revision) {
            return new Decision(true, revision);
        }

        private static Decision rejected(long revision) {
            return new Decision(false, revision);
        }
    }
}
