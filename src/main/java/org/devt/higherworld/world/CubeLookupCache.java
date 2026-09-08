package org.devt.higherworld.world;

import java.util.Arrays;
import java.util.function.Function;
import org.devt.higherworld.storage.CubePos;

/**
 * Bounded, thread-confined lookup cache for voxel readers. A 4x4x4 direct
 * mapping keeps a cube and its immediate neighbours in distinct slots. Values
 * (including misses) are valid only for the caller's supplied publication epoch.
 */
public final class CubeLookupCache<T> {
    private final Function<CubePos, T> lookup;
    private final CubePos[] positions = new CubePos[64];
    private final Object[] values = new Object[64];
    private final long[] epochs = new long[64];

    public CubeLookupCache(Function<CubePos, T> lookup) {
        this.lookup = lookup;
    }

    @SuppressWarnings("unchecked")
    public T getBlock(int x, int y, int z, long epoch) {
        int cx = x >> 4;
        int cy = y >> 4;
        int cz = z >> 4;
        int slot = (cx & 3) | ((cy & 3) << 2) | ((cz & 3) << 4);
        CubePos previous = positions[slot];
        boolean samePosition = previous != null && previous.x() == cx && previous.y() == cy
                && previous.z() == cz;
        if (samePosition && epochs[slot] == epoch) {
            return (T) values[slot];
        }
        CubePos pos = samePosition ? previous : new CubePos(cx, cy, cz);
        T value = lookup.apply(pos);
        positions[slot] = pos;
        values[slot] = value;
        epochs[slot] = epoch;
        return value;
    }

    /** Release references at the end of a propagation slice or world lifetime. */
    public void clear() {
        Arrays.fill(positions, null);
        Arrays.fill(values, null);
    }
}
