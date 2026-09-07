package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.devt.higherworld.storage.CubePos;
import org.junit.jupiter.api.Test;

class CubeDemandIndexTest {
    @Test
    void overlappingRootsRetainHighestStageUntilItsLastOwnerLeaves() {
        CubeDemandIndex index = new CubeDemandIndex();
        CubePos pos = new CubePos(0, -20, 0);
        index.replace("first", Map.of(pos, CubeStatus.PAYLOAD));
        index.replace("second", Map.of(pos, CubeStatus.PAYLOAD));
        assertTrue(index.replace("first", Map.of()).isEmpty());
        assertEquals(CubeStatus.PAYLOAD, index.target(pos));
        index.replace("simulation", Map.of(pos, CubeStatus.FULL));
        assertEquals(Set.of(pos), index.replace("simulation", Map.of()));
        assertEquals(CubeStatus.PAYLOAD, index.target(pos));
        index.replace("second", Map.of());
        assertTrue(index.positions().isEmpty());
    }

    @Test
    void randomRootMovesMatchFullDemandRecomputation() {
        CubeDemandIndex index = new CubeDemandIndex();
        Map<Integer, Map<CubePos, CubeStatus>> roots = new HashMap<>();
        Random random = new Random(7341);
        for (int iteration = 0; iteration < 200; iteration++) {
            int root = random.nextInt(8);
            Map<CubePos, CubeStatus> replacement = new HashMap<>();
            if (random.nextBoolean()) {
                CubeTicketManager.collectRequired(new CubePos(random.nextInt(8), -20, 0),
                        CubeDependencyRadius.NONE,
                        random.nextBoolean() ? CubeStatus.FULL : CubeStatus.PAYLOAD, replacement);
            }
            roots.put(root, replacement);
            index.replace(root, replacement);
            Map<CubePos, CubeStatus> expected = new HashMap<>();
            roots.values().forEach(closure -> closure.forEach((pos, stage) -> expected.merge(pos, stage,
                    (a, b) -> a.ordinal() >= b.ordinal() ? a : b)));
            assertEquals(expected.keySet(), index.positions());
            expected.forEach((pos, stage) -> assertEquals(stage, index.target(pos)));
            assertTrue(index.replace(root, replacement).isEmpty());
        }
    }
}
