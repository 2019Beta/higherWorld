package org.devt.higherworld.world;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.devt.higherworld.storage.CubePos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CubePriorityIndexTest {
    @Test void ownerChangesMatchFullAggregation() {
        var index = new CubePriorityIndex();
        var owners = new HashMap<Integer, Map<CubePos, Integer>>();
        var random = new Random(9781);
        for (int i = 0; i < 3000; i++) {
            int owner = random.nextInt(20);
            var ranks = new HashMap<CubePos, Integer>();
            for (int j = 0, n = random.nextInt(20); j < n; j++) {
                ranks.put(new CubePos(random.nextInt(20), -4, 0), random.nextInt(10));
            }
            owners.put(owner, ranks);
            index.replace(owner, ranks);
            var expected = new HashMap<CubePos, Integer>();
            owners.values().forEach(values -> values.forEach(
                    (pos, rank) -> expected.merge(pos, rank, Math::min)));
            assertEquals(expected, index.snapshot());
        }
        index.clear();
        assertTrue(index.snapshot().isEmpty());
    }
}
