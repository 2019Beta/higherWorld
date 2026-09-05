package org.devt.higherworld.client;

import net.minecraft.text.Text;
import org.devt.higherworld.world.TerrainGeneratorStatusPayload;

/** Holds the server's terrain backend status for the client-side F3 line. */
public final class TerrainGeneratorDebugHud {
    private static final int MAX_DISPLAY_DETAIL_LENGTH = 96;
    private static volatile TerrainGeneratorStatusPayload status;

    private TerrainGeneratorDebugHud() {
    }

    public static void update(TerrainGeneratorStatusPayload next) {
        status = next;
    }

    public static void clear() {
        status = null;
    }

    public static String line() {
        TerrainGeneratorStatusPayload current = status;
        if (current == null) {
            return Text.translatable(
                    "debug.higherworld.terrain_generator",
                    Text.translatable("debug.higherworld.terrain_generator.unknown")).getString();
        }

        String detail = current.detail();
        if (detail.length() > MAX_DISPLAY_DETAIL_LENGTH) {
            detail = detail.substring(0, MAX_DISPLAY_DETAIL_LENGTH - 3) + "...";
        }
        String owner = Text.translatable(
                current.openCl()
                        ? "debug.higherworld.terrain_generator.opencl"
                        : "debug.higherworld.terrain_generator.cpu",
                detail).getString();
        return Text.translatable("debug.higherworld.terrain_generator", owner).getString();
    }
}
