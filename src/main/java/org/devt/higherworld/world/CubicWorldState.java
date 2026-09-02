package org.devt.higherworld.world;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.Heightmap;
import net.minecraft.world.rule.GameRules;
import org.devt.higherworld.Higherworld;
import org.devt.higherworld.storage.CubeIoScheduler;
import org.devt.higherworld.storage.CubePos;
import org.devt.higherworld.storage.CubeStorage;

/** Runtime cube cache and persistence boundary for one server dimension. */
final class CubicWorldState implements AutoCloseable {
    private static final byte[] EMPTY_PAYLOAD = new byte[0];
    private static final int SYNCHRONOUS_IO_PRIORITY = 0;
    private static final int LIGHT_STEPS_PER_SLICE = 50_000;
    private static final long LIGHT_NANOS_PER_SLICE = 1_000_000L;
    private static final int EVICTIONS_PER_PASS = 32;
    private static final long EVICTION_NANOS_PER_PASS = 2_000_000L;
    private static final int LIGHT_BROADCASTS_PER_TICK = 2;
    private final ServerWorld world;
    private final CubeStorage storage;
    private final CubeIoScheduler ioScheduler;
    private final CubeTaskScheduler taskScheduler;
    private final CubeSpatialLock generationLocks = new CubeSpatialLock(1024);
    private final boolean generateInfinitelyDownward;
    private final boolean customWorld;
    private final boolean generateStructures;
    private final StructureGenerationSettings structureSettings;
    private final CustomWorldSettings customWorldSettings;
    private final ConcurrentMap<ColumnPos, CubeColumn<LoadedCube>> columns = new ConcurrentHashMap<>();
    private final ConcurrentMap<CubePos, LoadedCube> cubes = new ConcurrentHashMap<>();
    private final ConcurrentMap<CubePos, LoadContext> loadContexts = new ConcurrentHashMap<>();
    private final ConcurrentMap<BlockColumnPos, Integer> highestBlocks = new ConcurrentHashMap<>();
    private final SparseCubeLightEngine lightEngine = new SparseCubeLightEngine(new LightAccess());
    private final Set<CubePos> pendingLightBroadcasts = new HashSet<>();
    private final ThreadLocal<Boolean> committingFeatures = ThreadLocal.withInitial(() -> false);
    /**
     * World listener callbacks are not safe while terrain/features are being
     * committed.  Structure block entities can call ServerWorld.updateListeners
     * while they are installed, which would otherwise synchronously request the
     * same cube's FULL payload and re-enter generation.
     */
    private final ThreadLocal<Boolean> suppressingGenerationUpdates =
            ThreadLocal.withInitial(() -> false);

    CubicWorldState(
            ServerWorld world, CubeStorage storage, boolean generateStructures,
            StructureGenerationSettings structureSettings,
            CustomWorldSettings customWorldSettings) {
        this.world = world;
        this.storage = storage;
        this.ioScheduler = new CubeIoScheduler(storage);
        this.taskScheduler = new CubeTaskScheduler(ioScheduler);
        this.generateInfinitelyDownward = CubicWorldManager.generatesInfinitelyDownward(world);
        this.customWorld = CubicWorldManager.generatesCustomWorld(world);
        this.generateStructures = generateStructures;
        this.structureSettings = structureSettings;
        this.customWorldSettings = customWorldSettings;
    }

    BlockState getBlockState(BlockPos pos) throws IOException {
        return cubeForRead(pos).getBlockState(pos);
    }

    FluidState getFluidState(BlockPos pos) throws IOException {
        return cubeForRead(pos).getFluidState(pos);
    }

    /**
     * Feature callbacks such as mob-spawner dirty notifications may query a
     * neighbouring block through {@code World}.  Advancing that neighbour all
     * the way to FULL would start its features while the current feature is
     * still running, recursively repeating the callback until the stack is
     * exhausted.  Terrain is sufficient for generation-time reads and is also
     * the declared prerequisite for neighbouring features.
     */
    private LoadedCube cubeForRead(BlockPos blockPos) throws IOException {
        if (!committingFeatures.get()) return cube(blockPos);
        CubePos pos = CubePos.fromBlock(blockPos.getX(), blockPos.getY(), blockPos.getZ());
        LoadedCube loaded = loadedCube(pos);
        if (loaded != null) {
            LoadContext context = loadContexts.get(pos);
            if (context != null && context.committing) return loaded;
            if (taskScheduler.holder(pos).status().isAtLeast(CubeStatus.TERRAIN)) return loaded;
        }
        return ensureStage(pos, CubeStatus.TERRAIN, SYNCHRONOUS_IO_PRIORITY, null);
    }

