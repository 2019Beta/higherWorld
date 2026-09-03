package org.devt.higherworld.world;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.util.math.BlockPos;
import org.devt.higherworld.storage.CubePos;

/**
 * Sparse, cube-owned POI index.
 *
 * <p>This is intentionally not a wrapper around vanilla
 * {@code PointOfInterestStorage}: that storage is region/section based and
 * its constructor requires a finite {@code HeightLimitView}.  The index is a
 * small persistence-safe seam which can later be used by a version-specific
 * POI adapter.</p>
 */
public final class CubePoiIndex {
    private static final int MAGIC = 0x48575031; // "HWP1"
    private static final int VERSION = 1;
    private static final int MAX_RECORDS = 65_536;
    private static final int MAX_ENCODED_BYTES = 4 * 1024 * 1024;
    private static final int MAX_QUERY_RADIUS = 128;

    private static final Comparator<CubePoiRecord> ORDER = Comparator
            .comparingInt((CubePoiRecord poi) -> poi.cube().x())
            .thenComparingInt(poi -> poi.cube().y())
            .thenComparingInt(poi -> poi.cube().z())
            .thenComparingInt(CubePoiRecord::localX)
            .thenComparingInt(CubePoiRecord::localY)
            .thenComparingInt(CubePoiRecord::localZ)
            .thenComparing(CubePoiRecord::type)
            .thenComparingInt(CubePoiRecord::ticketCount)
            .thenComparingInt(CubePoiRecord::freeTickets);

    /** The outer key makes bounded cube queries independent of total index size. */
    private final Map<CubePos, Map<Key, CubePoiRecord>> records = new HashMap<>();

    /** Adds or replaces the POI at the same cube/local/type key. */
    public synchronized Optional<CubePoiRecord> add(CubePoiRecord poi) {
        Objects.requireNonNull(poi, "poi");
        Map<Key, CubePoiRecord> cubeRecords = records.computeIfAbsent(poi.cube(), ignored -> new HashMap<>());
        return Optional.ofNullable(cubeRecords.put(Key.of(poi), poi));
    }

    public synchronized Optional<CubePoiRecord> get(Key key) {
        Objects.requireNonNull(key, "key");
        Map<Key, CubePoiRecord> cubeRecords = records.get(key.cube());
        return cubeRecords == null ? Optional.empty() : Optional.ofNullable(cubeRecords.get(key));
    }

    /** Looks up a POI by the absolute position used by vanilla AI code. */
    public synchronized Optional<CubePoiRecord> get(BlockPos position, String type) {
        return get(keyAt(position, type));
    }

    /** Removes one exact POI entry. */
    public synchronized Optional<CubePoiRecord> remove(Key key) {
        Objects.requireNonNull(key, "key");
        Map<Key, CubePoiRecord> cubeRecords = records.get(key.cube());
        if (cubeRecords == null) return Optional.empty();
        CubePoiRecord removed = cubeRecords.remove(key);
        if (cubeRecords.isEmpty()) records.remove(key.cube());
        return Optional.ofNullable(removed);
    }

    /** Removes a POI by the absolute position used by vanilla AI code. */
    public synchronized Optional<CubePoiRecord> remove(BlockPos position, String type) {
        return remove(keyAt(position, type));
    }

    /** Removes all POIs owned by one cube and returns the removed entries. */
    public synchronized List<CubePoiRecord> removeCube(CubePos cube) {
        Objects.requireNonNull(cube, "cube");
        Map<Key, CubePoiRecord> cubeRecords = records.remove(cube);
        if (cubeRecords == null) return List.of();
        List<CubePoiRecord> removed = new ArrayList<>(cubeRecords.values());
        removed.sort(ORDER);
        return List.copyOf(removed);
    }

    /** Reserves one vanilla-style POI ticket atomically. */
    public synchronized Optional<CubePoiRecord> reserveTicket(Key key) {
        Objects.requireNonNull(key, "key");
        Map<Key, CubePoiRecord> cubeRecords = records.get(key.cube());
        CubePoiRecord current = cubeRecords == null ? null : cubeRecords.get(key);
        if (current == null) return Optional.empty();
        Optional<CubePoiRecord> reserved = current.tryReserveTicket();
        reserved.ifPresent(updated -> cubeRecords.put(key, updated));
        return reserved;
    }

