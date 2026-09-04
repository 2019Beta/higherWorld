package org.devt.higherworld.storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Prioritized, deduplicated cube IO with batch reads and coalesced writes. */
public final class CubeIoScheduler implements AutoCloseable {
    private static final int DEFAULT_WORKERS = Math.max(1,
            Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    private static final int MAX_READ_BATCH = 16;
    private static final int MAX_NEGATIVE_ENTRIES = 8192;
    private static final long NEGATIVE_TTL_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final int WRITE_PRIORITY = 32;

    private final CubeStorage storage;
    private final ThreadPoolExecutor executor;
    private final ConcurrentMap<CubePos, ReadTask> reads = new ConcurrentHashMap<>();
    private final ConcurrentMap<CubePos, PendingWrite> writes = new ConcurrentHashMap<>();
    private final ConcurrentMap<CubePos, Long> negativeCache = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Throwable> writeFailure = new AtomicReference<>();
    /** Serializes write submission with an explicit durability barrier. */
    private final Object writeGate = new Object();

    public CubeIoScheduler(CubeStorage storage) {
        this(storage, DEFAULT_WORKERS);
    }

    CubeIoScheduler(CubeStorage storage, int workers) {
        if (workers < 1) {
            throw new IllegalArgumentException("workers must be positive");
        }
        this.storage = storage;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "higherworld-cube-io");
            thread.setDaemon(true);
            return thread;
        };
        executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(), factory);
    }

    public void prefetch(CubePos pos, int priority) {
        request(pos, priority);
    }

    /** Returns the deduplicated asynchronous read used by a cube lifecycle. */
    public CompletableFuture<Optional<byte[]>> readAsync(CubePos pos, int priority) {
        Optional<byte[]> cachedWrite = latestPendingWrite(pos);
        if (cachedWrite.isPresent()) {
            return CompletableFuture.completedFuture(cachedWrite);
        }
        if (isKnownMissing(pos)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        ReadTask task = request(pos, priority);
        task.future.whenComplete((ignored, throwable) -> reads.remove(pos, task));
        return task.future;
    }

    public void retainPrefetches(Set<CubePos> retained) {
        reads.forEach((pos, task) -> {
            if (!retained.contains(pos) && reads.remove(pos, task)) {
                executor.remove(task);
                task.future.cancel(false);
            }
        });
    }

    public ReadResult poll(CubePos pos, int priority) throws IOException {
        Optional<byte[]> cachedWrite = latestPendingWrite(pos);
        if (cachedWrite.isPresent()) {
            return ReadResult.ready(cachedWrite);
        }
        if (isKnownMissing(pos)) {
            return ReadResult.ready(Optional.empty());
        }
        ReadTask task = request(pos, priority);
        if (!task.future.isDone()) {
            return ReadResult.pending();
        }
        try {
            Optional<byte[]> payload = task.future.join();
            reads.remove(pos, task);
            return ReadResult.ready(payload);
        } catch (CompletionException exception) {
            reads.remove(pos, task);
            throw asIOException("Cannot read cube " + pos, exception.getCause());
        }
    }

    public Optional<byte[]> read(CubePos pos, int priority) throws IOException {
        Optional<byte[]> cachedWrite = latestPendingWrite(pos);
        if (cachedWrite.isPresent()) {
            return cachedWrite;
        }
        if (isKnownMissing(pos)) {
            return Optional.empty();
        }
        ReadTask task = request(pos, priority);
        try {
            return task.future.join();
        } catch (CompletionException exception) {
            throw asIOException("Cannot read cube " + pos, exception.getCause());
        } finally {
            reads.remove(pos, task);
        }
    }

    /** Replaces an older queued payload for the same cube instead of writing it twice. */
    public CompletableFuture<Void> write(CubePos pos, byte[] payload) {
        byte[] immutablePayload = payload.clone();
        synchronized (writeGate) {
            if (closed.get()) {
                return CompletableFuture.failedFuture(new IOException("Cube IO scheduler is closed"));
            }
            negativeCache.remove(pos);
            while (true) {
                PendingWrite current = writes.get(pos);
                if (current != null) {
                    if (current.replace(immutablePayload)) {
                        return current.completion;
                    }
                    Thread.onSpinWait();
                    continue;
                }
                PendingWrite candidate = new PendingWrite(pos, immutablePayload);
                if (writes.putIfAbsent(pos, candidate) == null) {
                    submit(new WriteTask(candidate, sequence.getAndIncrement()));
                    return candidate.completion;
                }
            }
        }
    }

    /** Waits until every write accepted before or during this call is durable. */
    public void flushWrites() throws IOException {
        synchronized (writeGate) {
            while (true) {
                CompletableFuture<?>[] pending = writes.values().stream()
                        .map(write -> write.completion)
                        .toArray(CompletableFuture[]::new);
                if (pending.length == 0) {
                    break;
                }
                try {
                    CompletableFuture.allOf(pending).join();
                } catch (CompletionException ignored) {
                    // The original failure is retained below so concurrent failures
                    // are not lost when a completed entry leaves the map.
                }
            }
            Throwable failure = writeFailure.getAndSet(null);
            try {
                storage.sync();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw asIOException("Cannot flush cube writes", failure);
            }
        }
    }

    int pendingReadCount() {
        return reads.size();
    }

    int pendingWriteCount() {
        return writes.size();
    }

    private ReadTask request(CubePos pos, int priority) {
        if (closed.get()) {
            throw new RejectedExecutionException("Cube IO scheduler is closed");
        }
        ReadTask candidate = new ReadTask(pos, priority, sequence.getAndIncrement());
        ReadTask existing = reads.putIfAbsent(pos, candidate);
        if (existing != null) {
            existing.promote(priority);
            return existing;
        }
        try {
            submit(candidate);
            pruneNegativeCache(candidate.sequenceNumber);
            return candidate;
        } catch (RuntimeException exception) {
            reads.remove(pos, candidate);
            candidate.future.completeExceptionally(exception);
            throw exception;
        }
    }

    private void submit(IoTask task) {
        try {
            executor.execute(task);
        } catch (RuntimeException exception) {
            task.fail(exception);
            throw exception;
        }
    }

    private Optional<byte[]> latestPendingWrite(CubePos pos) {
        PendingWrite write = writes.get(pos);
        return write == null ? Optional.empty() : Optional.of(write.latestPayload());
    }

    private boolean isKnownMissing(CubePos pos) {
        Long expiresAt = negativeCache.get(pos);
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt > System.nanoTime()) {
            return true;
        }
        negativeCache.remove(pos, expiresAt);
        return false;
    }

    private void recordMissing(CubePos pos) {
        if (negativeCache.size() >= MAX_NEGATIVE_ENTRIES) {
            pruneNegativeCache(sequence.get());
            if (negativeCache.size() >= MAX_NEGATIVE_ENTRIES) {
                negativeCache.keySet().stream().findAny().ifPresent(negativeCache::remove);
            }
        }
        negativeCache.put(pos, System.nanoTime() + NEGATIVE_TTL_NANOS);
    }

    private void pruneNegativeCache(long currentSequence) {
        if ((currentSequence & 255L) != 0L) {
            return;
        }
        long now = System.nanoTime();
        negativeCache.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private void runReadBatch(ReadTask first) {
        List<ReadTask> batch = new ArrayList<>(MAX_READ_BATCH);
        batch.add(first);
        RegionPos region = first.pos.region();
        for (Runnable runnable : executor.getQueue()) {
            if (batch.size() >= MAX_READ_BATCH) {
                break;
            }
            if (runnable instanceof ReadTask candidate
                    && candidate.pos.region().equals(region)
                    && executor.getQueue().remove(candidate)) {
                batch.add(candidate);
            }
        }

        try {
            List<CubePos> diskPositions = new ArrayList<>(batch.size());
            for (ReadTask task : batch) {
                Optional<byte[]> pending = latestPendingWrite(task.pos);
                if (pending.isPresent()) {
                    task.future.complete(pending);
                } else if (isKnownMissing(task.pos)) {
                    task.future.complete(Optional.empty());
                } else {
                    diskPositions.add(task.pos);
                }
            }
            Map<CubePos, Optional<byte[]>> results = storage.readBatch(diskPositions);
            for (ReadTask task : batch) {
                if (task.future.isDone()) {
                    continue;
                }
                Optional<byte[]> payload = results.getOrDefault(task.pos, Optional.empty());
                if (payload.isEmpty()) {
                    recordMissing(task.pos);
                }
                task.future.complete(payload);
            }
        } catch (Throwable throwable) {
            batch.forEach(task -> task.future.completeExceptionally(throwable));
        }
    }

    private void runWriteBatch(WriteTask first) {
        List<WriteTask> batch = new ArrayList<>(MAX_READ_BATCH);
        batch.add(first);
        RegionPos region = first.write.pos.region();
        for (Runnable runnable : executor.getQueue()) {
            if (batch.size() >= MAX_READ_BATCH) break;
            if (runnable instanceof WriteTask candidate
                    && candidate.write.pos.region().equals(region)
                    && executor.getQueue().remove(candidate)) {
                batch.add(candidate);
            }
        }
        try {
            Map<CubePos, PendingWrite.Snapshot> snapshots = new java.util.LinkedHashMap<>();
            Map<CubePos, byte[]> payloads = new java.util.LinkedHashMap<>();
            for (WriteTask task : batch) {
                PendingWrite.Snapshot snapshot = task.write.snapshot();
                snapshots.put(task.write.pos, snapshot);
                payloads.put(task.write.pos, snapshot.payload());
            }
            storage.writeBatch(payloads);
            storage.compactIfNeeded(region);
            for (WriteTask task : batch) {
                PendingWrite write = task.write;
                if (write.sealIfCurrent(snapshots.get(write.pos).version())) {
                    writes.remove(write.pos, write);
                    write.completion.complete(null);
                } else {
                    submit(new WriteTask(write, sequence.getAndIncrement()));
                }
            }
        } catch (Throwable throwable) {
            writeFailure.compareAndSet(null, throwable);
            for (WriteTask task : batch) {
                PendingWrite write = task.write;
                write.stopAccepting();
                writes.remove(write.pos, write);
                write.completion.completeExceptionally(throwable);
            }
        }
    }

    private static IOException asIOException(String message, Throwable cause) {
        if (cause instanceof IOException ioException) {
            return ioException;
        }
        return new IOException(message, cause);
    }

    @Override
    public void close() throws IOException {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        IOException failure = null;
        try {
            flushWrites();
        } catch (IOException exception) {
            failure = exception;
        }
        executor.shutdown();
        boolean interrupted = false;
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                IOException closedException = new IOException("Cube IO scheduler closed before queued task ran");
                for (Runnable runnable : executor.shutdownNow()) {
                    if (runnable instanceof IoTask task) {
                        task.fail(closedException);
                    }
                }
                executor.awaitTermination(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            interrupted = true;
            executor.shutdownNow();
            IOException interruptedFailure = new IOException("Interrupted while closing cube IO", exception);
            if (failure == null) failure = interruptedFailure;
            else failure.addSuppressed(interruptedFailure);
        } finally {
            reads.clear();
            writes.clear();
            negativeCache.clear();
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (failure != null) {
            throw failure;
        }
    }

    public record ReadResult(boolean ready, Optional<byte[]> payload) {
        private static ReadResult pending() {
            return new ReadResult(false, Optional.empty());
        }

        private static ReadResult ready(Optional<byte[]> payload) {
            return new ReadResult(true, payload);
        }
    }

    private abstract class IoTask implements Runnable, Comparable<IoTask> {
        protected volatile int priority;
        protected final long sequenceNumber;

        private IoTask(int priority, long sequenceNumber) {
            this.priority = priority;
            this.sequenceNumber = sequenceNumber;
        }

        protected abstract void fail(Throwable throwable);

        @Override
        public int compareTo(IoTask other) {
            int byPriority = Integer.compare(priority, other.priority);
            return byPriority != 0 ? byPriority : Long.compare(sequenceNumber, other.sequenceNumber);
        }
    }

    private final class ReadTask extends IoTask {
        private final CubePos pos;
        private final CompletableFuture<Optional<byte[]>> future = new CompletableFuture<>();

        private ReadTask(CubePos pos, int priority, long sequenceNumber) {
            super(priority, sequenceNumber);
            this.pos = pos;
        }

        private void promote(int requestedPriority) {
            if (requestedPriority >= priority) return;
            synchronized (this) {
                if (requestedPriority >= priority) return;
                priority = requestedPriority;
                if (executor.getQueue().remove(this)) executor.getQueue().offer(this);
            }
        }

        @Override
        public void run() {
            runReadBatch(this);
        }

        @Override
        protected void fail(Throwable throwable) {
            future.completeExceptionally(throwable);
        }
    }

    private final class WriteTask extends IoTask {
        private final PendingWrite write;

        private WriteTask(PendingWrite write, long sequenceNumber) {
            super(WRITE_PRIORITY, sequenceNumber);
            this.write = write;
        }

        @Override
        public void run() {
            runWriteBatch(this);
        }

        @Override
        protected void fail(Throwable throwable) {
            writeFailure.compareAndSet(null, throwable);
            write.stopAccepting();
            writes.remove(write.pos, write);
            write.completion.completeExceptionally(throwable);
        }
    }

    private static final class PendingWrite {
        private final CubePos pos;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private byte[] payload;
        private long version;
        private boolean accepting = true;

        private PendingWrite(CubePos pos, byte[] payload) {
            this.pos = pos;
            this.payload = payload;
        }

        private synchronized boolean replace(byte[] replacement) {
            if (!accepting) return false;
            payload = replacement;
            version++;
            return true;
        }

        private synchronized byte[] latestPayload() {
            return payload.clone();
        }

        private synchronized Snapshot snapshot() {
            return new Snapshot(version, payload);
        }

        private synchronized boolean sealIfCurrent(long writtenVersion) {
            if (version != writtenVersion) return false;
            accepting = false;
            return true;
        }

        private synchronized void stopAccepting() {
            accepting = false;
        }

        private record Snapshot(long version, byte[] payload) {}
    }
}
