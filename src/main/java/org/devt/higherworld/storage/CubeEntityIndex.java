package org.devt.higherworld.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Thread-safe sparse index for serialized entities owned by cubes.
 *
 * <p>The index is deliberately independent of live Minecraft entities.  A
 * server-thread adapter can remove an entity from one cube, move its opaque
 * NBT record to another cube, and restore a cube after load without exposing
 * mutable payloads or relying on vanilla's finite-height chunk arrays.</p>
 */
public final class CubeEntityIndex {
    private static final int MAGIC = 0x48574531; // "HWE1"
    private static final int VERSION = 1;
    public static final int MAX_RECORDS = 32_768;
    public static final int MAX_ENCODED_BYTES = 16 * 1024 * 1024;

    private static final Comparator<CubeEntityRecord> ORDER =
            Comparator.comparing(CubeEntityRecord::uuid);

    private final Map<UUID, CubeEntityRecord> byUuid = new HashMap<>();
    private final Map<CubePos, Set<UUID>> byCube = new HashMap<>();

    /** Adds one entity; duplicate UUIDs are rejected instead of overwritten. */
    public synchronized boolean add(CubeEntityRecord record) {
        Objects.requireNonNull(record, "record");
        if (byUuid.containsKey(record.uuid())) {
            throw new IllegalArgumentException("Duplicate entity UUID: " + record.uuid());
        }
        if (byUuid.size() >= MAX_RECORDS) {
            throw new IllegalStateException("Too many cube entities: " + byUuid.size());
        }
        putUnchecked(record);
        return true;
    }

    public synchronized Optional<CubeEntityRecord> get(UUID uuid) {
        return Optional.ofNullable(byUuid.get(Objects.requireNonNull(uuid, "uuid")));
    }

    public synchronized boolean contains(UUID uuid) {
        return byUuid.containsKey(Objects.requireNonNull(uuid, "uuid"));
    }

    /** Removes one entity, returning its last owner and payload snapshot. */
    public synchronized Optional<CubeEntityRecord> remove(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        CubeEntityRecord removed = byUuid.remove(uuid);
        if (removed != null) removeFromCube(removed.owner(), uuid);
        return Optional.ofNullable(removed);
    }

