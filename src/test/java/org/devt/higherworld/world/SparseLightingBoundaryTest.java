package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.devt.higherworld.storage.CubePos;
import org.junit.jupiter.api.Test;

/** Focused regression coverage for sparse light boundaries and lifecycle edges. */
class SparseLightingBoundaryTest {
    @Test
    void blockLightCrossesThreeFacesAndRemovalReachesTheFarCube() {
        TestAccess access = new TestAccess();
        CubePos origin = new CubePos(0, 0, 0);
        CubePos xNeighbour = new CubePos(1, 0, 0);
        CubePos xyNeighbour = new CubePos(1, 1, 0);
        CubePos diagonal = new CubePos(1, 1, 1);
        access.add(origin);
        access.add(xNeighbour);
        access.add(xyNeighbour);
        access.add(diagonal);
        Point source = new Point(15, 15, 15);
        access.emission.put(source, 15);
        SparseCubeLightEngine engine = new SparseCubeLightEngine(access);

        engine.queueCube(origin, true);
        engine.queueCube(xNeighbour, true);
        engine.queueCube(xyNeighbour, true);
        engine.queueCube(diagonal, true);
        assertTrue(engine.propagate(500_000).complete());
        assertEquals(15, access.block(15, 15, 15));
        assertEquals(14, access.block(16, 15, 15));
        assertEquals(13, access.block(17, 15, 15));
        assertEquals(12, access.block(16, 16, 16));

        access.emission.remove(source);
        engine.queueBlock(source.x, source.y, source.z);
        assertTrue(engine.propagate(500_000).complete());
        assertEquals(0, access.block(15, 15, 15));
        assertEquals(0, access.block(16, 15, 15));
        assertEquals(0, access.block(17, 15, 15));
        assertEquals(0, access.block(16, 16, 16));
    }

    @Test
    void openingAnOpaqueBoundaryRepropagatesExistingBlockLight() {
        TestAccess access = new TestAccess();
        CubePos cube = new CubePos(0, 0, 0);
        access.add(cube);
        Point source = new Point(0, 8, 8);
        Point barrier = new Point(1, 8, 8);
        access.emission.put(source, 15);
        access.opacity.put(barrier, 15);
        SparseCubeLightEngine engine = new SparseCubeLightEngine(access);

        engine.queueCube(cube, true);
        assertTrue(engine.propagate(500_000).complete());
        assertEquals(0, access.block(1, 8, 8));
        // A single opaque cell does not seal the plane: light routes around it
        // through the y/z neighbours (15 - 4 hops), as vanilla block light does.
        assertEquals(11, access.block(2, 8, 8));

        access.opacity.put(barrier, 1);
        engine.queueBlock(barrier.x, barrier.y, barrier.z);
        assertTrue(engine.propagate(500_000).complete());
        assertEquals(14, access.block(1, 8, 8));
        assertEquals(13, access.block(2, 8, 8));
    }

    @Test
    void skyLightIsColumnSourceAndRoofRemovalReachesTheCubeBelow() {
        TestAccess access = new TestAccess();
        CubePos lower = new CubePos(0, 0, 0);
        CubePos upper = new CubePos(0, 1, 0);
        access.add(lower);
        access.add(upper);
        for (int z = 0; z < CubePos.SIZE; z++) {
            for (int x = 0; x < CubePos.SIZE; x++) {
                access.opacity.put(new Point(x, 8, z), 15);
            }
        }
        access.roofPresent = true;
        SparseCubeLightEngine engine = new SparseCubeLightEngine(access);

        engine.queueCube(lower, true);
        engine.queueCube(upper, true);
        assertTrue(engine.propagate(1_000_000).complete());
        assertEquals(15, access.sky(0, 9, 0));
        assertEquals(0, access.sky(0, 8, 0));
        assertEquals(0, access.sky(0, 7, 0));

        for (int z = 0; z < CubePos.SIZE; z++) {
            for (int x = 0; x < CubePos.SIZE; x++) {
                access.opacity.remove(new Point(x, 8, z));
                engine.queueBlock(x, 8, z);
            }
        }
        access.roofPresent = false;
        assertTrue(engine.propagate(1_000_000).complete());
        assertEquals(15, access.sky(0, 8, 0));
        assertEquals(15, access.sky(0, 0, 0));
    }

