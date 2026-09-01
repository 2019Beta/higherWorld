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
            queue(x + direction[0], y + direction[1], z + direction[2]);
        }
    }

    /** Runs until stable or the supplied safety limit is reached. */
    public Result propagate(int maximumSteps) {
        Set<CubePos> changed = new HashSet<>();
        int steps = 0;
        while (!pending.isEmpty() && steps < maximumSteps) {
            Node node = pending.removeFirst();
            queued.remove(node);
            steps++;
            if (!access.managed(node.x, node.y, node.z)) continue;

            int opacity = Math.max(1, Math.min(15, access.opacity(node.x, node.y, node.z)));
            int block = access.emitted(node.x, node.y, node.z);
            int sky = access.skySource(node.x, node.y, node.z) ? 15 : 0;
            if (opacity < 15) {
                for (int[] direction : DIRECTIONS) {
                    int x = node.x + direction[0];
                    int y = node.y + direction[1];
                    int z = node.z + direction[2];
                    block = Math.max(block, access.block(x, y, z) - opacity);
                    sky = Math.max(sky, access.sky(x, y, z) - opacity);
                }
            }

            boolean blockChanged = access.setBlock(node.x, node.y, node.z, block);
            boolean skyChanged = access.setSky(node.x, node.y, node.z, sky);
            if (blockChanged || skyChanged) {
                changed.add(CubePos.fromBlock(node.x, node.y, node.z));
                for (int[] direction : DIRECTIONS) {
                    queue(node.x + direction[0], node.y + direction[1], node.z + direction[2]);
                }
            }
        }
        changed.forEach(access::publish);
        return new Result(Set.copyOf(changed), pending.isEmpty(), steps);
    }

    private void queueWithNeighbour(int x, int y, int z, int dx, int dy, int dz) {
        queue(x, y, z);
        queue(x + dx, y + dy, z + dz);
    }

    private void queue(int x, int y, int z) {
        if (!access.managed(x, y, z)) return;
        Node node = new Node(x, y, z);
        if (queued.add(node)) pending.addLast(node);
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
