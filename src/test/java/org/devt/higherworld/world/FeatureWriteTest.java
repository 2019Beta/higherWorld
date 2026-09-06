package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import net.minecraft.util.math.BlockPos;
import org.devt.higherworld.storage.CubePos;
import org.junit.jupiter.api.Test;

class FeatureWriteTest {
    @Test
    void allLocalPositionsRoundTripAcrossNegativeAndExtremeCubes() {
        for (int base : new int[] {Integer.MIN_VALUE, -544, -16, 0, 16, Integer.MAX_VALUE - 15}) {
            var indices = new HashSet<Integer>();
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockPos pos = new BlockPos(base + x, base + y, base + z);
                        var write = new VanillaPlacedFeatureGenerator.FeatureWrite(pos, null);
                        CubePos cube = CubePos.fromBlock(pos.getX(), pos.getY(), pos.getZ());
                        assertTrue(indices.add(write.localIndex()));
                        assertEquals(pos, new BlockPos(cube.minBlockX() + write.localX(),
                                cube.minBlockY() + write.localY(), cube.minBlockZ() + write.localZ()));
                        // A virtual feature source is translated by whole 64-block bands.
                        assertEquals(y, Math.floorMod((long) pos.getY() - 64L * 100, 16));
                    }
                }
            }
            assertEquals(4096, indices.size());
        }
    }

    @Test
    void mutableSourceIsNotRetained() {
        var pos = new BlockPos.Mutable(-1, -18, 35);
        var write = new VanillaPlacedFeatureGenerator.FeatureWrite(pos, null);
        pos.set(0, 0, 0);
        assertEquals(15, write.localX());
        assertEquals(14, write.localY());
        assertEquals(3, write.localZ());
    }
}
