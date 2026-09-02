package org.devt.higherworld.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
    // Cube creation runs vanilla noise and biome decoration. A small world-wide
    // budget keeps initial view streaming from monopolizing the server thread.
    // Generation and decoding cross into Minecraft code and must stay on the
    // server thread. Share this budget across all players so additional players
    // cannot multiply the amount of synchronous cube work in a single tick.
    private static final int CUBE_WORK_PER_WORLD_TICK = 1;
    private static final int POLL_ATTEMPTS_PER_PLAYER_TICK = 2;
    private static final int READ_AHEAD_PER_WORLD_TICK = 2;
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
                watchers.remove();
                watcherTicketsChanged = true;
            }
        }

        if (watcherTicketsChanged) {
            Set<CubePos> requestedReads = new HashSet<>();
            for (WatchState state : WATCHERS.values()) {
                if (state.world == world) {
                    requestedReads.addAll(state.pending);
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
                    retained.addAll(state.pending);
                }
            }
            CubicWorldManager.evictExcept(world, retained);
        }
        CubicWorldManager.tick(world);
        CubicWorldManager.flushDirty(world);
        adaptive.record(System.nanoTime() - workStarted);
    }

    /** A second bounded completion drain reduces future latency within a tick. */
    public static void midTick(ServerWorld world) {
        AdaptiveBudget budget = BUDGETS.computeIfAbsent(world, ignored -> new AdaptiveBudget());
        CubicWorldManager.advanceReadyTasks(world, budget.commitNanos());
    }

    public static void removeWorld(ServerWorld world) {
        WATCHERS.entrySet().removeIf(entry -> {
            if (entry.getValue().world != world) return false;
            CubicWorldManager.removeTicket(world, entry.getKey());
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

    /** Sends authoritative light snapshots for every cube touched by propagation. */
    public static void broadcastCubeUpdates(ServerWorld world, Set<CubePos> positions) {
        if (CubicWorldManager.suppressingGenerationUpdates(world)) return;
        for (CubePos pos : positions) {
            List<ServerPlayerEntity> recipients = new ArrayList<>();
            for (ServerPlayerEntity player : world.getPlayers()) {
                if (watches(player, pos) && ServerPlayNetworking.canSend(player, CubeDataPayload.ID)) {
                    recipients.add(player);
                }
            }
            if (recipients.isEmpty()) continue;
            byte[] data = CubicWorldManager.cubePayload(world, pos);
            if (data.length == 0 || !CubeDataPayload.canEncode(data)) continue;
            CubeDataPayload payload = new CubeDataPayload(
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

        for (CubePos pos : state.pending) {
            if (!readAheadBudget.hasRemaining()) break;
            if (withinView(pos, state.center, state.horizontalRadius)
                    && isOutsideVanillaHeight(world, pos) && !state.sent.contains(pos)) {
                CubicWorldManager.prefetchCubePayload(world, pos, cubePriority(pos, state.center));
                readAheadBudget.consume();
            }
        }

        int pollAttempts = 0;
        while (workBudget.hasRemaining() && pollAttempts < POLL_ATTEMPTS_PER_PLAYER_TICK
                && !state.pending.isEmpty()) {
            CubePos pos = state.pending.removeFirst();
            pollAttempts++;
            if (!withinView(pos, state.center, state.horizontalRadius)
                    || !isOutsideVanillaHeight(world, pos)
                    || state.sent.contains(pos)) {
                continue;
            }
            if (!ServerPlayNetworking.canSend(player, CubeDataPayload.ID)) {
                // The play channel can become ready a few ticks after the watcher
                // is created. Keep the cube queued instead of losing it forever.
                state.pending.addFirst(pos);
                break;
            }
            byte[] payload = CubicWorldManager.tryCubePayload(
                    world, pos, cubePriority(pos, state.center));
            if (payload == null) {
                // Rotate pending reads so a slow region cannot head-of-line block
                // cubes whose IO has already completed.
                state.pending.addLast(pos);
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
        }
        return rebuilt;
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

        state.pending.clear();
        ArrayList<CubePos> pending = new ArrayList<>();
        for (int dy = -VERTICAL_RADIUS; dy <= VERTICAL_RADIUS; dy++) {
            for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
                    CubePos pos = new CubePos(center.x() + dx, center.y() + dy, center.z() + dz);
                    if (pos.isBlockRangeRepresentable() && isOutsideVanillaHeight(world, pos)
                            && !state.sent.contains(pos)) {
                        pending.add(pos);
                    }
                }
            }
        }
        pending.sort(Comparator.comparingInt(pos -> cubePriority(pos, center)));
        state.pending.addAll(pending);
    }

    private static void unloadAll(ServerPlayerEntity player, WatchState state) {
        if (ServerPlayNetworking.canSend(player, CubeUnloadPayload.ID)) {
            for (CubePos pos : state.sent) {
                ServerPlayNetworking.send(player, new CubeUnloadPayload(
                        pos, CubicWorldManager.cubeRevision(state.world, pos)));
            }
        }
        state.sent.clear();
        state.pending.clear();
    }

    private static void retainWorldPrefetches(ServerWorld world, WatchState excluded) {
        Set<CubePos> requestedReads = new HashSet<>();
        for (WatchState state : WATCHERS.values()) {
            if (state != excluded && state.world == world) {
                requestedReads.addAll(state.pending);
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

    private static final class WatchState {
        private ServerWorld world;
        private CubePos center;
        private int horizontalRadius;
        private final Set<CubePos> sent = new HashSet<>();
        private final ArrayDeque<CubePos> pending = new ArrayDeque<>();
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
    private static final class AdaptiveBudget {
        private static final long TARGET_NANOS = 2_000_000L;
        private double averageNanos = TARGET_NANOS;

        private int cubeAllowance() {
            double ratio = TARGET_NANOS / Math.max(250_000.0, averageNanos);
            return Math.max(1, Math.min(4, (int) Math.round(CUBE_WORK_PER_WORLD_TICK * ratio)));
        }

        private long commitNanos() {
            return Math.max(250_000L, Math.min(TARGET_NANOS, (long) (TARGET_NANOS *
                    TARGET_NANOS / Math.max(TARGET_NANOS, averageNanos))));
        }

        private void record(long elapsedNanos) {
            averageNanos = averageNanos * 0.8 + elapsedNanos * 0.2;
        }
    }
}
