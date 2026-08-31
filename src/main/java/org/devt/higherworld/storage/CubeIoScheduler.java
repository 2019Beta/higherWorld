package org.devt.higherworld.storage;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deduplicates and prioritizes cube reads while keeping world mutation on the
 * server thread.
 *
 * <p>Only region IO runs on these workers. Decoding, generation and insertion
 * into the live world are deliberately left to the caller because those stages
 * can invoke Minecraft code that is not safe on an arbitrary executor.</p>
 */
public final class CubeIoScheduler implements AutoCloseable {
    private static final int DEFAULT_WORKERS = Math.max(
            1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));

    private final CubeStorage storage;
    private final ThreadPoolExecutor executor;
    private final ConcurrentMap<CubePos, ReadTask> reads = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public CubeIoScheduler(CubeStorage storage) {
        this(storage, DEFAULT_WORKERS);
    }

    CubeIoScheduler(CubeStorage storage, int workers) {
        if (workers < 1) {
            throw new IllegalArgumentException("workers must be positive");
        }
        this.storage = storage;
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "higherworld-cube-io");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = new ThreadPoolExecutor(
                workers, workers, 0L, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(), threadFactory);
    }

    /** Starts a read if this cube is not already queued or being read. */
    public void prefetch(CubePos pos, int priority) {
        request(pos, priority);
    }

    /** Cancels queued read-ahead work that no longer has a 3D watcher ticket. */
    public void retainPrefetches(Set<CubePos> retained) {
        reads.forEach((pos, task) -> {
            if (!retained.contains(pos) && reads.remove(pos, task)) {
                executor.remove(task);
                task.future.cancel(false);
            }
        });
    }

    /**
     * Returns a completed read without waiting, or a pending marker while IO is
     * still running. A completed missing record is represented by an empty
     * payload Optional.
     */
    public ReadResult poll(CubePos pos, int priority) throws IOException {
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
            throw asIOException(pos, exception.getCause());
        }
    }

    /** Shares an existing queued read, blocking only callers that require sync access. */
    public Optional<byte[]> read(CubePos pos, int priority) throws IOException {
        ReadTask task = request(pos, priority);
        try {
            return task.future.join();
        } catch (CompletionException exception) {
            throw asIOException(pos, exception.getCause());
        } finally {
            reads.remove(pos, task);
        }
    }

    int pendingReadCount() {
        return reads.size();
    }

    private ReadTask request(CubePos pos, int priority) {
        ReadTask candidate = new ReadTask(pos, priority, sequence.getAndIncrement());
        ReadTask existing = reads.putIfAbsent(pos, candidate);
        if (existing != null) {
            existing.promote(priority);
            return existing;
        }
        try {
            executor.execute(candidate);
            return candidate;
        } catch (RuntimeException exception) {
            reads.remove(pos, candidate);
            candidate.future.completeExceptionally(exception);
            throw exception;
        }
    }

    private static IOException asIOException(CubePos pos, Throwable cause) {
        if (cause instanceof IOException ioException) {
            return ioException;
        }
        return new IOException("Cannot read cube " + pos, cause);
    }

    @Override
    public void close() {
        executor.shutdown();
        boolean interrupted = false;
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                List<Runnable> cancelled = executor.shutdownNow();
                IOException closed = new IOException("Cube IO scheduler closed before queued read ran");
                for (Runnable runnable : cancelled) {
                    if (runnable instanceof ReadTask task) {
                        task.future.completeExceptionally(closed);
                    }
                }
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    reads.values().forEach(task -> task.future.completeExceptionally(closed));
                }
            }
        } catch (InterruptedException exception) {
            interrupted = true;
            executor.shutdownNow();
        } finally {
            reads.clear();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
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

    private final class ReadTask implements Runnable, Comparable<ReadTask> {
        private final CubePos pos;
        private volatile int priority;
        private final long sequenceNumber;
        private final CompletableFuture<Optional<byte[]>> future = new CompletableFuture<>();

        private ReadTask(CubePos pos, int priority, long sequenceNumber) {
            this.pos = pos;
            this.priority = priority;
            this.sequenceNumber = sequenceNumber;
        }

        private void promote(int requestedPriority) {
            if (requestedPriority >= priority) {
                return;
            }
            synchronized (this) {
                if (requestedPriority >= priority) {
                    return;
                }
                priority = requestedPriority;
                // PriorityBlockingQueue does not reorder an element whose sort
                // key changed. Reinsert only if a worker has not claimed it.
                if (executor.getQueue().remove(this)) {
                    executor.getQueue().offer(this);
                }
            }
        }

        @Override
        public void run() {
            try {
                future.complete(storage.read(pos));
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        }

        @Override
        public int compareTo(ReadTask other) {
            int byPriority = Integer.compare(priority, other.priority);
            return byPriority != 0 ? byPriority : Long.compare(sequenceNumber, other.sequenceNumber);
        }
    }
}
