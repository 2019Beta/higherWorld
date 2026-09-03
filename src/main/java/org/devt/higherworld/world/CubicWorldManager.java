package org.devt.higherworld.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.Set;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.entity.Entity;
import net.minecraft.entity.Entity.RemovalReason;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.WorldSavePath;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.LightType;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import org.devt.higherworld.Higherworld;
import org.devt.higherworld.storage.CubePos;
import org.devt.higherworld.storage.CubeEntityStorage;
import org.devt.higherworld.storage.CubeStorage;

/** Owns sparse cube state outside the vanilla dimension height range. */
public final class CubicWorldManager {
    private static final Map<ServerWorld, CubicWorldState> WORLDS = new ConcurrentHashMap<>();
    private static final Map<ServerWorld, CubeEntityRuntime> ENTITIES = new ConcurrentHashMap<>();
    /**
     * The scheduler intentionally keeps ticket ownership private.  This
     * small mirror is only for the entity restore boundary: a durable entity
     * may be materialized after a successful FULL future, but only while an
     * active FULL ticket still covers its owner cube.
     */
    private static final Map<ServerWorld, Map<Object, CubeTicket>> ENTITY_TICKETS =
            new ConcurrentHashMap<>();
    private static final Map<ServerWorld, Set<CubePos>> ENTITY_RESTORE_FUTURES =
            new ConcurrentHashMap<>();

    private CubicWorldManager() {
    }

    public static void open(MinecraftServer server, ServerWorld world) {
        Path cubicRoot = server.getSavePath(WorldSavePath.ROOT).resolve("hw_chunks");
        Path root = cubicRoot
                .resolve(world.getRegistryKey().getValue().getNamespace())
                .resolve(world.getRegistryKey().getValue().getPath())
                .resolve("region3d");
        CubicWorldState created = null;
        CubeEntityRuntime entityRuntime = null;
        boolean published = false;
        try {
            boolean infiniteDownward = generatesInfinitelyDownward(world);
            if (infiniteDownward) {
                configureInfiniteGenerator(world);
            }
            StructureGenerationSettings structureSettings = infiniteDownward
                    ? StructureGenerationSettings.load(cubicRoot, true)
                    : StructureGenerationSettings.defaults();
            CustomWorldSettings customWorldSettings = generatesCustomWorld(world)
                    ? CustomWorldSettings.load(cubicRoot, true)
                    : CustomWorldSettings.defaults();
            boolean generateStructures = server.getSaveProperties()
                    .getGeneratorOptions().shouldGenerateStructures();
            created = new CubicWorldState(
                    world, new CubeStorage(root), root, generateStructures, structureSettings,
                    customWorldSettings);
            created.openSimulationServices();
            entityRuntime = new CubeEntityRuntime(world,
                    new CubeEntityStorage(root.getParent().resolve("entities3d")));
            entityRuntime.load();
            // Publish only after both authoritative stores have opened.  A
            // corrupt HWE1 file must not leave a half-open cube state visible
            // to ticks or mixins.
            CubicWorldState previous = WORLDS.put(world, created);
            CubeEntityRuntime previousEntities = ENTITIES.put(world, entityRuntime);
            published = true;
            if (previousEntities != null) {
                previousEntities.close();
            }
            if (previous != null) {
                previous.close();
            }
            scheduleEntityRestores(world);
            Higherworld.LOGGER.info("Opened cubic storage for {} at {}", world.getRegistryKey().getValue(), root);
        } catch (IOException | RuntimeException exception) {
            if (!published) {
                if (created != null) WORLDS.remove(world, created);
                if (entityRuntime != null) ENTITIES.remove(world, entityRuntime);
                if (entityRuntime != null) {
                    try {
                        entityRuntime.close();
                    } catch (IOException closeException) {
                        exception.addSuppressed(closeException);
                    }
                }
                if (created != null) {
                    try {
                        created.close();
                    } catch (IOException closeException) {
                        exception.addSuppressed(closeException);
                    }
                }
            }
            Higherworld.LOGGER.error("Cannot open cubic storage for {}", world.getRegistryKey().getValue(), exception);
        }
    }

