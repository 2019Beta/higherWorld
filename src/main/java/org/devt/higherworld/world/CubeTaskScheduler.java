package org.devt.higherworld.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
        return requestGraph(pos, target, priority, new HashSet<>());
    }

    private CubeHolder requestGraph(
            CubePos pos, CubeStatus target, int priority, Set<StageRequest> visited) {
        CubeHolder holder = holders.computeIfAbsent(pos, CubeHolder::new);
        holder.request(target);
        if (target.isAtLeast(CubeStatus.IO_READY)) {
            holder.startIo(() -> io.readAsync(pos, priority));
        }
        StageRequest request = new StageRequest(pos, target);
        if (!visited.add(request)) return holder;

        for (CubeStatus stage : CubeStatus.values()) {
            if (stage.ordinal() > target.ordinal()) break;
            CubeStatus neighbourStatus = stage.neighbourPrerequisite();
            if (neighbourStatus == null) continue;
            stage.dependencyRadius().forEach(pos, dependency ->
                    requestGraph(dependency, neighbourStatus, priority + 1, visited));
        }
        return holder;
    }

    void replaceTicket(CubeTicket ticket) {
        tickets.replace(ticket);
        refreshTargets();
    }

    void removeTicket(Object key) {
        tickets.remove(key);
        refreshTargets();
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

    CubeHolder adoptLoaded(LoadedCube cube) {
        CubeHolder holder = holders.computeIfAbsent(cube.pos(), CubeHolder::new);
        holder.request(CubeStatus.FULL);
        holder.advance(CubeStatus.LIGHT);
        holder.materialize(cube);
        return holder;
    }

    CompletableFuture<Void> dependenciesFuture(CubeHolder holder, CubeStatus stage, int priority) {
        List<CompletableFuture<Void>> dependencies = new ArrayList<>();
        CubeStatus local = stage.localPrerequisite();
        if (local != null && local != CubeStatus.EMPTY) {
            dependencies.add(holder.localStageFuture(local));
        }
        dependencies.add(neighbourDependenciesFuture(holder, stage, priority));
        return CompletableFuture.allOf(dependencies.toArray(CompletableFuture[]::new));
    }

    CompletableFuture<Void> neighbourDependenciesFuture(
            CubeHolder holder, CubeStatus stage, int priority) {
        List<CompletableFuture<Void>> dependencies = new ArrayList<>();
        CubeStatus neighbour = stage.neighbourPrerequisite();
        if (neighbour != null) {
            stage.dependencyRadius().forEach(holder.pos(), dependency -> {
                CubeHolder dependencyHolder = request(dependency, neighbour, priority + 1);
                dependencies.add(dependencyHolder.localStageFuture(neighbour));
            });
        }
        return CompletableFuture.allOf(dependencies.toArray(CompletableFuture[]::new));
    }

    boolean neighbourDependenciesReady(CubeHolder holder, CubeStatus stage, int priority) {
        CompletableFuture<Void> future = neighbourDependenciesFuture(holder, stage, priority);
        return future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled();
    }

    void awaitNeighbourDependencies(CubeHolder holder, CubeStatus stage, int priority) {
        neighbourDependenciesFuture(holder, stage, priority).join();
    }

    boolean dependenciesReady(CubeHolder holder, CubeStatus stage, int priority) {
        CompletableFuture<Void> future = dependenciesFuture(holder, stage, priority);
        return future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled();
    }

    void awaitDependencies(CubeHolder holder, CubeStatus stage, int priority) {
        dependenciesFuture(holder, stage, priority).join();
    }

    CompletableFuture<CustomCubeGenerator.TerrainSnapshot> prepareTerrain(
            CubeHolder holder, long seed, CustomWorldSettings settings, int priority) {
        return holder.startTerrain(() -> dependenciesFuture(holder, CubeStatus.TERRAIN, priority)
                .thenCompose(ignored -> {
                    CompletableFuture<CustomCubeGenerator.TerrainSnapshot> result = new CompletableFuture<>();
                    long epoch = holder.epoch();
                    generationExecutor.execute(new GenerationTask(priority, sequence.getAndIncrement(), () -> {
                        if (!holder.isCurrent(epoch) || !holder.target().isAtLeast(CubeStatus.TERRAIN)) {
                            result.cancel(false);
                            return;
                        }
                        try {
                            result.complete(CustomCubeGenerator.prepareTerrain(seed, holder.pos(), settings));
                        } catch (Throwable throwable) {
                            result.completeExceptionally(throwable);
                            holder.fail(epoch, throwable);
                        }
                    }));
                    return result;
                }));
    }

    List<CubeHolder> readyTerrain(int limit) {
        return holders.values().stream()
                .filter(holder -> holder.terrainPreparationFuture() != null
                        && holder.terrainPreparationFuture().isDone())
                .filter(holder -> !holder.terrainPreparationFuture().isCancelled()
                        && !holder.terrainPreparationFuture().isCompletedExceptionally())
                .filter(holder -> !holder.status().isAtLeast(CubeStatus.TERRAIN))
                .filter(holder -> neighbourDependenciesReady(holder, CubeStatus.FEATURES,
                        tickets.priority(holder.pos())))
                .sorted(Comparator.comparingInt(holder -> tickets.priority(holder.pos())))
                .limit(limit)
                .toList();
    }

    void trackSave(CubePos pos, CompletableFuture<Void> save) {
        CubeHolder holder = holders.computeIfAbsent(pos, CubeHolder::new);
        holder.trackSave(save);
        save.whenComplete((ignored, throwable) -> {
            if (!tickets.isActive(pos) && holder.target() == CubeStatus.EMPTY) {
                holders.remove(pos, holder);
            }
        });
    }

    void retainTicketedHolders() {
        refreshTargets();
    }

    private void refreshTargets() {
        Map<CubePos, CubeStatus> required = new HashMap<>();
        Set<StageRequest> visited = new HashSet<>();
        for (CubePos pos : tickets.activePositions()) {
            collectRequired(pos, tickets.targetStatus(pos), required, visited);
        }

        // Tickets describe demand only. Actual IO starts from the watcher's
        // bounded read-ahead request, which then expands just that cube's DAG.
        required.forEach((pos, target) ->
                holders.computeIfAbsent(pos, CubeHolder::new).request(target));
        io.retainPrefetches(required.keySet());
        holders.forEach((pos, holder) -> {
            CubeStatus target = required.get(pos);
            if (target == null) {
                holder.cancel();
                holders.remove(pos, holder);
            } else {
                holder.lowerTarget(target);
            }
        });
    }

    private static void collectRequired(
            CubePos pos, CubeStatus target, Map<CubePos, CubeStatus> required,
            Set<StageRequest> visited) {
        required.merge(pos, target, CubeTaskScheduler::maximum);
        StageRequest request = new StageRequest(pos, target);
        if (!visited.add(request)) return;
        for (CubeStatus stage : CubeStatus.values()) {
            if (stage.ordinal() > target.ordinal()) break;
            CubeStatus neighbour = stage.neighbourPrerequisite();
            if (neighbour == null) continue;
            stage.dependencyRadius().forEach(pos, dependency ->
                    collectRequired(dependency, neighbour, required, visited));
        }
    }

    private static CubeStatus maximum(CubeStatus first, CubeStatus second) {
        return first.ordinal() >= second.ordinal() ? first : second;
    }

    @Override
    public void close() {
        generationExecutor.shutdownNow();
        holders.values().forEach(CubeHolder::cancel);
        holders.clear();
    }

    private record StageRequest(CubePos pos, CubeStatus status) {}

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
