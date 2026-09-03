package org.devt.higherworld.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.world.ServerWorld;
import org.devt.higherworld.storage.CubeIoScheduler;
import org.devt.higherworld.storage.CubePos;

/** Cube dependency DAG coordinator. IO and pure terrain run off-thread; commits do not. */
final class CubeTaskScheduler implements AutoCloseable {
    private final CubeIoScheduler io;
    private final CubeTicketManager tickets = new CubeTicketManager();
    private final ConcurrentMap<CubePos, CubeHolder> holders = new ConcurrentHashMap<>();
    /**
     * The complete closure currently demanded by live tickets.  Keeping this
     * separately from {@link #holders} is important: a dependency cube can be
     * required by a ticket without being part of a player's sent/pending view.
     */
    private volatile Set<CubePos> requiredPositions = Set.of();
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

    /** Returns whether a cube is part of an active ticket's dependency closure. */
    boolean isRequired(CubePos pos) {
        return requiredPositions.contains(pos);
    }

    /**
     * Releases a cache entry that is no longer ticket-required.  Cancelling the
     * holder here prevents a stale IO or generation completion from
     * resurrecting a cube after its live state has been evicted.
     */
    void release(CubePos pos) {
        if (isRequired(pos)) return;
        CubeHolder holder = holders.remove(pos);
        if (holder != null) holder.cancel();
    }

    int priority(CubePos pos) {
        return tickets.priority(pos);
    }

    Set<CubePos> activePositions() {
        return tickets.activePositions();
    }

    CubeHolder holder(CubePos pos) {
        return holders.computeIfAbsent(pos, CubeHolder::new);
    }

    /**
     * Reads lifecycle state without creating a holder. Generation-time reads
     * use this method so a missing neighbour cannot silently become a new
     * synchronous dependency request.
     */
    boolean reached(CubePos pos, CubeStatus status) {
        CubeHolder holder = holders.get(pos);
        return holder != null && holder.status().isAtLeast(status);
    }

    /** Package-private inspection hook used by lifecycle tests. */
    int holderCount() {
        return holders.size();
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
        CubeStatus neighbour = stage.neighbourPrerequisite();
        if (neighbour == null) return true;

        boolean ready = true;
        CubeDependencyRadius radius = stage.dependencyRadius();
        for (int offsetY = -radius.y(); offsetY <= radius.y(); offsetY++) {
            for (int offsetZ = -radius.z(); offsetZ <= radius.z(); offsetZ++) {
                for (int offsetX = -radius.x(); offsetX <= radius.x(); offsetX++) {
                    CubePos dependency = new CubePos(
                            Math.addExact(holder.pos().x(), offsetX),
                            Math.addExact(holder.pos().y(), offsetY),
                            Math.addExact(holder.pos().z(), offsetZ));
                    // requestGraph has already materialized the dependency
                    // closure for every holder that can reach this commit.
                    // Use it directly on the hot polling path; retain a fallback
                    // for holders adopted by tests or other direct callers.
                    CubeHolder dependencyHolder = holders.get(dependency);
                    if (dependencyHolder == null) {
                        dependencyHolder = request(dependency, neighbour, priority + 1);
                    }
                    if (dependencyHolder.failed()
                            || !dependencyHolder.status().isAtLeast(neighbour)) {
                        ready = false;
                    }
                }
            }
        }
        return ready;
    }

    /**
     * Starts/inspects the terrain-only read set for a batched underground
     * feature pass.  The extra positions are deliberately not FEATURES
     * dependencies: they provide read-only terrain to the immutable batch and
     * must never recursively start another feature pass.
     */
    boolean featureBatchTerrainReady(ServerWorld world, CubeHolder holder, int priority) {
        boolean ready = true;
        for (CubePos dependency : VanillaPlacedFeatureGenerator.terrainBatchPositions(
                world, holder.pos())) {
            CubeHolder dependencyHolder = request(dependency, CubeStatus.TERRAIN, priority + 1);
            if (dependencyHolder.failed()
                    || !dependencyHolder.status().isAtLeast(CubeStatus.TERRAIN)) {
                ready = false;
            }
        }
        return ready;
    }

