package org.devt.higherworld.world;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import org.devt.higherworld.storage.CubePos;

/**
 * The configurable cave walker from CubicWorldGen, adapted to a single sparse
 * cube.  All decisions are local to a source cube and seeded with its position,
 * so loading two columns in a different order cannot alter either result.
 */
final class CustomCaveGenerator {
    private static final long CAVE_SALT = 0x4C554341564543L;
    private static final long RAVINE_SALT = 0x524156494E455L;
    private static final int RANGE = 8;
    private static final int MAX_BLOCK_RADIUS = (RANGE - 1) * CubePos.SIZE;

    // The old generator had no independent ravine page.  These are its fixed
    // 1.12-era values, kept here rather than smuggling them into the settings.
    private static final int RAVINE_RARITY = 100;
    private static final double RAVINE_LAVA_HEIGHT_OFFSET = -10.0;
    private static final double RAVINE_VERTICAL_FACTOR = -0.1;
    private static final double RAVINE_VERTICAL_SIZE_FACTOR = 3.0;
    private static final double RAVINE_SIZE_ADD = 1.5;
    private static final double RAVINE_FLATTEN_FACTOR = 0.7;
    private static final double RAVINE_DIRECTION_FACTOR = 0.05;
    private static final double RAVINE_PREVIOUS_HORIZ_WEIGHT = 0.5;
    private static final double RAVINE_PREVIOUS_VERT_WEIGHT = 0.8;
    private static final double RAVINE_MAX_ADD_HORIZ = 4.0;
    private static final double RAVINE_MAX_ADD_VERT = 2.0;
    private static final int RAVINE_CARVE_STEP_RARITY = 4;
    private static final double RAVINE_Y_STRETCH = 6.0;

    private CustomCaveGenerator() {
    }

    static void generate(ServerWorld world, LoadedCube cube, List<CustomWorldSettings.CaveSettings> caves) {
        for (int index = 0; index < caves.size(); index++) generate(world, cube, caves, index);
    }

    static void generate(
            ServerWorld world, LoadedCube cube, List<CustomWorldSettings.CaveSettings> caves, int index) {
        CubePos pos = cube.pos();
        CustomWorldSettings.CaveSettings settings = caves.get(index);
        if (pos.y() < settings.caveMinHeight() || pos.y() > settings.caveMaxHeight()) {
            return;
        }
        BlockState caveBlock = CustomBlockStateResolver.resolve(world, settings.caveBlock());
        List<BlockState> replaceableStates = CustomBlockStateResolver.resolveAll(
                world, settings.isBlockReplaceable());
        if (caveBlock == null || replaceableStates == null || replaceableStates.isEmpty()) {
            return;
        }
        Set<Block> replaceable = new HashSet<>();
        for (BlockState state : replaceableStates) {
            replaceable.add(state.getBlock());
        }
        Random random = new Random(seed(world.getSeed(), pos, index, CAVE_SALT));
        if (random.nextInt(settings.caveRarity()) != 0) {
            return;
        }
        int nodeCount = random.nextInt(
                random.nextInt(random.nextInt(settings.maxInitNodes() + 1) + 1) + 1);
        for (int node = 0; node < nodeCount; node++) {
            double x = pos.minBlockX() + random.nextInt(CubePos.SIZE);
            double y = pos.minBlockY() + random.nextInt(CubePos.SIZE);
            double z = pos.minBlockZ() + random.nextInt(CubePos.SIZE);
            int branches = 1;
            if (random.nextInt(settings.largeNodeRarity()) == 0) {
                generateNode(random.nextLong(), cube, caveBlock, replaceable,
                        x, y, z, 0.0, 0.0, 1.0 + random.nextDouble() * 6.0,
                        0.5, -1, -1, settings);
                branches += random.nextInt(settings.largeNodeMaxBranches());
            }
            for (int branch = 0; branch < branches; branch++) {
                float horizontalAngle = random.nextFloat() * (float) (Math.PI * 2.0);
                float verticalAngle = (random.nextFloat() - 0.5f) * 2.0f / 8.0f;
                double size = random.nextFloat() * 2.0 + random.nextFloat();
                if (random.nextInt(settings.bigCaveRarity()) == 0) {
                    size *= random.nextFloat() * random.nextFloat() * 3.0 + 1.0;
                }
                generateNode(random.nextLong(), cube, caveBlock, replaceable,
                        x, y, z, horizontalAngle, verticalAngle, size, 1.0,
                        0, 0, settings);
            }
        }
    }

