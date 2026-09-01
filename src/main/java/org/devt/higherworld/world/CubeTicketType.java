package org.devt.higherworld.world;

/** Sources that keep a 3D cube volume loaded. Lower base priority runs first. */
public enum CubeTicketType {
    COLLISION(0),
    PLAYER(8),
    ENTITY(12),
    PORTAL(16),
    FORCED(20),
    PREGENERATION(40);

    private final int basePriority;

    CubeTicketType(int basePriority) {
        this.basePriority = basePriority;
    }

    public int basePriority() {
        return basePriority;
    }
}
