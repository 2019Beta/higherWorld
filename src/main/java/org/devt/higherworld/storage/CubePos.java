package org.devt.higherworld.storage;

/** Coordinates of one 16 x 16 x 16 cube. */
public record CubePos(int x, int y, int z) {
    public static final int SIZE = 16;
    public static final int REGION_DIAMETER = 16;

    /** Disperse spatial neighbours in the scheduler's hash maps and immutable sets. */
    @Override
    public int hashCode() {
        // The record default (961*x + 31*y + z) clusters dense cuboids and
        // aliases coordinates separated by (0, 1, -31). Mix each axis before
        // combining; a final avalanche also disperses open-addressed SetN slots.
        int hash = mix(x) ^ Integer.rotateLeft(mix(y), 11) ^ Integer.rotateLeft(mix(z), 22);
        return mix(hash);
    }

    private static int mix(int value) {
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        return value ^ (value >>> 16);
    }

    public static CubePos fromBlock(int blockX, int blockY, int blockZ) {
        return new CubePos(Math.floorDiv(blockX, SIZE), Math.floorDiv(blockY, SIZE), Math.floorDiv(blockZ, SIZE));
    }

    public static CubePos fromSection(int sectionX, int sectionY, int sectionZ) {
        return new CubePos(sectionX, sectionY, sectionZ);
    }

    public int minBlockX() {
        return Math.multiplyExact(x, SIZE);
    }

    public int minBlockY() {
        return Math.multiplyExact(y, SIZE);
    }

    public int minBlockZ() {
        return Math.multiplyExact(z, SIZE);
    }

    public boolean isBlockRangeRepresentable() {
        return coordinateRangeRepresentable(x) && coordinateRangeRepresentable(y)
                && coordinateRangeRepresentable(z);
    }

    private static boolean coordinateRangeRepresentable(int cubeCoordinate) {
        long minimum = (long) cubeCoordinate * SIZE;
        return minimum >= Integer.MIN_VALUE && minimum + SIZE - 1L <= Integer.MAX_VALUE;
    }

    RegionPos region() {
        return new RegionPos(
                Math.floorDiv(x, REGION_DIAMETER),
                Math.floorDiv(y, REGION_DIAMETER),
                Math.floorDiv(z, REGION_DIAMETER));
    }

    int localIndex() {
        int localX = Math.floorMod(x, REGION_DIAMETER);
        int localY = Math.floorMod(y, REGION_DIAMETER);
        int localZ = Math.floorMod(z, REGION_DIAMETER);
        return localX | localY << 4 | localZ << 8;
    }
}