    public static void close(MinecraftServer server, ServerWorld world) {
        CubeWatchManager.removeWorld(world);
        InfiniteDownwardGenerator.release(world);
        CubeEntityRuntime entities = ENTITIES.remove(world);
        if (entities != null) {
            try {
                entities.close();
            } catch (IOException exception) {
                Higherworld.LOGGER.error("Cannot close cubic entity storage for {}",
                        world.getRegistryKey().getValue(), exception);
            }
        }
        ENTITY_TICKETS.remove(world);
        ENTITY_RESTORE_FUTURES.remove(world);
        CubicWorldState state = WORLDS.remove(world);
        if (state == null) {
            return;
        }
        try {
            state.close();
        } catch (IOException exception) {
            Higherworld.LOGGER.error("Cannot close cubic storage for {}", world.getRegistryKey().getValue(), exception);
        }
    }

    public static boolean isCubic(ServerWorld world) {
        return WORLDS.containsKey(world);
    }

    /** Returns the world's version-neutral POI/path/spawn service, if open. */
    public static CubeSimulationServices simulationServices(ServerWorld world) {
        CubicWorldState state = WORLDS.get(world);
        return state == null ? null : state.simulationServices();
    }

    /** Returns a read-only path view; it never synchronously loads a cube. */
    public static CubePathfindingAccess cubePathfindingAccess(ServerWorld world) {
        CubicWorldState state = WORLDS.get(world);
        return state == null ? null : state.cubePathfindingAccess();
    }

    /** Returns the bounded natural-spawn batch for the world's current windows. */
    public static List<CubeSpawnPolicy.Candidate> spawnCandidates(
            ServerWorld world, long worldSeed, long gameTime, int attemptsPerCube) {
        CubicWorldState state = WORLDS.get(world);
        return state == null
                ? List.of()
                : state.simulationServices().spawnCandidates(worldSeed, gameTime, attemptsPerCube);
    }

    /** Acquires an ENTITY ticket only when the cube is FULL and simulating. */
    public static Optional<CubeSpawnPolicy.Ticket> acquireEntityTicket(
            ServerWorld world, Object owner, CubePos cube) {
        CubicWorldState state = WORLDS.get(world);
        return state == null
                ? Optional.empty()
                : state.simulationServices().acquireEntityTicket(owner, cube);
    }

    public static void releaseEntityTicket(ServerWorld world, Object owner) {
        CubicWorldState state = WORLDS.get(world);
        if (state != null) state.simulationServices().releaseEntityTicket(owner);
    }

    /** True when an ordinary non-player entity belongs to the sparse runtime. */
    public static boolean shouldManageEntity(ServerWorld world, Entity entity) {
        // Do not cancel vanilla spawn unless the entity runtime is present and
        // loaded.  This makes ownership explicit during open/close races:
        // either the cube runtime admits the entity, or vanilla remains the
        // owner; there is no half-registered entity in either index.
        return WORLDS.containsKey(world) && ENTITIES.containsKey(world)
                && CubeEntityRuntime.isManaged(world, entity);
    }

    /** Adds an outside-height entity to the sparse owner index. */
    public static boolean addEntity(ServerWorld world, Entity entity) {
        CubeEntityRuntime runtime = ENTITIES.get(world);
        return runtime != null && runtime.add(entity);
    }

    /** Forwards movement callbacks without exposing the runtime map. */
    public static void entityPositionChanged(Entity entity) {
        if (entity.getEntityWorld() instanceof ServerWorld world) {
            CubeEntityRuntime runtime = ENTITIES.get(world);
            if (runtime != null) runtime.onEntityPositionChanged(entity);
        }
    }

    /** Forwards removal callbacks; UUID/owner checks make this idempotent. */
    public static void entityRemoved(Entity entity, RemovalReason reason) {
        if (entity.getEntityWorld() instanceof ServerWorld world) {
            CubeEntityRuntime runtime = ENTITIES.get(world);
            if (runtime != null) runtime.onEntityRemoved(entity, reason);
        }
    }