    BlockChange setBlockState(BlockPos pos, BlockState state) throws IOException {
        LoadedCube cube = cube(pos);
        BlockState previous = cube.setBlockState(pos, state);
        Set<CubePos> changedLight = Set.of();
        if (previous != state) {
            updateHeight(pos, state);
            lightEngine.queueBlock(pos.getX(), pos.getY(), pos.getZ());
            queueLoadedSkyColumn(pos.getX(), pos.getZ());
            changedLight = lightEngine.propagate(
                    LIGHT_STEPS_PER_SLICE, LIGHT_NANOS_PER_SLICE).changedCubes();
        }
        return new BlockChange(previous, changedLight);
    }

    BlockEntity getBlockEntity(BlockPos pos) throws IOException {
        return cubeForRead(pos).getBlockEntity(pos);
    }

    void putBlockEntity(BlockEntity blockEntity) throws IOException {
        blockEntity.setWorld(world);
        cube(blockEntity.getPos()).putBlockEntity(blockEntity);
    }

    void removeBlockEntity(BlockPos pos) throws IOException {
        cube(pos).removeBlockEntity(pos);
    }

    void markDirty(BlockPos pos) throws IOException {
        // Block entities created by placed features (notably dungeon spawners)
        // call World#markDirty while FEATURES is still being committed.  Do not
        // advance a neighbouring cube to FULL from that callback, or feature
        // generation recursively starts again through the LIGHT dependencies.
        cubeForRead(pos).markDirty();
    }

    LoadedCube cube(BlockPos blockPos) throws IOException {
        return cube(CubePos.fromBlock(blockPos.getX(), blockPos.getY(), blockPos.getZ()));
    }

    LoadedCube cube(CubePos pos) throws IOException {
        ColumnPos columnPos = new ColumnPos(pos.x(), pos.z());
        CubeColumn<LoadedCube> column = columns.get(columnPos);
        LoadedCube loaded = column == null ? null : column.get(pos.y());
        if (loaded != null) {
            LoadContext context = loadContexts.get(pos);
            if (context != null && context.committing) return loaded;
            if (context != null && context.full) {
                taskScheduler.adoptLoaded(loaded);
                return loaded;
            }
            CubeHolder holder = taskScheduler.holder(pos);
            if (holder.status().isAtLeast(CubeStatus.FULL)) return loaded;
            return ensureStage(pos, CubeStatus.FULL, SYNCHRONOUS_IO_PRIORITY, null);
        }

        return ensureStage(pos, CubeStatus.FULL, SYNCHRONOUS_IO_PRIORITY, null);
    }

    int loadedCubeCount() {
        return cubes.size();
    }

    long cubeRevision(CubePos pos) {
        LoadedCube loaded = loadedCube(pos);
        return loaded == null ? 0L : loaded.revision();
    }

    boolean suppressingGenerationUpdates() {
        return suppressingGenerationUpdates.get();
    }

    Integer highestBlockY(int blockX, int blockZ) {
        return highestBlocks.get(new BlockColumnPos(blockX, blockZ));
    }

    int lightLevel(net.minecraft.world.LightType type, BlockPos pos) {
        LoadedCube cube = loadedCube(CubePos.fromBlock(pos.getX(), pos.getY(), pos.getZ()));
        if (cube == null) return 0;
        return type == net.minecraft.world.LightType.BLOCK
                ? cube.light().block(local(pos.getX()), local(pos.getY()), local(pos.getZ()))
                : cube.light().sky(local(pos.getX()), local(pos.getY()), local(pos.getZ()));
    }

    byte[] cubePayload(CubePos pos) throws IOException {
        LoadedCube loaded = loadedCube(pos);
        if (loaded != null) {
            LoadContext context = loadContexts.get(pos);
            if (context != null && context.full) {
                taskScheduler.adoptLoaded(loaded);
                return CubeRecordCodec.encode(loaded, world);
            }
            CubeHolder holder = taskScheduler.holder(pos);
            if (!holder.status().isAtLeast(CubeStatus.FULL)) {
                loaded = ensureStage(pos, CubeStatus.FULL, SYNCHRONOUS_IO_PRIORITY, null);
            } else {
                taskScheduler.adoptLoaded(loaded);
            }
            return CubeRecordCodec.encode(loaded, world);
        }

        CubeHolder holder = taskScheduler.request(pos, CubeStatus.FULL, SYNCHRONOUS_IO_PRIORITY);
        Optional<byte[]> stored = holder.ioFuture().join();
        return finishCubePayload(pos, stored);
    }

    /**
     * Requests/adopts the cube's FULL lifecycle without waiting for IO or
     * server-thread stage commits.  Completion is signalled by the holder's
     * FULL future; its payload is intentionally not exposed here because the
     * watcher can encode it after the completion callback runs on the server
     * thread.
     */
    CompletableFuture<Void> prefetchCubePayload(CubePos pos, int priority) {
        CubeHolder holder;
        LoadedCube loaded = loadedCube(pos);
        if (loaded == null) {
            holder = taskScheduler.request(pos, CubeStatus.FULL, priority);
        } else {
            LoadContext context = loadContexts.get(pos);
            if (context != null && context.full) {
                holder = taskScheduler.adoptLoaded(loaded);
            } else {
                holder = taskScheduler.holder(pos);
                if (holder.status().isAtLeast(CubeStatus.FULL)) {
                    holder = taskScheduler.adoptLoaded(loaded);
                } else {
                    holder = taskScheduler.request(pos, CubeStatus.FULL, priority);
                }
            }
        }
        return fullReadyFuture(holder);
    }

