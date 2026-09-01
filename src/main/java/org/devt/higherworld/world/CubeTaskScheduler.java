package org.devt.higherworld.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.devt.higherworld.storage.CubeIoScheduler;
import org.devt.higherworld.storage.CubePos;

/** Cube dependency DAG coordinator. IO and pure terrain run off-thread; commits do not. */
final class CubeTaskScheduler implements AutoCloseable {
    private final CubeIoScheduler io;
    private final CubeTicketManager tickets = new CubeTicketManager();
    private final ConcurrentMap<CubePos, CubeHolder> holders = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor generationExecutor;
    private final AtomicLong sequence = new AtomicLong();

    CubeTaskScheduler(CubeIoScheduler io) {
        this.io = io;
        int workers = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 2));
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "higherworld-cube-generation");
            thread.setDaemon(true);
            return thread;
        };
        generationExecutor = new ThreadPoolExecutor(workers, workers, 30L, TimeUnit.SECONDS,
                new PriorityBlockingQueue<>(), factory);
        generationExecutor.allowCoreThreadTimeOut(true);
    }

    CubeHolder request(CubePos pos, CubeStatus target, int priority) {
        CubeHolder holder = holders.computeIfAbsent(pos, CubeHolder::new);
        holder.request(target);
        io.prefetch(pos, priority);
        // Dependencies are deliberately anisotropic per status instead of a
        // mechanically expanded cubic radius.
        for (CubeStatus status : CubeStatus.values()) {
            if (status.ordinal() > target.ordinal()) break;
            status.dependencyRadius().forEach(pos, dependency -> io.prefetch(dependency, priority + 1));
        }
        return holder;
    }

    void replaceTicket(CubeTicket ticket) {
        Set<CubePos> changed = tickets.replace(ticket);
        refresh(changed);
    }

    void removeTicket(Object key) {
        refresh(tickets.remove(key));
    }

    boolean isTicketed(CubePos pos) {
        return tickets.isActive(pos);
    }

    Set<CubePos> activePositions() {
        return tickets.activePositions();
    }

    CubeHolder holder(CubePos pos) {
        return holders.computeIfAbsent(pos, CubeHolder::new);
    }

    CompletableFuture<CustomCubeGenerator.TerrainSnapshot> prepareTerrain(
            CubeHolder holder, long seed, CustomWorldSettings settings, int priority) {
        return holder.startTerrain(() -> {
            CompletableFuture<CustomCubeGenerator.TerrainSnapshot> result = new CompletableFuture<>();
            generationExecutor.execute(new GenerationTask(priority, sequence.getAndIncrement(), () -> {
                if (!holder.target().isAtLeast(CubeStatus.TERRAIN)) {
                    result.cancel(false);
                    return;
                }
                try {
                    result.complete(CustomCubeGenerator.prepareTerrain(seed, holder.pos(), settings));
                } catch (Throwable throwable) {
                    result.completeExceptionally(throwable);
                    holder.fail(throwable);
                }
            }));
            return result;
        });
    }

    List<CubeHolder> readyTerrain(int limit) {
        return holders.values().stream()
                .filter(holder -> holder.terrainFuture() != null && holder.terrainFuture().isDone())
                .filter(holder -> !holder.terrainFuture().isCancelled()
                        && !holder.terrainFuture().isCompletedExceptionally())
                .filter(holder -> !holder.status().isAtLeast(CubeStatus.TERRAIN))
                .sorted(Comparator.comparingInt(holder -> tickets.priority(holder.pos())))
                .limit(limit)
                .toList();
    }

    void retainTicketedHolders() {
        Set<CubePos> active = tickets.activePositions();
        io.retainPrefetches(withDependencies(active));
        holders.forEach((pos, holder) -> {
            if (!active.contains(pos)) {
                holder.lowerTarget(CubeStatus.EMPTY);
                CompletableFuture<?> terrain = holder.terrainFuture();
                if (terrain != null && !terrain.isDone()) terrain.cancel(false);
                holders.remove(pos, holder);
            }
        });
    }

    private void refresh(Set<CubePos> changed) {
        for (CubePos pos : changed) {
            CubeStatus target = tickets.targetStatus(pos);
            if (target == CubeStatus.EMPTY) {
                CubeHolder holder = holders.get(pos);
                if (holder != null) holder.lowerTarget(CubeStatus.EMPTY);
            } else {
                // A ticket records demand immediately, while the watcher's
                // bounded read-ahead pump decides when demand becomes IO. This
                // avoids turning a view-distance update into thousands of tasks.
                holders.computeIfAbsent(pos, CubeHolder::new).request(target);
            }
        }
        retainTicketedHolders();
    }

    private static Set<CubePos> withDependencies(Set<CubePos> active) {
        Set<CubePos> retained = ConcurrentHashMap.newKeySet();
        retained.addAll(active);
        CubeDependencyRadius radius = CubeStatus.FEATURES.dependencyRadius();
        active.forEach(pos -> radius.forEach(pos, retained::add));
        return retained;
    }

    @Override
    public void close() {
        generationExecutor.shutdownNow();
        holders.values().forEach(holder -> {
            CompletableFuture<?> future = holder.terrainFuture();
            if (future != null) future.cancel(false);
        });
        holders.clear();
    }

    private record GenerationTask(int priority, long sequence, Runnable action)
            implements Runnable, Comparable<GenerationTask> {
        @Override public void run() { action.run(); }

        @Override
        public int compareTo(GenerationTask other) {
            int byPriority = Integer.compare(priority, other.priority);
            return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
        }
    }
}
