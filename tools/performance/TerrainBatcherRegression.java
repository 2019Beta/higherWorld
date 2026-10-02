package org.devt.higherworld.world;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Standalone concurrency regression; requires only the JDK and CubeTerrainBatcher. */
public final class TerrainBatcherRegression {
    private record Request(int id, CompletableFuture<Void> result) {}

    public static void main(String[] args) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger fallback = new AtomicInteger();
        var batcher = new CubeTerrainBatcher<Request>("batcher-regression", Request::result,
                (first, next) -> true, batch -> {
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("blocked worker");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    batch.forEach(request -> request.result().complete(null));
                }, request -> {
                    fallback.incrementAndGet();
                    request.result().complete(null);
                });
        var first = new Request(-1, new CompletableFuture<>());
        batcher.submit(first);
        if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("worker never started");
        var queued = new ArrayList<Request>();
        try {
            for (int index = 0; index < 1024; index++) {
                var request = new Request(index, new CompletableFuture<>());
                queued.add(request);
                batcher.submit(request);
            }
            var overflow = new Request(1024, new CompletableFuture<>());
            batcher.submit(overflow);
            if (fallback.get() != 1 || !overflow.result().isDone()) throw new AssertionError("overflow not routed");
            queued.get(0).result().cancel(false);
            var replacement = new Request(1025, new CompletableFuture<>());
            batcher.submit(replacement);
            if (fallback.get() != 1) throw new AssertionError("cancelled queue slot was retained");
            batcher.close();
            if (!first.result().isDone() || !replacement.result().isCancelled()
                    || queued.stream().anyMatch(request -> !request.result().isDone())) {
                throw new AssertionError("close left unresolved work");
            }
            var afterClose = new Request(1026, new CompletableFuture<>());
            batcher.submit(afterClose);
            if (!afterClose.result().isCompletedExceptionally()) throw new AssertionError("closed collector accepted work");
        } finally {
            release.countDown();
            batcher.close();
        }
        System.out.println("Terrain batcher regression passed: overflow, cancellation, active close, closed submit");
    }
}