    private static void generateNode(
            long nodeSeed, LoadedCube cube, BlockState caveBlock, Set<Block> replaceable,
            double x, double y, double z, double horizontalAngle, double verticalAngle,
            double baseSize, double verticalModifier, int start, int maximumWalked,
            CustomWorldSettings.CaveSettings settings) {
        Random random = new Random(nodeSeed);
        double horizontalChange = 0.0;
        double verticalChange = 0.0;
        boolean finalStep = start == -1;
        if (maximumWalked <= 0) {
            maximumWalked = MAX_BLOCK_RADIUS - random.nextInt(MAX_BLOCK_RADIUS / 4);
        }
        int walked = finalStep ? maximumWalked / 2 : start;
        int splitPoint = random.nextInt(maximumWalked / 2) + maximumWalked / 4;
        while (walked < maximumWalked) {
            double fraction = walked / (double) maximumWalked;
            double horizontalSize = settings.caveSizeAdd()
                    + Math.sin(fraction * Math.PI) * baseSize;
            double verticalSize = horizontalSize * verticalModifier;
            double horizontalFactor = Math.cos(verticalAngle);
            double verticalFactor = Math.sin(verticalAngle);
            x += Math.cos(horizontalAngle) * horizontalFactor;
            y += verticalFactor;
            z += Math.sin(horizontalAngle) * horizontalFactor;
            verticalAngle *= random.nextInt(settings.steepStepRarity()) == 0
                    ? settings.steeperFlattenFactor() : settings.flattenFactor();
            verticalAngle += verticalChange * settings.directionChangeFactor();
            horizontalAngle += horizontalChange * settings.directionChangeFactor();
            verticalChange *= settings.prevVertDirectionChangeWeight();
            horizontalChange *= settings.prevHorizDirectionChangeWeight();
            verticalChange += (random.nextFloat() - random.nextFloat())
                    * random.nextFloat() * settings.maxAddDirectionChangeVert();
            horizontalChange += (random.nextFloat() - random.nextFloat())
                    * random.nextFloat() * settings.maxAddDirectionChangeHoriz();

            if (!finalStep && walked == splitPoint && baseSize > 1.0) {
                generateNode(random.nextLong(), cube, caveBlock, replaceable,
                        x, y, z, horizontalAngle - Math.PI / 2.0, verticalAngle / 3.0,
                        random.nextFloat() * 0.5 + 0.5, 1.0,
                        walked, maximumWalked, settings);
                generateNode(random.nextLong(), cube, caveBlock, replaceable,
                        x, y, z, horizontalAngle + Math.PI / 2.0, verticalAngle / 3.0,
                        random.nextFloat() * 0.5 + 0.5, 1.0,
                        walked, maximumWalked, settings);
                return;
            }
            if (random.nextInt(settings.carveStepRarity()) != 0 && !finalStep) {
                walked++;
                continue;
            }

            double remaining = maximumWalked - walked;
            double maxDistanceToCube = baseSize * Math.max(1.0, verticalModifier)
                    + settings.caveSizeAdd() + CubePos.SIZE;
            double xDistance = x - (cube.pos().minBlockX() + CubePos.SIZE / 2.0);
            double yDistance = y - (cube.pos().minBlockY() + CubePos.SIZE / 2.0);
            double zDistance = z - (cube.pos().minBlockZ() + CubePos.SIZE / 2.0);
            if (xDistance * xDistance + yDistance * yDistance + zDistance * zDistance
                    - remaining * remaining > maxDistanceToCube * maxDistanceToCube) {
                return;
            }
            carve(cube, caveBlock, replaceable, x, y, z,
                    horizontalSize, verticalSize, settings.caveFloorDepth());
            if (finalStep) {
                return;
            }
            walked++;
        }
    }

