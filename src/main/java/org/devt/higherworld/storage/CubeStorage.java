package org.devt.higherworld.storage;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Thread-safe entry point for one dimension's three-dimensional region store. */
public final class CubeStorage implements Closeable {
    private static final int MAX_OPEN_REGIONS = 64;
    private final Path directory;
    private final Object regionCacheLock = new Object();
    private final Map<RegionPos, RegionHandle> regions = new LinkedHashMap<>(16, 0.75F, true);
    private boolean closed;

    public CubeStorage(Path directory) throws IOException {
        this.directory = directory;
        Files.createDirectories(directory);
    }

    public void write(CubePos pos, byte[] payload) throws IOException {
        RegionHandle handle = acquire(pos.region(), true);
        try {
            handle.file.write(pos.localIndex(), payload);
        } finally {
            release(handle);
        }
    }

    /** Appends a same-region write group; sync/close provides the durability barrier. */
    public void writeBatch(Map<CubePos, byte[]> payloads) throws IOException {
        if (payloads.isEmpty()) return;
        Iterator<CubePos> iterator = payloads.keySet().iterator();
        CubePos first = iterator.next();
        RegionPos region = first.region();
        while (iterator.hasNext()) {
            if (!iterator.next().region().equals(region)) {
                throw new IllegalArgumentException("A cube write batch must belong to one region");
            }
        }
        RegionHandle handle = acquire(region, true);
        try {
            Map<Integer, byte[]> localPayloads = new LinkedHashMap<>(payloads.size());
            payloads.forEach((pos, payload) -> localPayloads.put(pos.localIndex(), payload));
            handle.file.writeBatch(localPayloads);
        } finally {
            release(handle);
        }
    }

    public Optional<byte[]> read(CubePos pos) throws IOException {
        RegionHandle handle = acquire(pos.region(), false);
        if (handle == null) {
            return Optional.empty();
        }
        try {
            return handle.file.read(pos.localIndex());
        } finally {
            release(handle);
        }
    }

    /** Reads cubes from one region while acquiring the region handle only once. */
    public Map<CubePos, Optional<byte[]>> readBatch(Collection<CubePos> positions) throws IOException {
        if (positions.isEmpty()) {
            return Map.of();
        }
        Iterator<CubePos> iterator = positions.iterator();
        CubePos first = iterator.next();
        RegionPos region = first.region();
        while (iterator.hasNext()) {
            if (!iterator.next().region().equals(region)) {
                throw new IllegalArgumentException("A cube batch must belong to one region");
            }
        }

        Map<CubePos, Optional<byte[]>> result = new HashMap<>(positions.size());
        RegionHandle handle = acquire(region, false);
        if (handle == null) {
            positions.forEach(pos -> result.put(pos, Optional.empty()));
            return result;
        }
        try {
            for (CubePos pos : positions) {
                result.put(pos, handle.file.read(pos.localIndex()));
            }
            return result;
        } finally {
            release(handle);
        }
    }

    /** Runs bounded append-log compaction for one region on the IO worker. */
    void compactIfNeeded(RegionPos pos) throws IOException {
        RegionHandle handle = acquire(pos, true);
        try {
            handle.file.compactIfNeeded();
        } finally {
            release(handle);
        }
    }

    public int openRegionCount() {
        synchronized (regionCacheLock) {
            return regions.size();
        }
    }

    /** Flushes all currently open regions at an explicit durability barrier. */
    void sync() throws IOException {
        List<RegionHandle> toSync;
        synchronized (regionCacheLock) {
            ensureOpen();
            toSync = List.copyOf(regions.values());
            for (RegionHandle region : toSync) {
                // Pin the handles while the cache lock is released.  This keeps
                // eviction from closing a file before its sync, without holding
                // regionCacheLock across a potentially blocking filesystem call.
                region.users++;
            }
        }

        IOException failure = null;
        try {
            for (RegionHandle region : toSync) {
                try {
                    region.file.sync();
                } catch (IOException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
        } finally {
            for (RegionHandle region : toSync) {
                release(region);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private RegionHandle acquire(RegionPos pos, boolean create) throws IOException {
        synchronized (regionCacheLock) {
            ensureOpen();
            RegionHandle current = regions.get(pos);
            if (current != null) {
                current.users++;
                return current;
            }

            Path file = directory.resolve(pos.fileName());
            if (!create && !Files.exists(file)) {
                return null;
            }

            evictIdleRegionsIfNecessary();
            RegionHandle created = new RegionHandle(new CubeRegionFile(file, pos));
            created.users = 1;
            regions.put(pos, created);
            return created;
        }
    }

    private void release(RegionHandle handle) {
        synchronized (regionCacheLock) {
            handle.users--;
            if (handle.users < 0) {
                throw new IllegalStateException("Cube region handle released too many times");
            }
            regionCacheLock.notifyAll();
        }
    }

    private void evictIdleRegionsIfNecessary() throws IOException {
        while (regions.size() >= MAX_OPEN_REGIONS) {
            boolean evicted = false;
            Iterator<Map.Entry<RegionPos, RegionHandle>> iterator = regions.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<RegionPos, RegionHandle> candidate = iterator.next();
                if (candidate.getValue().users == 0) {
                    candidate.getValue().file.sync();
                    iterator.remove();
                    candidate.getValue().file.close();
                    evicted = true;
                    break;
                }
            }
            if (!evicted) {
                // Every cached region is currently in use. Temporarily exceeding
                // the cap is safer than closing a file underneath an async read.
                return;
            }
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Cube storage is closed");
        }
    }

    @Override
    public void close() throws IOException {
        List<RegionHandle> toClose;
        synchronized (regionCacheLock) {
            closed = true;
            while (regions.values().stream().anyMatch(handle -> handle.users != 0)) {
                try {
                    regionCacheLock.wait();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while closing cube storage", exception);
                }
            }
            toClose = List.copyOf(regions.values());
            regions.clear();
        }

        IOException failure = null;
        for (RegionHandle region : toClose) {
            try {
                region.file.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static final class RegionHandle {
        private final CubeRegionFile file;
        private int users;

        private RegionHandle(CubeRegionFile file) {
            this.file = file;
        }
    }
}