    /** Restores pending records only after the caller proves FULL + ticketed. */
    public static int restoreCubeEntities(
            ServerWorld world, CubePos owner, boolean full, boolean ticketed) {
        CubeEntityRuntime runtime = ENTITIES.get(world);
        return runtime == null || !full || !ticketed || !isFullTicketed(world, owner)
                ? 0
                : runtime.restoreCube(owner, true, true);
    }

    /** Persists live entity payloads at a world-save barrier. */
    public static void saveEntities(ServerWorld world) {
        CubeEntityRuntime runtime = ENTITIES.get(world);
        if (runtime == null || !runtime.isDirty()) return;
        try {
            runtime.save();
        } catch (IOException | RuntimeException exception) {
            Higherworld.LOGGER.error("Cannot save cubic entities for {}",
                    world.getRegistryKey().getValue(), exception);
        }
    }

    /**
     * Persists at a vanilla world-save barrier even when no entity moved.
     * Runtime maintenance keeps the cheaper dirty-only path above, while a
     * real save must capture age, health, inventory and other tick mutations.
     */
    public static void saveEntitiesAtWorldSave(ServerWorld world) {
        CubeEntityRuntime runtime = ENTITIES.get(world);
        if (runtime == null || !CubeEntityRuntime.shouldSaveAtWorldSave(
                runtime.isDirty(), runtime.liveCount())) return;
        try {
            runtime.save();
        } catch (IOException | RuntimeException exception) {
            Higherworld.LOGGER.error("Cannot save cubic entities at world-save barrier for {}",
                    world.getRegistryKey().getValue(), exception);
        }
    }

    /** Persists scheduled ticks even when their target cubes are not loaded. */
    public static void saveScheduledTicksAtWorldSave(ServerWorld world) {
        CubicWorldState state = WORLDS.get(world);
        if (state == null) return;
        try {
            state.saveScheduledTicks();
        } catch (IOException | RuntimeException exception) {
            Higherworld.LOGGER.error("Cannot save scheduled tick journal for {}",
                    world.getRegistryKey().getValue(), exception);
        }
    }

    static boolean generatesInfinitelyDownward(ServerWorld world) {
        return world.getDimensionEntry().matchesKey(Higherworld.INFINITE_OVERWORLD)
                || world.getDimensionEntry().matchesKey(Higherworld.CUSTOM_OVERWORLD);
    }

    static boolean generatesCustomWorld(ServerWorld world) {
        return world.getDimensionEntry().matchesKey(Higherworld.CUSTOM_OVERWORLD);
    }

    private static void configureInfiniteGenerator(ServerWorld world) {
        if (!(world.getChunkManager().getChunkGenerator() instanceof NoiseChunkGenerator generator)) {
            return;
        }
        InfiniteNoiseSettings.rewriteWorldGenerator(world, generator);
    }

    /** Reads through the sparse cube cache without packing Y into a vanilla long key. */
    public static BlockState getBlockState(ServerWorld world, BlockPos pos) {
        CubicWorldState state = WORLDS.get(world);
        if (state == null) {
            return Blocks.VOID_AIR.getDefaultState();
        }
        try {
            return state.getBlockState(pos);
        } catch (IOException | RuntimeException exception) {
            Higherworld.LOGGER.error("Cannot load cube containing {} in {}", pos, world.getRegistryKey().getValue(), exception);
            return Blocks.VOID_AIR.getDefaultState();
        }
    }

    public static FluidState getFluidState(ServerWorld world, BlockPos pos) {
        CubicWorldState state = WORLDS.get(world);
        if (state == null) {
            return Fluids.EMPTY.getDefaultState();
        }
        try {
            return state.getFluidState(pos);
        } catch (IOException | RuntimeException exception) {
            Higherworld.LOGGER.error("Cannot load cube containing {} in {}", pos, world.getRegistryKey().getValue(), exception);
            return Fluids.EMPTY.getDefaultState();
        }
    }

