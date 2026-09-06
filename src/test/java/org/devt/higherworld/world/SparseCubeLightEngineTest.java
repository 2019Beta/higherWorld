package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.devt.higherworld.storage.CubePos;
import org.junit.jupiter.api.Test;

class SparseCubeLightEngineTest {
    @Test
    void queuedCubesShareBudgetAndUnloadedWorkIsDiscarded() {
        TestAccess access = new TestAccess();
        CubePos first = new CubePos(-2, -20, 0);
        CubePos second = new CubePos(2, -20, 0);
        access.add(first);
        access.add(second);
        access.emission.put(new Point(second.minBlockX(), second.minBlockY(), 0), 15);
        SparseCubeLightEngine engine = new SparseCubeLightEngine(access);
        engine.queueCube(first, true);
        engine.queueCube(second, true);
        engine.queueCube(first, true);
        assertEquals(2, engine.pendingCubeCount());

        // The second cube must progress without waiting for 4096 old cells.
        engine.propagate(65);
        assertEquals(15, access.block(second.minBlockX(), second.minBlockY(), 0));
        engine.discardCube(first);
        assertEquals(1, engine.pendingCubeCount());
        assertTrue(engine.propagate(200_000).complete());
        assertEquals(0, engine.pendingCubeCount());
        engine.queueCube(first, true);
        engine.clear();
        assertTrue(engine.propagate(1).complete());
    }

    @Test
    void blockLightCrossesCubeBoundaryAndFallsOff() {
        TestAccess access = new TestAccess();
        access.add(new CubePos(0, 0, 0));
        access.add(new CubePos(1, 0, 0));
        access.emission.put(new Point(15, 8, 8), 15);
        SparseCubeLightEngine engine = new SparseCubeLightEngine(access);

        engine.queueCube(new CubePos(0, 0, 0), true);
        engine.queueCube(new CubePos(1, 0, 0), true);
        SparseCubeLightEngine.Result result = engine.propagate(200_000);

        assertTrue(result.complete());
        assertEquals(15, access.block(15, 8, 8));
        assertEquals(14, access.block(16, 8, 8));
        assertEquals(13, access.block(17, 8, 8));
    }

    @Test
    void sourceRemovalPropagatesDecrease() {
        TestAccess access = new TestAccess();
        access.add(new CubePos(0, 0, 0));
        Point source = new Point(8, 8, 8);
        access.emission.put(source, 15);
        SparseCubeLightEngine engine = new SparseCubeLightEngine(access);
        engine.queueCube(new CubePos(0, 0, 0), true);
        engine.propagate(200_000);
        access.emission.remove(source);

        engine.queueBlock(source.x, source.y, source.z);
        assertTrue(engine.propagate(200_000).complete());
        assertEquals(0, access.block(8, 8, 8));
        assertEquals(0, access.block(9, 8, 8));
    }

    @Test
    void zeroTimeBudgetDefersQueuedWorkWithoutLosingIt() {
        TestAccess access = new TestAccess();
        CubePos cube = new CubePos(0, 0, 0);
        access.add(cube);
        SparseCubeLightEngine engine = new SparseCubeLightEngine(access);
        engine.queueCube(cube, true);

        SparseCubeLightEngine.Result deferred = engine.propagate(200_000, 0L);
        assertEquals(0, deferred.steps());
        assertTrue(!deferred.complete());

        assertTrue(engine.propagate(200_000).complete());
    }

    private static final class TestAccess implements SparseCubeLightEngine.Access {
        private final Map<CubePos, CubeLightData> cubes = new HashMap<>();
        private final Map<Point, Integer> emission = new HashMap<>();

        void add(CubePos pos) { cubes.put(pos, new CubeLightData()); }
        private CubeLightData light(int x, int y, int z) { return cubes.get(CubePos.fromBlock(x, y, z)); }
        private static int local(int value) { return Math.floorMod(value, 16); }

        @Override public boolean managed(int x, int y, int z) { return light(x, y, z) != null; }
        @Override public int emitted(int x, int y, int z) { return emission.getOrDefault(new Point(x, y, z), 0); }
        @Override public int opacity(int x, int y, int z) { return 1; }
        @Override public boolean skySource(int x, int y, int z) { return false; }
        @Override public int block(int x, int y, int z) {
            CubeLightData light = light(x, y, z);
            return light == null ? 0 : light.workingBlock(local(x), local(y), local(z));
        }
        @Override public int sky(int x, int y, int z) { return 0; }
        @Override public boolean setBlock(int x, int y, int z, int value) {
            return light(x, y, z).setWorkingBlock(local(x), local(y), local(z), value);
        }
        @Override public boolean setSky(int x, int y, int z, int value) { return false; }
        @Override public void publish(CubePos pos) { cubes.get(pos).publish(); }
    }

    private record Point(int x, int y, int z) {}
}