    void awaitNeighbourDependencies(CubeHolder holder, CubeStatus stage, int priority) {
        neighbourDependenciesFuture(holder, stage, priority).join();
    }

    boolean dependenciesReady(CubeHolder holder, CubeStatus stage, int priority) {
        boolean ready = true;
        CubeStatus local = stage.localPrerequisite();
        if (local != null && local != CubeStatus.EMPTY
                && (holder.failed() || !holder.status().isAtLeast(local))) {
            ready = false;
        }
        // Keep requesting every neighbour even when the local prerequisite is
        // not ready.  The future-based implementation did the same, and it
        // ensures the dependency graph keeps making progress between polls.
        if (!neighbourDependenciesReady(holder, stage, priority)) ready = false;
        return ready;
    }

    void awaitDependencies(CubeHolder holder, CubeStatus stage, int priority) {
        dependenciesFuture(holder, stage, priority).join();
    }

    CompletableFuture<CubeTerrainSnapshot> prepareTerrain(
            CubeHolder holder, long seed, CustomWorldSettings settings, int priority) {
        return holder.startTerrain(() -> dependenciesFuture(holder, CubeStatus.TERRAIN, priority)
                .thenCompose(ignored -> {
                    CompletableFuture<CubeTerrainSnapshot> result = new CompletableFuture<>();
                    long epoch = holder.epoch();
                    try {
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
                    } catch (RuntimeException exception) {
                        result.completeExceptionally(exception);
                        holder.fail(epoch, exception);
                    }
                    return result;
                }));
    }