    /** Mirrors a holder's FULL future while preserving exceptional completion
     * and cancellation on the payload-free API exposed to watchers. */
    private static CompletableFuture<Void> fullReadyFuture(CubeHolder holder) {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        CompletableFuture<Optional<LoadedCube>> full = holder.fullFuture();
        full.whenComplete((ignored, failure) -> {
            if (failure == null) {
                ready.complete(null);
            } else {
                // completeExceptionally preserves the holder's original
                // throwable, including CancellationException identity.
                ready.completeExceptionally(failure);
            }
        });
        return ready;
    }

    void retainPrefetches(Set<CubePos> retained) {
        taskScheduler.retainTicketedHolders();
    }

    /**
     * Advances the IO -> live-cube boundary without ever waiting for disk on the
     * server thread. Returns {@code null} while the deduplicated read is pending.
     */
    byte[] tryCubePayload(CubePos pos, int priority) throws IOException {
        LoadedCube loaded = loadedCube(pos);
        if (loaded != null) {
            LoadContext context = loadContexts.get(pos);
            if (context != null && context.full) {
                taskScheduler.adoptLoaded(loaded);
                return CubeRecordCodec.encode(loaded, world);
            }
            CubeHolder holder = taskScheduler.holder(pos);
            if (!holder.status().isAtLeast(CubeStatus.FULL)) {
                if (!holder.fullFuture().isDone()) return null;
            } else {
                taskScheduler.adoptLoaded(loaded);
            }
            return CubeRecordCodec.encode(loaded, world);
        }

        CubeHolder holder = taskScheduler.request(pos, CubeStatus.FULL, priority);
        CompletableFuture<Optional<byte[]>> read = holder.ioFuture();
        if (!read.isDone()) {
            return null;
        }
        try {
            return finishCubePayload(pos, read.join(), priority, true);
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Cannot read cube " + pos, cause);
        }
    }

    private LoadedCube loadedCube(CubePos pos) {
        return cubes.get(pos);
    }

    private byte[] finishCubePayload(CubePos pos, Optional<byte[]> stored) throws IOException {
        return finishCubePayload(pos, stored, SYNCHRONOUS_IO_PRIORITY, false);
    }

    private byte[] finishCubePayload(
            CubePos pos, Optional<byte[]> stored, int priority, boolean allowPending) throws IOException {
        // A missing sparse cube is implicitly air unless this world's selected
        // preset asks HigherWorld to lazily generate the terrain below it.
        if (stored.isEmpty()) {
            if (!shouldGenerate(pos)) {
                CubeHolder holder = taskScheduler.request(pos, CubeStatus.FULL, priority);
                holder.advance(CubeStatus.LIGHT);
                holder.complete(null);
                return EMPTY_PAYLOAD;
            }
            CubeHolder holder = taskScheduler.request(pos, CubeStatus.FULL, priority);
            if (customWorld) {
                var terrain = taskScheduler.prepareTerrain(holder, world.getSeed(), customWorldSettings, priority);
                if (allowPending && !terrain.isDone()) {
                    return null;
                }
            }
            LoadedCube generated;
            if (allowPending) {
                Optional<LoadedCube> completed = holder.fullFuture().getNow(null);
                if (completed == null) return null;
                generated = completed.orElse(null);
                if (generated == null) return EMPTY_PAYLOAD;
            } else {
                generated = ensureStage(pos, CubeStatus.FULL, priority, Optional.empty());
            }
            return CubeRecordCodec.encode(generated, world);
        }

        // Real stored cubes still enter the runtime so their block entities and
        // random-ticking blocks continue to simulate while watched.
        CubeHolder holder = taskScheduler.request(pos, CubeStatus.FULL, priority);
        LoadedCube created;
        if (allowPending) {
            Optional<LoadedCube> completed = holder.fullFuture().getNow(null);
            if (completed == null) return null;
            created = completed.orElse(null);
            if (created == null) return EMPTY_PAYLOAD;
        } else {
            created = ensureStage(pos, CubeStatus.FULL, priority, stored);
        }
        return created.isDirty() ? CubeRecordCodec.encode(created, world) : stored.get();
    }

