package org.devt.higherworld.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
    private static final int HORIZONTAL_RADIUS = 4;
    private static final int VERTICAL_RADIUS = 4;
    private static final int SENDS_PER_TICK = 32;
    private static final Map<UUID, WatchState> WATCHERS = new HashMap<>();

    private CubeWatchManager() {
    }

    public static void tick(ServerWorld world) {
        Set<UUID> present = new HashSet<>();
        for (ServerPlayerEntity player : world.getPlayers()) {
            present.add(player.getUuid());
            WatchState state = WATCHERS.computeIfAbsent(player.getUuid(), ignored -> new WatchState());
            update(player, state);
        }
        WATCHERS.entrySet().removeIf(entry -> !present.contains(entry.getKey())
                && entry.getValue().world == world);

        Set<CubePos> retained = new HashSet<>();
        for (WatchState state : WATCHERS.values()) {
            if (state.world == world) {
                retained.addAll(state.sent);
            }
        }
        // Cache eviction is maintenance, not simulation. Running the full cache
        // walk every server tick creates avoidable allocation and CPU pressure.
        if (world.getTime() % 20L == 0L) {
            CubicWorldManager.evictExcept(world, retained);
        }
        CubicWorldManager.tick(world);

        if (world.getTime() % 200L == 0L) {
            CubicWorldManager.flushDirty(world);
        }
    }

    public static void removeWorld(ServerWorld world) {
        WATCHERS.entrySet().removeIf(entry -> entry.getValue().world == world);
    }

    public static void broadcastBlockUpdate(ServerWorld world, BlockPos pos, BlockState state) {
        CubeBlockUpdatePayload payload = new CubeBlockUpdatePayload(pos, state);
        for (ServerPlayerEntity player : PlayerLookup.around(world, pos.toCenterPos(), 256.0)) {
            if (watches(player, CubePos.fromBlock(pos.getX(), pos.getY(), pos.getZ()))
                    && ServerPlayNetworking.canSend(player, CubeBlockUpdatePayload.ID)) {
                ServerPlayNetworking.send(player, payload);
            }
        }
    }

    public static void broadcastCubeUpdate(ServerWorld world, BlockPos blockPos) {
        CubePos pos = CubePos.fromBlock(blockPos.getX(), blockPos.getY(), blockPos.getZ());
        byte[] data = CubicWorldManager.cubePayload(world, pos);
        if (data.length == 0 || !CubeDataPayload.canEncode(data)) {
            return;
        }
        for (ServerPlayerEntity player : PlayerLookup.around(world, blockPos.toCenterPos(), 256.0)) {
            if (watches(player, pos) && ServerPlayNetworking.canSend(player, CubeDataPayload.ID)) {
                ServerPlayNetworking.send(player, new CubeDataPayload(pos, data));
            }
        }
    }

    private static void update(ServerPlayerEntity player, WatchState state) {
        ServerWorld world = player.getEntityWorld();
        CubePos center = CubePos.fromBlock(
                player.getBlockX(), player.getBlockY(), player.getBlockZ());
        if (state.world != world || !center.equals(state.center)) {
            rebuildQueue(player, state, world, center);
        }

        int processed = 0;
        while (processed < SENDS_PER_TICK && !state.pending.isEmpty()) {
            CubePos pos = state.pending.removeFirst();
            processed++;
            if (!withinView(pos, state.center) || !isOutsideVanillaHeight(world, pos)
                    || state.sent.contains(pos)) {
                continue;
            }
            if (!ServerPlayNetworking.canSend(player, CubeDataPayload.ID)) {
                // The play channel can become ready a few ticks after the watcher
                // is created. Keep the cube queued instead of losing it forever.
                state.pending.addFirst(pos);
                break;
            }
            byte[] payload = CubicWorldManager.cubePayload(world, pos);
            if (payload.length != 0 && CubeDataPayload.canEncode(payload)) {
                ServerPlayNetworking.send(player, new CubeDataPayload(pos, payload));
            }
            // Track empty positions too. They are implicit air and need no packet,
            // but remembering them prevents rechecking the overlapping 3D view
            // every time the player crosses a section boundary.
            state.sent.add(pos);
        }
    }

    private static void rebuildQueue(
            ServerPlayerEntity player, WatchState state, ServerWorld world, CubePos center) {
        if (state.world != null && state.world != world) {
            unloadAll(player, state);
        }
        state.world = world;
        state.center = center;

        Iterator<CubePos> iterator = state.sent.iterator();
        while (iterator.hasNext()) {
            CubePos pos = iterator.next();
            if (!withinView(pos, center)) {
                if (ServerPlayNetworking.canSend(player, CubeUnloadPayload.ID)) {
                    ServerPlayNetworking.send(player, new CubeUnloadPayload(pos));
                }
                iterator.remove();
            }
        }

        state.pending.clear();
        ArrayList<CubePos> pending = new ArrayList<>();
        for (int dy = -VERTICAL_RADIUS; dy <= VERTICAL_RADIUS; dy++) {
            for (int dz = -HORIZONTAL_RADIUS; dz <= HORIZONTAL_RADIUS; dz++) {
                for (int dx = -HORIZONTAL_RADIUS; dx <= HORIZONTAL_RADIUS; dx++) {
                    CubePos pos = new CubePos(center.x() + dx, center.y() + dy, center.z() + dz);
                    if (pos.isBlockRangeRepresentable() && isOutsideVanillaHeight(world, pos)
                            && !state.sent.contains(pos)) {
                        pending.add(pos);
                    }
                }
            }
        }
        pending.sort(Comparator.comparingInt(pos -> squaredDistance(pos, center)));
        state.pending.addAll(pending);
    }

    private static void unloadAll(ServerPlayerEntity player, WatchState state) {
        if (ServerPlayNetworking.canSend(player, CubeUnloadPayload.ID)) {
            for (CubePos pos : state.sent) {
                ServerPlayNetworking.send(player, new CubeUnloadPayload(pos));
            }
        }
        state.sent.clear();
        state.pending.clear();
    }

    private static boolean withinView(CubePos pos, CubePos center) {
        return Math.abs(pos.x() - center.x()) <= HORIZONTAL_RADIUS
                && Math.abs(pos.y() - center.y()) <= VERTICAL_RADIUS
                && Math.abs(pos.z() - center.z()) <= HORIZONTAL_RADIUS;
    }

    private static int squaredDistance(CubePos pos, CubePos center) {
        int dx = pos.x() - center.x();
        int dy = pos.y() - center.y();
        int dz = pos.z() - center.z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean watches(ServerPlayerEntity player, CubePos pos) {
        CubePos center = CubePos.fromBlock(player.getBlockX(), player.getBlockY(), player.getBlockZ());
        return withinView(pos, center);
    }

    private static boolean isOutsideVanillaHeight(ServerWorld world, CubePos pos) {
        return pos.y() < world.getBottomSectionCoord() || pos.y() >= world.getTopSectionCoord();
    }

    private static final class WatchState {
        private ServerWorld world;
        private CubePos center;
        private final Set<CubePos> sent = new HashSet<>();
        private final ArrayDeque<CubePos> pending = new ArrayDeque<>();
    }
}
