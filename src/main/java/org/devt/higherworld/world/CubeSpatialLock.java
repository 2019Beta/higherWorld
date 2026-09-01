package org.devt.higherworld.world;

import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
import org.devt.higherworld.storage.CubePos;

/** Ordered striped locks for small 3D commit regions; prevents lock cycles. */
final class CubeSpatialLock {
    private final ReentrantLock[] stripes;

    CubeSpatialLock(int stripeCount) {
        if (Integer.bitCount(stripeCount) != 1) {
            throw new IllegalArgumentException("stripeCount must be a power of two");
        }
        stripes = new ReentrantLock[stripeCount];
        Arrays.setAll(stripes, ignored -> new ReentrantLock());
    }

    Scope lock(CubePos center, CubeDependencyRadius radius) {
        int[] indices = new int[radius.volume()];
        int[] cursor = {0};
        radius.forEach(center, pos -> indices[cursor[0]++] = stripe(pos));
        Arrays.sort(indices);
        int unique = deduplicate(indices);
        for (int i = 0; i < unique; i++) stripes[indices[i]].lock();
        return new Scope(indices, unique);
    }

    private int stripe(CubePos pos) {
        int hash = 31 * (31 * pos.x() + pos.y()) + pos.z();
        hash ^= hash >>> 16;
        return hash & (stripes.length - 1);
    }

    private static int deduplicate(int[] values) {
        if (values.length == 0) return 0;
        int write = 1;
        for (int read = 1; read < values.length; read++) {
            if (values[read] != values[write - 1]) values[write++] = values[read];
        }
        return write;
    }

    final class Scope implements AutoCloseable {
        private final int[] indices;
        private final int count;

        private Scope(int[] indices, int count) {
            this.indices = indices;
            this.count = count;
        }

        @Override
        public void close() {
            for (int i = count - 1; i >= 0; i--) stripes[indices[i]].unlock();
        }
    }
}
