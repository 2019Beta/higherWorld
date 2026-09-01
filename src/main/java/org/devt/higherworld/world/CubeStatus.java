package org.devt.higherworld.world;

/** Monotonic stages of a cube task graph. */
public enum CubeStatus {
    EMPTY(CubeDependencyRadius.NONE),
    IO_READY(CubeDependencyRadius.NONE),
    TERRAIN(CubeDependencyRadius.NONE),
    FEATURES(new CubeDependencyRadius(1, 1, 1)),
    LIGHT(new CubeDependencyRadius(1, 1, 1)),
    FULL(CubeDependencyRadius.NONE);

    private final CubeDependencyRadius dependencyRadius;

    CubeStatus(CubeDependencyRadius dependencyRadius) {
        this.dependencyRadius = dependencyRadius;
    }

    public CubeStatus localPrerequisite() {
        return switch (this) {
            case EMPTY -> null;
            case IO_READY -> EMPTY;
            case TERRAIN -> IO_READY;
            case FEATURES -> TERRAIN;
            case LIGHT -> FEATURES;
            case FULL -> LIGHT;
        };
    }

    public CubeDependencyRadius dependencyRadius() {
        return dependencyRadius;
    }

    public CubeStatus neighbourPrerequisite() {
        return switch (this) {
            case FEATURES -> TERRAIN;
            case LIGHT -> FEATURES;
            default -> null;
        };
    }

    public boolean isAtLeast(CubeStatus other) {
        return ordinal() >= other.ordinal();
    }
}