    private static void carve(
            LoadedCube cube, BlockState caveBlock, Set<Block> replaceable,
            double centerX, double centerY, double centerZ,
            double radiusXZ, double radiusY, double floorDepth) {
        if (radiusXZ <= 0.0 || radiusY <= 0.0) {
            return;
        }
        CubePos cubePos = cube.pos();
        int minX = Math.max(0, (int) Math.floor(centerX - radiusXZ) - cubePos.minBlockX() - 1);
        int maxX = Math.min(CubePos.SIZE, (int) Math.floor(centerX + radiusXZ)
                - cubePos.minBlockX() + 1);
        int minY = Math.max(0, (int) Math.floor(centerY - radiusY) - cubePos.minBlockY() - 1);
        int maxY = Math.min(CubePos.SIZE, (int) Math.floor(centerY + radiusY)
                - cubePos.minBlockY() + 1);
        int minZ = Math.max(0, (int) Math.floor(centerZ - radiusXZ) - cubePos.minBlockZ() - 1);
        int maxZ = Math.min(CubePos.SIZE, (int) Math.floor(centerZ + radiusXZ)
                - cubePos.minBlockZ() + 1);
        for (int localY = minY; localY < maxY; localY++) {
            double dy = (cubePos.minBlockY() + localY + 0.5 - centerY) / radiusY;
            for (int localZ = minZ; localZ < maxZ; localZ++) {
                double dz = (cubePos.minBlockZ() + localZ + 0.5 - centerZ) / radiusXZ;
                for (int localX = minX; localX < maxX; localX++) {
                    double dx = (cubePos.minBlockX() + localX + 0.5 - centerX) / radiusXZ;
                    double horizontal = dx * dx + dz * dz;
                    if (horizontal >= 1.0 || dy * dy + horizontal >= 1.0) {
                        continue;
                    }
                    BlockState current = cube.section().getBlockState(localX, localY, localZ);
                    if (!replaceable.contains(current.getBlock())) {
                        continue;
                    }
                    if (dy > floorDepth) {
                        cube.setGeneratedBlockState(localX, localY, localZ, caveBlock);
                    } else if (current.isOf(Blocks.DIRT) && localY < CubePos.SIZE - 1) {
                        // Vanilla's cave pass promotes dirt to grass when the
                        // block above would be carved during this same pass.
                        // Check the ellipsoid directly instead of inspecting
                        // the not-yet-visited block above.
                        double dyAbove = (cubePos.minBlockY() + localY + 1.5 - centerY) / radiusY;
                        if (dyAbove > floorDepth && dyAbove * dyAbove + horizontal < 1.0) {
                            cube.setGeneratedBlockState(localX, localY, localZ,
                                    Blocks.GRASS_BLOCK.getDefaultState());
                        }
                    }
                }
            }
        }
    }

    static void generateRavine(ServerWorld world, LoadedCube cube) {
        Random random = new Random(seed(world.getSeed(), cube.pos(), 0, RAVINE_SALT));
        if (random.nextInt(RAVINE_RARITY) != 0) {
            return;
        }
        CubePos pos = cube.pos();
        double startX = pos.minBlockX() + random.nextInt(CubePos.SIZE);
        double startY = pos.minBlockY() + random.nextInt(CubePos.SIZE);
        double startZ = pos.minBlockZ() + random.nextInt(CubePos.SIZE);
        double verticalAngle = random.nextDouble() * Math.PI * 2.0;
        double horizontalAngle = (random.nextDouble() - 0.5) * 2.0 / 8.0;
        double baseSize = (random.nextDouble() * 2.0 + random.nextDouble()) * 2.0;
        int lavaHeight = (int) (startY - (baseSize + RAVINE_SIZE_ADD)
                * RAVINE_VERTICAL_SIZE_FACTOR + RAVINE_LAVA_HEIGHT_OFFSET
                + startY * RAVINE_VERTICAL_FACTOR);
        generateRavineNode(random.nextLong(), cube, startX, startY, startZ,
                baseSize, horizontalAngle, verticalAngle, lavaHeight);
    }

    private static void generateRavineNode(
            long nodeSeed, LoadedCube cube, double x, double y, double z,
            double baseSize, double horizontalAngle, double verticalAngle,
            int lavaHeight) {
        Random random = new Random(nodeSeed);
        double horizontalChange = 0.0;
        double verticalChange = 0.0;
        int maximumWalked = MAX_BLOCK_RADIUS
                - random.nextInt(MAX_BLOCK_RADIUS / 4);
        float[] widthFactors = generateRavineWidthFactors(random);
        for (int walked = 0; walked < maximumWalked; walked++) {
            double fraction = walked / (double) maximumWalked;
            double horizontalSize = RAVINE_SIZE_ADD
                    + Math.sin(fraction * Math.PI) * baseSize;
            double verticalSize = horizontalSize * RAVINE_VERTICAL_SIZE_FACTOR;
            horizontalSize *= random.nextDouble() * 0.25 + 0.75;
            verticalSize *= random.nextDouble() * 0.25 + 0.75;

            double horizontalFactor = Math.cos(verticalAngle);
            double verticalFactor = Math.sin(verticalAngle);
            x += Math.cos(horizontalAngle) * horizontalFactor;
            y += verticalFactor;
            z += Math.sin(horizontalAngle) * horizontalFactor;
            verticalAngle *= RAVINE_FLATTEN_FACTOR;
            verticalAngle += verticalChange * RAVINE_DIRECTION_FACTOR;
            horizontalAngle += horizontalChange * RAVINE_DIRECTION_FACTOR;
            verticalChange *= RAVINE_PREVIOUS_VERT_WEIGHT;
            horizontalChange *= RAVINE_PREVIOUS_HORIZ_WEIGHT;
            verticalChange += (random.nextFloat() - random.nextFloat())
                    * random.nextFloat() * RAVINE_MAX_ADD_VERT;
            horizontalChange += (random.nextFloat() - random.nextFloat())
                    * random.nextFloat() * RAVINE_MAX_ADD_HORIZ;

            if (random.nextInt(RAVINE_CARVE_STEP_RARITY) == 0) {
                continue;
            }
            double remaining = maximumWalked - walked;
            double maxDistanceToCube = baseSize + RAVINE_SIZE_ADD + CubePos.SIZE;
            double xDistance = x - (cube.pos().minBlockX() + CubePos.SIZE / 2.0);
            double zDistance = z - (cube.pos().minBlockZ() + CubePos.SIZE / 2.0);
            if (xDistance * xDistance + zDistance * zDistance
                    - remaining * remaining > maxDistanceToCube * maxDistanceToCube) {
                return;
            }
            carveRavineSection(cube, x, y, z, horizontalSize, verticalSize,
                    lavaHeight, widthFactors);
        }
    }

