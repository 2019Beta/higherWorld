package org.devt.higherworld.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** Append-only region log. A region contains 16 x 16 x 16 addressable cube slots. */
final class CubeRegionFile implements Closeable {
    private static final int MAGIC = 0x48575231; // HWR1
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = 20;
    private static final int RECORD_MAGIC = 0x43554245; // CUBE
    private static final int SLOT_COUNT = 4096;
    private static final int MAX_COMPRESSED_BYTES = 4 * 1024 * 1024;
    private static final int MAX_UNCOMPRESSED_BYTES = 16 * 1024 * 1024;

    private final RandomAccessFile file;
    private final long[] offsets = new long[SLOT_COUNT];
    private final int[] compressedLengths = new int[SLOT_COUNT];
    private final int[] uncompressedLengths = new int[SLOT_COUNT];
    private final int[] checksums = new int[SLOT_COUNT];
    private boolean dirty;

    CubeRegionFile(Path path, RegionPos pos) throws IOException {
        Files.createDirectories(path.getParent());
        this.file = new RandomAccessFile(path.toFile(), "rw");
        if (file.length() == 0) {
            writeHeader(pos);
            dirty = true;
        } else {
            readHeader(pos);
        }
        rebuildIndex();
    }

    synchronized Optional<byte[]> read(int slot) throws IOException {
        validateSlot(slot);
        if (offsets[slot] == 0) {
            return Optional.empty();
        }

        byte[] compressed = new byte[compressedLengths[slot]];
        file.seek(offsets[slot]);
        file.readFully(compressed);
        byte[] payload = inflate(compressed, uncompressedLengths[slot]);
        CRC32 crc = new CRC32();
        crc.update(payload);
        if ((int) crc.getValue() != checksums[slot]) {
            throw new IOException("CRC mismatch in cube slot " + slot);
        }
        return Optional.of(payload);
    }

    synchronized void write(int slot, byte[] payload) throws IOException {
        append(slot, payload);
    }

    synchronized void writeBatch(Map<Integer, byte[]> payloads) throws IOException {
        for (Map.Entry<Integer, byte[]> entry : payloads.entrySet()) {
            append(entry.getKey(), entry.getValue());
        }
    }

    /** Flushes appended records to durable storage when the caller reaches a save barrier. */
    synchronized void sync() throws IOException {
        if (!dirty) return;
        file.getFD().sync();
        dirty = false;
    }

    private void append(int slot, byte[] payload) throws IOException {
        validateSlot(slot);
        if (payload.length > MAX_UNCOMPRESSED_BYTES) {
            throw new IOException("Cube payload is too large: " + payload.length);
        }

        byte[] compressed = deflate(payload);
        if (compressed.length > MAX_COMPRESSED_BYTES) {
            throw new IOException("Compressed cube payload is too large: " + compressed.length);
        }
        CRC32 crc = new CRC32();
        crc.update(payload);

        long recordStart = file.length();
        file.seek(recordStart);
        file.writeInt(RECORD_MAGIC);
        file.writeInt(slot);
        file.writeInt(compressed.length);
        file.writeInt(payload.length);
        file.writeInt((int) crc.getValue());
        long payloadOffset = file.getFilePointer();
        file.write(compressed);
        offsets[slot] = payloadOffset;
        compressedLengths[slot] = compressed.length;
        uncompressedLengths[slot] = payload.length;
        checksums[slot] = (int) crc.getValue();
        dirty = true;
    }

    private void writeHeader(RegionPos pos) throws IOException {
        file.writeInt(MAGIC);
        file.writeInt(VERSION);
        file.writeInt(pos.x());
        file.writeInt(pos.y());
        file.writeInt(pos.z());
    }

    private void readHeader(RegionPos expected) throws IOException {
        if (file.length() < HEADER_BYTES) {
            throw new IOException("Truncated HigherWorld region header");
        }
        file.seek(0);
        int magic = file.readInt();
        int version = file.readInt();
        RegionPos stored = new RegionPos(file.readInt(), file.readInt(), file.readInt());
        if (magic != MAGIC || version != VERSION) {
            throw new IOException("Unsupported HigherWorld region format");
        }
        if (!stored.equals(expected)) {
            throw new IOException("Region coordinate mismatch: expected " + expected + ", got " + stored);
        }
    }

    private void rebuildIndex() throws IOException {
        long cursor = HEADER_BYTES;
        while (cursor < file.length()) {
            file.seek(cursor);
            try {
                if (file.readInt() != RECORD_MAGIC) {
                    throw new IOException("Invalid cube record at byte " + cursor);
                }
                int slot = file.readInt();
                int compressedLength = file.readInt();
                int uncompressedLength = file.readInt();
                int checksum = file.readInt();
                validateSlot(slot);
                validateLengths(compressedLength, uncompressedLength);

                long payloadOffset = file.getFilePointer();
                long next = payloadOffset + compressedLength;
                if (next > file.length()) {
                    // Remove an interrupted append so later records stay discoverable on reopen.
                    file.setLength(cursor);
                    dirty = true;
                    return;
                }
                offsets[slot] = payloadOffset;
                compressedLengths[slot] = compressedLength;
                uncompressedLengths[slot] = uncompressedLength;
                checksums[slot] = checksum;
                cursor = next;
            } catch (EOFException ignored) {
                file.setLength(cursor);
                dirty = true;
                return;
            }
        }
    }

    private static byte[] deflate(byte[] payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(payload.length / 2);
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(bytes);
             DataOutputStream output = new DataOutputStream(deflater)) {
            output.write(payload);
        }
        return bytes.toByteArray();
    }

    private static byte[] inflate(byte[] compressed, int expectedLength) throws IOException {
        if (expectedLength < 0 || expectedLength > MAX_UNCOMPRESSED_BYTES) {
            throw new IOException("Invalid expected cube length " + expectedLength);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(expectedLength);
        try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(compressed));
             DataInputStream input = new DataInputStream(inflater)) {
            // Do not use transferTo here.  The length in the record header is
            // untrusted, and a corrupt compressed stream can expand far past it
            // before the old post-read length check gets a chance to run.
            byte[] buffer = new byte[Math.min(8192, Math.max(1, expectedLength))];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > expectedLength - total) {
                    throw new IOException("Cube payload exceeds declared length " + expectedLength);
                }
                bytes.write(buffer, 0, read);
                total += read;
            }
        }
        byte[] payload = bytes.toByteArray();
        if (payload.length != expectedLength) {
            throw new IOException("Cube length mismatch: expected " + expectedLength + ", got " + payload.length);
        }
        return payload;
    }

    private static void validateSlot(int slot) throws IOException {
        if (slot < 0 || slot >= SLOT_COUNT) {
            throw new IOException("Invalid cube slot " + slot);
        }
    }

    private static void validateLengths(int compressed, int uncompressed) throws IOException {
        if (compressed < 0 || compressed > MAX_COMPRESSED_BYTES
                || uncompressed < 0 || uncompressed > MAX_UNCOMPRESSED_BYTES) {
            throw new IOException("Invalid cube record lengths " + compressed + "/" + uncompressed);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        IOException failure = null;
        try {
            sync();
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            file.close();
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