    /**
     * Writes a block into the sparse cube runtime. Listener/network propagation is
     * added by the cube packet layer; this method only owns authoritative state.
     */
    public static boolean setBlockState(
            ServerWorld world, BlockPos pos, BlockState blockState, int flags, int maxUpdateDepth) {
        CubicWorldState state = WORLDS.get(world);
        if (state == null) {
            return false;
        }
        try {
            CubicWorldState.BlockChange result = state.setBlockState(pos, blockState);
            BlockState previous = result.previous();
            boolean changed = result.changed();
            if (changed) {
                if (previous.hasBlockEntity() && !previous.keepBlockEntityWhenReplacedWith(blockState)) {
                    BlockEntity removed = state.getBlockEntity(pos);
                    if (removed != null) {
                        removed.markRemoved();
                    }
                    state.removeBlockEntity(pos);
                }
                if (blockState.hasBlockEntity() && state.getBlockEntity(pos) == null
                        && blockState.getBlock() instanceof BlockEntityProvider provider) {
                    BlockEntity created = provider.createBlockEntity(pos, blockState);
                    if (created != null) {
                        state.putBlockEntity(created);
                    }
                }
                previous.onStateReplaced(world, pos, false);
                blockState.onBlockAdded(world, pos, previous, false);
                world.onBlockStateChanged(pos, previous, blockState);
                if ((flags & net.minecraft.block.Block.NOTIFY_LISTENERS) != 0) {
                    world.updateListeners(pos, previous, blockState, flags);
                }
                if ((flags & net.minecraft.block.Block.NOTIFY_NEIGHBORS) != 0 && maxUpdateDepth > 0) {
                    world.updateNeighborsAlways(pos, blockState.getBlock(), null);
                }
                if (!result.changedLight().isEmpty()) {
                    CubeWatchManager.broadcastLightUpdates(world, result.changedLight());
                }
            }
            return changed;
        } catch (IOException | RuntimeException exception) {
            Higherworld.LOGGER.error("Cannot update cube containing {} in {}", pos, world.getRegistryKey().getValue(), exception);
            return false;
        }
    }

    public static int loadedCubeCount(ServerWorld world) {
        CubicWorldState state = WORLDS.get(world);
        return state == null ? 0 : state.loadedCubeCount();
    }

    public static byte[] cubePayload(ServerWorld world, CubePos pos) {
        CubicWorldState state = WORLDS.get(world);
        if (state == null) {
            return new byte[0];
        }
        try {
            return state.cubePayload(pos);
        } catch (IOException | RuntimeException exception) {
            Higherworld.LOGGER.error("Cannot encode cube {} in {}", pos, world.getRegistryKey().getValue(), exception);
            return new byte[0];
        }
    }

    public static long cubeRevision(ServerWorld world, CubePos pos) {
        CubicWorldState state = WORLDS.get(world);
        return state == null ? 0L : state.cubeRevision(pos);
    }

    static byte[] cubeLightPayload(ServerWorld world, CubePos pos) {
        CubicWorldState state = WORLDS.get(world);
        return state == null ? new byte[0] : state.cubeLightPayload(pos);
    }

    /**
     * Returns whether this thread is committing generated cube state for the
     * given world.  ServerWorld.updateListeners is invoked by some structure
     * block entities during placement; allowing that callback to request a FULL
     * payload would recursively restart the same feature commit.
     */
    public static boolean suppressingGenerationUpdates(ServerWorld world) {
        CubicWorldState state = WORLDS.get(world);
        return state != null && state.suppressingGenerationUpdates();
    }

    /**
     * Requests a watched cube through its block/feature PAYLOAD stage. The
     * watcher promotes it to FULL only after sending that first packet, so
     * lighting dependencies cannot delay initial visibility. The returned
     * future is deliberately payload-free: callers should use
     * {@link #tryCubePayload} when they need encoded data.
     *
     * <p>If this world has not been opened yet, there is no lifecycle to wait
     * for, so the method returns an already completed future.</p>
     */
    public static CompletableFuture<Void> prefetchCubePayload(
            ServerWorld world, CubePos pos, int priority) {
        CubicWorldState state = WORLDS.get(world);
        return state == null
                ? CompletableFuture.completedFuture(null)
                : state.prefetchCubePayload(pos, priority);
    }

