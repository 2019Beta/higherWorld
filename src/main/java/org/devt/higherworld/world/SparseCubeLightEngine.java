package org.devt.higherworld.world;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import org.devt.higherworld.storage.CubePos;

/**
 * Stateless six-direction light relaxation over loaded sparse cubes.
 * Missing cubes are null light sections and are never allocated by propagation.
 */
public final class SparseCubeLightEngine {
    private static final int MAX_LIGHT_LEVEL = 15;
    private static final int[][] DIRECTIONS = {
            {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}
    };
    private final Access access;
    private final ArrayDeque<Node> pending = new ArrayDeque<>();
    private final Set<Node> queued = new HashSet<>();

    public SparseCubeLightEngine(Access access) {
        this.access = access;
    }

    public void clear() {
        pending.clear();
        queued.clear();
    }

    /** Queues an entire new cube, or only its faces when saved light is present. */
    public void queueCube(CubePos pos, boolean initialize) {
        // CubePos deliberately permits every int coordinate for storage keys, but
        // a cube whose block range is outside int space cannot participate in the
        // block light graph.  Silently ignoring it also keeps a malformed packet
        // from turning a boundary coordinate into an opposite-side neighbour.
        if (!pos.isBlockRangeRepresentable()) return;
        int minX = pos.minBlockX();
        int minY = pos.minBlockY();
        int minZ = pos.minBlockZ();
        if (initialize) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) queue(minX + x, minY + y, minZ + z);
                }
            }
        }
        for (int a = 0; a < 16; a++) {
            for (int b = 0; b < 16; b++) {
                queueWithNeighbour(minX, minY + a, minZ + b, -1, 0, 0);
                queueWithNeighbour(minX + 15, minY + a, minZ + b, 1, 0, 0);
                queueWithNeighbour(minX + a, minY, minZ + b, 0, -1, 0);
                queueWithNeighbour(minX + a, minY + 15, minZ + b, 0, 1, 0);
                queueWithNeighbour(minX + a, minY + b, minZ, 0, 0, -1);
                queueWithNeighbour(minX + a, minY + b, minZ + 15, 0, 0, 1);
            }
        }
    }

    public void queueBlock(int x, int y, int z) {
        queue(x, y, z);
        for (int[] direction : DIRECTIONS) {
            queueOffset(x, y, z, direction[0], direction[1], direction[2]);
        }
    }

    /** Runs until stable or the supplied safety limit is reached. */
    public Result propagate(int maximumSteps) {
        return propagate(maximumSteps, Long.MAX_VALUE);
    }

    /**
     * Runs a bounded slice of light work.  The time check is deliberately
     * sampled instead of performed for every node: {@link System#nanoTime()}
     * is measurable in this very hot loop, while a small overshoot is harmless.
     */
    public Result propagate(int maximumSteps, long maximumNanos) {
        Set<CubePos> changed = new HashSet<>();
        int steps = 0;
        long budget = Math.max(0L, maximumNanos);
        long started = System.nanoTime();
        while (!pending.isEmpty() && steps < maximumSteps
                && ((steps & 63) != 0 || System.nanoTime() - started < budget)) {
            Node node = pending.removeFirst();
            queued.remove(node);
            steps++;
            if (!access.managed(node.x, node.y, node.z)) continue;

            int opacity = Math.max(1, Math.min(MAX_LIGHT_LEVEL,
                    access.opacity(node.x, node.y, node.z)));
            int block = clampLight(access.emitted(node.x, node.y, node.z));
            int sky = access.skySource(node.x, node.y, node.z) ? MAX_LIGHT_LEVEL : 0;
            if (opacity < MAX_LIGHT_LEVEL) {
                for (int[] direction : DIRECTIONS) {
                    long neighbourX = (long) node.x + direction[0];
                    long neighbourY = (long) node.y + direction[1];
                    long neighbourZ = (long) node.z + direction[2];
                    if (!inIntegerRange(neighbourX) || !inIntegerRange(neighbourY)
                            || !inIntegerRange(neighbourZ)) continue;
                    int x = (int) neighbourX;
                    int y = (int) neighbourY;
                    int z = (int) neighbourZ;
                    block = Math.max(block,
                            clampLight(access.block(x, y, z)) - opacity);
                    sky = Math.max(sky,
                            clampLight(access.sky(x, y, z)) - opacity);
                }
            }

            boolean blockChanged = access.setBlock(node.x, node.y, node.z, block);
            boolean skyChanged = access.setSky(node.x, node.y, node.z, sky);
            if (blockChanged || skyChanged) {
                changed.add(CubePos.fromBlock(node.x, node.y, node.z));
                for (int[] direction : DIRECTIONS) {
                    queueOffset(node.x, node.y, node.z,
                            direction[0], direction[1], direction[2]);
                }
            }
        }
        changed.forEach(access::publish);
        return new Result(Set.copyOf(changed), pending.isEmpty(), steps);
    }

    private void queueWithNeighbour(int x, int y, int z, int dx, int dy, int dz) {
        queue(x, y, z);
        queueOffset(x, y, z, dx, dy, dz);
    }

    private void queue(int x, int y, int z) {
        if (!access.managed(x, y, z)) return;
        Node node = new Node(x, y, z);
        if (queued.add(node)) pending.addLast(node);
    }

    private void queueOffset(int x, int y, int z, int dx, int dy, int dz) {
        long nextX = (long) x + dx;
        long nextY = (long) y + dy;
        long nextZ = (long) z + dz;
        if (inIntegerRange(nextX) && inIntegerRange(nextY) && inIntegerRange(nextZ)) {
            queue((int) nextX, (int) nextY, (int) nextZ);
        }
    }

    private static boolean inIntegerRange(long value) {
        return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
    }

    private static int clampLight(int value) {
        return Math.max(0, Math.min(MAX_LIGHT_LEVEL, value));
    }

    private record Node(int x, int y, int z) {}

    public record Result(Set<CubePos> changedCubes, boolean complete, int steps) {}

    public interface Access {
        boolean managed(int x, int y, int z);
        int emitted(int x, int y, int z);
        int opacity(int x, int y, int z);
        boolean skySource(int x, int y, int z);
        int block(int x, int y, int z);
        int sky(int x, int y, int z);
        boolean setBlock(int x, int y, int z, int value);
        boolean setSky(int x, int y, int z, int value);
        void publish(CubePos pos);
    }
}
