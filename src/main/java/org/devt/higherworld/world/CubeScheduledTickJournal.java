package org.devt.higherworld.world;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.devt.higherworld.Higherworld;

/**
 * World-level recovery journal for scheduled ticks whose cubes are not
 * currently materialized.  Cube records remain authoritative; this file is a
 * bounded replay source and queue restoration deduplicates both sources.
 */
final class CubeScheduledTickJournal {
    static final int MAGIC = 0x48575431; // HWT1
    static final int VERSION = 1;
    static final int MAX_TICKS = 65_536;
    static final int MAX_BYTES = 16 * 1024 * 1024;
    static final String FILE_NAME = "scheduled_ticks.journal";
    private static final int HEADER_BYTES = Integer.BYTES * 3;
    private static final Comparator<CubeScheduledTick> ORDER = (first, second) -> {
        int byTime = Long.compare(first.triggerTick(), second.triggerTick());
        if (byTime != 0) return byTime;
        int byPriority = Integer.compare(first.priority(), second.priority());
        if (byPriority != 0) return byPriority;
        int byOrder = Long.compare(first.subTickOrder(), second.subTickOrder());
        if (byOrder != 0) return byOrder;
        int byY = Integer.compare(first.pos().getY(), second.pos().getY());
        if (byY != 0) return byY;
        int byZ = Integer.compare(first.pos().getZ(), second.pos().getZ());
        if (byZ != 0) return byZ;
        int byX = Integer.compare(first.pos().getX(), second.pos().getX());
        if (byX != 0) return byX;
        int byKind = Integer.compare(first.kind().ordinal(), second.kind().ordinal());
        if (byKind != 0) return byKind;
        return first.typeId().compareTo(second.typeId());
    };

    private final Path path;

    CubeScheduledTickJournal(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    /**
     * Loads the complete journal or returns an empty recovery set.  A corrupt
     * journal is intentionally isolated from world startup: cube payloads and
    * terrain remain authoritative even when this auxiliary file is invalid.
     */
    List<CubeScheduledTick> load() {
        try {
            if (!Files.isRegularFile(path)) return List.of();
            long size = Files.size(path);
            if (size > MAX_BYTES) {
                throw new IOException("Scheduled tick journal exceeds " + MAX_BYTES + " bytes");
            }
            return decode(Files.readAllBytes(path));
        } catch (IOException | RuntimeException exception) {
            Higherworld.LOGGER.warn("Ignoring corrupt scheduled tick journal at {}", path, exception);
            return List.of();
        }
    }

    /** Writes a bounded snapshot with an atomic replace of the previous file. */
    void save(Collection<CubeScheduledTick> ticks) throws IOException {
        byte[] payload = encode(ticks);
        Path absolute = path.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temporary = absolute.resolveSibling(absolute.getFileName() + ".tmp");
        Files.write(temporary, payload, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static byte[] encode(Collection<CubeScheduledTick> ticks) throws IOException {
        Objects.requireNonNull(ticks, "ticks");
        List<CubeScheduledTick> ordered = new ArrayList<>(ticks.size());
        for (CubeScheduledTick tick : ticks) {
            if (tick == null) throw new IOException("Null scheduled tick in journal");
            ordered.add(tick);
            if (ordered.size() > MAX_TICKS) {
                throw new IOException("Scheduled tick journal contains too many records");
            }
        }
        ordered.sort(ORDER);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(MAX_BYTES, HEADER_BYTES + ordered.size() * 64));
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(ordered.size());
            for (CubeScheduledTick tick : ordered) tick.write(output);
        }
        if (bytes.size() > MAX_BYTES) {
            throw new IOException("Scheduled tick journal exceeds " + MAX_BYTES + " bytes");
        }
        return bytes.toByteArray();
    }

    static List<CubeScheduledTick> decode(byte[] payload) throws IOException {
        Objects.requireNonNull(payload, "payload");
        if (payload.length < HEADER_BYTES || payload.length > MAX_BYTES) {
            throw new IOException("Invalid scheduled tick journal size: " + payload.length);
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            int magic = input.readInt();
            int version = input.readInt();
            if (magic != MAGIC || version != VERSION) {
                throw new IOException("Unsupported scheduled tick journal header");
            }
            int count = input.readInt();
            if (count < 0 || count > MAX_TICKS) {
                throw new IOException("Invalid scheduled tick journal count: " + count);
            }
            List<CubeScheduledTick> result = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                result.add(CubeScheduledTick.read(input));
            }
            if (input.available() != 0) {
                throw new IOException("Trailing bytes in scheduled tick journal: " + input.available());
            }
            return List.copyOf(result);
        }
    }
}
