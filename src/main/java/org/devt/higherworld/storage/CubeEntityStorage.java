package org.devt.higherworld.storage;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Atomic per-world persistence boundary for the HWE1 entity index. */
public final class CubeEntityStorage implements AutoCloseable {
    private final Path file;
    private boolean closed;

    public CubeEntityStorage(Path directory) throws IOException {
        Files.createDirectories(directory);
        this.file = directory.resolve("entities.hwe1");
    }

    /** Loads a complete index, or an empty index when the world has no entity file. */
    public synchronized CubeEntityIndex load() throws IOException {
        ensureOpen();
        if (!Files.exists(file)) return new CubeEntityIndex();
        long size = Files.size(file);
        if (size <= 0 || size > CubeEntityIndex.MAX_ENCODED_BYTES) {
            throw new IOException("Invalid HWE1 entity file size: " + size);
        }
        try {
            return CubeEntityIndex.decode(Files.readAllBytes(file));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid HWE1 entity file: " + file, exception);
        }
    }

    /** Replaces the complete index atomically in the same directory. */
    public synchronized void save(CubeEntityIndex index) throws IOException {
        ensureOpen();
        if (index == null) throw new NullPointerException("index");
        byte[] encoded;
        try {
            encoded = index.encode();
        } catch (RuntimeException exception) {
            throw new IOException("Cannot encode HWE1 entity index", exception);
        }
        if (encoded.length > CubeEntityIndex.MAX_ENCODED_BYTES) {
            throw new IOException("HWE1 entity file is too large: " + encoded.length);
        }

        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.write(temporary, encoded, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    Path fileForTest() {
        return file;
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("HWE1 entity storage is closed");
    }

    @Override
    public synchronized void close() {
        closed = true;
    }
}
