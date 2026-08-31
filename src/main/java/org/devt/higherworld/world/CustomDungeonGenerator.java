package org.devt.higherworld.world;

import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import org.devt.higherworld.storage.CubePos;

/** Cube-local stone-brick dungeon rooms for custom worlds. */
final class CustomDungeonGenerator {
    private static final long SALT = 0x44554E47454F4E4CL;

    private CustomDungeonGenerator() {
    }

    static void generate(ServerWorld world, LoadedCube cube, CustomWorldSettings settings) {
        if (!settings.dungeons() || settings.dungeonCount() == 0) {
            return;
        }
        Random random = new Random(seed(world.getSeed(), cube.pos()));
        for (int attempt = 0; attempt < settings.dungeonCount(); attempt++) {
            int halfWidth = random.nextBoolean() ? 2 : 3;
            int halfDepth = random.nextBoolean() ? 2 : 3;
            int centerX = halfWidth + 1 + random.nextInt(CubePos.SIZE - (halfWidth * 2 + 2));
            int centerZ = halfDepth + 1 + random.nextInt(CubePos.SIZE - (halfDepth * 2 + 2));
            int centerY = 2 + random.nextInt(CubePos.SIZE - 4);
            if (!canFit(cube, centerX, centerY, centerZ, halfWidth, halfDepth)) {
                continue;
            }
            placeRoom(world, cube, random, centerX, centerY, centerZ, halfWidth, halfDepth);
        }
    }

    private static boolean canFit(
            LoadedCube cube, int centerX, int centerY, int centerZ,
            int halfWidth, int halfDepth) {
        for (int y = centerY - 1; y <= centerY + 2; y++) {
            for (int z = centerZ - halfDepth; z <= centerZ + halfDepth; z++) {
                for (int x = centerX - halfWidth; x <= centerX + halfWidth; x++) {
                    if (x < 0 || x >= CubePos.SIZE || y < 0 || y >= CubePos.SIZE
                            || z < 0 || z >= CubePos.SIZE) {
                        return false;
                    }
                    BlockState state = cube.section().getBlockState(x, y, z);
                    boolean shell = y == centerY - 1 || y == centerY + 2
                            || x == centerX - halfWidth || x == centerX + halfWidth
                            || z == centerZ - halfDepth || z == centerZ + halfDepth;
                    if (shell && !isNatural(state)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void placeRoom(
            ServerWorld world, LoadedCube cube, Random random,
            int centerX, int centerY, int centerZ, int halfWidth, int halfDepth) {
        BlockState bricks = Blocks.STONE_BRICKS.getDefaultState();
        BlockState mossy = Blocks.MOSSY_COBBLESTONE.getDefaultState();
        BlockState air = Blocks.AIR.getDefaultState();
        for (int y = centerY - 1; y <= centerY + 2; y++) {
            for (int z = centerZ - halfDepth; z <= centerZ + halfDepth; z++) {
                for (int x = centerX - halfWidth; x <= centerX + halfWidth; x++) {
                    boolean shell = y == centerY - 1 || y == centerY + 2
                            || x == centerX - halfWidth || x == centerX + halfWidth
                            || z == centerZ - halfDepth || z == centerZ + halfDepth;
                    BlockState state = shell
                            ? (random.nextInt(10) == 0 ? mossy : bricks) : air;
                    cube.setGeneratedBlockState(x, y, z, state);
                }
            }
        }
        // Open no more than two wall blocks, matching the old dungeon's
        // entrance constraint while retaining deterministic room geometry.
        int openingCount = random.nextInt(3);
        for (int opening = 0; opening < openingCount; opening++) {
            boolean xWall = random.nextBoolean();
            int along = xWall
                    ? centerZ - halfDepth + 1 + random.nextInt(halfDepth * 2 - 1)
                    : centerX - halfWidth + 1 + random.nextInt(halfWidth * 2 - 1);
            int x = xWall ? centerX + (random.nextBoolean() ? halfWidth : -halfWidth) : along;
            int z = xWall ? along : centerZ + (random.nextBoolean() ? halfDepth : -halfDepth);
            cube.setGeneratedBlockState(x, centerY, z, air);
        }

        placeBlockEntityBlock(world, cube, centerX, centerY, centerZ,
                Blocks.SPAWNER.getDefaultState());
        int chestCount = random.nextInt(3);
        for (int chest = 0; chest < chestCount; chest++) {
            boolean alongX = random.nextBoolean();
            int x = alongX
                    ? centerX - halfWidth + 1 + random.nextInt(halfWidth * 2 - 1)
                    : centerX + (random.nextBoolean() ? halfWidth - 1 : -halfWidth + 1);
            int z = alongX
                    ? centerZ + (random.nextBoolean() ? halfDepth - 1 : -halfDepth + 1)
                    : centerZ - halfDepth + 1 + random.nextInt(halfDepth * 2 - 1);
            if (cube.section().getBlockState(x, centerY, z).isAir()) {
                placeBlockEntityBlock(world, cube, x, centerY, z,
                        Blocks.CHEST.getDefaultState());
            }
        }
    }

    private static void placeBlockEntityBlock(
            ServerWorld world, LoadedCube cube, int x, int y, int z, BlockState state) {
        cube.setGeneratedBlockState(x, y, z, state);
        // The block state is useful even if a future registry changes the block
        // entity factory.  Current 1.21 has a safe provider factory, so retain an
        // empty spawner/chest entity for interaction and ticking.
        Block block = state.getBlock();
        if (block instanceof BlockEntityProvider provider) {
            BlockPos pos = new BlockPos(cube.pos().minBlockX() + x,
                    cube.pos().minBlockY() + y, cube.pos().minBlockZ() + z);
            try {
                BlockEntity entity = provider.createBlockEntity(pos, state);
                if (entity != null) {
                    entity.setWorld(world);
                    cube.putLoadedBlockEntity(entity);
                }
            } catch (RuntimeException exception) {
                CustomGenerationSupport.warnOnce("dungeon-be:" + block,
                        "Could not create a custom dungeon block entity for " + block);
            }
        }
    }

    private static boolean isNatural(BlockState state) {
        return state.isOf(Blocks.STONE) || state.isOf(Blocks.DEEPSLATE)
                || state.isOf(Blocks.TUFF) || state.isOf(Blocks.DIRT)
                || state.isOf(Blocks.GRASS_BLOCK) || state.isAir();
    }

    private static long seed(long worldSeed, CubePos pos) {
        long value = worldSeed ^ SALT
                ^ (long) pos.x() * 0x9E3779B97F4A7C15L
                ^ (long) pos.y() * 0xC2B2AE3D27D4EB4FL
                ^ (long) pos.z() * 0x165667B19E3779F9L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
