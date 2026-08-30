package org.devt.higherworld.world;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import org.devt.higherworld.storage.CubePos;

/** Deterministic, order-independent structure placement for sparse cubes. */
final class LegacyStructureGenerator {
    private static final BlockState AIR = Blocks.CAVE_AIR.getDefaultState();

    private LegacyStructureGenerator() {
    }

    static void generate(long seed, LoadedCube cube, StructureGenerationSettings settings) {
        generate(seed, cube, settings, false);
    }

    static void generateVersion6(long seed, LoadedCube cube, StructureGenerationSettings settings) {
        generate(seed, cube, settings, true);
    }

    private static void generate(
            long seed, LoadedCube cube, StructureGenerationSettings settings,
            boolean version6Layout) {
        for (UndergroundStructure structure : UndergroundStructure.values()) {
            if (settings.enables(structure)) {
                generateFamily(seed, cube, structure, version6Layout);
            }
        }
    }

    private static void generateFamily(
            long seed, LoadedCube cube, UndergroundStructure type, boolean version6Layout) {
        StructureSpec spec = spec(type);
        CubePos pos = cube.pos();
        int minX = pos.minBlockX() - spec.radius;
        int maxX = pos.minBlockX() + CubePos.SIZE - 1 + spec.radius;
        int minY = pos.minBlockY() - spec.halfHeight;
        int maxY = pos.minBlockY() + CubePos.SIZE - 1 + spec.halfHeight;
        int minZ = pos.minBlockZ() - spec.radius;
        int maxZ = pos.minBlockZ() + CubePos.SIZE - 1 + spec.radius;

        for (int cellY = Math.floorDiv(minY, spec.verticalSpacing);
                cellY <= Math.floorDiv(maxY, spec.verticalSpacing); cellY++) {
            for (int cellZ = Math.floorDiv(minZ, spec.spacing);
                    cellZ <= Math.floorDiv(maxZ, spec.spacing); cellZ++) {
                for (int cellX = Math.floorDiv(minX, spec.spacing);
                        cellX <= Math.floorDiv(maxX, spec.spacing); cellX++) {
                    long random = hash(seed ^ spec.salt, cellX, cellY, cellZ);
                    if (Math.floorMod(random, spec.rarity) != 0) {
                        continue;
                    }
                    int x = cellX * spec.spacing + spec.margin
                            + (int) Math.floorMod(random >>> 8, spec.spacing - spec.margin * 2);
                    int y = cellY * spec.verticalSpacing + spec.halfHeight
                            + (int) Math.floorMod(random >>> 24,
                                    spec.verticalSpacing - spec.halfHeight * 2);
                    int z = cellZ * spec.spacing + spec.margin
                            + (int) Math.floorMod(random >>> 40, spec.spacing - spec.margin * 2);
                    if (y > -80 || !intersects(cube, x, y, z, spec)) {
                        continue;
                    }
                    place(type, cube, x, y, z, random, version6Layout);
                }
            }
        }
    }

    private static boolean intersects(LoadedCube cube, int x, int y, int z, StructureSpec spec) {
        CubePos pos = cube.pos();
        return x + spec.radius >= pos.minBlockX()
                && x - spec.radius < pos.minBlockX() + CubePos.SIZE
                && y + spec.halfHeight >= pos.minBlockY()
                && y - spec.halfHeight < pos.minBlockY() + CubePos.SIZE
                && z + spec.radius >= pos.minBlockZ()
                && z - spec.radius < pos.minBlockZ() + CubePos.SIZE;
    }

    private static void place(
            UndergroundStructure type, LoadedCube cube, int x, int y, int z,
            long random, boolean version6Layout) {
        switch (type) {
            case MINESHAFT -> {
                if (version6Layout) {
                    placeVersion6Mineshaft(cube, x, y, z, random);
                } else {
                    placeMineshaft(cube, x, y, z, random);
                }
            }
            case STRONGHOLD -> placeStronghold(cube, x, y, z);
            case ANCIENT_CITY -> placeAncientCity(cube, x, y, z);
            case TRIAL_CHAMBERS -> placeTrialChambers(cube, x, y, z);
        }
    }

    private static void placeMineshaft(LoadedCube cube, int x, int y, int z, long random) {
        BlockState planks = Blocks.OAK_PLANKS.getDefaultState();
        boolean alongX = (random & 1L) == 0;
        placeMineshaftCorridor(cube, x, y, z, alongX, 22, planks);
        placeMineshaftCorridor(cube, x, y, z, !alongX, 9, planks);
    }

