package org.devt.higherworld.world;

/** Monotonic stages of a cube task graph. */
public enum CubeStatus {
    EMPTY(CubeDependencyRadius.NONE),
    IO_READY(CubeDependencyRadius.NONE),
    TERRAIN(new CubeDependencyRadius(1, 0, 1)),
    FEATURES(new CubeDependencyRadius(1, 1, 1)),
    LIGHT(new CubeDependencyRadius(1, 1, 1)),
    FULL(CubeDependencyRadius.NONE);

    private final CubeDependencyRadius dependencyRadius;

    CubeStatus(CubeDependencyRadius dependencyRadius) {
        this.dependencyRadius = dependencyRadius;
    }

    public CubeDependencyRadius dependencyRadius() {
        return dependencyRadius;
    }

    public boolean isAtLeast(CubeStatus other) {
        return ordinal() >= other.ordinal();
    }
}
