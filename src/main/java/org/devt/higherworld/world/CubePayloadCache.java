package org.devt.higherworld.world;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import org.devt.higherworld.storage.CubePos;

/** Byte-bounded, access-ordered cache for immutable cube payload encodings. */
final class CubePayloadCache {
    static final long DEFAULT_MAX_BYTES = 32L * 1024L * 1024L;

    private final long maxBytes;
    private final Map<CubePos, Entry> entries = new LinkedHashMap<>(32, 0.75f, true);
    private long cachedBytes;

    CubePayloadCache() {
        this(DEFAULT_MAX_BYTES);
    }

    CubePayloadCache(long maxBytes) {
        if (maxBytes <= 0L) throw new IllegalArgumentException("maxBytes must be positive");
        this.maxBytes = maxBytes;
    }

    synchronized byte[] get(CubePos pos, LoadedCube cube, long revision, boolean lightIncluded) {
        Entry cached = entries.get(pos);
        if (cached != null && cached.cube() == cube && cached.revision() == revision
                && cached.lightIncluded() == lightIncluded) {
            return cached.payload();
        }
        return null;
    }

    synchronized void put(
            CubePos pos, LoadedCube cube, long revision, boolean lightIncluded, byte[] payload) {
        if (payload.length > maxBytes) return;
        Entry previous = entries.put(
                pos, new Entry(cube, revision, lightIncluded, payload));
        if (previous != null) cachedBytes -= previous.payload().length;
        cachedBytes += payload.length;
        while (cachedBytes > maxBytes && !entries.isEmpty()) {
            Iterator<Map.Entry<CubePos, Entry>> iterator = entries.entrySet().iterator();
            Map.Entry<CubePos, Entry> eldest = iterator.next();
            iterator.remove();
            cachedBytes -= eldest.getValue().payload().length;
        }
    }

    synchronized void invalidate(CubePos pos) {
        Entry removed = entries.remove(pos);
        if (removed != null) cachedBytes -= removed.payload().length;
    }

    synchronized void clear() {
        entries.clear();
        cachedBytes = 0L;
    }

    private record Entry(
            LoadedCube cube, long revision, boolean lightIncluded, byte[] payload) {
    }
}
