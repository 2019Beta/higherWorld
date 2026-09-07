package org.devt.higherworld.world;

import java.util.ArrayDeque;

/** FIFO publication and byte accounting share a lock across network/client threads. */
public final class CubeStreamQueue<T> {
    private final ArrayDeque<Entry<T>> queue = new ArrayDeque<>();
    private long streamId;
    private long processedBytes;
    private long pendingBytes;

    public synchronized void begin(long id) {
        streamId = id;
        processedBytes = 0;
        // Keep queued data/light/unload order across the stream boundary.
    }

    public synchronized void offer(T value, int bytes, long nowNanos) {
        if (bytes < 0) throw new IllegalArgumentException("Negative payload size");
        queue.addLast(new Entry<>(value, streamId, bytes, nowNanos));
        pendingBytes += bytes;
    }

    public synchronized T poll() {
        Entry<T> next = queue.pollFirst();
        if (next == null) return null;
        pendingBytes -= next.bytes();
        if (next.streamId() == streamId) processedBytes += next.bytes();
        return next.value();
    }

    public synchronized boolean isEmpty() { return queue.isEmpty(); }

    public synchronized CubeStreamFeedbackPayload feedback(long nowNanos, int renders, int lightCubes) {
        long ageMillis = queue.isEmpty() ? 0 : Math.max(0, (nowNanos - queue.getFirst().enqueuedNanos()) / 1_000_000L);
        return new CubeStreamFeedbackPayload(streamId, processedBytes, pendingBytes, queue.size(),
                (int) Math.min(Integer.MAX_VALUE, ageMillis), renders, lightCubes);
    }

    public synchronized void clear() {
        queue.clear();
        streamId = processedBytes = pendingBytes = 0;
    }

    private record Entry<T>(T value, long streamId, int bytes, long enqueuedNanos) {}
}