    /** Moves an entity between cubes without changing its UUID or NBT. */
    public synchronized Optional<CubeEntityRecord> move(UUID uuid, CubePos newOwner) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(newOwner, "newOwner");
        CubeEntityRecord current = byUuid.get(uuid);
        if (current == null) return Optional.empty();
        if (current.owner().equals(newOwner)) return Optional.of(current);
        CubeEntityRecord moved = current.withOwner(newOwner);
        removeFromCube(current.owner(), uuid);
        byUuid.put(uuid, moved);
        byCube.computeIfAbsent(newOwner, ignored -> new HashSet<>()).add(uuid);
        return Optional.of(moved);
    }

    /** Removes every entity owned by one cube and returns a stable snapshot. */
    public synchronized List<CubeEntityRecord> removeCube(CubePos owner) {
        Objects.requireNonNull(owner, "owner");
        List<CubeEntityRecord> removed = snapshot(owner);
        for (CubeEntityRecord record : removed) byUuid.remove(record.uuid());
        byCube.remove(owner);
        return removed;
    }

    /** Returns entities owned by one cube in deterministic UUID order. */
    public synchronized List<CubeEntityRecord> snapshot(CubePos owner) {
        Objects.requireNonNull(owner, "owner");
        Set<UUID> ids = byCube.get(owner);
        if (ids == null || ids.isEmpty()) return List.of();
        List<CubeEntityRecord> result = new ArrayList<>(ids.size());
        for (UUID uuid : ids) {
            CubeEntityRecord record = byUuid.get(uuid);
            if (record != null) result.add(record);
        }
        result.sort(ORDER);
        return List.copyOf(result);
    }

    /** Returns a deterministic snapshot of all currently indexed entities. */
    public synchronized List<CubeEntityRecord> snapshotAll() {
        List<CubeEntityRecord> result = new ArrayList<>(byUuid.values());
        result.sort(ORDER);
        return List.copyOf(result);
    }

    /**
     * Restores one cube atomically. Every record must name the requested owner;
     * any duplicate UUID or invalid cross-cube record rejects the whole batch.
     */
    public synchronized int restore(CubePos owner, Collection<CubeEntityRecord> records) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(records, "records");
        List<CubeEntityRecord> pending = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (CubeEntityRecord record : records) {
            Objects.requireNonNull(record, "record");
            if (!owner.equals(record.owner())) {
                throw new IllegalArgumentException("Entity record belongs to another cube");
            }
            if (!seen.add(record.uuid()) || byUuid.containsKey(record.uuid())) {
                throw new IllegalArgumentException("Duplicate entity UUID: " + record.uuid());
            }
            pending.add(record);
            if (byUuid.size() + pending.size() > MAX_RECORDS) {
                throw new IllegalStateException("Too many cube entities");
            }
        }
        for (CubeEntityRecord record : pending) putUnchecked(record);
        return pending.size();
    }

    public synchronized int size() {
        return byUuid.size();
    }

    public synchronized int cubeCount() {
        return byCube.size();
    }

    /** Encodes all records in deterministic UUID order with strict size bounds. */
    public synchronized byte[] encode() {
        if (byUuid.size() > MAX_RECORDS) {
            throw new IllegalStateException("Too many cube entities: " + byUuid.size());
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(byUuid.size());
            for (CubeEntityRecord record : snapshotAll()) {
                byte[] nbt = record.nbtBytes();
                output.writeLong(record.uuid().getMostSignificantBits());
                output.writeLong(record.uuid().getLeastSignificantBits());
                output.writeInt(record.owner().x());
                output.writeInt(record.owner().y());
                output.writeInt(record.owner().z());
                output.writeInt(nbt.length);
                output.write(nbt);
                if (bytes.size() > MAX_ENCODED_BYTES) {
                    throw new IllegalStateException("Cube entity index is too large");
                }
            }
            output.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError("Byte array stream cannot fail", impossible);
        }
    }

    /** Decodes and validates a complete entity index. */
    public static CubeEntityIndex decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("Cube entity index is too large");
        }
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
            if (input.readInt() != MAGIC) throw new IOException("bad magic");
            if (input.readInt() != VERSION) throw new IOException("unsupported version");
            int count = input.readInt();
            if (count < 0 || count > MAX_RECORDS) throw new IOException("bad record count");

            CubeEntityIndex result = new CubeEntityIndex();
            for (int index = 0; index < count; index++) {
                if (input.available() < 16 + 12 + 4) throw new EOFException("truncated entity record");
                UUID uuid = new UUID(input.readLong(), input.readLong());
                CubePos owner = new CubePos(input.readInt(), input.readInt(), input.readInt());
                int nbtLength = input.readInt();
                if (nbtLength < 0 || nbtLength > CubeEntityRecord.MAX_NBT_BYTES
                        || nbtLength > input.available()) {
                    throw new IOException("invalid entity NBT length");
                }
                byte[] nbt = new byte[nbtLength];
                input.readFully(nbt);
                result.add(new CubeEntityRecord(owner, uuid, nbt));
            }
            if (input.available() != 0) throw new IOException("trailing entity bytes");
            return result;
        } catch (EOFException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid cube entity index", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid cube entity index", exception);
        }
    }

    private void putUnchecked(CubeEntityRecord record) {
        byUuid.put(record.uuid(), record);
        byCube.computeIfAbsent(record.owner(), ignored -> new HashSet<>()).add(record.uuid());
    }

    private void removeFromCube(CubePos owner, UUID uuid) {
        Set<UUID> ids = byCube.get(owner);
        if (ids == null) return;
        ids.remove(uuid);
        if (ids.isEmpty()) byCube.remove(owner);
    }
}
