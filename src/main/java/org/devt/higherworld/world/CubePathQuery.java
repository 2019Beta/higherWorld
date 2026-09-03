package org.devt.higherworld.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import org.devt.higherworld.storage.CubePos;

/**
 * Deterministic, bounded A* query for AI adapters.
 *
 * <p>The query is intentionally independent of Minecraft's
 * {@code ChunkCache}/{@code PathNodeMaker}.  Vanilla path makers assume a
 * finite section array; this seam makes cube availability and the search
 * budget explicit so a later version-specific adapter can safely bridge to
 * them.</p>
 */
public final class CubePathQuery {
    private static final int MAX_CUBE_RADIUS = 64;
    private static final int MAX_NODES = 100_000;
    private static final CubePathNode[] DIRECTIONS = {
            new CubePathNode(1, 0, 0),
            new CubePathNode(-1, 0, 0),
            new CubePathNode(0, 1, 0),
            new CubePathNode(0, -1, 0),
            new CubePathNode(0, 0, 1),
            new CubePathNode(0, 0, -1)
    };

    private CubePathQuery() {
    }

    /** Search request with a finite cube box and explicit lifecycle gate. */
    public record Request(
            CubePathNode start,
            CubePathNode goal,
            CubePos cubeCenter,
            CubeDependencyRadius cubeRadius,
            CubeStatus minimumStatus,
            int maxExpandedNodes) {
        public Request {
            if (start == null || goal == null || cubeCenter == null
                    || cubeRadius == null || minimumStatus == null) {
                throw new NullPointerException("path request fields");
            }
            if (cubeRadius.x() > MAX_CUBE_RADIUS
                    || cubeRadius.y() > MAX_CUBE_RADIUS
                    || cubeRadius.z() > MAX_CUBE_RADIUS) {
                throw new IllegalArgumentException("path cube radius is out of bounds");
            }
            if (maxExpandedNodes < 1 || maxExpandedNodes > MAX_NODES) {
                throw new IllegalArgumentException("path node budget is out of bounds");
            }
        }
    }

    /** Search diagnostics are useful to a scheduler without exposing mutable state. */
    public record Result(List<CubePathNode> path, int expandedNodes, boolean budgetExhausted) {
        public Result {
            path = List.copyOf(path);
            if (expandedNodes < 0) throw new IllegalArgumentException("negative expansion count");
        }

        public boolean found() {
            return !path.isEmpty();
        }
    }

    /** Returns a path or an empty optional when the bounded query cannot complete. */
    public static Optional<List<CubePathNode>> findPath(
            CubePathfindingAccess access, Request request) {
        return Optional.ofNullable(search(access, request).path()).filter(path -> !path.isEmpty());
    }

    /** Performs a deterministic A* search without blocking for cube loads. */
    public static Result search(CubePathfindingAccess access, Request request) {
        if (access == null || request == null) throw new NullPointerException("path search input");
        if (!inside(request, request.start()) || !inside(request, request.goal())) {
            return new Result(List.of(), 0, false);
        }
        if (!availableAndPassable(access, request, request.start())
                || !availableAndPassable(access, request, request.goal())) {
            return new Result(List.of(), 0, false);
        }
        if (request.start().equals(request.goal())) {
            return new Result(List.of(request.start()), 0, false);
        }

        Map<CubePathNode, Integer> distance = new HashMap<>();
        Map<CubePathNode, CubePathNode> previous = new HashMap<>();
        Set<CubePathNode> closed = new HashSet<>();
        PriorityQueue<FrontierEntry> frontier = new PriorityQueue<>(FrontierEntry.ORDER);
        distance.put(request.start(), 0);
        frontier.add(new FrontierEntry(request.start(), 0, heuristic(request.start(), request.goal())));
        int expanded = 0;

        while (!frontier.isEmpty()) {
            FrontierEntry currentEntry = frontier.poll();
            CubePathNode current = currentEntry.node();
            Integer knownDistance = distance.get(current);
            if (knownDistance == null || knownDistance != currentEntry.distance()
                    || !closed.add(current)) {
                continue;
            }
            if (current.equals(request.goal())) {
                return new Result(reconstruct(previous, current), expanded, false);
            }
            if (expanded >= request.maxExpandedNodes()) {
                return new Result(List.of(), expanded, true);
            }
            expanded++;

            for (CubePathNode direction : DIRECTIONS) {
                CubePathNode next;
                try {
                    next = current.offset(direction.x(), direction.y(), direction.z());
                } catch (ArithmeticException overflow) {
                    continue;
                }
                if (!inside(request, next) || closed.contains(next)
                        || !availableAndPassable(access, request, next)) {
                    continue;
                }
                int candidateDistance = knownDistance + 1;
                Integer oldDistance = distance.get(next);
                if (oldDistance != null && candidateDistance >= oldDistance) continue;
                distance.put(next, candidateDistance);
                previous.put(next, current);
                frontier.add(new FrontierEntry(
                        next, candidateDistance,
                        saturatingAdd(candidateDistance, heuristic(next, request.goal()))));
            }
        }
        return new Result(List.of(), expanded, false);
    }

    private static boolean availableAndPassable(
            CubePathfindingAccess access, Request request, CubePathNode node) {
        CubePos cube = node.cube();
        return inside(request, node)
                && access.isAvailable(cube, request.minimumStatus())
                && access.isPassable(node);
    }

    private static boolean inside(Request request, CubePathNode node) {
        CubePos center = request.cubeCenter();
        CubePos cube = node.cube();
        return Math.abs((long) cube.x() - center.x()) <= request.cubeRadius().x()
                && Math.abs((long) cube.y() - center.y()) <= request.cubeRadius().y()
                && Math.abs((long) cube.z() - center.z()) <= request.cubeRadius().z();
    }

    private static int heuristic(CubePathNode from, CubePathNode to) {
        long distance = Math.abs((long) from.x() - to.x())
                + Math.abs((long) from.y() - to.y())
                + Math.abs((long) from.z() - to.z());
        return distance >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) distance;
    }

    private static int saturatingAdd(int first, int second) {
        long sum = (long) first + second;
        return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    private static List<CubePathNode> reconstruct(
            Map<CubePathNode, CubePathNode> previous, CubePathNode end) {
        ArrayList<CubePathNode> path = new ArrayList<>();
        CubePathNode current = end;
        path.add(current);
        while (previous.containsKey(current)) {
            current = previous.get(current);
            path.add(current);
        }
        Collections.reverse(path);
        return path;
    }

    private record FrontierEntry(CubePathNode node, int distance, int estimate) {
        private static final Comparator<FrontierEntry> ORDER = Comparator
                .comparingInt(FrontierEntry::estimate)
                .thenComparingInt(FrontierEntry::distance)
                .thenComparing(FrontierEntry::node);
    }
}
