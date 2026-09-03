package org.devt.higherworld.world;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.tick.TickPriority;

/**
 * The persistent, registry-id based representation of one cube-native tick.
 *
 * <p>Vanilla's {@code OrderedTick} keeps the object instance as its type. That
 * is not safe to persist because raw registry ids are not stable between
 * server launches. Cube ticks therefore keep the resource id, absolute
 * trigger time, vanilla priority index and deterministic sub-tick order.</p>
 */
public record CubeScheduledTick(
        Kind kind,
        String typeId,
        BlockPos pos,
        long triggerTick,
        int priority,
        long subTickOrder) {
    public static final int MAX_TYPE_ID_LENGTH = 256;

    public CubeScheduledTick {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(typeId, "typeId");
        Objects.requireNonNull(pos, "pos");
        if (typeId.isBlank() || typeId.length() > MAX_TYPE_ID_LENGTH) {
            throw new IllegalArgumentException("Invalid scheduled tick type id length");
        }
        boolean knownPriority = false;
        for (TickPriority candidate : TickPriority.values()) {
            if (candidate.getIndex() == priority) {
                knownPriority = true;
                break;
            }
        }
        if (!knownPriority) {
            throw new IllegalArgumentException("Invalid scheduled tick priority: " + priority);
        }
        if (subTickOrder < 0L) {
            throw new IllegalArgumentException("Negative scheduled tick sub-tick order");
        }
        pos = pos.toImmutable();
    }

    static CubeScheduledTick block(
            BlockPos pos, String typeId, long triggerTick, TickPriority priority, long subTickOrder) {
        return new CubeScheduledTick(
                Kind.BLOCK, typeId, pos, triggerTick, priority.getIndex(), subTickOrder);
    }

    static CubeScheduledTick fluid(
            BlockPos pos, String typeId, long triggerTick, TickPriority priority, long subTickOrder) {
        return new CubeScheduledTick(
                Kind.FLUID, typeId, pos, triggerTick, priority.getIndex(), subTickOrder);
    }

    CubeScheduledTick withTriggerTick(long newTriggerTick) {
        return new CubeScheduledTick(kind, typeId, pos, newTriggerTick, priority, subTickOrder);
    }

    TickPriority tickPriority() {
        return TickPriority.byIndex(priority);
    }

    /** Writes a bounded, version-independent record used by HWC5. */
    void write(DataOutput output) throws IOException {
        byte[] id = typeId.getBytes(StandardCharsets.UTF_8);
        if (id.length == 0 || id.length > MAX_TYPE_ID_LENGTH) {
            throw new IOException("Scheduled tick type id is too long");
        }
        output.writeByte(kind.ordinal());
        output.writeShort(id.length);
        output.write(id);
        output.writeInt(pos.getX());
        output.writeInt(pos.getY());
        output.writeInt(pos.getZ());
        output.writeLong(triggerTick);
        output.writeByte(priority);
        output.writeLong(subTickOrder);
    }

    static CubeScheduledTick read(DataInput input) throws IOException {
        int kindIndex = input.readUnsignedByte();
        Kind[] kinds = Kind.values();
        if (kindIndex >= kinds.length) {
            throw new IOException("Invalid scheduled tick kind: " + kindIndex);
        }
        int length = input.readUnsignedShort();
        if (length == 0 || length > MAX_TYPE_ID_LENGTH) {
            throw new IOException("Invalid scheduled tick type id length: " + length);
        }
        byte[] id = new byte[length];
        readFully(input, id);
        String typeId = new String(id, StandardCharsets.UTF_8);
        int x = input.readInt();
        int y = input.readInt();
        int z = input.readInt();
        long triggerTick = input.readLong();
        int priority = input.readByte();
        long subTickOrder = input.readLong();
        try {
            return new CubeScheduledTick(
                    kinds[kindIndex], typeId, new BlockPos(x, y, z),
                    triggerTick, priority, subTickOrder);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid scheduled tick record", exception);
        }
    }

    private static void readFully(DataInput input, byte[] bytes) throws IOException {
        int offset = 0;
        while (offset < bytes.length) {
            try {
                bytes[offset++] = input.readByte();
            } catch (EOFException exception) {
                throw new IOException("Truncated scheduled tick type id", exception);
            }
        }
    }

    public enum Kind {
        BLOCK,
        FLUID
    }
}
