package org.devt.higherworld.storage;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Immutable serialized entity record owned by one cubic position. */
public record CubeEntityRecord(CubePos owner, UUID uuid, byte[] nbtBytes) {
    public static final int MAX_NBT_BYTES = 2 * 1024 * 1024;

    public CubeEntityRecord {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(nbtBytes, "nbtBytes");
        if (!owner.isBlockRangeRepresentable()) {
            throw new IllegalArgumentException("Entity owner is outside block range: " + owner);
        }
        if (nbtBytes.length > MAX_NBT_BYTES) {
            throw new IllegalArgumentException("Entity NBT exceeds " + MAX_NBT_BYTES + " bytes");
        }
        nbtBytes = nbtBytes.clone();
    }

    @Override
    public byte[] nbtBytes() {
        return nbtBytes.clone();
    }

    CubeEntityRecord withOwner(CubePos newOwner) {
        return new CubeEntityRecord(newOwner, uuid, nbtBytes);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CubeEntityRecord record)) return false;
        return owner.equals(record.owner)
                && uuid.equals(record.uuid)
                && Arrays.equals(nbtBytes, record.nbtBytes);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * owner.hashCode() + uuid.hashCode()) + Arrays.hashCode(nbtBytes);
    }
}