    void flushDirty() throws IOException {
        // Serialization still reads Minecraft's ChunkSection and therefore
        // stays on the server thread.  Queue one snapshot per tick instead of
        // encoding every loaded cube and then waiting for fsync in one tick.
        for (LoadedCube cube : loadedCubes()) {
            LoadContext context = loadContexts.get(cube.pos());
            if (cube.isDirty() && (context == null || context.full)) {
                save(cube, false, false);
                return;
            }
        }
    }

    void evictExcept(Set<CubePos> retained) throws IOException {
        IOException failure = null;
        int evicted = 0;
        long started = System.nanoTime();
        boolean budgetExhausted = false;
        for (Map.Entry<ColumnPos, CubeColumn<LoadedCube>> entry : columns.entrySet()) {
            if (budgetExhausted) break;
            CubeColumn<LoadedCube> column = entry.getValue();
            boolean removedFromColumn = false;
            for (LoadedCube cube : List.copyOf(column.loaded())) {
                if (evicted >= EVICTIONS_PER_PASS
                        || System.nanoTime() - started >= EVICTION_NANOS_PER_PASS) {
                    budgetExhausted = true;
                    break;
                }
                // A ticket's dependency halo is not necessarily present in a
                // player's sent/pending set.  Evicting it while FEATURES or
                // LIGHT is waiting makes the DAG restart mid-commit and can
                // expose an incomplete neighbour to gameplay code.
                if (!isOutsideVanillaHeight(cube.pos()) || retained.contains(cube.pos())
                        || taskScheduler.isRequired(cube.pos())) {
                    continue;
                }
                try {
                    // Eviction is two-phase.  First queue the latest immutable
                    // payload; a later maintenance pass removes the live cube
                    // only after the asynchronous write has completed.
                    if (cube.isDirty()) {
                        save(cube, false, false);
                        continue;
                    }
                    if (!taskScheduler.saveComplete(cube.pos())) continue;
                    column.remove(cube.pos().y());
                    cubes.remove(cube.pos(), cube);
                    loadContexts.remove(cube.pos());
                    removeIndexedHeights(cube);
                    taskScheduler.release(cube.pos());
                    removedFromColumn = true;
                    evicted++;
                } catch (IOException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
            if (removedFromColumn && !column.isEmpty()) {
                // Only a boundary changed. Reinitializing every one of the
                // remaining cube's 4096 cells creates an unload-time light storm.
                column.forEach(remaining -> lightEngine.queueCube(remaining.pos(), false));
            }
            if (column.isEmpty()) {
                columns.remove(entry.getKey(), column);
            }
        }
        // The ordinary tick slice drains boundary light work incrementally.
        if (failure != null) {
            throw failure;
        }
    }

    void tick() {
        SparseCubeLightEngine.Result lightWork = lightEngine.propagate(
                LIGHT_STEPS_PER_SLICE, LIGHT_NANOS_PER_SLICE);
        pendingLightBroadcasts.addAll(lightWork.changedCubes());
        if (!pendingLightBroadcasts.isEmpty()) {
            Set<CubePos> batch = new HashSet<>(LIGHT_BROADCASTS_PER_TICK);
            Iterator<CubePos> iterator = pendingLightBroadcasts.iterator();
            while (iterator.hasNext() && batch.size() < LIGHT_BROADCASTS_PER_TICK) {
                CubePos pos = iterator.next();
                LoadedCube loaded = loadedCube(pos);
                if (loaded == null) {
                    iterator.remove();
                    continue;
                }
                LoadContext context = loadContexts.get(pos);
                // Encoding a partial cube would synchronously force its whole
                // lifecycle from this maintenance path. Keep the update
                // coalesced until the normal bounded scheduler reaches FULL.
                if (context != null && !context.full) continue;
                iterator.remove();
                batch.add(pos);
            }
            if (!batch.isEmpty()) CubeWatchManager.broadcastCubeUpdates(world, batch);
        }
        int randomTickSpeed = world.getGameRules().getValue(GameRules.RANDOM_TICK_SPEED);
        for (CubeColumn<LoadedCube> column : columns.values()) {
            column.forEach(cube -> {
                // Loaded is not the same as ticketed.  Direct block queries can
                // leave a sparse cube cached for a short time; ticking it would
                // continue simulation after its owner has gone away.
                if (!taskScheduler.isRequired(cube.pos())) return;
                CubeHolder holder = taskScheduler.holder(cube.pos());
                // A lower-priority ticket may deliberately downgrade an
                // already-loaded cube to TERRAIN/FEATURES.  Status is
                // monotonic within an epoch, so target must be checked too;
                // otherwise the old FULL state would keep ticking forever.
                if (!holder.target().isAtLeast(CubeStatus.FULL)
                        || !holder.status().isAtLeast(CubeStatus.FULL)) return;
                if (!CubeWatchManager.shouldTick(world, cube.pos())) return;
                cube.tickBlockEntities(world);
                cube.tickRandomly(world, randomTickSpeed);
            });
        }
    }

    /** Commits completed worker terrain during a bounded mid-tick slice. */
    void advanceReadyTasks(long budgetNanos) {
        long deadline = System.nanoTime() + Math.max(0L, budgetNanos);
        for (CubeHolder holder : taskScheduler.readyForCommit(64)) {
            if (System.nanoTime() >= deadline) break;
            try {
                tryAdvance(holder, taskScheduler.priority(holder.pos()));
            } catch (IOException | RuntimeException exception) {
                holder.fail(exception);
                Higherworld.LOGGER.error("Cannot finish asynchronous cube {}", holder.pos(), exception);
            }
        }
    }

    void replaceTicket(CubeTicket ticket) {
        taskScheduler.replaceTicket(ticket);
    }

    void removeTicket(Object key) {
        taskScheduler.removeTicket(key);
    }

    boolean isOutsideVanillaHeight(CubePos pos) {
        return pos.y() < world.getBottomSectionCoord() || pos.y() >= world.getTopSectionCoord();
    }

    private LoadedCube registerPlaceholder(CubePos pos) {
        ColumnPos columnPos = new ColumnPos(pos.x(), pos.z());
        while (true) {
            LoadedCube alreadyLoaded = cubes.get(pos);
            if (alreadyLoaded != null) return alreadyLoaded;
            CubeColumn<LoadedCube> column = columns.computeIfAbsent(
                    columnPos, ignored -> new CubeColumn<>());
            // Register the placeholder before generation. Feature code and block
            // entities can call World#getBlockState while this cube is loading.
            LoadedCube created = new LoadedCube(
                    pos, new ChunkSection(world.getPalettesFactory()));
            LoadedCube racedCube = cubes.putIfAbsent(pos, created);
            if (racedCube != null) return racedCube;
            try {
                column.put(pos.y(), created);
            } catch (IllegalArgumentException raced) {
                cubes.remove(pos, created);
                continue;
            }

            return created;
        }
    }

    private LoadedCube ensureStage(
            CubePos pos, CubeStatus target, int priority, Optional<byte[]> knownPayload)
            throws IOException {
        CubeHolder holder = taskScheduler.request(pos, target, priority);
        Optional<byte[]> payload = knownPayload == null ? joinIo(pos, holder) : knownPayload;
        LoadContext context = context(holder, payload);
        if (target.isAtLeast(CubeStatus.TERRAIN) && !holder.status().isAtLeast(CubeStatus.TERRAIN)) {
            commitTerrain(holder, context, priority, true);
        }
        if (target.isAtLeast(CubeStatus.FEATURES) && !holder.status().isAtLeast(CubeStatus.FEATURES)) {
            CubeStatus.FEATURES.dependencyRadius().forEach(pos, dependency -> {
                try {
                    ensureStage(dependency, CubeStatus.TERRAIN, priority + 1, null);
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            });
            commitFeatures(holder, context);
        }
        if (target.isAtLeast(CubeStatus.LIGHT) && !holder.status().isAtLeast(CubeStatus.LIGHT)) {
            CubeStatus.LIGHT.dependencyRadius().forEach(pos, dependency -> {
                try {
                    ensureStage(dependency, CubeStatus.FEATURES, priority + 1, null);
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            });
            while (!commitLight(holder, context)) {
                // Synchronous gameplay access preserves the FULL contract. The
                // normal watcher path uses tryAdvance and therefore never spins
                // here while streaming cubes.
            }
        }
        if (target == CubeStatus.FULL && !holder.fullFuture().isDone()) {
            holder.complete(context.cube);
            context.full = true;
        } else if (target == CubeStatus.FULL
                && holder.fullFuture().getNow(Optional.empty()).isEmpty()) {
            holder.materialize(context.cube);
            context.full = true;
        }
        return context.cube;
    }

    private boolean tryAdvance(CubeHolder holder, int priority) throws IOException {
        Optional<byte[]> payload = holder.ioFuture().getNow(null);
        if (payload == null) return false;
        LoadContext context = context(holder, payload);
        if (!holder.status().isAtLeast(CubeStatus.TERRAIN)) {
            return commitTerrain(holder, context, priority, false);
        }
        if (!holder.status().isAtLeast(CubeStatus.FEATURES)) {
            if (!taskScheduler.dependenciesReady(holder, CubeStatus.FEATURES, priority)) return false;
            commitFeatures(holder, context);
            return true;
        }
        if (!holder.status().isAtLeast(CubeStatus.LIGHT)) {
            if (!taskScheduler.dependenciesReady(holder, CubeStatus.LIGHT, priority)) return false;
            return commitLight(holder, context);
        }
        if (holder.target() == CubeStatus.FULL && !holder.fullFuture().isDone()) {
            holder.complete(context.cube);
            context.full = true;
            return true;
        }
        return false;
    }

    private Optional<byte[]> joinIo(CubePos pos, CubeHolder holder) throws IOException {
        try {
            return holder.ioFuture().join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Cannot read cube " + pos, cause);
        }
    }

    private LoadContext context(CubeHolder holder, Optional<byte[]> payload) throws IOException {
        CubePos pos = holder.pos();
        LoadContext existing = loadContexts.get(pos);
        if (existing != null && (existing.epoch == holder.epoch() || existing.full)) return existing;
        if (existing != null && loadContexts.remove(pos, existing)) {
            CubeColumn<LoadedCube> column = columns.get(new ColumnPos(pos.x(), pos.z()));
            if (column != null) {
                column.remove(pos.y(), existing.cube);
                if (column.isEmpty()) columns.remove(new ColumnPos(pos.x(), pos.z()), column);
            }
            cubes.remove(pos, existing.cube);
            removeIndexedHeights(existing.cube);
        }
        LoadedCube cube = registerPlaceholder(pos);
        LoadContext created = new LoadContext(cube, payload.orElse(null), holder.epoch());
        LoadContext raced = loadContexts.putIfAbsent(pos, created);
        return raced == null ? created : raced;
    }

    private boolean commitTerrain(
            CubeHolder holder, LoadContext context, int priority, boolean wait) throws IOException {
        CubePos pos = holder.pos();
        boolean wasSuppressing = suppressingGenerationUpdates.get();
        suppressingGenerationUpdates.set(true);
        try {
            if (context.payload != null) {
                context.committing = true;
                try {
                    context.cube.setGenerationVersion(CubeRecordCodec.generationVersion(context.payload));
                    CubeRecordCodec.DecodedCube decoded = CubeRecordCodec.decode(
                            context.payload, context.cube.section(), world);
                    for (BlockEntity blockEntity : decoded.blockEntities()) {
                        context.cube.putLoadedBlockEntity(blockEntity);
                    }
                    if (decoded.hasLight()) {
                        context.cube.light().load(decoded.light());
                        context.hasSavedLight = true;
                    }
                    if (!customWorld && shouldGenerate(pos)) {
                        // A legacy upgrade may deterministically replay placed
                        // features. Route any neighbour reads through TERRAIN
                        // while this holder is still committing its own terrain;
                        // asking for FULL here recursively re-enters generation.
                        boolean alreadyCommittingFeatures = committingFeatures.get();
                        committingFeatures.set(true);
                        try {
                            if (InfiniteDownwardGenerator.upgradeLegacyTerrain(
                                    world, context.cube, effectiveStructureSettings())) {
                                Higherworld.LOGGER.debug(
                                        "Upgraded generated terrain cube {}", pos);
                            }
                        } finally {
                            if (alreadyCommittingFeatures) committingFeatures.set(true);
                            else committingFeatures.remove();
                        }
                    }
                } finally {
                    context.committing = false;
                }
            } else if (shouldGenerate(pos) && customWorld) {
                CompletableFuture<CustomCubeGenerator.TerrainSnapshot> preparation =
                        taskScheduler.prepareTerrain(holder, world.getSeed(), customWorldSettings, priority);
                if (!wait && !preparation.isDone()) return false;
                try {
                    context.committing = true;
                    try {
                        CustomCubeGenerator.applyTerrain(context.cube, preparation.join());
                    } finally {
                        context.committing = false;
                    }
                } catch (CompletionException exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof RuntimeException runtime) throw runtime;
                    throw new IOException("Cannot prepare terrain for cube " + pos, cause);
                }
            } else if (shouldGenerate(pos)) {
                context.committing = true;
                try {
                    InfiniteDownwardGenerator.generateTerrain(world, context.cube);
                } finally {
                    context.committing = false;
                }
            }
            holder.advance(CubeStatus.TERRAIN);
            return true;
        } finally {
            if (wasSuppressing) {
                suppressingGenerationUpdates.set(true);
            } else {
                suppressingGenerationUpdates.remove();
            }
        }
    }

    private void commitFeatures(CubeHolder holder, LoadContext context) {
        boolean alreadyCommittingFeatures = committingFeatures.get();
        boolean wasSuppressing = suppressingGenerationUpdates.get();
        committingFeatures.set(true);
        suppressingGenerationUpdates.set(true);
        context.committing = true;
        try {
            if (context.payload == null && shouldGenerate(holder.pos())) {
                try (CubeSpatialLock.Scope ignored = generationLocks.lock(
                        holder.pos(), CubeStatus.FEATURES.dependencyRadius())) {
                    if (customWorld) {
                        CustomCubeGenerator.finishGeneration(world, context.cube, customWorldSettings,
                                structureSettings, generateStructures);
                    } else {
                        InfiniteDownwardGenerator.generateFeatures(
                                world, context.cube, effectiveStructureSettings());
                    }
                }
            }
            holder.advance(CubeStatus.FEATURES);
        } finally {
            context.committing = false;
            if (alreadyCommittingFeatures) committingFeatures.set(true);
            else committingFeatures.remove();
            if (wasSuppressing) suppressingGenerationUpdates.set(true);
            else suppressingGenerationUpdates.remove();
        }
    }

    private boolean commitLight(CubeHolder holder, LoadContext context) {
        if (!context.lightQueued) {
            indexHeights(context.cube);
            lightEngine.queueCube(holder.pos(), !context.hasSavedLight);
            context.lightQueued = true;
        }
        SparseCubeLightEngine.Result result = lightEngine.propagate(
                LIGHT_STEPS_PER_SLICE, LIGHT_NANOS_PER_SLICE);
        pendingLightBroadcasts.addAll(result.changedCubes());
        if (!result.complete()) return false;
        holder.advance(CubeStatus.LIGHT);
        return true;
    }

    private boolean shouldGenerate(CubePos pos) {
        if (!generateInfinitelyDownward || pos.y() >= world.getBottomSectionCoord()) {
            return false;
        }
        return customWorldSettings.isUnlimited()
                || world.getBottomY() - pos.minBlockY() <= customWorldSettings.generationDepth();
    }

    private StructureGenerationSettings effectiveStructureSettings() {
        return generateStructures ? structureSettings : StructureGenerationSettings.none();
    }

    private void indexHeights(LoadedCube cube) {
        int baseX = cube.pos().minBlockX();
        int baseY = cube.pos().minBlockY();
        int baseZ = cube.pos().minBlockZ();
        for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
            for (int localX = 0; localX < CubePos.SIZE; localX++) {
                for (int localY = CubePos.SIZE - 1; localY >= 0; localY--) {
                    if (!cube.section().getBlockState(localX, localY, localZ).isAir()) {
                        BlockColumnPos key = new BlockColumnPos(baseX + localX, baseZ + localZ);
                        highestBlocks.merge(key, baseY + localY, Math::max);
                        break;
                    }
                }
            }
        }
    }

    private void updateHeight(BlockPos pos, BlockState state) {
        BlockColumnPos key = new BlockColumnPos(pos.getX(), pos.getZ());
        if (!state.isAir()) {
            highestBlocks.merge(key, pos.getY(), Math::max);
            return;
        }
        Integer current = highestBlocks.get(key);
        if (current != null && current == pos.getY()) {
            recomputeHeight(key);
        }
    }

    private void removeIndexedHeights(LoadedCube cube) {
        int baseX = cube.pos().minBlockX();
        int baseZ = cube.pos().minBlockZ();
        int minY = cube.pos().minBlockY();
        int maxY = minY + CubePos.SIZE - 1;
        for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
            for (int localX = 0; localX < CubePos.SIZE; localX++) {
                BlockColumnPos key = new BlockColumnPos(baseX + localX, baseZ + localZ);
                Integer height = highestBlocks.get(key);
                if (height != null && height >= minY && height <= maxY) {
                    recomputeHeight(key);
                }
            }
        }
    }

    private void recomputeHeight(BlockColumnPos key) {
        CubeColumn<LoadedCube> column = columns.get(
                new ColumnPos(Math.floorDiv(key.x(), CubePos.SIZE), Math.floorDiv(key.z(), CubePos.SIZE)));
        int highest = Integer.MIN_VALUE;
        if (column != null) {
            int localX = Math.floorMod(key.x(), CubePos.SIZE);
            int localZ = Math.floorMod(key.z(), CubePos.SIZE);
            for (LoadedCube cube : column.loaded()) {
                for (int localY = CubePos.SIZE - 1; localY >= 0; localY--) {
                    if (!cube.section().getBlockState(localX, localY, localZ).isAir()) {
                        highest = Math.max(highest, cube.pos().minBlockY() + localY);
                        break;
                    }
                }
            }
        }
        if (highest == Integer.MIN_VALUE) {
            highestBlocks.remove(key);
        } else {
            highestBlocks.put(key, highest);
        }
    }

    private Collection<LoadedCube> loadedCubes() {
        return cubes.values();
    }

    private void save(LoadedCube cube, boolean force, boolean wait) throws IOException {
        LoadContext context = loadContexts.get(cube.pos());
        if (context != null && !context.full) return;
        boolean wasDirty = cube.takeDirty();
        if (!force && !wasDirty) {
            return;
        }
        try {
            var write = ioScheduler.write(cube.pos(), CubeRecordCodec.encode(cube, world));
            taskScheduler.trackSave(cube.pos(), write);
            write.whenComplete((ignored, exception) -> {
                if (exception != null && wasDirty) cube.restoreDirty();
            });
            if (wait) write.join();
        } catch (CompletionException exception) {
            if (wasDirty) {
                cube.restoreDirty();
            }
            throw new IOException("Cannot save cube " + cube.pos(), exception.getCause());
        } catch (IOException exception) {
            if (wasDirty) cube.restoreDirty();
            throw exception;
        } catch (RuntimeException exception) {
            if (wasDirty) cube.restoreDirty();
            throw exception;
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (LoadedCube cube : List.copyOf(loadedCubes())) {
            try {
                save(cube, false, false);
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        taskScheduler.close();
        try {
            ioScheduler.close();
        } catch (IOException exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        }
        try {
            storage.close();
        } catch (IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        columns.clear();
        cubes.clear();
        loadContexts.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private record ColumnPos(int x, int z) {
    }

    private record BlockColumnPos(int x, int z) {
    }

    private static final class LoadContext {
        private final LoadedCube cube;
        private final byte[] payload;
        private final long epoch;
        private boolean hasSavedLight;
        private boolean lightQueued;
        private boolean committing;
        private boolean full;

        private LoadContext(LoadedCube cube, byte[] payload, long epoch) {
            this.cube = cube;
            this.payload = payload;
            this.epoch = epoch;
        }
    }

    /** Re-evaluates only loaded cells in one sparse vertical block column. */
    private void queueLoadedSkyColumn(int blockX, int blockZ) {
        CubeColumn<LoadedCube> column = columns.get(new ColumnPos(
                Math.floorDiv(blockX, CubePos.SIZE), Math.floorDiv(blockZ, CubePos.SIZE)));
        if (column == null) return;
        for (LoadedCube cube : column.loaded()) {
            int minY = cube.pos().minBlockY();
            for (int localY = 0; localY < CubePos.SIZE; localY++) {
                lightEngine.queueBlock(blockX, minY + localY, blockZ);
            }
        }
    }

    record BlockChange(BlockState previous, Set<CubePos> changedLight) {
    }

    private final class LightAccess implements SparseCubeLightEngine.Access {
        @Override
        public boolean managed(int x, int y, int z) {
            return loadedCube(CubePos.fromBlock(x, y, z)) != null;
        }

        @Override
        public int emitted(int x, int y, int z) {
            return state(x, y, z).getLuminance();
        }

        @Override
        public int opacity(int x, int y, int z) {
            return Math.max(1, state(x, y, z).getOpacity());
        }

        @Override
        public boolean skySource(int x, int y, int z) {
            if (!world.getDimension().hasSkyLight() || opacity(x, y, z) >= 15) return false;
            Integer cubicHighest = highestBlocks.get(new BlockColumnPos(x, z));
            // Managed cells are outside the vanilla height band.  Below the
            // band they can never be above the vanilla heightmap; above it they
            // always are. Avoid a synchronous vanilla chunk/heightmap lookup
            // for every light node (and every one of its retries).
            if (y < world.getBottomY()) return false;
            if (y > world.getTopYInclusive()) return cubicHighest == null || y > cubicHighest;
            int vanillaHighest = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z) - 1;
            int highest = cubicHighest == null ? vanillaHighest : Math.max(vanillaHighest, cubicHighest);
            return y > highest;
        }

        @Override
        public int block(int x, int y, int z) {
            LoadedCube cube = loadedCube(CubePos.fromBlock(x, y, z));
            if (cube != null) return cube.light().workingBlock(local(x), local(y), local(z));
            if (y >= world.getBottomY() && y <= world.getTopYInclusive()) {
                return world.getLightingProvider().get(net.minecraft.world.LightType.BLOCK)
                        .getLightLevel(new BlockPos(x, y, z));
            }
            return 0;
        }

        @Override
        public int sky(int x, int y, int z) {
            LoadedCube cube = loadedCube(CubePos.fromBlock(x, y, z));
            if (cube != null) return cube.light().workingSky(local(x), local(y), local(z));
            if (y >= world.getBottomY() && y <= world.getTopYInclusive()) {
                return world.getLightingProvider().get(net.minecraft.world.LightType.SKY)
                        .getLightLevel(new BlockPos(x, y, z));
            }
            return 0;
        }

        @Override
        public boolean setBlock(int x, int y, int z, int value) {
            LoadedCube cube = loadedCube(CubePos.fromBlock(x, y, z));
            return cube != null && cube.light().setWorkingBlock(local(x), local(y), local(z), value);
        }

        @Override
        public boolean setSky(int x, int y, int z, int value) {
            LoadedCube cube = loadedCube(CubePos.fromBlock(x, y, z));
            return cube != null && cube.light().setWorkingSky(local(x), local(y), local(z), value);
        }

        @Override
        public void publish(CubePos pos) {
            LoadedCube cube = loadedCube(pos);
            if (cube != null) cube.publishLight();
        }

        private BlockState state(int x, int y, int z) {
            LoadedCube cube = loadedCube(CubePos.fromBlock(x, y, z));
            return cube == null ? net.minecraft.block.Blocks.VOID_AIR.getDefaultState()
                    : cube.section().getBlockState(local(x), local(y), local(z));
        }
    }

    private static int local(int coordinate) {
        return Math.floorMod(coordinate, CubePos.SIZE);
    }
}