    /** Requests the complete lifecycle for simulation/entity restoration. */
    static CompletableFuture<Void> prefetchCubeFull(
            ServerWorld world, CubePos pos, int priority) {
        CubicWorldState state = WORLDS.get(world);
        return state == null
                ? CompletableFuture.completedFuture(null)
                : state.prefetchCubeFull(pos, priority);
    }

    /** Drops queued read-ahead work after its last 3D watcher ticket disappears. */
    public static void retainPrefetches(ServerWorld world, Set<CubePos> retained) {
        CubicWorldState state = WORLDS.get(world);
        if (state != null) {
            state.retainPrefetches(retained);
        }
    }

    static void replaceTicket(ServerWorld world, CubeTicket ticket) {
        CubicWorldState state = WORLDS.get(world);
        if (state == null) return;
        state.replaceTicket(ticket);
        ENTITY_TICKETS.computeIfAbsent(world, ignored -> new ConcurrentHashMap<>())
                .put(ticket.key(), ticket);
        scheduleEntityRestores(world);
    }

    static void removeTicket(ServerWorld world, Object key) {
        Map<Object, CubeTicket> tickets = ENTITY_TICKETS.get(world);
        if (tickets != null) {
            tickets.remove(key);
            if (tickets.isEmpty()) ENTITY_TICKETS.remove(world, tickets);
        }
        CubicWorldState state = WORLDS.get(world);
        if (state != null) state.removeTicket(key);
    }

