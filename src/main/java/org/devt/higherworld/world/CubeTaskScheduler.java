package org.devt.higherworld.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.world.ServerWorld;
import org.devt.higherworld.Higherworld;
import org.devt.higherworld.gpu.GpuAccelerationConfig;
import org.devt.higherworld.gpu.GpuTerrainAccelerator;
import org.devt.higherworld.storage.CubeIoScheduler;
import org.devt.higherworld.storage.CubePos;

/** Cube dependency DAG coordinator. IO and pure terrain run off-thread; commits do not. */
final class CubeTaskScheduler implements AutoCloseable {
    private final CubeIoScheduler io;
    private final CubeTicketManager tickets = new CubeTicketManager();
    private final ConcurrentMap<CubePos, CubeHolder> holders = new ConcurrentHashMap<>();
    /** Lifecycle-maintained frontier; cached terrain/payload cubes never enter it. */
    private final Set<CubeHolder> fullTickingHolders = ConcurrentHashMap.newKeySet();
    /** Lowest request priority seen for each live lifecycle node. */
    private final ConcurrentMap<CubePos, Integer> requestPriorities = new ConcurrentHashMap<>();
    /**
     * The complete closure currently demanded by live tickets.  Keeping this
     * separately from {@link #holders} is important: a dependency cube can be
     * required by a ticket without being part of a player's sent/pending view.
     */
    private final CubeDemandIndex demand = new CubeDemandIndex();
    private final Set<CubePos> requiredPositions = demand.positions();
    private final Set<CubePos> dirtyTargets = ConcurrentHashMap.newKeySet();
    private final Map<Object, CubeTicket> demandTickets = new HashMap<>();
    /**
     * Roots currently retained by the streaming watcher.  These roots are
     * PAYLOAD demand, not simulation demand, so they must participate in the
     * same closure calculation as tickets while remaining independently
     * cancellable when a view leaves them.
     */
    private volatile Set<CubePos> prefetchPositions = Set.of();
    private final ThreadPoolExecutor generationExecutor;
    /** Collects independent custom cubes so OpenCL sees a useful work batch. */
    private final CustomTerrainBatcher customTerrainBatcher = new CustomTerrainBatcher();
    /** Collects deep vanilla cubes so the shared OpenCL raster stage is useful. */
    private final VanillaTerrainBatcher vanillaTerrainBatcher = new VanillaTerrainBatcher();
    /**
     * Terrain-only reads requested by a vanilla placed-feature batch.  These
     * reads are outside the normal 3x3x3 FEATURES graph, so keep them alive
     * until their owning FEATURES/PAYLOAD/FULL node leaves the active closure.
     * Without this retention, refreshTargets() cancelled them after the first
     * poll and every retry regenerated the same deep dependency set.
     */
    private final Map<CubePos, List<CubePos>> featureTerrainDependencies = new HashMap<>();
    /**
     * Feature terrain reads registered between target refreshes.  The owner map
     * is server-thread state, while this refcounted set is read by eviction and
     * release paths that can observe the newly requested dependency immediately.
     */
    private final ConcurrentMap<CubePos, Integer> featureTerrainRequiredCounts =
            new ConcurrentHashMap<>();
    /** Owners whose wider feature terrain read set has already become ready. */
    private final Set<CubePos> featureTerrainReady = new HashSet<>();
    /** Counts feature terrain graph starts for the package-level regression hook. */
    private long featureTerrainRequestCount;
    private final AtomicLong sequence = new AtomicLong();
    private final PriorityBlockingQueue<ReadyEntry> readyQueue =
            new PriorityBlockingQueue<>(256, CubeTaskScheduler::compareReadyEntries);
    /** At most one live ready entry per holder; stale physical entries are skipped. */
    private final ConcurrentMap<CubeHolder, ReadyEntry> queuedReady = new ConcurrentHashMap<>();
    /**
     * Holders whose next stage is still missing a dependency stay out of the
     * priority queue.  Re-offering every blocked holder on every commit scan
     * made the physical queue grow to ~10x the live entry count; the scans
     * then spent their whole budget polling and re-queuing the same blocked
     * front instead of committing work.  A waiter is re-offered only after
     * some holder somewhere advanced (the epoch gate below), and only at a
     * bounded rate, so dependency completion still wakes owners promptly
     * without resurrecting the queue churn.
     */
    private final ConcurrentMap<CubeHolder, Long> waitingSinceEpoch = new ConcurrentHashMap<>();
    /** Rotating scan order for {@link #waitingSinceEpoch}; server-thread only. */
    private final java.util.ArrayDeque<WaitingRef> waitingOrder = new java.util.ArrayDeque<>();
    /** Upper bound of waiting holders rechecked per commit scan. */
    private static final int MAX_WAITING_RECHECK = 512;
    /** Bumped by every holder change; gates the waiting recheck scan. */
    private final AtomicLong dependencyEpoch = new AtomicLong();

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
        // Resolve a ticket only for the explicit/root request.  requestGraph()
        // recursively walks the dependency DAG; applying the ticket target to
        // every child would turn all PAYLOAD prefetches inside the simulation
        // window into FULL requests and repeatedly expand the lighting graph.
        CubeStatus ticketTarget = tickets.targetStatus(pos);
        if (ticketTarget.ordinal() > target.ordinal()) target = ticketTarget;
        return requestGraph(pos, target, priority, new HashSet<>());
    }

    /**
     * Requests a prerequisite from an already selected root.  Ticket promotion
     * belongs to root ownership; applying it to a FEATURES/LIGHT neighbour or
     * a feature batch read would turn a cheap prerequisite into another FULL
     * root and recreate the deep-view explosion this scheduler is avoiding.
     */
    CubeHolder requestDependency(CubePos pos, CubeStatus target, int priority) {
        return requestGraph(pos, target, priority, new HashSet<>());
    }

    private CubeHolder requestGraph(
            CubePos pos, CubeStatus target, int priority, Set<StageRequest> visited) {
        requestPriorities.merge(pos, priority, Math::min);
        dirtyTargets.add(pos);
        CubeHolder holder = holders.computeIfAbsent(pos, this::newHolder);
        holder.request(target);
        refreshPriority(holder);
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
        CubeTicket previous = demandTickets.put(ticket.key(), ticket);
        if (ticket.equals(previous)) return;
        tickets.replace(ticket);
        Map<CubePos, CubeStatus> closure = new HashMap<>();
        CubeTicketManager.collectRequired(ticket.center(), ticket.radius(), ticket.targetStatus(), closure);
        dirtyTargets.addAll(demand.replace(new TicketRoot(ticket.key()), closure));
        refreshTargets();
        refreshTicketPriorities(previous, ticket);
    }

    void removeTicket(Object key) {
        CubeTicket previous = demandTickets.remove(key);
        if (previous == null) return;
        tickets.remove(key);
        dirtyTargets.addAll(demand.replace(new TicketRoot(key), Map.of()));
        refreshTargets();
        refreshTicketPriorities(previous, null);
    }

    private void refreshTicketPriorities(CubeTicket previous, CubeTicket next) {
        Set<CubePos> affected = new HashSet<>();
        if (previous != null) previous.radius().forEach(previous.center(), affected::add);
        if (next != null) next.radius().forEach(next.center(), affected::add);
        for (CubePos pos : affected) {
            CubeHolder holder = holders.get(pos);
            if (holder != null) refreshPriority(holder);
        }
    }

    private void refreshPriority(CubeHolder holder) {
        ReadyEntry entry = queuedReady.get(holder);
        if (entry != null && entry.priority() != priority(holder.pos())) enqueueReady(holder);
    }

    /**
     * Retains the active watcher roots and their PAYLOAD dependency closure.
     * A watcher is deliberately not represented as a FULL ticket: streaming a
     * block payload must not start the lighting graph for the whole view.
     */
    void retainPrefetches(Set<CubePos> retained) {
        if (!prefetchPositions.equals(retained)) {
            for (CubePos pos : prefetchPositions) {
                if (!retained.contains(pos)) {
                    dirtyTargets.addAll(demand.replace(new PrefetchRoot(pos), Map.of()));
                }
            }
            for (CubePos pos : retained) {
                if (prefetchPositions.contains(pos)) continue;
                Map<CubePos, CubeStatus> closure = new HashMap<>();
                CubeTicketManager.collectRequired(pos, CubeDependencyRadius.NONE, CubeStatus.PAYLOAD, closure);
                dirtyTargets.addAll(demand.replace(new PrefetchRoot(pos), closure));
            }
            prefetchPositions = Set.copyOf(retained);
        }
        // Still reconcile holders: ad-hoc requests and feature terrain reads
        // can change even when the watcher roots themselves have not moved.
        refreshTargets();
    }

    boolean isTicketed(CubePos pos) {
        return tickets.isActive(pos);
    }

    /** Returns whether a cube is part of an active ticket's dependency closure. */
    boolean isRequired(CubePos pos) {
        return requiredPositions.contains(pos) || featureTerrainRequiredCounts.containsKey(pos);
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
            removeQueuedReady(holder);
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
        dirtyTargets.add(pos);
        return new CubeHolder(pos, this::holderChanged);
    }

    private void holderChanged(CubeHolder holder) {
        // Any lifecycle change may have satisfied a waiting holder's
        // dependency.  A single counter is deliberately cheaper than reverse
        // dependency edges; the bounded recheck scan tolerates the occasional
        // unrelated wake-up.
        dependencyEpoch.incrementAndGet();
        enqueueReady(holder);
    }

    private void enqueueReady(CubeHolder holder) {
        waitingSinceEpoch.remove(holder);
        synchronized (holder) {
            if (!holder.failed() && holder.target() == CubeStatus.FULL
                    && holder.status() == CubeStatus.FULL) {
                fullTickingHolders.add(holder);
            } else {
                fullTickingHolders.remove(holder);
            }
            if (holder.target() == CubeStatus.EMPTY
                    || holder.failed()
                    || holder.status().isAtLeast(holder.target())) {
                removeQueuedReady(holder);
                return;
            }
            ReadyEntry entry = new ReadyEntry(
                    holder, holder.changeVersion(), priority(holder.pos()),
                    holder.status().ordinal());
            ReadyEntry previous = queuedReady.get(holder);
            // Equal snapshots already have a physical queue entry. Check this
            // before replacing the map value: replacing it with an equal but
            // different record would make the old entry fail the identity
            // check, while the new record would never be offered.
            if (entry.equals(previous)) return;
            // Keep replacement entries lazy. Removing from a global
            // PriorityBlockingQueue is O(n); the poller discards superseded
            // records by identity instead.
            queuedReady.put(holder, entry);
            readyQueue.offer(entry);
            compactReadyQueueIfNeeded();
        }
    }

    /** Requeues a holder whose next stage still needs a bounded commit slice. */
    void requeue(CubeHolder holder) {
        enqueueReady(holder);
    }

    Iterable<CubeHolder> fullTickingHolders() {
        return fullTickingHolders;
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

    /** Package-private inspection hooks used by lifecycle tests. */
    int readyQueueSizeForTest() {
        return readyQueue.size();
    }

    int queuedReadySizeForTest() {
        return queuedReady.size();
    }

    /** Package-private hook for verifying the feature dependency poll path. */
    CubeHolder featureTerrainDependencyForTest(CubePos dependency, int priority) {
        return featureTerrainDependency(dependency, priority);
    }

    /** Package-private hook for verifying graph requests stay one-shot. */
    long featureTerrainRequestCountForTest() {
        return featureTerrainRequestCount;
    }

    /** Package-private hook for registering a wide feature read in scheduler tests. */
    void registerFeatureTerrainDependenciesForTest(CubePos owner, List<CubePos> dependencies) {
        registerFeatureTerrainDependencies(owner, dependencies);
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
                    CubeHolder dependencyHolder = requestDependency(dependency, neighbour, priority + 1);
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
                        dependencyHolder = requestDependency(dependency, neighbour, priority + 1);
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
        if (featureTerrainReady.contains(holder.pos())) return true;
        boolean ready = true;
        List<CubePos> dependencies = featureTerrainDependencies.get(holder.pos());
        if (dependencies == null) {
            dependencies = VanillaPlacedFeatureGenerator.terrainBatchPositions(world, holder.pos());
            registerFeatureTerrainDependencies(holder.pos(), dependencies);
        }
        for (CubePos dependency : dependencies) {
            // The first pass materializes this wider read set. Once a holder
            // exists, polling only needs its lifecycle state; recursively
            // rebuilding the same request graph on every blocked FEATURES
            // commit accounted for a large share of server-thread samples.
            CubeHolder dependencyHolder = featureTerrainDependency(dependency, priority + 1);
            if (dependencyHolder.failed()
                    || !dependencyHolder.status().isAtLeast(CubeStatus.TERRAIN)) {
                ready = false;
            }
        }
        if (ready) featureTerrainReady.add(holder.pos());
        return ready;
    }

    private void registerFeatureTerrainDependencies(
            CubePos owner, List<CubePos> dependencies) {
        List<CubePos> immutable = List.copyOf(dependencies);
        List<CubePos> previous = featureTerrainDependencies.putIfAbsent(owner, immutable);
        if (previous != null) return;
        dirtyTargets.add(owner);
        for (CubePos dependency : immutable) {
            featureTerrainRequiredCounts.merge(dependency, 1, Integer::sum);
            dirtyTargets.add(dependency);
        }
    }

    private void releaseFeatureTerrainDependencies(List<CubePos> dependencies) {
        for (CubePos dependency : dependencies) {
            dirtyTargets.add(dependency);
            featureTerrainRequiredCounts.computeIfPresent(
                    dependency, (ignored, count) -> count > 1 ? count - 1 : null);
        }
    }

    private CubeHolder featureTerrainDependency(CubePos dependency, int priority) {
        CubeHolder dependencyHolder = holders.get(dependency);
        if (dependencyHolder == null
                || (!dependencyHolder.failed()
                        && dependencyHolder.target().ordinal() < CubeStatus.TERRAIN.ordinal())) {
            featureTerrainRequestCount++;
            dependencyHolder = requestDependency(dependency, CubeStatus.TERRAIN, priority);
        }
        return dependencyHolder;
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
                    long epoch = holder.epoch();
                    if (GpuTerrainAccelerator.isEnabled()) {
                        return customTerrainBatcher.submit(
                                holder, epoch, seed, settings, priority);
                    }
                    return submitCpuTerrain(holder, epoch, seed, settings, priority);
                }));
    }

    private CompletableFuture<CubeTerrainSnapshot> submitCpuTerrain(
            CubeHolder holder, long epoch, long seed,
            CustomWorldSettings settings, int priority) {
        CompletableFuture<CubeTerrainSnapshot> result = new CompletableFuture<>();
        try {
            generationExecutor.execute(new GenerationTask(priority, sequence.getAndIncrement(), () -> {
                if (!holder.isCurrent(epoch) || !holder.target().isAtLeast(CubeStatus.TERRAIN)) {
                    result.cancel(false);
                    return;
                }
                try {
                    result.complete(CustomCubeGenerator.prepareTerrainCpu(
                            seed, holder.pos(), settings));
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
                    long epoch = holder.epoch();
                    if (request.gpuEligible() && GpuTerrainAccelerator.isEnabled()) {
                        return vanillaTerrainBatcher.submit(holder, epoch, request, priority);
                    }
                    return submitVanillaCpuTerrain(holder, epoch, request, priority);
                }));
    }

    private CompletableFuture<CubeTerrainSnapshot> submitVanillaCpuTerrain(
            CubeHolder holder, long epoch,
            VanillaCubeTerrainGenerator.TerrainRequest request, int priority) {
        CompletableFuture<CubeTerrainSnapshot> result = new CompletableFuture<>();
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
    }

    /**
     * Returns lifecycle nodes that have finished IO but still need server-thread
     * stage commits. Dependencies are requested recursively by {@link #request}.
     */
    List<CubeHolder> readyForCommit(int limit) {
        return readyForCommit(limit, Long.MAX_VALUE);
    }

    List<CubeHolder> readyForCommit(int limit, long deadlineNanos) {
        if (limit <= 0) return List.of();

        // Holder state transitions enqueue a versioned entry. Poll only the
        // current frontier instead of scanning and partially sorting every
        // holder on every server-thread commit slice.
        ArrayList<CubeHolder> result = new ArrayList<>(limit);
        Set<CubeHolder> selected = new HashSet<>();
        // Reserve one bounded waiting pass even when fresh work keeps arriving.
        // Re-offering is not a dependency transition and never refunds scans.
        long now = System.nanoTime();
        long waitingDeadline = deadlineNanos == Long.MAX_VALUE ? Long.MAX_VALUE
                : now + Math.max(0L, (deadlineNanos - now) / 4L);
        recheckWaitingHolders(waitingDeadline);
        int scanBudget = (int) Math.min(8192L, 2L * readyQueue.size());
        while (result.size() < limit) {
            if (scanBudget-- <= 0 || System.nanoTime() >= deadlineNanos) return result;
            ReadyEntry entry = readyQueue.poll();
            if (entry == null) return result;
            CubeHolder holder = entry.holder();
            // The queue is intentionally lazy: a state update can supersede an
            // entry while its old object is still physically present. Only the
            // entry that still owns the holder's map slot may inspect or
            // requeue it. Identity is checked under the same lock used by
            // holderChanged(), so an old entry cannot consume a new wake-up.
            synchronized (holder) {
                if (queuedReady.get(holder) != entry) continue;
                queuedReady.remove(holder);
            }
            if (entry.changeVersion() != holder.changeVersion()
                    || entry.priority() != priority(holder.pos())
                    || entry.statusOrdinal() != holder.status().ordinal()) {
                enqueueReady(holder);
                continue;
            }
            if (!isReadyCandidate(holder)) continue;
            long blockedEpoch = dependencyEpoch.get();
            if (!nextCommitDependenciesReady(holder)) {
                // Do not put a blocked high-priority node back at the head
                // while scanning. Its lower-priority TERRAIN/FEATURES
                // prerequisites advance independently; the epoch-gated
                // waiting recheck re-offers it once some dependency has
                // actually moved, instead of bouncing it through the queue
                // on every scan.
                waitingSinceEpoch.put(holder, blockedEpoch);
                // Always enqueue a ref.  Transient duplicates are harmless:
                // a ref whose epoch no longer matches the map value is stale
                // and is dropped on its first pop.
                waitingOrder.addLast(new WaitingRef(holder, blockedEpoch));
                continue;
            }
            if (selected.add(holder)) result.add(holder);
        }
        return result;
    }

    /**
     * Re-offers waiting holders whose recorded dependency epoch is stale.
     * Server-thread only.  Each holder is rechecked at most once per epoch
     * change and the round is bounded, so a large waiting set cannot turn
     * back into the old unbounded queue churn.
     */
    private int recheckWaitingHolders(long deadlineNanos) {
        int budget = Math.min(MAX_WAITING_RECHECK, waitingOrder.size());
        long epoch = dependencyEpoch.get();
        int reoffered = 0;
        while (budget-- > 0 && !waitingOrder.isEmpty()) {
            if (System.nanoTime() >= deadlineNanos) break;
            WaitingRef ref = waitingOrder.pollFirst();
            CubeHolder holder = ref.holder();
            Long since = waitingSinceEpoch.get(holder);
            if (since == null || since != ref.epoch()) {
                // The holder left the set (or was re-blocked) after this ref
                // was enqueued; the map value owns the holder now.
                continue;
            }
            if (ref.epoch() == epoch) {
                // Nothing advanced since this holder was blocked. Rotate the
                // ref instead of re-offering it; its epoch is rechecked on a
                // later round.
                waitingOrder.addLast(ref);
                continue;
            }
            waitingSinceEpoch.remove(holder);
            if (holder.failed()
                    || holder.target() == CubeStatus.EMPTY
                    || holder.status().isAtLeast(holder.target())) {
                continue;
            }
            enqueueReady(holder);
            reoffered++;
        }
        return reoffered;
    }

    private void compactReadyQueueIfNeeded() {
        long live = queuedReady.size();
        if (readyQueue.size() <= 4L * live + 1024L) return;
        readyQueue.removeIf(entry -> queuedReady.get(entry.holder()) != entry);
    }

    private void removeQueuedReady(CubeHolder holder) {
        synchronized (holder) {
            queuedReady.remove(holder);
        }
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
        // Root diffs and ad-hoc requests identify the entire reconciliation
        // frontier. No full holder walk, halo walk or priority epoch reset.
        while (!dirtyTargets.isEmpty()) {
            List<CubePos> changed = new ArrayList<>(dirtyTargets);
            dirtyTargets.removeAll(changed);
            for (CubePos pos : changed) {
                CubeStatus target = demand.target(pos);
                if (!target.isAtLeast(CubeStatus.FEATURES)) {
                    List<CubePos> dependencies = featureTerrainDependencies.remove(pos);
                    if (dependencies != null) {
                        featureTerrainReady.remove(pos);
                        releaseFeatureTerrainDependencies(dependencies);
                    }
                }
                if (featureTerrainRequiredCounts.containsKey(pos)
                        && !target.isAtLeast(CubeStatus.TERRAIN)) target = CubeStatus.TERRAIN;
                CubeHolder holder = holders.get(pos);
                if (target == CubeStatus.EMPTY) {
                    requestPriorities.remove(pos);
                    if (holder != null) {
                        removeQueuedReady(holder);
                        holder.cancel();
                        holders.remove(pos, holder);
                    }
                    io.cancelPrefetch(pos);
                } else if (holder != null) {
                    if (target.ordinal() < holder.target().ordinal()) holder.lowerTarget(target);
                    else if (target.ordinal() > holder.target().ordinal()) {
                        requestGraph(pos, target, priority(pos), new HashSet<>());
                    }
                }
            }
        }
    }

    private record StageRequest(CubePos pos, CubeStatus status) {}
    private record TicketRoot(Object key) {}
    private record PrefetchRoot(CubePos pos) {}

    @Override
    public void close() {
        vanillaTerrainBatcher.close();
        customTerrainBatcher.close();
        generationExecutor.shutdownNow();
        holders.values().forEach(CubeHolder::cancel);
        holders.clear();
        fullTickingHolders.clear();
        requestPriorities.clear();
        demand.clear();
        demandTickets.clear();
        dirtyTargets.clear();
        prefetchPositions = Set.of();
        featureTerrainDependencies.clear();
        featureTerrainRequiredCounts.clear();
        featureTerrainReady.clear();
        featureTerrainRequestCount = 0L;
        readyQueue.clear();
        queuedReady.clear();
        waitingSinceEpoch.clear();
        waitingOrder.clear();
    }

    private record ReadyEntry(
            CubeHolder holder, long changeVersion, int priority,
            int statusOrdinal) {}

    private record WaitingRef(CubeHolder holder, long epoch) {}

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

        // Vanilla placed features read a wider terrain halo than the normal
        // 3x3x3 FEATURES graph.  Once the first feature poll has registered
        // that halo, keep its owner out of the commit result until every read
        // is ready.  A missing registration deliberately passes above: the
        // feature poll then materializes the halo exactly once.
        if (holder.status() == CubeStatus.TERRAIN) {
            List<CubePos> featureDependencies = featureTerrainDependencies.get(holder.pos());
            if (featureDependencies != null) {
                for (CubePos dependency : featureDependencies) {
                    CubeHolder dependencyHolder = holders.get(dependency);
                    if (dependencyHolder == null
                            || dependencyHolder.failed()
                            || !dependencyHolder.status().isAtLeast(CubeStatus.TERRAIN)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Batches the sparse, below-vanilla-height part of the normal
     * NoiseChunkGenerator path.  Four vertical cubes share one 64-block
     * density batch, and nearby horizontal batches are dispatched together so
     * the OpenCL raster kernel is not invoked once per cube.
     */
    private final class VanillaTerrainBatcher implements AutoCloseable {
        private static final int MAX_BATCH = 16;
        private static final long COLLECTION_WINDOW_NANOS = 750_000L;

        private final BlockingQueue<VanillaTerrainRequest> pending =
                new LinkedBlockingQueue<>();
        private final Object lifecycleLock = new Object();
        private volatile boolean closed;
        private Thread worker;

        private CompletableFuture<CubeTerrainSnapshot> submit(
                CubeHolder holder, long epoch,
                VanillaCubeTerrainGenerator.TerrainRequest request, int priority) {
            CompletableFuture<CubeTerrainSnapshot> result = new CompletableFuture<>();
            VanillaTerrainRequest queued = new VanillaTerrainRequest(
                    holder, epoch, request, priority, result);
            synchronized (lifecycleLock) {
                if (closed) {
                    result.completeExceptionally(new IllegalStateException(
                            "Vanilla terrain batcher is closed"));
                    return result;
                }
                pending.offer(queued);
                if (worker == null) {
                    worker = new Thread(this::run, "higherworld-vanilla-terrain-batcher");
                    worker.setDaemon(true);
                    worker.start();
                }
                lifecycleLock.notifyAll();
            }
            return result;
        }

        private void run() {
            while (!closed) {
                VanillaTerrainRequest first;
                try {
                    first = pending.take();
                } catch (InterruptedException interrupted) {
                    if (closed) return;
                    continue;
                }
                List<VanillaTerrainRequest> batch = new ArrayList<>(MAX_BATCH);
                batch.add(first);
                List<VanillaTerrainRequest> deferred = new ArrayList<>();
                long deadline = System.nanoTime() + COLLECTION_WINDOW_NANOS;
                while (batch.size() < MAX_BATCH) {
                    VanillaTerrainRequest next = pending.poll();
                    if (next == null) {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0L) break;
                        try {
                            next = pending.poll(remaining, TimeUnit.NANOSECONDS);
                        } catch (InterruptedException interrupted) {
                            if (closed) return;
                            continue;
                        }
                        if (next == null) break;
                    }
                    if (compatible(first, next)) batch.add(next);
                    else deferred.add(next);
                }
                deferred.forEach(pending::offer);
                process(batch);
            }
            VanillaTerrainRequest request;
            while ((request = pending.poll()) != null) {
                request.result().cancel(false);
            }
        }

        private boolean compatible(VanillaTerrainRequest first, VanillaTerrainRequest next) {
            VanillaCubeTerrainGenerator.TerrainRequest firstRequest = first.request();
            VanillaCubeTerrainGenerator.TerrainRequest nextRequest = next.request();
            return firstRequest.seed() == nextRequest.seed()
                    && firstRequest.settings() == nextRequest.settings()
                    && firstRequest.noiseParameters() == nextRequest.noiseParameters()
                    && firstRequest.biomeSource() == nextRequest.biomeSource()
                    && firstRequest.palettesFactory() == nextRequest.palettesFactory()
                    && firstRequest.structureAccessor() == nextRequest.structureAccessor()
                    && firstRequest.asyncBatches() == nextRequest.asyncBatches();
        }

        private void process(List<VanillaTerrainRequest> requests) {
            List<VanillaTerrainRequest> live = new ArrayList<>(requests.size());
            for (VanillaTerrainRequest request : requests) {
                if (request.holder().isCurrent(request.epoch())
                        && request.holder().target().isAtLeast(CubeStatus.TERRAIN)
                        && !request.result().isCancelled()) {
                    live.add(request);
                } else {
                    request.result().cancel(false);
                }
            }
            if (live.isEmpty()) return;

            Map<CubePos, CubeTerrainSnapshot> generated;
            try {
                List<VanillaCubeTerrainGenerator.TerrainRequest> terrainRequests =
                        live.stream().map(VanillaTerrainRequest::request).toList();
                generated = VanillaCubeTerrainGenerator.prepareGpuTerrainBatch(terrainRequests);
            } catch (Throwable throwable) {
                // The GPU facade throws when fallback_on_error=false, or when
                // a request violates a validation invariant (an over-large
                // batch once failed thousands of cubes here with no log at
                // all).  Honor an explicitly requested hard failure; any
                // other throwable falls back to per-request CPU generation
                // instead of failing the whole collected batch.
                if (!GpuAccelerationConfig.settings().fallbackOnError()) {
                    Higherworld.LOGGER.error(
                            "Cannot rasterize vanilla terrain batch of {} cubes",
                            live.size(), throwable);
                    for (VanillaTerrainRequest request : live) fail(request, throwable);
                    return;
                }
                Higherworld.LOGGER.warn(
                        "Vanilla terrain GPU batch failed; falling back to CPU for {} cubes",
                        live.size(), throwable);
                for (VanillaTerrainRequest request : live) submitCpuFallback(request);
                return;
            }
            if (generated == null) {
                for (VanillaTerrainRequest request : live) submitCpuFallback(request);
                return;
            }

            for (VanillaTerrainRequest request : live) {
                if (!request.holder().isCurrent(request.epoch())) {
                    request.result().cancel(false);
                    continue;
                }
                CubeTerrainSnapshot snapshot = generated.get(request.holder().pos());
                if (snapshot == null) {
                    submitCpuFallback(request);
                } else {
                    request.result().complete(snapshot);
                }
            }
        }

        private void submitCpuFallback(VanillaTerrainRequest request) {
            submitVanillaCpuTerrain(
                    request.holder(), request.epoch(), request.request(), request.priority())
                    .whenComplete((snapshot, throwable) -> {
                        if (throwable != null) {
                            request.result().completeExceptionally(throwable);
                        } else if (snapshot != null) {
                            request.result().complete(snapshot);
                        } else {
                            request.result().cancel(false);
                        }
                    });
        }

        private void fail(VanillaTerrainRequest request, Throwable throwable) {
            request.result().completeExceptionally(throwable);
            request.holder().fail(request.epoch(), throwable);
        }

        @Override
        public void close() {
            synchronized (lifecycleLock) {
                if (closed) return;
                closed = true;
                pending.forEach(request -> request.result().cancel(false));
                pending.clear();
                if (worker != null) worker.interrupt();
                lifecycleLock.notifyAll();
            }
        }
    }

    /**
     * A tiny collector for custom terrain requests. The normal scheduler keeps
     * one lifecycle future per cube, but OpenCL is much faster when those
     * independent futures are submitted as one sample and one raster dispatch.
     * Requests are collected for at most a fraction of a tick, so a
     * single-cube world does not wait behind a large batch.
     */
    private final class CustomTerrainBatcher implements AutoCloseable {
        private static final int MAX_BATCH = 16;
        private static final long COLLECTION_WINDOW_NANOS = 750_000L;

        private final BlockingQueue<CustomTerrainRequest> pending =
                new LinkedBlockingQueue<>();
        private final Object lifecycleLock = new Object();
        private volatile boolean closed;
        private Thread worker;

        private CompletableFuture<CubeTerrainSnapshot> submit(
                CubeHolder holder, long epoch, long seed,
                CustomWorldSettings settings, int priority) {
            CompletableFuture<CubeTerrainSnapshot> result = new CompletableFuture<>();
            CustomTerrainRequest request = new CustomTerrainRequest(
                    holder, epoch, seed, settings, priority, result);
            synchronized (lifecycleLock) {
                if (closed) {
                    result.completeExceptionally(new IllegalStateException(
                            "Custom terrain batcher is closed"));
                    return result;
                }
                pending.offer(request);
                if (worker == null) {
                    worker = new Thread(this::run, "higherworld-custom-terrain-batcher");
                    worker.setDaemon(true);
                    worker.start();
                }
                lifecycleLock.notifyAll();
            }
            return result;
        }

        private void run() {
            while (!closed) {
                CustomTerrainRequest first;
                try {
                    first = pending.take();
                } catch (InterruptedException interrupted) {
                    if (closed) return;
                    continue;
                }
                List<CustomTerrainRequest> batch = new ArrayList<>(MAX_BATCH);
                batch.add(first);
                List<CustomTerrainRequest> deferred = new ArrayList<>();
                long deadline = System.nanoTime() + COLLECTION_WINDOW_NANOS;
                while (batch.size() < MAX_BATCH) {
                    CustomTerrainRequest next = pending.poll();
                    if (next == null) {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0L) break;
                        try {
                            next = pending.poll(remaining, TimeUnit.NANOSECONDS);
                        } catch (InterruptedException interrupted) {
                            if (closed) return;
                            continue;
                        }
                        if (next == null) break;
                    }
                    if (compatible(first, next)) batch.add(next);
                    else deferred.add(next);
                }
                deferred.forEach(pending::offer);
                process(batch);
            }
            CustomTerrainRequest request;
            while ((request = pending.poll()) != null) {
                request.result().cancel(false);
            }
        }

        private boolean compatible(CustomTerrainRequest first, CustomTerrainRequest next) {
            // Settings are immutable and one scheduler belongs to one world;
            // identity is cheaper than serializing or hashing the full preset.
            return first.seed() == next.seed() && first.settings() == next.settings();
        }

        private void process(List<CustomTerrainRequest> requests) {
            List<CustomTerrainRequest> live = new ArrayList<>(requests.size());
            for (CustomTerrainRequest request : requests) {
                if (request.holder().isCurrent(request.epoch())
                        && request.holder().target().isAtLeast(CubeStatus.TERRAIN)
                        && !request.result().isCancelled()) {
                    live.add(request);
                } else {
                    request.result().cancel(false);
                }
            }
            if (live.isEmpty()) return;

            CubePos[] positions = new CubePos[live.size()];
            boolean[][] solid = new boolean[live.size()][TerrainInterpolation.VOXEL_COUNT];
            for (int index = 0; index < live.size(); index++) {
                positions[index] = live.get(index).holder().pos();
            }

            boolean gpu;
            try {
                gpu = GpuTerrainAccelerator.trySampleAndRasterizeCustomBatch(
                        live.get(0).seed(), positions, live.get(0).settings(), solid);
            } catch (Throwable throwable) {
                // Same contract as the vanilla batcher: honor an explicitly
                // requested hard failure, otherwise fall back to CPU instead
                // of silently failing every request in the collected batch.
                if (!GpuAccelerationConfig.settings().fallbackOnError()) {
                    Higherworld.LOGGER.error(
                            "Cannot rasterize custom terrain batch of {} cubes",
                            live.size(), throwable);
                    for (CustomTerrainRequest request : live) fail(request, throwable);
                    return;
                }
                Higherworld.LOGGER.warn(
                        "Custom terrain GPU batch failed; falling back to CPU for {} cubes",
                        live.size(), throwable);
                for (CustomTerrainRequest request : live) submitCpuFallback(request);
                return;
            }
            if (gpu) {
                for (int index = 0; index < live.size(); index++) {
                    CustomTerrainRequest request = live.get(index);
                    if (!request.holder().isCurrent(request.epoch())) {
                        request.result().cancel(false);
                    } else {
                        request.result().complete(new CustomCubeGenerator.TerrainSnapshot(solid[index]));
                    }
                }
                return;
            }

            // Device probing can fail after the requests have been collected.
            // Return CPU work to the normal priority executor so the fallback
            // retains the scheduler's parallelism.
            for (CustomTerrainRequest request : live) submitCpuFallback(request);
        }

        private void submitCpuFallback(CustomTerrainRequest request) {
            try {
                generationExecutor.execute(new GenerationTask(
                        request.priority(), sequence.getAndIncrement(), () -> {
                            if (!request.holder().isCurrent(request.epoch())
                                    || !request.holder().target().isAtLeast(CubeStatus.TERRAIN)) {
                                request.result().cancel(false);
                                return;
                            }
                            try {
                                request.result().complete(CustomCubeGenerator.prepareTerrainCpu(
                                        request.seed(), request.holder().pos(), request.settings()));
                            } catch (Throwable throwable) {
                                fail(request, throwable);
                            }
                        }));
            } catch (RuntimeException exception) {
                fail(request, exception);
            }
        }

        private void fail(CustomTerrainRequest request, Throwable throwable) {
            request.result().completeExceptionally(throwable);
            request.holder().fail(request.epoch(), throwable);
        }

        @Override
        public void close() {
            synchronized (lifecycleLock) {
                if (closed) return;
                closed = true;
                pending.forEach(request -> request.result().cancel(false));
                pending.clear();
                if (worker != null) worker.interrupt();
                lifecycleLock.notifyAll();
            }
        }
    }

    private record CustomTerrainRequest(
            CubeHolder holder, long epoch, long seed, CustomWorldSettings settings,
            int priority, CompletableFuture<CubeTerrainSnapshot> result) {
    }

    private record VanillaTerrainRequest(
            CubeHolder holder, long epoch,
            VanillaCubeTerrainGenerator.TerrainRequest request,
            int priority, CompletableFuture<CubeTerrainSnapshot> result) {
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
