package org.devt.higherworld.world;

import org.devt.higherworld.storage.CubePos;

/** An anisotropic 3D dependency radius; vertical work is never implied by X/Z. */
public record CubeDependencyRadius(int x, int y, int z) {
    public static final CubeDependencyRadius NONE = new CubeDependencyRadius(0, 0, 0);

    public CubeDependencyRadius {
        if (x < 0 || y < 0 || z < 0) {
            throw new IllegalArgumentException("Cube dependency radii cannot be negative");
        }
    }

    public int volume() {
        return Math.multiplyExact(Math.multiplyExact(2 * x + 1, 2 * y + 1), 2 * z + 1);
    }

    public void forEach(CubePos center, java.util.function.Consumer<CubePos> consumer) {
        for (int offsetY = -y; offsetY <= y; offsetY++) {
            for (int offsetZ = -z; offsetZ <= z; offsetZ++) {
                for (int offsetX = -x; offsetX <= x; offsetX++) {
                    consumer.accept(new CubePos(
                            Math.addExact(center.x(), offsetX),
                            Math.addExact(center.y(), offsetY),
                            Math.addExact(center.z(), offsetZ)));
                }
            }
        }
    }
}
