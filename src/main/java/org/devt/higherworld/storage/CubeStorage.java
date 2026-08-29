package org.devt.higherworld.storage;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Thread-safe entry point for one dimension's three-dimensional region store. */
public final class CubeStorage implements Closeable {
    private static final int MAX_OPEN_REGIONS = 64;
    private final Path directory;
    private final Map<RegionPos, CubeRegionFile> regions = new LinkedHashMap<>(16, 0.75F, true);

    public CubeStorage(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
    }

    public synchronized void write(CubePos pos, byte[] payload) throws IOException {
        region(pos.region()).write(pos.localIndex(), payload);
    }

    public synchronized Optional<byte[]> read(CubePos pos) throws IOException {
        Path file = directory.resolve(pos.region().fileName());
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        return region(pos.region()).read(pos.localIndex());
    }

    public synchronized int openRegionCount() {
        return regions.size();
    }

    private CubeRegionFile region(RegionPos pos) throws IOException {
        CubeRegionFile current = regions.get(pos);
        if (current != null) {
            return current;
        }

        CubeRegionFile created = new CubeRegionFile(directory.resolve(pos.fileName()), pos);
        regions.put(pos, created);
        if (regions.size() > MAX_OPEN_REGIONS) {
            Iterator<Map.Entry<RegionPos, CubeRegionFile>> iterator = regions.entrySet().iterator();
            Map.Entry<RegionPos, CubeRegionFile> eldest = iterator.next();
            iterator.remove();
            eldest.getValue().close();
        }
        return created;
    }

    @Override
    public synchronized void close() throws IOException {
        IOException failure = null;
        for (CubeRegionFile region : regions.values()) {
            try {
                region.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        regions.clear();
        if (failure != null) {
            throw failure;
        }
    }
}