    /**
     * Binds pending entity records to the same FULL future used by the cube
     * watcher.  The completion callback only enqueues a bounded server-thread
     * action and rechecks the live ticket before instantiation.
     */
    private static void scheduleEntityRestores(ServerWorld world) {
        CubeEntityRuntime runtime = ENTITIES.get(world);
        Map<Object, CubeTicket> tickets = ENTITY_TICKETS.get(world);
        if (runtime == null || tickets == null || tickets.isEmpty()) return;
        Set<CubePos> scheduled = ENTITY_RESTORE_FUTURES.computeIfAbsent(
                world, ignored -> ConcurrentHashMap.newKeySet());

        for (CubePos owner : runtime.pendingOwners()) {
            CubeTicket ticket = fullTicketCovering(tickets, owner);
            if (ticket == null) continue;
            if (!scheduled.add(owner)) continue;
            CompletableFuture<Void> ready;
            try {
                ready = prefetchCubeFull(world, owner, ticket.effectivePriority());
            } catch (RuntimeException exception) {
                scheduled.remove(owner);
                continue;
            }
            ready.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    scheduled.remove(owner);
                    return;
                }
                try {
                    world.getServer().execute(() -> {
                        try {
                            restorePendingEntityCube(world, runtime, owner);
                        } finally {
                            scheduled.remove(owner);
                        }
                    });
                } catch (RuntimeException ignoredException) {
                    // The server is closing; the durable record remains.
                    scheduled.remove(owner);
                }
            });
        }
    }

    private static void restorePendingEntityCube(
            ServerWorld world, CubeEntityRuntime expectedRuntime, CubePos owner) {
        if (ENTITIES.get(world) != expectedRuntime || !isFullTicketed(world, owner)) return;
        try {
            expectedRuntime.restoreCube(owner, true, true);
        } catch (RuntimeException exception) {
            Higherworld.LOGGER.warn("Cannot restore cubic entities in {} at {}",
                    world.getRegistryKey().getValue(), owner, exception);
        }
    }

    private static boolean isFullTicketed(ServerWorld world, CubePos owner) {
        Map<Object, CubeTicket> tickets = ENTITY_TICKETS.get(world);
        return tickets != null && fullTicketCovering(tickets, owner) != null;
    }

    private static CubeTicket fullTicketCovering(
            Map<Object, CubeTicket> tickets, CubePos owner) {
        for (CubeTicket ticket : tickets.values()) {
            if (ticket.targetStatus().isAtLeast(CubeStatus.FULL)
                    && covers(ticket, owner)) {
                return ticket;
            }
        }
        return null;
    }

    private static boolean covers(CubeTicket ticket, CubePos pos) {
        CubePos center = ticket.center();
        CubeDependencyRadius radius = ticket.radius();
        return Math.abs((long) pos.x() - center.x()) <= radius.x()
                && Math.abs((long) pos.y() - center.y()) <= radius.y()
                && Math.abs((long) pos.z() - center.z()) <= radius.z();
    }

    public static void advanceReadyTasks(ServerWorld world, long budgetNanos) {
        CubicWorldState state = WORLDS.get(world);
        if (state != null) state.advanceReadyTasks(budgetNanos);
    }

    /**
     * Returns {@code null} while asynchronous IO is pending. Generation and
     * serialization still run on the server thread once the read completes.
     */
    public static byte[] tryCubePayload(ServerWorld world, CubePos pos, int priority) {
        CubicWorldState state = WORLDS.get(world);
        if (state == null) {
            return new byte[0];
        }
        try {
            return state.tryCubePayload(pos, priority);
        } catch (IOException | RuntimeException exception) {
            Higherworld.LOGGER.error("Cannot asynchronously load cube {} in {}",
                    pos, world.getRegistryKey().getValue(), exception);
            return new byte[0];
        }
    }

    public static void flushDirty(ServerWorld world) {
        CubicWorldState state = WORLDS.get(world);
        if (state != null) {
            try {
                state.flushDirty();
            } catch (IOException exception) {
                Higherworld.LOGGER.error("Cannot flush cubic storage for {}", world.getRegistryKey().getValue(), exception);
            }
        }
        saveEntities(world);
    }

    public static Integer highestBlockY(ServerWorld world, int blockX, int blockZ) {
        CubicWorldState state = WORLDS.get(world);
        return state == null ? null : state.highestBlockY(blockX, blockZ);
    }

    public static int lightLevel(ServerWorld world, LightType type, BlockPos pos) {
        CubicWorldState state = WORLDS.get(world);
        return state == null ? 0 : state.lightLevel(type, pos);
    }

    public static BlockEntity getBlockEntity(ServerWorld world, BlockPos pos) {
        CubicWorldState state = WORLDS.get(world);
        if (state == null) {
            return null;
        }
        try {
            return state.getBlockEntity(pos);
        } catch (IOException | RuntimeException exception) {
            Higherworld.LOGGER.error("Cannot load block entity at {}", pos, exception);
            return null;
        }
    }

    public static void putBlockEntity(ServerWorld world, BlockEntity blockEntity) {
        CubicWorldState state = WORLDS.get(world);
        if (state == null) {
            return;
        }
        try {
            state.putBlockEntity(blockEntity);
        } catch (IOException | RuntimeException exception) {
            Higherworld.LOGGER.error("Cannot store block entity at {}", blockEntity.getPos(), exception);
        }
    }

    public static void removeBlockEntity(ServerWorld world, BlockPos pos) {
        CubicWorldState state = WORLDS.get(world);
        if (state == null) {
            return;
        }
        try {
            state.removeBlockEntity(pos);
        } catch (IOException | RuntimeException exception) {
            Higherworld.LOGGER.error("Cannot remove block entity at {}", pos, exception);
        }
    }

    public static void markDirty(ServerWorld world, BlockPos pos) {
        CubicWorldState state = WORLDS.get(world);
        if (state == null) {
            return;
        }
        try {
            state.markDirty(pos);
        } catch (IOException | RuntimeException exception) {
            Higherworld.LOGGER.error("Cannot mark cube dirty at {}", pos, exception);
        }
    }

    public static void tick(ServerWorld world) {
        CubicWorldState state = WORLDS.get(world);
        if (state != null) {
            state.tick();
        }
        CubeEntityRuntime entities = ENTITIES.get(world);
        if (entities != null) entities.tick();
    }

    public static void evictExcept(ServerWorld world, Set<CubePos> retained) {
        CubicWorldState state = WORLDS.get(world);
        if (state != null) {
            try {
                state.evictExcept(retained);
            } catch (IOException exception) {
                Higherworld.LOGGER.error("Cannot evict cubic cache for {}", world.getRegistryKey().getValue(), exception);
            }
        }
        CubeEntityRuntime entities = ENTITIES.get(world);
        if (entities != null) entities.unloadExcept(retained);
        saveEntities(world);
    }
}
