package org.devt.higherworld.world;

import java.util.ArrayList;
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
import net.minecraft.server.world.ServerWorld;
import org.devt.higherworld.storage.CubeIoScheduler;
import org.devt.higherworld.storage.CubePos;

/** Cube dependency DAG coordinator. IO and pure terrain run off-thread; commits do not. */
final class CubeTaskScheduler implements AutoCloseable {
    private final CubeIoScheduler io;
    private final CubeTicketManager tickets = new CubeTicketManager();
    private final ConcurrentMap<CubePos, CubeHolder> holders = new ConcurrentHashMap<>();
    /** Lowest request priority seen for each live lifecycle node. */
    private final ConcurrentMap<CubePos, Integer> requestPriorities = new ConcurrentHashMap<>();
    /**
     * The complete closure currently demanded by live tickets.  Keeping this
     * separately from {@link #holders} is important: a dependency cube can be
     * required by a ticket without being part of a player's sent/pending view.
     */
    private volatile Set<CubePos> requiredPositions = Set.of();
    /**
     * Roots currently retained by the streaming watcher.  These roots are
     * PAYLOAD demand, not simulation demand, so they must participate in the
     * same closure calculation as tickets while remaining independently
     * cancellable when a view leaves them.
     */
    private volatile Set<CubePos> prefetchPositions = Set.of();
    private final ThreadPoolExecutor generationExecutor;
    private final AtomicLong sequence = new AtomicLong();
    private final PriorityBlockingQueue<ReadyEntry> readyQueue =
            new PriorityBlockingQueue<>(256, CubeTaskScheduler::compareReadyEntries);
    private volatile long priorityEpoch;

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
        requestPriorities.merge(pos, priority, Math::min);
        CubeHolder holder = holders.computeIfAbsent(pos, this::newHolder);
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

    /**
     * Retains the active watcher roots and their PAYLOAD dependency closure.
     * A watcher is deliberately not represented as a FULL ticket: streaming a
     * block payload must not start the lighting graph for the whole view.
     */
    void retainPrefetches(Set<CubePos> retained) {
        prefetchPositions = Set.copyOf(retained);
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
        if (holder != null) {
            requestPriorities.remove(pos);
            holder.cancel();
        }
    }

    int priority(CubePos pos) {
        return Math.min(
                tickets.priority(pos), requestPriorities.getOrDefault(pos, Integer.MAX_VALUE));
    }

    Set<CubePos> activePositions() {
        return tickets.activePositions();
    }

    CubeHolder holder(CubePos pos) {
        return holders.computeIfAbsent(pos, this::newHolder);
    }

    private CubeHolder newHolder(CubePos pos) {
        return new CubeHolder(pos, this::holderChanged);
    }

    private void holderChanged(CubeHolder holder) {
        if (holder.target() == CubeStatus.EMPTY || holder.failed()) return;
        readyQueue.offer(new ReadyEntry(
                holder, holder.changeVersion(), priority(holder.pos()),
                holder.status().ordinal(), priorityEpoch));
    }

    /** Requeues a holder whose next stage still needs a bounded commit slice. */
    void requeue(CubeHolder holder) {
        holderChanged(holder);
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
        CubeHolder holder = holders.computeIfAbsent(cube.pos(), this::newHolder);
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

    /**
     * Returns lifecycle nodes that have finished IO but still need server-thread
     * stage commits. Dependencies are requested recursively by {@link #request}.
     */
    List<CubeHolder> readyForCommit(int limit) {
        if (limit <= 0) return List.of();

        // Holder state transitions enqueue a versioned entry. Poll only the
        // current frontier instead of scanning and partially sorting every
        // holder on every server-thread commit slice.
        ArrayList<CubeHolder> result = new ArrayList<>(limit);
        Set<CubeHolder> selected = new HashSet<>();
        ArrayList<ReadyEntry> blocked = new ArrayList<>();
        int scanBudget = readyQueue.size();
        while (result.size() < limit && scanBudget-- > 0) {
            ReadyEntry entry = readyQueue.poll();
            if (entry == null) break;
            CubeHolder holder = entry.holder();
            if (entry.changeVersion() != holder.changeVersion()
                    || entry.priorityEpoch() != priorityEpoch
                    || entry.priority() != priority(holder.pos())
                    || entry.statusOrdinal() != holder.status().ordinal()) {
                holderChanged(holder);
                continue;
            }
            if (!isReadyCandidate(holder)) continue;
            if (!nextCommitDependenciesReady(holder)) {
                // Do not put a blocked high-priority node back at the head
                // while scanning. Its lower-priority TERRAIN/FEATURES
                // prerequisites are in this same queue; re-offering it here
                // would make the priority queue return the same node until
                // scanBudget is exhausted, so the dependency can never run.
                blocked.add(entry);
                continue;
            }
            if (selected.add(holder)) result.add(holder);
        }
        blocked.forEach(readyQueue::offer);
        return result;
    }

    private boolean isReadyCandidate(CubeHolder holder) {
        if (holder.failed()
                || !holder.target().isAtLeast(CubeStatus.TERRAIN)
                || !holder.status().isAtLeast(CubeStatus.IO_READY)
                || holder.status().isAtLeast(holder.target())) {
            return false;
        }

        // The first visit starts terrain preparation. Once preparation is in
        // flight, leave this holder out until the worker completes.
        CompletableFuture<CubeTerrainSnapshot> terrain = holder.terrainPreparationFuture();
        return holder.status() != CubeStatus.IO_READY
                || terrain == null || terrain.isDone();
    }

    void trackSave(CubePos pos, CompletableFuture<Void> save) {
        CubeHolder holder = holders.computeIfAbsent(pos, this::newHolder);
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

    private void refreshTargets() {
        priorityEpoch++;
        Map<CubePos, CubeStatus> required = new HashMap<>();
        for (CubeTicket ticket : tickets.activeTickets()) {
            collectRequired(ticket.center(), ticket.radius(), ticket.targetStatus(), required);
        }
        for (CubePos pos : prefetchPositions) {
            collectRequired(pos, CubeDependencyRadius.NONE, CubeStatus.PAYLOAD, required);
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
                requestPriorities.remove(pos);
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
        requestPriorities.clear();
        requiredPositions = Set.of();
        prefetchPositions = Set.of();
        readyQueue.clear();
    }

    private record ReadyEntry(
            CubeHolder holder, long changeVersion, int priority,
            int statusOrdinal, long priorityEpoch) {}

    private static int compareReadyEntries(ReadyEntry first, ReadyEntry second) {
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
