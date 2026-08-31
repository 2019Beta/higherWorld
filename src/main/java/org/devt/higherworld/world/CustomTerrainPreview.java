package org.devt.higherworld.world;

import java.util.Objects;

/**
 * Public, side-effect-free access to the custom terrain equation for clients
 * and other preview tools.  The generator remains the single source of truth;
 * this class deliberately does not copy its noise or density implementation.
 */
public final class CustomTerrainPreview {
    private CustomTerrainPreview() {
    }

    public static double density(
            long seed, CustomWorldSettings settings, double x, double y, double z) {
        return CustomCubeGenerator.terrainDensity(
                seed, Objects.requireNonNull(settings), x, y, z);
    }
}
