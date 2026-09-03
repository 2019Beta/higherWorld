package org.devt.higherworld.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.devt.higherworld.storage.CubePos;

/** Maintains a bounded three-dimensional cube view around every player. */
public final class CubeWatchManager {
    private static final int VERTICAL_RADIUS = 4;
    // Cube creation still commits palettes, features and light on the server
    // thread. A small world-wide budget keeps initial view streaming from
    // monopolizing that thread; pure vanilla terrain sampling is dispatched by
    // CubeTaskScheduler. Share this budget across all players so additional
    // players cannot multiply synchronous cube work in a single tick.
    private static final int CUBE_WORK_PER_WORLD_TICK = 4;
    private static final int POLL_ATTEMPTS_PER_PLAYER_TICK = 32;
    private static final int READ_AHEAD_PER_WORLD_TICK = 8;
    private static final long PREFETCH_RETRY_DELAY_TICKS = 2L;
    private static final long SEND_RETRY_DELAY_TICKS = 1L;
    private static final Map<UUID, WatchState> WATCHERS = new HashMap<>();
    private static final Map<ServerWorld, AdaptiveBudget> BUDGETS = new HashMap<>();

    private CubeWatchManager() {
    }

    public static void tick(ServerWorld world) {
        long workStarted = System.nanoTime();
        AdaptiveBudget adaptive = BUDGETS.computeIfAbsent(world, ignored -> new AdaptiveBudget());
        Set<UUID> present = new HashSet<>();
        boolean watcherTicketsChanged = false;
        CubeWorkBudget workBudget = new CubeWorkBudget(adaptive.cubeAllowance());
        CubeWorkBudget readAheadBudget = new CubeWorkBudget(READ_AHEAD_PER_WORLD_TICK);
        List<ServerPlayerEntity> players = world.getPlayers();
        int playerCount = players.size();
        int firstPlayer = playerCount == 0 ? 0 : Math.floorMod(world.getTime(), playerCount);
        for (int index = 0; index < playerCount; index++) {
            ServerPlayerEntity player = players.get((firstPlayer + index) % playerCount);
            present.add(player.getUuid());
            WatchState state = WATCHERS.computeIfAbsent(player.getUuid(), ignored -> new WatchState());
            watcherTicketsChanged |= update(player, state, workBudget, readAheadBudget);
        }
        Iterator<Map.Entry<UUID, WatchState>> watchers = WATCHERS.entrySet().iterator();
        while (watchers.hasNext()) {
            Map.Entry<UUID, WatchState> entry = watchers.next();
            if (!present.contains(entry.getKey()) && entry.getValue().world == world) {
                CubicWorldManager.removeTicket(world, entry.getKey());
                entry.getValue().invalidate();
                watchers.remove();
                watcherTicketsChanged = true;
            }
        }

        if (watcherTicketsChanged) {
            Set<CubePos> requestedReads = new HashSet<>();
            for (WatchState state : WATCHERS.values()) {
                if (state.world == world) {
                    requestedReads.addAll(state.activeUnsentPositions());
                }
            }
            CubicWorldManager.retainPrefetches(world, requestedReads);
        }

        // Cache eviction is maintenance, not simulation. Running the full cache
        // walk every server tick creates avoidable allocation and CPU pressure.
        if (world.getTime() % 5L == 0L) {
            Set<CubePos> retained = new HashSet<>();
            for (WatchState state : WATCHERS.values()) {
                if (state.world == world) {
                    retained.addAll(state.sent);
                    retained.addAll(state.activeUnsentPositions());
                }
            }
            CubicWorldManager.evictExcept(world, retained);
        }
        // Feed send throttling with watcher work, not simulation/save time.
        // Including the rest of the world tick made one unrelated slow block
        // entity collapse streaming back to a single cube per tick.
        adaptive.record(System.nanoTime() - workStarted);
        CubicWorldManager.tick(world);
        CubicWorldManager.flushDirty(world);
    }

