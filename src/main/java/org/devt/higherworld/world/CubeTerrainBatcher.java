package org.devt.higherworld.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;

/** Bounded collector shared by terrain strategies. Overflow uses the CPU executor. */
final class CubeTerrainBatcher<R> implements AutoCloseable {
    private static final int MAX_BATCH = 16;
    private static final int MAX_PENDING = 1024;
    private static final long COLLECTION_WINDOW_NANOS = 750_000L;
    private final LinkedBlockingQueue<R> pending = new LinkedBlockingQueue<>(MAX_PENDING);
    private final Set<R> active = ConcurrentHashMap.newKeySet();
    private final Function<R, CompletableFuture<?>> result;
    private final BiPredicate<R, R> compatible;
    private final Consumer<List<R>> process;
    private final Consumer<R> fallback;
    private final String threadName;
    private volatile boolean closed;
    private Thread worker;

    CubeTerrainBatcher(String threadName, Function<R, CompletableFuture<?>> result,
            BiPredicate<R, R> compatible, Consumer<List<R>> process, Consumer<R> fallback) {
        this.threadName = threadName;
        this.result = result;
        this.compatible = compatible;
        this.process = process;
        this.fallback = fallback;
    }

    void submit(R request) {
        boolean overflow;
        synchronized (this) {
            if (closed) {
                result.apply(request).completeExceptionally(new IllegalStateException("Terrain batcher is closed"));
                return;
            }
            active.add(request);
            overflow = !pending.offer(request);
            result.apply(request).whenComplete((value, failure) -> {
                pending.remove(request);
                active.remove(request);
            });
            if (worker == null) {
                worker = new Thread(this::run, threadName);
                worker.setDaemon(true);
                worker.start();
            }
        }
        if (overflow && !result.apply(request).isDone()) fallback.accept(request);
    }

    private void run() {
        while (!closed) {
            try {
                R first = pending.take();
                if (result.apply(first).isDone()) continue;
                List<R> batch = new ArrayList<>(MAX_BATCH);
                List<R> deferred = new ArrayList<>();
                batch.add(first);
                long deadline = System.nanoTime() + COLLECTION_WINDOW_NANOS;
                while (!closed && batch.size() < MAX_BATCH) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    R next = pending.poll(remaining, TimeUnit.NANOSECONDS);
                    if (next == null) break;
                    if (result.apply(next).isDone()) continue;
                    if (compatible.test(first, next)) batch.add(next);
                    else deferred.add(next);
                }
                for (R request : deferred) {
                    synchronized (this) {
                        if (closed || result.apply(request).isDone()) continue;
                        if (pending.offer(request)) continue;
                    }
                    fallback.accept(request);
                }
                if (!closed) process.accept(batch);
            } catch (InterruptedException interrupted) {
                if (closed) return;
            } catch (Throwable failure) {
                // A collector failure must not leave any queued future waiting forever.
                synchronized (this) {
                    closed = true;
                    active.forEach(request -> result.apply(request).completeExceptionally(failure));
                    pending.clear();
                }
                return;
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        active.forEach(request -> result.apply(request).cancel(false));
        pending.clear();
        if (worker != null) worker.interrupt();
    }
}
