package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.devt.higherworld.storage.CubePos;
import org.junit.jupiter.api.Test;

class CubePathQueryTest {
    @Test
    void pathCrossesCubeBoundaryOnlyThroughFullCubes() {
        Map<CubePos, CubeStatus> statuses = new HashMap<>();
        statuses.put(new CubePos(0, 0, 0), CubeStatus.FULL);
        statuses.put(new CubePos(1, 0, 0), CubeStatus.FULL);
        CubePathfindingAccess access = grid(statuses, node -> true);
        CubePathQuery.Request request = new CubePathQuery.Request(
                new CubePathNode(0, 0, 0), new CubePathNode(31, 0, 0),
                new CubePos(0, 0, 0), new CubeDependencyRadius(1, 0, 0),
                CubeStatus.FULL, 1_000);

        CubePathQuery.Result result = CubePathQuery.search(access, request);
        assertTrue(result.found());
        assertEquals(32, result.path().size());
        assertEquals(new CubePathNode(31, 0, 0), result.path().get(result.path().size() - 1));
    }

    @Test
    void pathDoesNotWaitOrBlockForAnInsufficientDependency() {
        Map<CubePos, CubeStatus> statuses = Map.of(
                new CubePos(0, 0, 0), CubeStatus.FULL,
                new CubePos(1, 0, 0), CubeStatus.TERRAIN);
        CubePathQuery.Request request = new CubePathQuery.Request(
                new CubePathNode(0, 0, 0), new CubePathNode(31, 0, 0),
                new CubePos(0, 0, 0), new CubeDependencyRadius(1, 0, 0),
                CubeStatus.FULL, 1_000);

        assertFalse(CubePathQuery.findPath(grid(statuses, node -> true), request).isPresent());
    }

    @Test
    void searchIsBoundedAndHonoursPassability() {
        CubePathfindingAccess access = grid(Map.of(new CubePos(0, 0, 0), CubeStatus.FULL),
                node -> node.x() != 1 || node.y() != 0);
        CubePathQuery.Request request = new CubePathQuery.Request(
                new CubePathNode(0, 0, 0), new CubePathNode(2, 0, 0),
                new CubePos(0, 0, 0), new CubeDependencyRadius(0, 0, 0),
                CubeStatus.FULL, 1);
        CubePathQuery.Result result = CubePathQuery.search(access, request);
        assertFalse(result.found());
        assertTrue(result.budgetExhausted());

        CubePathQuery.Request detour = new CubePathQuery.Request(
                new CubePathNode(0, 0, 0), new CubePathNode(2, 0, 0),
                new CubePos(0, 0, 0), new CubeDependencyRadius(0, 1, 0),
                CubeStatus.FULL, 100);
        CubePathQuery.Result detourResult = CubePathQuery.search(access, detour);
        assertTrue(detourResult.found());
        assertTrue(detourResult.path().stream().noneMatch(node -> node.equals(new CubePathNode(1, 0, 0))));
    }

    private static CubePathfindingAccess grid(
            Map<CubePos, CubeStatus> statuses,
            java.util.function.Predicate<CubePathNode> passable) {
        return new CubePathfindingAccess() {
            @Override
            public CubeStatus status(CubePos cube) {
                return statuses.getOrDefault(cube, CubeStatus.EMPTY);
            }

            @Override
            public boolean isPassable(CubePathNode node) {
                return passable.test(node);
            }
        };
    }
}
