package org.devt.higherworld.world;

/** Shared light-level policy for cubes outside vanilla's fixed-height range. */
public final class CubeLightMath {
    private CubeLightMath() {
    }

    /**
     * Combines sky light after ambient-darkness adjustment with block emission.
     * Vanilla's world lookup is not authoritative for an external cube, so the
     * caller supplies the block luminance read from the sparse cube cache.
     */
    public static int externalBaseLightLevel(
            int skyLight, int ambientDarkness, int blockLuminance) {
        return Math.max(Math.max(0, skyLight - ambientDarkness), blockLuminance);
    }
}
