package org.devt.higherworld.world;

import org.devt.higherworld.storage.CubePos;

/** Immutable absolute block coordinate used by the bounded path seam. */
public record CubePathNode(int x, int y, int z) implements Comparable<CubePathNode> {
    public CubePos cube() {
        return CubePos.fromBlock(x, y, z);
    }

    public CubePathNode offset(int dx, int dy, int dz) {
        return new CubePathNode(Math.addExact(x, dx), Math.addExact(y, dy), Math.addExact(z, dz));
    }

    @Override
    public int compareTo(CubePathNode other) {
        int byY = Integer.compare(y, other.y);
        if (byY != 0) return byY;
        int byZ = Integer.compare(z, other.z);
        return byZ != 0 ? byZ : Integer.compare(x, other.x);
    }
}
