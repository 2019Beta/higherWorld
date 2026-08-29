package org.devt.higherworld.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CubePosTest {
    @Test
    void convertsNegativeBlocksWithFloorDivision() {
        assertEquals(new CubePos(-1, -1, -1), CubePos.fromBlock(-1, -16, -15));
        assertEquals(new CubePos(-2, -2, -2), CubePos.fromBlock(-17, -17, -32));
    }

    @Test
    void regionSlotsAreUniqueAndBounded() {
        boolean[] found = new boolean[4096];
        for (int z = -16; z < 0; z++) {
            for (int y = -16; y < 0; y++) {
                for (int x = -16; x < 0; x++) {
                    CubePos pos = new CubePos(x, y, z);
                    assertEquals(new RegionPos(-1, -1, -1), pos.region());
                    found[pos.localIndex()] = true;
                }
            }
        }
        for (boolean slot : found) {
            assertEquals(true, slot);
        }
    }

    @Test
    void identifiesCubesThatMapToTheFullSignedBlockRange() {
        assertTrue(new CubePos(-134_217_728, 134_217_727, 0).isBlockRangeRepresentable());
        assertEquals(Integer.MIN_VALUE, new CubePos(0, -134_217_728, 0).minBlockY());
        assertEquals(2_147_483_632, new CubePos(0, 134_217_727, 0).minBlockY());
        assertFalse(new CubePos(0, 134_217_728, 0).isBlockRangeRepresentable());
    }
}