    private static void placeMineshaftCorridor(
            LoadedCube cube, int x, int y, int z, boolean alongX,
            int halfLength, BlockState planks) {
        for (int forward = -halfLength; forward <= halfLength; forward++) {
            for (int cross = -1; cross <= 1; cross++) {
                int worldX = x + (alongX ? forward : cross);
                int worldZ = z + (alongX ? cross : forward);
                BlockState floor = get(cube, worldX, y - 1, worldZ);
                if (floor != null && floor.isAir()) {
                    set(cube, worldX, y - 1, worldZ, planks);
                }
                for (int dy = 0; dy <= 2; dy++) {
                    set(cube, worldX, y + dy, worldZ, AIR);
                }
            }
        }

        BlockState logs = Blocks.OAK_LOG.getDefaultState();
        int firstFrame = -halfLength + Math.floorMod(halfLength, 5);
        for (int offset = firstFrame; offset <= halfLength; offset += 5) {
            int frameX = x + (alongX ? offset : 0);
            int frameZ = z + (alongX ? 0 : offset);
            for (int dy = -1; dy <= 2; dy++) {
                set(cube, frameX - (alongX ? 0 : 2), y + dy,
                        frameZ - (alongX ? 2 : 0), logs);
                set(cube, frameX + (alongX ? 0 : 2), y + dy,
                        frameZ + (alongX ? 2 : 0), logs);
            }
            box(cube, frameX - (alongX ? 0 : 2), y + 3,
                    frameZ - (alongX ? 2 : 0), frameX + (alongX ? 0 : 2), y + 3,
                    frameZ + (alongX ? 2 : 0), planks);
        }
    }

    private static void placeVersion6Mineshaft(
            LoadedCube cube, int x, int y, int z, long random) {
        BlockState planks = Blocks.OAK_PLANKS.getDefaultState();
        BlockState fence = Blocks.OAK_FENCE.getDefaultState();
        boolean alongX = (random & 1L) == 0;
        box(cube, x - (alongX ? 22 : 2), y - 1, z - (alongX ? 2 : 22),
                x + (alongX ? 22 : 2), y + 3, z + (alongX ? 2 : 22), AIR);
        box(cube, x - (alongX ? 22 : 2), y - 2, z - (alongX ? 2 : 22),
                x + (alongX ? 22 : 2), y - 2, z + (alongX ? 2 : 22), planks);
        for (int offset = -20; offset <= 20; offset += 5) {
            int beamX = x + (alongX ? offset : 0);
            int beamZ = z + (alongX ? 0 : offset);
            box(cube, beamX - (alongX ? 0 : 2), y + 3, beamZ - (alongX ? 2 : 0),
                    beamX + (alongX ? 0 : 2), y + 3, beamZ + (alongX ? 2 : 0), planks);
            set(cube, beamX - (alongX ? 0 : 2), y, beamZ - (alongX ? 2 : 0), fence);
            set(cube, beamX + (alongX ? 0 : 2), y, beamZ + (alongX ? 2 : 0), fence);
        }
    }

    private static void placeStronghold(LoadedCube cube, int x, int y, int z) {
        BlockState bricks = Blocks.STONE_BRICKS.getDefaultState();
        shell(cube, x - 11, y - 4, z - 11, x + 11, y + 5, z + 11, bricks);
        box(cube, x - 3, y - 2, z - 16, x + 3, y + 2, z + 16, AIR);
        box(cube, x - 16, y - 2, z - 3, x + 16, y + 2, z + 3, AIR);
        box(cube, x - 1, y - 3, z - 1, x + 1, y - 3, z + 1,
                Blocks.CHISELED_STONE_BRICKS.getDefaultState());
    }

    private static void placeAncientCity(LoadedCube cube, int x, int y, int z) {
        BlockState tiles = Blocks.DEEPSLATE_TILES.getDefaultState();
        box(cube, x - 18, y - 3, z - 18, x + 18, y + 8, z + 18, AIR);
        box(cube, x - 18, y - 4, z - 18, x + 18, y - 4, z + 18, tiles);
        box(cube, x - 3, y - 3, z - 18, x + 3, y - 3, z + 18,
                Blocks.SCULK.getDefaultState());
        for (int side : new int[] {-12, 12}) {
            box(cube, x + side - 1, y - 3, z - 14, x + side + 1, y + 6, z - 12, tiles);
            box(cube, x + side - 1, y - 3, z + 12, x + side + 1, y + 6, z + 14, tiles);
        }
        box(cube, x - 4, y - 3, z - 2, x + 4, y + 5, z + 2,
                Blocks.REINFORCED_DEEPSLATE.getDefaultState());
        box(cube, x - 2, y - 1, z - 3, x + 2, y + 3, z + 3, AIR);
    }

