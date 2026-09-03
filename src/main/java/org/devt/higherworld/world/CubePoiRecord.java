package org.devt.higherworld.world;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.util.math.BlockPos;
import org.devt.higherworld.storage.CubePos;

/**
 * A POI entry owned by one sparse cube.
 *
 * <p>The record deliberately stores a local position rather than a
 * {@link BlockPos}.  That keeps the index independent of vanilla's fixed
 * height assumptions and makes the on-disk representation stable at the
 * signed block-coordinate boundary.</p>
 */
public record CubePoiRecord(
        CubePos cube,
        int localX,
        int localY,
        int localZ,
        String type,
        int ticketCount,
        int freeTickets) {
    public static final int MAX_TYPE_BYTES = 256;

    public CubePoiRecord {
        Objects.requireNonNull(cube, "cube");
        if (!cube.isBlockRangeRepresentable()) {
            throw new IllegalArgumentException(
                    "POI cube is outside the signed block range: " + cube);
        }
        if (localX < 0 || localX >= CubePos.SIZE
                || localY < 0 || localY >= CubePos.SIZE
                || localZ < 0 || localZ >= CubePos.SIZE) {
            throw new IllegalArgumentException("POI local position must be inside a cube");
        }
        Objects.requireNonNull(type, "type");
        if (type.isBlank() || type.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("POI type must be a non-empty string");
        }
        if (type.getBytes(StandardCharsets.UTF_8).length > MAX_TYPE_BYTES) {
            throw new IllegalArgumentException("POI type is too long");
        }
        if (ticketCount < 1 || ticketCount > 255) {
            throw new IllegalArgumentException("POI ticket count must be in [1, 255]");
        }
        if (freeTickets < 0 || freeTickets > ticketCount) {
            throw new IllegalArgumentException("POI free tickets must be within ticket count");
        }
    }

    /** Convenience form for a POI with all tickets available. */
    public CubePoiRecord(
            CubePos cube, int localX, int localY, int localZ,
            String type, int ticketCount) {
        this(cube, localX, localY, localZ, type, ticketCount, ticketCount);
    }

    /** Returns this POI's absolute block position without narrowing overflow. */
    public BlockPos blockPos() {
        return new BlockPos(
                Math.addExact(cube.minBlockX(), localX),
                Math.addExact(cube.minBlockY(), localY),
                Math.addExact(cube.minBlockZ(), localZ));
    }

    public boolean isOccupied() {
        return freeTickets < ticketCount;
    }

    /** Returns a reserved copy, or empty when no ticket is available. */
    public Optional<CubePoiRecord> tryReserveTicket() {
        return freeTickets == 0
                ? Optional.empty()
                : Optional.of(new CubePoiRecord(
                        cube, localX, localY, localZ, type,
                        ticketCount, freeTickets - 1));
    }

    /** Releases one ticket, saturating at the POI's declared capacity. */
    public CubePoiRecord releaseTicket() {
        return freeTickets == ticketCount
                ? this
                : new CubePoiRecord(
                        cube, localX, localY, localZ, type,
                        ticketCount, freeTickets + 1);
    }

    public CubePoiRecord withFreeTickets(int tickets) {
        return new CubePoiRecord(cube, localX, localY, localZ, type, ticketCount, tickets);
    }
}
