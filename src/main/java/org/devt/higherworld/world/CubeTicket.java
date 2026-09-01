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
}
