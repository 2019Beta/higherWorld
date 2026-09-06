package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Random;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.PaletteProvider;
import org.devt.higherworld.storage.CubePos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TerrainCommitRegressionTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    private static LoadedCube cube() {
        // These block-only algorithms never query the biome container.
        return new LoadedCube(new CubePos(3, -20, -4), new ChunkSection(
                new PalettedContainer<>(Blocks.AIR.getDefaultState(),
                        PaletteProvider.forBlockStates(Block.STATE_IDS)), null));
    }

    @Test
    void bulkCommitPreservesBlocksCountsAndCanClearAnExistingSection() {
        LoadedCube cube = cube();
        Random random = new Random(42L);
        boolean[] solid = new boolean[4096];
        for (int i = 0; i < solid.length; i++) solid[i] = random.nextBoolean();
        // Exercise removal of random-ticking blocks and fluids during repairs.
        cube.setGeneratedBlockState(0, 0, 0, Blocks.OAK_LEAVES.getDefaultState());
        cube.setGeneratedBlockState(1, 0, 0, Blocks.WATER.getDefaultState());
        CustomCubeGenerator.applyTerrain(cube, new CustomCubeGenerator.TerrainSnapshot(solid));
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            assertEquals(solid[(y * 16 + z) * 16 + x] ? Blocks.DEEPSLATE.getDefaultState()
                    : Blocks.AIR.getDefaultState(), cube.section().getBlockState(x, y, z));
        }
        assertFalse(cube.section().isEmpty());
        assertFalse(cube.section().hasRandomTicks());
        assertFalse(cube.isDirty());
        assertEquals(0, cube.revision());
        Arrays.fill(solid, false);
        CustomCubeGenerator.applyTerrain(cube, new CustomCubeGenerator.TerrainSnapshot(solid));
        assertTrue(cube.section().isEmpty());
        // A subsequent ordinary write also verifies that the bulk lock was released.
        cube.setGeneratedBlockState(0, 0, 0, Blocks.STONE.getDefaultState());
        assertFalse(cube.section().isEmpty());
    }

    @Test
    void surfaceSearchMatchesReferenceAcrossAirSolidsFluidsAndCaves() {
        LoadedCube cube = cube();
        assertEquals(Integer.MIN_VALUE, CustomLakeGenerator.highestSurfaceY(cube));
        BlockState[] states = {Blocks.AIR.getDefaultState(), Blocks.STONE.getDefaultState(),
                Blocks.WATER.getDefaultState(), Blocks.LAVA.getDefaultState()};
        Random random = new Random(6106L);
        for (int sample = 0; sample < 40; sample++) {
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                cube.setGeneratedBlockState(x, y, z,
                        sample == 0 ? states[1] : states[random.nextInt(states.length)]);
            }
            assertEquals(referenceSurface(cube), CustomLakeGenerator.highestSurfaceY(cube));
        }
    }

    @Test
    void surfaceSearchFindsEveryLocalHeightAndExcludesFluidsAndTopFace() {
        for (int height = 0; height < 16; height++) {
            LoadedCube cube = cube();
            cube.setGeneratedBlockState(15, height, 15, Blocks.STONE.getDefaultState());
            assertEquals(height == 15 ? Integer.MIN_VALUE : cube.pos().minBlockY() + height,
                    CustomLakeGenerator.highestSurfaceY(cube));
            cube.setGeneratedBlockState(15, height, 15, Blocks.WATER.getDefaultState());
            assertEquals(Integer.MIN_VALUE, CustomLakeGenerator.highestSurfaceY(cube));
        }
    }

    private static int referenceSurface(LoadedCube cube) {
        int highest = Integer.MIN_VALUE;
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) for (int y = 14; y >= 0; y--) {
            BlockState current = cube.section().getBlockState(x, y, z);
            if (!current.isAir() && current.getFluidState().isEmpty()
                    && cube.section().getBlockState(x, y + 1, z).isAir()) {
                highest = Math.max(highest, cube.pos().minBlockY() + y);
                break;
            }
        }
        return highest;
    }
}
