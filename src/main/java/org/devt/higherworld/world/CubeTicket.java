package org.devt.higherworld.world;

import java.util.Objects;
import org.devt.higherworld.storage.CubePos;

/** One replaceable ticket. Its key identifies the owner, such as a player UUID. */
public record CubeTicket(
        Object key,
        CubeTicketType type,
        CubePos center,
        CubeDependencyRadius radius,
        CubeStatus targetStatus,
        int priority) {
    public CubeTicket {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(radius, "radius");
        Objects.requireNonNull(targetStatus, "targetStatus");
    }

    public int effectivePriority() {
        return Math.addExact(type.basePriority(), priority);
    }

    public static CubeTicket player(Object key, CubePos center, int horizontalRadius, int verticalRadius) {
        return new CubeTicket(key, CubeTicketType.PLAYER, center,
                new CubeDependencyRadius(horizontalRadius, verticalRadius, horizontalRadius),
                CubeStatus.FULL, 0);
    }

    /**
     * The player's simulation demand must not reuse the streaming-view key.
     * Streaming roots are retained separately at PAYLOAD, while this smaller
     * ticket promotes only the server simulation distance to FULL.
     */
    public static CubeTicket playerSimulation(
            Object owner, CubePos center, int horizontalRadius, int verticalRadius) {
        return new CubeTicket(playerSimulationKey(owner), CubeTicketType.PLAYER, center,
                new CubeDependencyRadius(horizontalRadius, verticalRadius, horizontalRadius),
                CubeStatus.FULL, 0);
    }

    public static Object playerSimulationKey(Object owner) {
        return new PlayerSimulationKey(owner);
    }

    private record PlayerSimulationKey(Object owner) {}
}