    private static void carveRavineSection(
            LoadedCube cube, double centerX, double centerY, double centerZ,
            double radiusXZ, double radiusY, int lavaHeight, float[] widthFactors) {
        if (radiusXZ <= 0.0 || radiusY <= 0.0) {
            return;
        }
        CubePos pos = cube.pos();
        int minX = Math.max(0, (int) Math.floor(centerX - radiusXZ)
                - pos.minBlockX() - 1);
        int maxX = Math.min(CubePos.SIZE, (int) Math.floor(centerX + radiusXZ)
                - pos.minBlockX() + 1);
        int minY = Math.max(0, (int) Math.floor(centerY - radiusY)
                - pos.minBlockY() - 1);
        int maxY = Math.min(CubePos.SIZE, (int) Math.floor(centerY + radiusY)
                - pos.minBlockY() + 1);
        int minZ = Math.max(0, (int) Math.floor(centerZ - radiusXZ)
                - pos.minBlockZ() - 1);
        int maxZ = Math.min(CubePos.SIZE, (int) Math.floor(centerZ + radiusXZ)
                - pos.minBlockZ() + 1);
        for (int localX = minX; localX < maxX; localX++) {
            double dx = (pos.minBlockX() + localX + 0.5 - centerX) / radiusXZ;
            for (int localZ = minZ; localZ < maxZ; localZ++) {
                double dz = (pos.minBlockZ() + localZ + 0.5 - centerZ) / radiusXZ;
                if (dx * dx + dz * dz >= 1.0) {
                    continue;
                }
                for (int localY = minY; localY < maxY; localY++) {
                    double dy = (pos.minBlockY() + localY + 0.5 - centerY) / radiusY;
                    int widthIndex = (pos.y() * CubePos.SIZE + localY) & 0xFF;
                    if ((dx * dx + dz * dz) * widthFactors[widthIndex]
                            + dy * dy / RAVINE_Y_STRETCH >= 1.0) {
                        continue;
                    }
                    BlockState state = cube.section().getBlockState(localX, localY, localZ);
                    if (!isNatural(state)) {
                        continue;
                    }
                    cube.setGeneratedBlockState(localX, localY, localZ,
                            pos.minBlockY() + localY < lavaHeight
                                    ? Blocks.LAVA.getDefaultState()
                                    : Blocks.AIR.getDefaultState());
                }
            }
        }
    }

    private static float[] generateRavineWidthFactors(Random random) {
        float[] values = new float[256];
        float value = 1.0f;
        for (int i = 0; i < values.length; i++) {
            if (i == 0 || random.nextInt(3) == 0) {
                value = 1.0f + random.nextFloat() * random.nextFloat();
            }
            values[i] = value * value;
        }
        return values;
    }

    private static boolean isNatural(BlockState state) {
        return state.isOf(Blocks.STONE) || state.isOf(Blocks.DEEPSLATE)
                || state.isOf(Blocks.TUFF) || state.isOf(Blocks.DIRT)
                || state.isOf(Blocks.GRASS_BLOCK);
    }

    private static long seed(long worldSeed, CubePos pos, int index, long salt) {
        long value = worldSeed ^ salt;
        value ^= (long) pos.x() * 0x9E3779B97F4A7C15L;
        value ^= (long) pos.y() * 0xC2B2AE3D27D4EB4FL;
        value ^= (long) pos.z() * 0x165667B19E3779F9L;
        value ^= (long) index * 0xD6E8FEB86659FD93L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