    /**
     * Captures the vanilla noise inputs on the server thread, then samples a
     * 16 x 16 x 64 immutable batch on a generation worker. The worker never
     * receives a ServerWorld or LoadedCube; its structure accessor and palette
     * factory are the read-only collaborators vanilla passes to the same
     * asynchronous noise-fill operation.
     */
    CompletableFuture<CubeTerrainSnapshot> prepareVanillaTerrain(
            CubeHolder holder, ServerWorld world, int priority) {
        VanillaCubeTerrainGenerator.TerrainRequest request =
                VanillaCubeTerrainGenerator.prepareRequest(world, holder.pos());
        if (request == null) return null;
        return holder.startTerrain(() -> dependenciesFuture(holder, CubeStatus.TERRAIN, priority)
                .thenCompose(ignored -> {
                    CompletableFuture<CubeTerrainSnapshot> result = new CompletableFuture<>();
                    long epoch = holder.epoch();
                    try {
                        generationExecutor.execute(new GenerationTask(
                                priority, sequence.getAndIncrement(), () -> {
                                    if (!holder.isCurrent(epoch)
                                            || !holder.target().isAtLeast(CubeStatus.TERRAIN)) {
                                        result.cancel(false);
                                        return;
                                    }
                                    try {
                                        result.complete(VanillaCubeTerrainGenerator.prepareTerrain(request));
                                    } catch (Throwable throwable) {
                                        result.completeExceptionally(throwable);
                                        holder.fail(epoch, throwable);
                                    }
                                }));
                    } catch (RuntimeException exception) {
                        result.completeExceptionally(exception);
                        holder.fail(epoch, exception);
                    }
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
                .sorted(Comparator.comparingInt(holder -> tickets.priority(holder.pos())))
                .limit(limit)
                .toList();
    }

    /**
     * Returns lifecycle nodes that have finished IO but still need server-thread
     * stage commits. Dependencies are requested recursively by {@link #request}.
     */
    List<CubeHolder> readyForCommit(int limit) {
        if (limit <= 0) return List.of();

        // Keep only the best limit candidates while scanning.  The previous
        // stream.sorted() retained and ordered every ready holder, even though
        // callers consume at most 64 per slice; during a view rebuild this list
        // can contain the whole dependency closure.
        PriorityQueue<ReadyCandidate> best = new PriorityQueue<>(limit, (first, second) ->
                compareReady(second, first));
        // Keep a second, dependency-first frontier. A high-priority LIGHT
        // candidate can be blocked on FEATURES cubes outside the ticket's
        // direct volume (and therefore with no ticket priority of their own).
        // Reserving fallback candidates lets those prerequisites advance
        // without making lifecycle stage the primary ordering again.
        PriorityQueue<ReadyCandidate> prerequisites = new PriorityQueue<>(
                limit, (first, second) -> comparePrerequisite(second, first));
        for (CubeHolder holder : holders.values()) {
            if (holder.failed()
                    || !holder.target().isAtLeast(CubeStatus.TERRAIN)
                    || !holder.status().isAtLeast(CubeStatus.IO_READY)
                    || holder.status().isAtLeast(holder.target())) {
                continue;
            }

            // The first visit starts terrain preparation. Once preparation is
            // in flight, leave this holder out until the worker completes;
            // repeatedly polling the same future on the server thread can
            // consume the whole bounded commit slice during view rebuilds.
            CompletableFuture<CubeTerrainSnapshot> terrain = holder.terrainPreparationFuture();
            if (holder.status() == CubeStatus.IO_READY
                    && terrain != null && !terrain.isDone()) {
                continue;
            }

            ReadyCandidate candidate = new ReadyCandidate(
                    holder, tickets.priority(holder.pos()), holder.status().ordinal());
            offerBounded(best, candidate, limit, CubeTaskScheduler::compareReady);
            offerBounded(
                    prerequisites, candidate, limit, CubeTaskScheduler::comparePrerequisite);
        }

        Set<ReadyCandidate> candidates = new HashSet<>(best);
        candidates.addAll(prerequisites);
        ArrayList<ReadyCandidate> ordered = new ArrayList<>(candidates.size());
        for (ReadyCandidate candidate : candidates) {
            if (nextCommitDependenciesReady(candidate.holder())) ordered.add(candidate);
        }
        ordered.sort(CubeTaskScheduler::compareReady);
        ArrayList<CubeHolder> result = new ArrayList<>(Math.min(limit, ordered.size()));
        for (ReadyCandidate candidate : ordered) {
            if (result.size() >= limit) break;
            result.add(candidate.holder());
        }
        return result;
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

    boolean saveComplete(CubePos pos) {
        CubeHolder holder = holders.get(pos);
        if (holder == null) return true;
        CompletableFuture<Void> save = holder.saveFuture();
        return save.isDone() && !save.isCompletedExceptionally() && !save.isCancelled();
    }

    void retainTicketedHolders() {
        refreshTargets();
    }

    private void refreshTargets() {
        Map<CubePos, CubeStatus> required = new HashMap<>();
        for (CubeTicket ticket : tickets.activeTickets()) {
            collectRequired(ticket.center(), ticket.radius(), ticket.targetStatus(), required);
        }

        // Publish the closure before lowering/cancelling holders.  The world
        // tick and eviction paths read this immutable snapshot concurrently
        // with asynchronous IO completion.
        requiredPositions = Set.copyOf(required.keySet());

        // Tickets describe demand, but do not themselves start work for every
        // cube in the (potentially very large) closure.  Only holders already
        // participating in an IO/generation request are retained.  The
        // watcher's bounded prefetch or an explicit request() materializes a
        // new DAG node and chooses its target status.
        io.retainPrefetches(required.keySet());
        holders.forEach((pos, holder) -> {
            CubeStatus target = required.get(pos);
            if (target == null) {
                holder.cancel();
                holders.remove(pos, holder);
            } else if (target.ordinal() < holder.target().ordinal()) {
                holder.lowerTarget(target);
            }
        });
    }

    /**
     * Adds one ticket and all of its transitive neighbour requirements by
     * expanding the ticket cuboid.  Dependency status always decreases, so
     * this visits at most the small status DAG once per edge rather than
     * recursively starting from every active cube position.
     */
    private static void collectRequired(
            CubePos center, CubeDependencyRadius radius, CubeStatus target,
            Map<CubePos, CubeStatus> required) {
        mergeAll(required, center, radius, target);
        for (CubeStatus stage : CubeStatus.values()) {
            if (stage.ordinal() > target.ordinal()) break;
            CubeStatus neighbour = stage.neighbourPrerequisite();
            if (neighbour == null) continue;

            CubeDependencyRadius expanded = expanded(radius, stage.dependencyRadius());
            collectRequired(center, expanded, neighbour, required);
        }
    }

    private static CubeDependencyRadius expanded(
            CubeDependencyRadius first, CubeDependencyRadius second) {
        return new CubeDependencyRadius(
                Math.addExact(first.x(), second.x()),
                Math.addExact(first.y(), second.y()),
                Math.addExact(first.z(), second.z()));
    }

    private static void mergeAll(
            Map<CubePos, CubeStatus> required, CubePos center,
            CubeDependencyRadius radius, CubeStatus target) {
        radius.forEach(center,
                pos -> required.merge(pos, target, CubeTaskScheduler::maximum));
    }

    private static CubeStatus maximum(CubeStatus first, CubeStatus second) {
        return first.ordinal() >= second.ordinal() ? first : second;
    }

    private record StageRequest(CubePos pos, CubeStatus status) {}

    @Override
    public void close() {
        generationExecutor.shutdownNow();
        holders.values().forEach(CubeHolder::cancel);
        holders.clear();
        requiredPositions = Set.of();
    }

    private record ReadyCandidate(CubeHolder holder, int priority, int statusOrdinal) {}

    private static int compareReady(ReadyCandidate first, ReadyCandidate second) {
        // Ticket priority includes distance from the ticket centre.  It must be
        // the primary key so newly arriving, distant IO_READY cubes cannot
        // indefinitely displace a nearby cube that is one commit from FULL.
        int byPriority = Integer.compare(first.priority(), second.priority());
        if (byPriority != 0) return byPriority;

        // Within the same distance band, finish work already in flight before
        // starting another stage.  Blocked high-stage nodes are filtered by
        // nextCommitDependenciesReady(), so this cannot starve their lower-stage
        // dependency cubes.
        return Integer.compare(second.statusOrdinal(), first.statusOrdinal());
    }

    private static int comparePrerequisite(ReadyCandidate first, ReadyCandidate second) {
        int byStatus = Integer.compare(first.statusOrdinal(), second.statusOrdinal());
        return byStatus != 0
                ? byStatus : Integer.compare(first.priority(), second.priority());
    }

    private static void offerBounded(
            PriorityQueue<ReadyCandidate> queue, ReadyCandidate candidate, int limit,
            Comparator<ReadyCandidate> comparator) {
        if (queue.size() < limit) {
            queue.offer(candidate);
        } else if (comparator.compare(candidate, queue.peek()) < 0) {
            queue.poll();
            queue.offer(candidate);
        }
    }

    private boolean nextCommitDependenciesReady(CubeHolder holder) {
        CubeStatus next = CubeStatus.values()[holder.status().ordinal() + 1];
        CubeStatus neighbour = next.neighbourPrerequisite();
        if (neighbour == null) return true;

        CubeDependencyRadius radius = next.dependencyRadius();
        for (int offsetY = -radius.y(); offsetY <= radius.y(); offsetY++) {
            for (int offsetZ = -radius.z(); offsetZ <= radius.z(); offsetZ++) {
                for (int offsetX = -radius.x(); offsetX <= radius.x(); offsetX++) {
                    CubePos dependency = new CubePos(
                            Math.addExact(holder.pos().x(), offsetX),
                            Math.addExact(holder.pos().y(), offsetY),
                            Math.addExact(holder.pos().z(), offsetZ));
                    CubeHolder dependencyHolder = holders.get(dependency);
                    if (dependencyHolder == null
                            || dependencyHolder.failed()
                            || !dependencyHolder.status().isAtLeast(neighbour)) {
                        return false;
                    }
                }
            }
        }
        return true;
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