    private static void placeTrialChambers(LoadedCube cube, int x, int y, int z) {
        BlockState tuff = Blocks.TUFF_BRICKS.getDefaultState();
        shell(cube, x - 13, y - 5, z - 13, x + 13, y + 6, z + 13, tuff);
        box(cube, x - 2, y - 4, z - 13, x + 2, y + 2, z + 13, AIR);
        box(cube, x - 13, y - 4, z - 2, x + 13, y + 2, z + 2, AIR);
        for (int dx : new int[] {-8, 8}) {
            for (int dz : new int[] {-8, 8}) {
                box(cube, x + dx - 1, y - 4, z + dz - 1,
                        x + dx + 1, y + 3, z + dz + 1,
                        Blocks.CUT_COPPER.getDefaultState());
            }
        }
    }

    private static void shell(
            LoadedCube cube, int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ, BlockState wall) {
        box(cube, minX, minY, minZ, maxX, maxY, maxZ, wall);
        box(cube, minX + 1, minY + 1, minZ + 1, maxX - 1, maxY - 1, maxZ - 1, AIR);
    }

    private static void box(
            LoadedCube cube, int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ, BlockState state) {
        CubePos pos = cube.pos();
        int fromX = Math.max(minX, pos.minBlockX());
        int fromY = Math.max(minY, pos.minBlockY());
        int fromZ = Math.max(minZ, pos.minBlockZ());
        int toX = Math.min(maxX, pos.minBlockX() + CubePos.SIZE - 1);
        int toY = Math.min(maxY, pos.minBlockY() + CubePos.SIZE - 1);
        int toZ = Math.min(maxZ, pos.minBlockZ() + CubePos.SIZE - 1);
        for (int worldY = fromY; worldY <= toY; worldY++) {
            for (int worldZ = fromZ; worldZ <= toZ; worldZ++) {
                for (int worldX = fromX; worldX <= toX; worldX++) {
                    set(cube, worldX, worldY, worldZ, state);
                }
            }
        }
    }

    private static void set(LoadedCube cube, int x, int y, int z, BlockState state) {
        CubePos pos = cube.pos();
        if (y >= -64 || x < pos.minBlockX() || x >= pos.minBlockX() + CubePos.SIZE
                || y < pos.minBlockY() || y >= pos.minBlockY() + CubePos.SIZE
                || z < pos.minBlockZ() || z >= pos.minBlockZ() + CubePos.SIZE) {
            return;
        }
        cube.setGeneratedBlockState(
                x - pos.minBlockX(), y - pos.minBlockY(), z - pos.minBlockZ(), state);
    }

    private static BlockState get(LoadedCube cube, int x, int y, int z) {
        CubePos pos = cube.pos();
        if (x < pos.minBlockX() || x >= pos.minBlockX() + CubePos.SIZE
                || y < pos.minBlockY() || y >= pos.minBlockY() + CubePos.SIZE
                || z < pos.minBlockZ() || z >= pos.minBlockZ() + CubePos.SIZE) {
            return null;
        }
        return cube.section().getBlockState(
                x - pos.minBlockX(), y - pos.minBlockY(), z - pos.minBlockZ());
    }

    private static StructureSpec spec(UndergroundStructure type) {
        return switch (type) {
            case MINESHAFT -> new StructureSpec(96, 72, 24, 5, 26, 3, 0x4D494E4553484146L);
            case STRONGHOLD -> new StructureSpec(320, 192, 18, 7, 22, 4, 0x5354524F4E47484FL);
            case ANCIENT_CITY -> new StructureSpec(384, 256, 22, 10, 28, 5, 0x414E4349454E544CL);
            case TRIAL_CHAMBERS -> new StructureSpec(256, 160, 17, 8, 22, 4, 0x545249414C43484DL);
        };
    }

    private static long hash(long seed, int x, int y, int z) {
        long value = seed ^ x * 0x9E3779B97F4A7C15L
                ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
        value = (value ^ value >>> 30) * 0xBF58476D1CE4E5B9L;
        value = (value ^ value >>> 27) * 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    private record StructureSpec(
            int spacing, int verticalSpacing, int radius, int halfHeight,
            int margin, int rarity, long salt) {
    }
}