    @Test
    void unloadedCubeStopsLightAndReloadReestablishesTheBoundary() {
        TestAccess access = new TestAccess();
        CubePos sourceCube = new CubePos(0, 0, 0);
        CubePos neighbour = new CubePos(1, 0, 0);
        access.add(sourceCube);
        access.add(neighbour);
        Point source = new Point(15, 8, 8);
        access.emission.put(source, 15);
        SparseCubeLightEngine engine = new SparseCubeLightEngine(access);

        engine.queueCube(sourceCube, true);
        engine.queueCube(neighbour, true);
        assertTrue(engine.propagate(500_000).complete());
        assertEquals(14, access.block(16, 8, 8));

        access.remove(neighbour);
        engine.queueCube(sourceCube, false);
        assertTrue(engine.propagate(500_000).complete());

        access.add(neighbour);
        engine.queueCube(neighbour, true);
        assertTrue(engine.propagate(500_000).complete());
        assertEquals(14, access.block(16, 8, 8));
    }

    @Test
    void integerBoundaryDoesNotWrapToTheOppositeSide() {
        TestAccess access = new TestAccess();
        CubePos edge = new CubePos(134_217_727, 0, 0);
        access.add(edge);
        Point source = new Point(Integer.MAX_VALUE, 0, 0);
        access.emission.put(source, 15);
        SparseCubeLightEngine engine = new SparseCubeLightEngine(access);

        engine.queueCube(edge, true);
        assertTrue(engine.propagate(500_000).complete());
        assertEquals(15, access.block(Integer.MAX_VALUE, 0, 0));

        CubePos lowerEdge = new CubePos(-134_217_728, 0, 0);
        access.add(lowerEdge);
        Point lowerSource = new Point(Integer.MIN_VALUE, 0, 0);
        access.emission.put(lowerSource, 15);
        engine.queueCube(lowerEdge, true);
        assertTrue(engine.propagate(500_000).complete());
        assertEquals(15, access.block(Integer.MIN_VALUE, 0, 0));

        // This key has no representable block range and should be ignored by
        // the light engine instead of throwing from CubePos.minBlockX().
        engine.queueCube(new CubePos(134_217_728, 0, 0), true);
        engine.queueCube(new CubePos(-134_217_729, 0, 0), true);
        assertTrue(engine.propagate(1).complete());
    }

    private static final class TestAccess implements SparseCubeLightEngine.Access {
        private final Map<CubePos, CubeLightData> cubes = new HashMap<>();
        private final Map<Point, Integer> emission = new HashMap<>();
        private final Map<Point, Integer> opacity = new HashMap<>();
        private boolean roofPresent;

        void add(CubePos pos) {
            cubes.put(pos, new CubeLightData());
        }

        void remove(CubePos pos) {
            cubes.remove(pos);
        }

        private CubeLightData light(int x, int y, int z) {
            return cubes.get(CubePos.fromBlock(x, y, z));
        }

        private static int local(int value) {
            return Math.floorMod(value, CubePos.SIZE);
        }

        @Override
        public boolean managed(int x, int y, int z) {
            return light(x, y, z) != null;
        }

        @Override
        public int emitted(int x, int y, int z) {
            return emission.getOrDefault(new Point(x, y, z), 0);
        }

        @Override
        public int opacity(int x, int y, int z) {
            return opacity.getOrDefault(new Point(x, y, z), 1);
        }

        @Override
        public boolean skySource(int x, int y, int z) {
            return !roofPresent || y > 8;
        }

        @Override
        public int block(int x, int y, int z) {
            CubeLightData light = light(x, y, z);
            return light == null ? 0 : light.workingBlock(local(x), local(y), local(z));
        }

        @Override
        public int sky(int x, int y, int z) {
            CubeLightData light = light(x, y, z);
            return light == null ? 0 : light.workingSky(local(x), local(y), local(z));
        }

        @Override
        public boolean setBlock(int x, int y, int z, int value) {
            CubeLightData light = light(x, y, z);
            return light != null && light.setWorkingBlock(local(x), local(y), local(z), value);
        }

        @Override
        public boolean setSky(int x, int y, int z, int value) {
            CubeLightData light = light(x, y, z);
            return light != null && light.setWorkingSky(local(x), local(y), local(z), value);
        }

        @Override
        public void publish(CubePos pos) {
            CubeLightData light = cubes.get(pos);
            if (light != null) light.publish();
        }
    }

    private record Point(int x, int y, int z) {
    }
}