    /** Drains completed lifecycle work once per world tick on the server thread. */
    public static void midTick(ServerWorld world) {
        AdaptiveBudget budget = BUDGETS.computeIfAbsent(world, ignored -> new AdaptiveBudget());
        long allowance = budget.claimCommitNanos();
        if (allowance <= 0L) return;

        long started = System.nanoTime();
        CubicWorldManager.advanceReadyTasks(world, allowance);
        budget.recordCommit(System.nanoTime() - started);
    }

    public static void removeWorld(ServerWorld world) {
        WATCHERS.entrySet().removeIf(entry -> {
            if (entry.getValue().world != world) return false;
            CubicWorldManager.removeTicket(world, entry.getKey());
            entry.getValue().invalidate();
            return true;
        });
        BUDGETS.remove(world);
    }

    public static void broadcastBlockUpdate(ServerWorld world, BlockPos pos, BlockState state) {
        if (CubicWorldManager.suppressingGenerationUpdates(world)) {
            return;
        }
        CubePos cubePos = CubePos.fromBlock(pos.getX(), pos.getY(), pos.getZ());
        CubeBlockUpdatePayload payload = new CubeBlockUpdatePayload(
                pos, state, CubicWorldManager.cubeRevision(world, cubePos));
        for (ServerPlayerEntity player : PlayerLookup.around(world, pos.toCenterPos(), 256.0)) {
            if (watches(player, cubePos)
                    && ServerPlayNetworking.canSend(player, CubeBlockUpdatePayload.ID)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    /**
     * Loading follows the client's view distance, but simulation must follow
     * the server's (usually smaller) simulation distance.  Ticking every FULL
     * cube in the streaming view makes block entities such as sculk catalysts
     * run across thousands of otherwise inactive sections.
     */
    static boolean shouldTick(ServerWorld world, CubePos pos) {
        int simulationDistance = world.getServer().getPlayerManager().getSimulationDistance();
        for (ServerPlayerEntity player : world.getPlayers()) {
            CubePos center = CubePos.fromBlock(
                    player.getBlockX(), player.getBlockY(), player.getBlockZ());
            if (withinSimulationDistance(pos, center, simulationDistance)) return true;
        }
        return false;
    }

    static boolean withinSimulationDistance(CubePos pos, CubePos center, int distance) {
        return Math.abs((long) pos.x() - center.x()) <= distance
                && Math.abs((long) pos.z() - center.z()) <= distance
                && Math.abs((long) pos.y() - center.y()) <= VERTICAL_RADIUS;
    }

    public static void broadcastCubeUpdate(ServerWorld world, BlockPos blockPos) {
        CubePos pos = CubePos.fromBlock(blockPos.getX(), blockPos.getY(), blockPos.getZ());
        if (CubicWorldManager.suppressingGenerationUpdates(world)) {
            return;
        }
        List<ServerPlayerEntity> recipients = new ArrayList<>();
        for (ServerPlayerEntity player : PlayerLookup.around(world, blockPos.toCenterPos(), 256.0)) {
            if (watches(player, pos) && ServerPlayNetworking.canSend(player, CubeDataPayload.ID)) {
                recipients.add(player);
            }
        }
        // Do not synchronously load/generate/encode a cube unless at least one
        // connected watcher can actually receive its payload.
        if (recipients.isEmpty()) return;
        byte[] data = CubicWorldManager.cubePayload(world, pos);
        if (data.length == 0 || !CubeDataPayload.canEncode(data)) return;
        CubeDataPayload payload = new CubeDataPayload(
                pos, CubicWorldManager.cubeRevision(world, pos), data);
        for (ServerPlayerEntity player : recipients) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    /** Sends authoritative light-only snapshots for every cube touched by propagation. */
    public static void broadcastLightUpdates(ServerWorld world, Set<CubePos> positions) {
        if (CubicWorldManager.suppressingGenerationUpdates(world)) return;
        for (CubePos pos : positions) {
            List<ServerPlayerEntity> recipients = new ArrayList<>();
            for (ServerPlayerEntity player : world.getPlayers()) {
                if (watches(player, pos)
                        && ServerPlayNetworking.canSend(player, CubeLightUpdatePayload.ID)) {
                    recipients.add(player);
                }
            }
            if (recipients.isEmpty()) continue;
            byte[] data = CubicWorldManager.cubeLightPayload(world, pos);
            if (data.length == 0) continue;
            CubeLightUpdatePayload payload = new CubeLightUpdatePayload(
                    pos, CubicWorldManager.cubeRevision(world, pos), data);
            for (ServerPlayerEntity player : recipients) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    private static boolean update(
            ServerPlayerEntity player, WatchState state, CubeWorkBudget workBudget,
            CubeWorkBudget readAheadBudget) {
        ServerWorld world = player.getEntityWorld();
        CubePos center = CubePos.fromBlock(
                player.getBlockX(), player.getBlockY(), player.getBlockZ());
        int horizontalRadius = player.getViewDistance();
        boolean rebuilt = false;
        if (state.world != world || !center.equals(state.center)
                || state.horizontalRadius != horizontalRadius) {
            rebuildQueue(player, state, world, center, horizontalRadius);
            rebuilt = true;
        }

        state.promoteDueRetries(world.getTime());

        // Starting a read is intentionally separate from sending its result.
        // The read-ahead budget is shared by every watcher in this world.
        while (readAheadBudget.hasRemaining()) {
            Watch watch = state.pollPendingStart();
            if (watch == null) break;

            long version = watch.version + 1L;
            long generation = state.generation;
            watch.phase = WatchPhase.IN_FLIGHT;
            watch.version = version;
            readAheadBudget.consume();
            try {
                CompletableFuture<Void> ready = CubicWorldManager.prefetchCubePayload(
                        world, watch.pos, watch.rank);
                ready.whenComplete((ignored, failure) -> dispatchPrefetchCompletion(
                        world, player.getUuid(), state, watch, generation, version, failure));
            } catch (RuntimeException exception) {
                // A synchronous request failure is already on the server thread,
                // so it can enter the same due-retry path directly.
                state.scheduleRetry(watch, world.getTime(), RetryTarget.START,
                        PREFETCH_RETRY_DELAY_TICKS);
            }
        }

        int pollAttempts = 0;
        while (workBudget.hasRemaining() && pollAttempts < POLL_ATTEMPTS_PER_PLAYER_TICK) {
            Watch watch = state.pollReadySend();
            if (watch == null) break;
            pollAttempts++;

            CubePos pos = watch.pos;
            if (!withinView(pos, state.center, state.horizontalRadius)
                    || !isOutsideVanillaHeight(world, pos)
                    || state.sent.contains(pos)
                    || state.watches.get(pos) != watch) {
                state.finish(watch);
                continue;
            }
            if (!ServerPlayNetworking.canSend(player, CubeDataPayload.ID)) {
                // The play channel can become ready a few ticks after the watcher
                // is created. Keep the cube queued instead of losing it forever.
                state.scheduleRetry(watch, world.getTime(), RetryTarget.SEND,
                        SEND_RETRY_DELAY_TICKS);
                break;
            }
            byte[] payload = CubicWorldManager.tryCubePayload(
                    world, pos, watch.rank);
            if (payload == null) {
                // A FULL future normally makes this impossible, but generation
                // dependencies and a lifecycle restart can still leave the
                // payload temporarily unavailable. Defer without hot polling.
                state.scheduleRetry(watch, world.getTime(), RetryTarget.SEND,
                        SEND_RETRY_DELAY_TICKS);
                continue;
            }
            workBudget.consume();
            if (payload.length != 0 && CubeDataPayload.canEncode(payload)) {
                ServerPlayNetworking.send(player, new CubeDataPayload(
                        pos, CubicWorldManager.cubeRevision(world, pos), payload));
            }
            // Track empty positions too. They are implicit air and need no packet,
            // but remembering them prevents rechecking the overlapping 3D view
            // every time the player crosses a section boundary.
            state.sent.add(pos);
            state.finish(watch);
            // Render PAYLOAD immediately, then expand the more expensive
            // lighting/FULL graph after the first packet has left the server.
            try {
                CubicWorldManager.prefetchCubeFull(world, pos, watch.rank);
            } catch (RuntimeException ignored) {
                // A later simulation/entity ticket can retry FULL; the visible
                // payload has already been delivered successfully.
            }
        }
        return rebuilt;
    }

    /**
     * A completion callback may run on an IO or generation worker. It must only
     * enqueue a server-thread action; no watcher state is touched here.
     */
    private static void dispatchPrefetchCompletion(
            ServerWorld world, UUID playerId, WatchState state, Watch watch,
            long generation, long version, Throwable failure) {
        try {
            world.getServer().execute(() -> onPrefetchCompletion(
                    world, playerId, state, watch, generation, version, failure));
        } catch (RejectedExecutionException | IllegalStateException ignored) {
            // The server is stopping. The captured state will be discarded by
            // world unload, and there is no queue work left to recover.
        }
    }

    private static void onPrefetchCompletion(
            ServerWorld world, UUID playerId, WatchState state, Watch watch,
            long generation, long version, Throwable failure) {
        // UUID lookup plus object identity prevents a late completion from
        // reviving a replacement watcher after logout or a world switch.
        if (WATCHERS.get(playerId) != state || state.world != world
                || state.generation != generation
                || state.watches.get(watch.pos) != watch
                || watch.phase != WatchPhase.IN_FLIGHT
                || watch.version != version) {
            return;
        }
        if (failure == null) {
            state.markReady(watch);
        } else if (isCancellation(failure)) {
            state.scheduleRetry(watch, world.getTime(), RetryTarget.START,
                    PREFETCH_RETRY_DELAY_TICKS);
        } else {
            // CubeHolder failures are terminal for their lifecycle epoch. A
            // blind retry would keep attaching to the same exceptional future
            // forever, so preserve the old error-as-empty behavior and retire
            // this watch until the player view is rebuilt.
            state.sent.add(watch.pos);
            state.finish(watch);
        }
    }

    private static boolean isCancellation(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof java.util.concurrent.CancellationException) return true;
            current = current.getCause();
        }
        return false;
    }

    private static void rebuildQueue(
            ServerPlayerEntity player, WatchState state, ServerWorld world, CubePos center,
            int horizontalRadius) {
        if (state.world != null && state.world != world) {
            ServerWorld previousWorld = state.world;
            CubicWorldManager.removeTicket(previousWorld, player.getUuid());
            unloadAll(player, state);
            retainWorldPrefetches(previousWorld, state);
        }
        state.world = world;
        state.center = center;
        state.horizontalRadius = horizontalRadius;
        if (center.y() - VERTICAL_RADIUS < world.getBottomSectionCoord()
                || center.y() + VERTICAL_RADIUS >= world.getTopSectionCoord()) {
            CubicWorldManager.replaceTicket(world,
                    CubeTicket.player(player.getUuid(), center, horizontalRadius, VERTICAL_RADIUS));
        } else {
            CubicWorldManager.removeTicket(world, player.getUuid());
        }

        Iterator<CubePos> iterator = state.sent.iterator();
        while (iterator.hasNext()) {
            CubePos pos = iterator.next();
            if (!withinView(pos, center, horizontalRadius)) {
                if (ServerPlayNetworking.canSend(player, CubeUnloadPayload.ID)) {
                    ServerPlayNetworking.send(player, new CubeUnloadPayload(
                            pos, CubicWorldManager.cubeRevision(world, pos)));
                }
                iterator.remove();
            }
        }

        Set<CubePos> desired = new HashSet<>();
        for (int dy = -VERTICAL_RADIUS; dy <= VERTICAL_RADIUS; dy++) {
            for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
                    CubePos pos = new CubePos(center.x() + dx, center.y() + dy, center.z() + dz);
                    if (pos.isBlockRangeRepresentable() && isOutsideVanillaHeight(world, pos)
                            && !state.sent.contains(pos)) {
                        desired.add(pos);
                    }
                }
            }
        }
        // Retain a watch whose IO is already in flight, but invalidate every
        // watch which left the new view. Newly entered positions get a fresh
        // identity, so stale queue entries/callbacks cannot target them.
        Iterator<Map.Entry<CubePos, Watch>> watches = state.watches.entrySet().iterator();
        while (watches.hasNext()) {
            Map.Entry<CubePos, Watch> entry = watches.next();
            if (!desired.contains(entry.getKey())) {
                entry.getValue().invalidate();
                watches.remove();
            }
        }
        for (CubePos pos : desired) {
            if (!state.watches.containsKey(pos)) {
                state.watches.put(pos, new Watch(pos, cubePriority(pos, center)));
            }
        }
        state.rebuildQueues();
    }

    private static void unloadAll(ServerPlayerEntity player, WatchState state) {
        if (ServerPlayNetworking.canSend(player, CubeUnloadPayload.ID)) {
            for (CubePos pos : state.sent) {
                ServerPlayNetworking.send(player, new CubeUnloadPayload(
                        pos, CubicWorldManager.cubeRevision(state.world, pos)));
            }
        }
        state.invalidate();
        state.sent.clear();
    }

    private static void retainWorldPrefetches(ServerWorld world, WatchState excluded) {
        Set<CubePos> requestedReads = new HashSet<>();
        for (WatchState state : WATCHERS.values()) {
            if (state != excluded && state.world == world) {
                requestedReads.addAll(state.activeUnsentPositions());
            }
        }
        CubicWorldManager.retainPrefetches(world, requestedReads);
    }

    private static boolean withinView(CubePos pos, CubePos center, int horizontalRadius) {
        return coordinateDistance(pos.x(), center.x()) <= horizontalRadius
                && coordinateDistance(pos.y(), center.y()) <= VERTICAL_RADIUS
                && coordinateDistance(pos.z(), center.z()) <= horizontalRadius;
    }

    private static int cubePriority(CubePos pos, CubePos center) {
        long dx = coordinateDistance(pos.x(), center.x());
        long dy = coordinateDistance(pos.y(), center.y());
        long dz = coordinateDistance(pos.z(), center.z());
        long horizontalShell = Math.max(dx, dz);
        // Anisotropic priority matches the 3D ticket shape: horizontal view
        // distance is large, while vertical demand is a narrow fixed column.
        long priority = horizontalShell * horizontalShell * (VERTICAL_RADIUS + 1L) + dy * dy;
        return (int) Math.min(Integer.MAX_VALUE, priority);
    }

    private static long coordinateDistance(int first, int second) {
        return Math.abs((long) first - second);
    }

    private static boolean watches(ServerPlayerEntity player, CubePos pos) {
        CubePos center = CubePos.fromBlock(player.getBlockX(), player.getBlockY(), player.getBlockZ());
        return withinView(pos, center, player.getViewDistance());
    }

    private static boolean isOutsideVanillaHeight(ServerWorld world, CubePos pos) {
        return pos.y() < world.getBottomSectionCoord() || pos.y() >= world.getTopSectionCoord();
    }

    enum WatchPhase {
        PENDING_START,
        IN_FLIGHT,
        READY_SEND,
        RETRY_WAIT,
        FINISHED
    }

    enum RetryTarget {
        START,
        SEND
    }

    static final class Watch {
        final CubePos pos;
        int rank;
        WatchPhase phase = WatchPhase.PENDING_START;
        RetryTarget retryTarget;
        long dueTick;
        long version;

        Watch(CubePos pos, int rank) {
            this.pos = pos;
            this.rank = rank;
        }

        private void invalidate() {
            phase = WatchPhase.FINISHED;
            retryTarget = null;
            version++;
        }
    }

    record QueueEntry(Watch watch, long version, int rank, long sequence) {
    }

    record RetryEntry(Watch watch, long version, RetryTarget target,
                              long dueTick, int rank, long sequence) {
    }

    static final Comparator<QueueEntry> NEAR_TO_FAR = (first, second) -> {
        int byRank = Integer.compare(first.rank(), second.rank());
        if (byRank != 0) return byRank;
        int byPosition = comparePosition(first.watch().pos, second.watch().pos);
        return byPosition != 0
                ? byPosition : Long.compare(first.sequence(), second.sequence());
    };

    static final Comparator<RetryEntry> DUE_RETRY_ORDER = (first, second) -> {
        int byDue = Long.compare(first.dueTick(), second.dueTick());
        if (byDue != 0) return byDue;
        int byRank = Integer.compare(first.rank(), second.rank());
        if (byRank != 0) return byRank;
        int byPosition = comparePosition(first.watch().pos, second.watch().pos);
        return byPosition != 0
                ? byPosition : Long.compare(first.sequence(), second.sequence());
    };

    private static int comparePosition(CubePos first, CubePos second) {
        int byY = Integer.compare(first.y(), second.y());
        if (byY != 0) return byY;
        int byZ = Integer.compare(first.z(), second.z());
        return byZ != 0 ? byZ : Integer.compare(first.x(), second.x());
    }

    static final class WatchState {
        private ServerWorld world;
        private CubePos center;
        private int horizontalRadius;
        long generation;
        private long sequence;
        private final Set<CubePos> sent = new HashSet<>();
        private final Map<CubePos, Watch> watches = new HashMap<>();
        private final PriorityQueue<QueueEntry> pendingStarts =
                new PriorityQueue<>(NEAR_TO_FAR);
        private final PriorityQueue<QueueEntry> readySends =
                new PriorityQueue<>(NEAR_TO_FAR);
        private final PriorityQueue<RetryEntry> retries =
                new PriorityQueue<>(DUE_RETRY_ORDER);

        /** Package-visible hooks keep the queue state independently testable. */
        Watch addForTest(CubePos pos, int rank) {
            Watch watch = new Watch(pos, rank);
            watches.put(pos, watch);
            return watch;
        }

        void setCenterForTest(CubePos center) {
            this.center = center;
            rebuildQueues();
        }

        int pendingStartCountForTest() {
            return pendingStarts.size();
        }

        int readySendCountForTest() {
            return readySends.size();
        }

        int retryCountForTest() {
            return retries.size();
        }

        private Set<CubePos> activeUnsentPositions() {
            return Set.copyOf(watches.keySet());
        }

        void rebuildQueues() {
            pendingStarts.clear();
            readySends.clear();
            retries.clear();
            for (Watch watch : watches.values()) {
                watch.rank = cubePriority(watch.pos, center);
                switch (watch.phase) {
                    case PENDING_START -> enqueuePendingStart(watch);
                    case READY_SEND -> enqueueReadySend(watch);
                    case RETRY_WAIT -> enqueueRetry(watch);
                    case IN_FLIGHT, FINISHED -> { }
                }
            }
        }

        Watch pollPendingStart() {
            while (!pendingStarts.isEmpty()) {
                QueueEntry entry = pendingStarts.poll();
                Watch watch = entry.watch();
                if (entry.version() == watch.version && watch.phase == WatchPhase.PENDING_START
                        && watches.get(watch.pos) == watch) {
                    return watch;
                }
            }
            return null;
        }

        Watch pollReadySend() {
            while (!readySends.isEmpty()) {
                QueueEntry entry = readySends.poll();
                Watch watch = entry.watch();
                if (entry.version() == watch.version && watch.phase == WatchPhase.READY_SEND
                        && watches.get(watch.pos) == watch) {
                    return watch;
                }
            }
            return null;
        }

        void promoteDueRetries(long now) {
            while (!retries.isEmpty() && retries.peek().dueTick() <= now) {
                RetryEntry entry = retries.poll();
                Watch watch = entry.watch();
                if (entry.version() != watch.version || entry.target() != watch.retryTarget
                        || watch.phase != WatchPhase.RETRY_WAIT
                        || watches.get(watch.pos) != watch) {
                    continue;
                }
                watch.retryTarget = null;
                watch.version++;
                if (entry.target() == RetryTarget.START) {
                    watch.phase = WatchPhase.PENDING_START;
                    enqueuePendingStart(watch);
                } else {
                    watch.phase = WatchPhase.READY_SEND;
                    enqueueReadySend(watch);
                }
            }
        }

        void markReady(Watch watch) {
            if (watch.phase != WatchPhase.IN_FLIGHT || watches.get(watch.pos) != watch) return;
            watch.phase = WatchPhase.READY_SEND;
            watch.version++;
            enqueueReadySend(watch);
        }

        void scheduleRetry(
                Watch watch, long now, RetryTarget target, long delayTicks) {
            if (watches.get(watch.pos) != watch || watch.phase == WatchPhase.FINISHED) return;
            watch.phase = WatchPhase.RETRY_WAIT;
            watch.retryTarget = target;
            watch.dueTick = now + Math.max(1L, delayTicks);
            watch.version++;
            enqueueRetry(watch);
        }

        void finish(Watch watch) {
            watch.invalidate();
            watches.remove(watch.pos, watch);
        }

        private void invalidate() {
            generation++;
            watches.values().forEach(Watch::invalidate);
            watches.clear();
            pendingStarts.clear();
            readySends.clear();
            retries.clear();
        }

        private void enqueuePendingStart(Watch watch) {
            pendingStarts.offer(new QueueEntry(watch, watch.version, watch.rank, sequence++));
        }

        private void enqueueReadySend(Watch watch) {
            readySends.offer(new QueueEntry(watch, watch.version, watch.rank, sequence++));
        }

        private void enqueueRetry(Watch watch) {
            retries.offer(new RetryEntry(
                    watch, watch.version, watch.retryTarget, watch.dueTick,
                    watch.rank, sequence++));
        }
    }

    private static final class CubeWorkBudget {
        private int remaining;

        private CubeWorkBudget(int remaining) {
            this.remaining = remaining;
        }

        private boolean hasRemaining() {
            return remaining > 0;
        }

        private void consume() {
            remaining--;
        }
    }

    /** Keeps cube work near a small tick slice using an EWMA of actual cost. */
    static final class AdaptiveBudget {
        private static final long TARGET_NANOS = 6_000_000L;
        private static final long MAX_DEBT_NANOS = 300_000_000L;
        private double averageNanos = TARGET_NANOS;
        private long debtNanos;

        int cubeAllowance() {
            if (debtNanos > 0L) return 0;
            double ratio = TARGET_NANOS / Math.max(250_000.0, averageNanos);
            return Math.max(1, Math.min(32, (int) Math.round(CUBE_WORK_PER_WORLD_TICK * ratio)));
        }

        long claimCommitNanos() {
            if (debtNanos > 0L) {
                debtNanos = Math.max(0L, debtNanos - TARGET_NANOS);
                return 0L;
            }
            return Math.max(500_000L, Math.min(TARGET_NANOS, (long) (TARGET_NANOS *
                    TARGET_NANOS / Math.max(TARGET_NANOS, averageNanos))));
        }

        void recordCommit(long elapsedNanos) {
            record(elapsedNanos);
            if (elapsedNanos > TARGET_NANOS) {
                debtNanos = Math.min(MAX_DEBT_NANOS,
                        debtNanos + elapsedNanos - TARGET_NANOS);
            }
        }

        void record(long elapsedNanos) {
            averageNanos = averageNanos * 0.8 + elapsedNanos * 0.2;
        }

        long debtNanos() {
            return debtNanos;
        }
    }
}