    /** Releases one vanilla-style POI ticket atomically. */
    public synchronized Optional<CubePoiRecord> releaseTicket(Key key) {
        Objects.requireNonNull(key, "key");
        Map<Key, CubePoiRecord> cubeRecords = records.get(key.cube());
        CubePoiRecord current = cubeRecords == null ? null : cubeRecords.get(key);
        if (current == null) return Optional.empty();
        CubePoiRecord released = current.releaseTicket();
        cubeRecords.put(key, released);
        return Optional.of(released);
    }

    /**
     * Queries a bounded cube box.  The radius is deliberately bounded to keep
     * an accidental AI query from expanding an unbounded sparse world.
     */
    public synchronized List<CubePoiRecord> query(
            CubePos center, CubeDependencyRadius radius,
            Predicate<CubePoiRecord> predicate) {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(radius, "radius");
        Objects.requireNonNull(predicate, "predicate");
        checkQueryRadius(radius);

        List<CubePoiRecord> result = new ArrayList<>();
        for (long y = (long) center.y() - radius.y(); y <= (long) center.y() + radius.y(); y++) {
            for (long z = (long) center.z() - radius.z(); z <= (long) center.z() + radius.z(); z++) {
                for (long x = (long) center.x() - radius.x(); x <= (long) center.x() + radius.x(); x++) {
                    if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE
                            || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE
                            || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) {
                        continue;
                    }
                    CubePos cube = new CubePos((int) x, (int) y, (int) z);
                    Map<Key, CubePoiRecord> cubeRecords = records.get(cube);
                    if (cubeRecords != null) {
                        cubeRecords.values().forEach(poi -> {
                            if (predicate.test(poi)) result.add(poi);
                        });
                    }
                }
            }
        }
        result.sort(ORDER);
        return List.copyOf(result);
    }

    /** Queries by absolute block distance, useful for village/AI lookups. */
    public synchronized List<CubePoiRecord> queryNear(
            CubePoiRecord origin, int radius, Predicate<CubePoiRecord> predicate) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(predicate, "predicate");
        if (radius < 0 || radius > MAX_QUERY_RADIUS * CubePos.SIZE) {
            throw new IllegalArgumentException("POI block query radius is out of bounds");
        }
        long radiusSquared = (long) radius * radius;
        long ox = origin.blockPos().getX();
        long oy = origin.blockPos().getY();
        long oz = origin.blockPos().getZ();
        List<CubePoiRecord> result = new ArrayList<>();
        records.values().forEach(cubeRecords -> cubeRecords.values().forEach(poi -> {
            if (!predicate.test(poi)) return;
            long dx = (long) poi.blockPos().getX() - ox;
            long dy = (long) poi.blockPos().getY() - oy;
            long dz = (long) poi.blockPos().getZ() - oz;
            if (Math.abs(dx) <= radius && Math.abs(dy) <= radius && Math.abs(dz) <= radius
                    && dx * dx + dy * dy + dz * dz <= radiusSquared) result.add(poi);
        }));
        result.sort(Comparator
                .comparingLong((CubePoiRecord poi) -> squaredDistance(origin, poi))
                .thenComparing(ORDER));
        return List.copyOf(result);
    }

    public synchronized int size() {
        return records.values().stream().mapToInt(Map::size).sum();
    }

    public synchronized int cubeCount() {
        return records.size();
    }

    /** Returns a deterministic defensive snapshot. */
    public synchronized List<CubePoiRecord> snapshot() {
        List<CubePoiRecord> result = new ArrayList<>();
        records.values().forEach(cubeRecords -> result.addAll(cubeRecords.values()));
        result.sort(ORDER);
        return List.copyOf(result);
    }

    /** Encodes the complete index in a bounded deterministic binary format. */
    public synchronized byte[] encode() {
        int size = size();
        if (size > MAX_RECORDS) {
            throw new IllegalStateException("Too many POI records: " + size);
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(size);
            List<CubePoiRecord> ordered = new ArrayList<>();
            records.values().forEach(cubeRecords -> ordered.addAll(cubeRecords.values()));
            ordered.sort(ORDER);
            for (CubePoiRecord poi : ordered) {
                byte[] type = poi.type().getBytes(StandardCharsets.UTF_8);
                output.writeInt(poi.cube().x());
                output.writeInt(poi.cube().y());
                output.writeInt(poi.cube().z());
                output.writeByte(poi.localX());
                output.writeByte(poi.localY());
                output.writeByte(poi.localZ());
                output.writeByte(poi.ticketCount());
                output.writeByte(poi.freeTickets());
                output.writeShort(type.length);
                output.write(type);
                if (bytes.size() > MAX_ENCODED_BYTES) {
                    throw new IllegalStateException("POI index is too large");
                }
            }
            output.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError("Byte array stream cannot fail", impossible);
        }
    }

    /** Decodes and validates an index, rejecting malformed or duplicate data. */
    public static CubePoiIndex decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("POI index is too large");
        }
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
            if (input.readInt() != MAGIC) throw new IOException("bad magic");
            if (input.readInt() != VERSION) throw new IOException("unsupported version");
            int count = input.readInt();
            if (count < 0 || count > MAX_RECORDS) throw new IOException("bad record count");

            CubePoiIndex result = new CubePoiIndex();
            for (int index = 0; index < count; index++) {
                CubePos cube = new CubePos(input.readInt(), input.readInt(), input.readInt());
                int localX = input.readUnsignedByte();
                int localY = input.readUnsignedByte();
                int localZ = input.readUnsignedByte();
                int ticketCount = input.readUnsignedByte();
                int freeTickets = input.readUnsignedByte();
                int typeLength = input.readUnsignedShort();
                if (typeLength < 1 || typeLength > CubePoiRecord.MAX_TYPE_BYTES) {
                    throw new IOException("bad POI type length");
                }
                byte[] typeBytes = new byte[typeLength];
                input.readFully(typeBytes);
                String type = new String(typeBytes, StandardCharsets.UTF_8);
                // Reject non-canonical UTF-8 instead of silently accepting a
                // replacement character in a persisted key.
                if (!java.util.Arrays.equals(typeBytes, type.getBytes(StandardCharsets.UTF_8))) {
                    throw new IOException("invalid UTF-8 POI type");
                }
                CubePoiRecord poi = new CubePoiRecord(
                        cube, localX, localY, localZ, type, ticketCount, freeTickets);
                Map<Key, CubePoiRecord> cubeRecords = result.records.computeIfAbsent(
                        cube, ignored -> new HashMap<>());
                if (cubeRecords.put(Key.of(poi), poi) != null) {
                    throw new IOException("duplicate POI key");
                }
            }
            if (input.available() != 0) throw new IOException("trailing POI bytes");
            return result;
        } catch (EOFException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid POI index", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid POI index", exception);
        }
    }

    private static long squaredDistance(CubePoiRecord first, CubePoiRecord second) {
        long dx = (long) first.blockPos().getX() - second.blockPos().getX();
        long dy = (long) first.blockPos().getY() - second.blockPos().getY();
        long dz = (long) first.blockPos().getZ() - second.blockPos().getZ();
        return saturatingAdd(saturatingAdd(square(dx), square(dy)), square(dz));
    }

    private static long square(long value) {
        long absolute = Math.abs(value);
        return absolute != 0L && absolute > Long.MAX_VALUE / absolute
                ? Long.MAX_VALUE : absolute * absolute;
    }

    private static long saturatingAdd(long first, long second) {
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }

    private static Key keyAt(BlockPos position, String type) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(type, "type");
        CubePos cube = CubePos.fromBlock(position.getX(), position.getY(), position.getZ());
        return new Key(cube,
                Math.floorMod(position.getX(), CubePos.SIZE),
                Math.floorMod(position.getY(), CubePos.SIZE),
                Math.floorMod(position.getZ(), CubePos.SIZE), type);
    }

    private static void checkQueryRadius(CubeDependencyRadius radius) {
        if (radius.x() > MAX_QUERY_RADIUS || radius.y() > MAX_QUERY_RADIUS
                || radius.z() > MAX_QUERY_RADIUS) {
            throw new IllegalArgumentException("POI query radius is out of bounds");
        }
    }

    /** Stable identity of a POI independent of mutable ticket occupancy. */
    public record Key(CubePos cube, int localX, int localY, int localZ, String type) {
        public Key {
            Objects.requireNonNull(cube, "cube");
            Objects.requireNonNull(type, "type");
        }

        public static Key of(CubePoiRecord poi) {
            return new Key(poi.cube(), poi.localX(), poi.localY(), poi.localZ(), poi.type());
        }
    }
}
