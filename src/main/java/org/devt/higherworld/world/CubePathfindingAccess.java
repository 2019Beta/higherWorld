package org.devt.higherworld.world;

import org.devt.higherworld.storage.CubePos;

/**
 * Version-neutral seam used by AI/path queries to inspect sparse cubes.
 *
 * <p>An implementation must answer from a server-thread-safe snapshot.  It
 * must not synchronously load a missing cube from inside a path search: a
 * missing or insufficiently staged cube is simply unavailable and the caller
 * can request a higher lifecycle ticket before retrying.</p>
 */
@FunctionalInterface
public interface CubePathfindingAccess {
    /** Returns the currently committed lifecycle status, or {@code EMPTY}. */
    CubeStatus status(CubePos cube);

    /** Returns whether one absolute block coordinate can be entered. */
    default boolean isPassable(CubePathNode node) {
        return false;
    }

    /** Returns whether the cube has enough committed data for this query. */
    default boolean isAvailable(CubePos cube, CubeStatus minimumStatus) {
        CubeStatus current = status(cube);
        return current != null && current.isAtLeast(minimumStatus);
    }
}
