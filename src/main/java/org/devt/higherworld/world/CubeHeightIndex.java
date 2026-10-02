package org.devt.higherworld.world;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import org.devt.higherworld.storage.CubePos;

/**
 * Tracks the highest non-air block in each loaded sparse block column.
 *
 * <p>The index deliberately receives a column lookup instead of owning the
 * world's cube map.  This keeps the height bookkeeping independent from the
 * lifecycle that loads and evicts cubes while preserving the existing lazy
 * recomputation behavior when a column's highest cube changes.</p>
 */
final class CubeHeightIndex {
    @FunctionalInterface
    interface ColumnLookup {
        CubeColumn<LoadedCube> find(int columnX, int columnZ);
    }

    private final ConcurrentMap<BlockColumn, Integer> highestBlocks = new ConcurrentHashMap<>();
    private final ColumnLookup columns;

    CubeHeightIndex(ColumnLookup columns) {
        this.columns = java.util.Objects.requireNonNull(columns, "columns");
    }

    Integer get(int blockX, int blockZ) {
        return highestBlocks.get(new BlockColumn(blockX, blockZ));
    }

    void index(LoadedCube cube) {
        int baseX = cube.pos().minBlockX();
        int baseY = cube.pos().minBlockY();
        int baseZ = cube.pos().minBlockZ();
        for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
            for (int localX = 0; localX < CubePos.SIZE; localX++) {
                for (int localY = CubePos.SIZE - 1; localY >= 0; localY--) {
                    if (!cube.section().getBlockState(localX, localY, localZ).isAir()) {
                        BlockColumn key = new BlockColumn(baseX + localX, baseZ + localZ);
                        highestBlocks.merge(key, baseY + localY, Math::max);
                        break;
                    }
                }
            }
        }
    }

    void update(BlockPos pos, BlockState state) {
        BlockColumn key = new BlockColumn(pos.getX(), pos.getZ());
        if (!state.isAir()) {
            highestBlocks.merge(key, pos.getY(), Math::max);
            return;
        }
        Integer current = highestBlocks.get(key);
        if (current != null && current == pos.getY()) {
            recompute(key);
        }
    }

    void remove(LoadedCube cube) {
        int baseX = cube.pos().minBlockX();
        int baseZ = cube.pos().minBlockZ();
        int minY = cube.pos().minBlockY();
        int maxY = minY + CubePos.SIZE - 1;
        for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
            for (int localX = 0; localX < CubePos.SIZE; localX++) {
                BlockColumn key = new BlockColumn(baseX + localX, baseZ + localZ);
                Integer height = highestBlocks.get(key);
                if (height != null && height >= minY && height <= maxY) {
                    recompute(key);
                }
            }
        }
    }

    private void recompute(BlockColumn key) {
        CubeColumn<LoadedCube> column = columns.find(
                Math.floorDiv(key.x(), CubePos.SIZE), Math.floorDiv(key.z(), CubePos.SIZE));
        int highest = Integer.MIN_VALUE;
        if (column != null) {
            int localX = Math.floorMod(key.x(), CubePos.SIZE);
            int localZ = Math.floorMod(key.z(), CubePos.SIZE);
            for (LoadedCube cube : column.loaded()) {
                for (int localY = CubePos.SIZE - 1; localY >= 0; localY--) {
                    if (!cube.section().getBlockState(localX, localY, localZ).isAir()) {
                        highest = Math.max(highest, cube.pos().minBlockY() + localY);
                        break;
                    }
                }
            }
        }
        if (highest == Integer.MIN_VALUE) {
            highestBlocks.remove(key);
        } else {
            highestBlocks.put(key, highest);
        }
    }

    private record BlockColumn(int x, int z) {
    }
}
