package org.devt.higherworld.world;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CompletionException;

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
    private final ConcurrentMap<BlockColumnPos, Integer> highestBlocks = new ConcurrentHashMap<>();
    private final SparseCubeLightEngine lightEngine = new SparseCubeLightEngine(new LightAccess());

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
        return cube(pos).getBlockState(pos);
    }

    FluidState getFluidState(BlockPos pos) throws IOException {
        return cube(pos).getFluidState(pos);
    }

    BlockChange setBlockState(BlockPos pos, BlockState state) throws IOException {
        LoadedCube cube = cube(pos);
        BlockState previous = cube.setBlockState(pos, state);
        Set<CubePos> changedLight = Set.of();
        if (previous != state) {
            updateHeight(pos, state);
            lightEngine.queueBlock(pos.getX(), pos.getY(), pos.getZ());
            queueLoadedSkyColumn(pos.getX(), pos.getZ());
            changedLight = lightEngine.propagate(250_000).changedCubes();
        }
        return new BlockChange(previous, changedLight);
    }

    BlockEntity getBlockEntity(BlockPos pos) throws IOException {
        return cube(pos).getBlockEntity(pos);
    }

    void putBlockEntity(BlockEntity blockEntity) throws IOException {
        blockEntity.setWorld(world);
        cube(blockEntity.getPos()).putBlockEntity(blockEntity);
    }

    void removeBlockEntity(BlockPos pos) throws IOException {
        cube(pos).removeBlockEntity(pos);
    }

    void markDirty(BlockPos pos) throws IOException {
        cube(pos).markDirty();
    }

    LoadedCube cube(BlockPos blockPos) throws IOException {
        return cube(CubePos.fromBlock(blockPos.getX(), blockPos.getY(), blockPos.getZ()));
    }

    LoadedCube cube(CubePos pos) throws IOException {
        ColumnPos columnPos = new ColumnPos(pos.x(), pos.z());
        CubeColumn<LoadedCube> column = columns.get(columnPos);
        LoadedCube loaded = column == null ? null : column.get(pos.y());
        if (loaded != null) {
            return loaded;
        }

        taskScheduler.request(pos, CubeStatus.FULL, SYNCHRONOUS_IO_PRIORITY);
        return loadOrRegister(pos, ioScheduler.read(pos, SYNCHRONOUS_IO_PRIORITY).orElse(null));
    }

    int loadedCubeCount() {
        return cubes.size();
    }

    long cubeRevision(CubePos pos) {
        LoadedCube loaded = loadedCube(pos);
        return loaded == null ? 0L : loaded.revision();
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
            return CubeRecordCodec.encode(loaded, world);
        }

        Optional<byte[]> stored = ioScheduler.read(pos, SYNCHRONOUS_IO_PRIORITY);
        return finishCubePayload(pos, stored);
    }

    void prefetchCubePayload(CubePos pos, int priority) {
        if (loadedCube(pos) == null) {
            taskScheduler.request(pos, CubeStatus.FULL, priority);
        }
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
            return CubeRecordCodec.encode(loaded, world);
        }

        CubeIoScheduler.ReadResult result = ioScheduler.poll(pos, priority);
        if (!result.ready()) {
            return null;
        }
        return finishCubePayload(pos, result.payload(), priority, true);
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
                return EMPTY_PAYLOAD;
            }
            if (customWorld) {
                CubeHolder holder = taskScheduler.request(pos, CubeStatus.FULL, priority);
                var terrain = taskScheduler.prepareTerrain(holder, world.getSeed(), customWorldSettings, priority);
                if (allowPending && !terrain.isDone()) {
                    return null;
                }
            }
            // We already know storage has no record. Construct directly instead
            // of making cube(pos) perform the same disk lookup a second time.
            LoadedCube generated = loadOrRegister(pos, null);
            return CubeRecordCodec.encode(generated, world);
        }

        // Real stored cubes still enter the runtime so their block entities and
        // random-ticking blocks continue to simulate while watched.
        LoadedCube created = loadOrRegister(pos, stored.get());
        return created.isDirty() ? CubeRecordCodec.encode(created, world) : stored.get();
    }

    void flushDirty() throws IOException {
        IOException failure = null;
        for (LoadedCube cube : loadedCubes()) {
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
        try {
            ioScheduler.flushWrites();
        } catch (IOException exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        }
        if (failure != null) {
            throw failure;
        }
    }

    void evictExcept(Set<CubePos> retained) throws IOException {
        IOException failure = null;
        boolean removedAny = false;
        for (Map.Entry<ColumnPos, CubeColumn<LoadedCube>> entry : columns.entrySet()) {
            CubeColumn<LoadedCube> column = entry.getValue();
            boolean removedFromColumn = false;
            for (LoadedCube cube : List.copyOf(column.loaded())) {
                if (!isOutsideVanillaHeight(cube.pos()) || retained.contains(cube.pos())) {
                    continue;
                }
                try {
                    save(cube, false, true);
                    column.remove(cube.pos().y());
                    cubes.remove(cube.pos(), cube);
                    removeIndexedHeights(cube);
                    removedAny = true;
                    removedFromColumn = true;
                } catch (IOException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
            if (removedFromColumn && !column.isEmpty()) {
                column.forEach(remaining -> lightEngine.queueCube(remaining.pos(), true));
            }
            if (column.isEmpty()) {
                columns.remove(entry.getKey(), column);
            }
        }
        if (removedAny) lightEngine.propagate(1_000_000);
        if (failure != null) {
            throw failure;
        }
    }

    void tick() {
        SparseCubeLightEngine.Result lightWork = lightEngine.propagate(50_000);
        if (!lightWork.changedCubes().isEmpty()) {
            CubeWatchManager.broadcastCubeUpdates(world, lightWork.changedCubes());
        }
        int randomTickSpeed = world.getGameRules().getValue(GameRules.RANDOM_TICK_SPEED);
        for (CubeColumn<LoadedCube> column : columns.values()) {
            column.forEach(cube -> {
                cube.tickBlockEntities(world);
                cube.tickRandomly(world, randomTickSpeed);
            });
        }
    }

    /** Commits completed worker terrain during a bounded mid-tick slice. */
    void advanceReadyTasks(long budgetNanos) {
        long deadline = System.nanoTime() + Math.max(0L, budgetNanos);
        for (CubeHolder holder : taskScheduler.readyTerrain(32)) {
            if (System.nanoTime() >= deadline) break;
            if (!taskScheduler.isTicketed(holder.pos()) || loadedCube(holder.pos()) != null) continue;
            try {
                loadOrRegister(holder.pos(), null);
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

    private LoadedCube loadOrRegister(CubePos pos, byte[] payload) throws IOException {
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

            try {
                load(created, payload);
                return created;
            } catch (IOException | RuntimeException | Error exception) {
                column.remove(pos.y(), created);
                cubes.remove(pos, created);
                if (column.isEmpty()) {
                    columns.remove(columnPos, column);
                }
                throw exception;
            }
        }
    }

    private void load(LoadedCube cube, byte[] payload) throws IOException {
        CubePos pos = cube.pos();
        CubeHolder holder = taskScheduler.holder(pos);
        holder.advance(CubeStatus.IO_READY);
        boolean hasSavedLight = false;
        if (payload != null) {
            cube.setGenerationVersion(CubeRecordCodec.generationVersion(payload));
            CubeRecordCodec.DecodedCube decoded = CubeRecordCodec.decode(payload, cube.section(), world);
            for (BlockEntity blockEntity : decoded.blockEntities()) {
                cube.putLoadedBlockEntity(blockEntity);
            }
            if (decoded.hasLight()) {
                cube.light().load(decoded.light());
                hasSavedLight = true;
            }
            // A stored custom cube is authoritative: it may contain player edits and
            // its terrain must never be passed through the legacy upgrade pipeline.
            if (!customWorld && shouldGenerate(pos) && InfiniteDownwardGenerator.upgradeLegacyTerrain(
                    world, cube, effectiveStructureSettings())) {
                Higherworld.LOGGER.debug("Upgraded untouched generated terrain cube {}", pos);
            }
        } else if (shouldGenerate(pos)) {
            if (customWorld) {
                try {
                    CustomCubeGenerator.TerrainSnapshot snapshot = taskScheduler.prepareTerrain(
                            holder, world.getSeed(), customWorldSettings,
                            SYNCHRONOUS_IO_PRIORITY).join();
                    try (CubeSpatialLock.Scope ignored = generationLocks.lock(
                            pos, CubeStatus.FEATURES.dependencyRadius())) {
                        CustomCubeGenerator.applyTerrain(cube, snapshot);
                        holder.advance(CubeStatus.TERRAIN);
                        CustomCubeGenerator.finishGeneration(world, cube, customWorldSettings,
                                structureSettings, generateStructures);
                        holder.advance(CubeStatus.FEATURES);
                    }
                } catch (CompletionException exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof RuntimeException runtime) throw runtime;
                    throw new IOException("Cannot prepare terrain for cube " + pos, cause);
                }
            } else {
                try (CubeSpatialLock.Scope ignored = generationLocks.lock(
                        pos, CubeStatus.FEATURES.dependencyRadius())) {
                    InfiniteDownwardGenerator.generate(world, cube, effectiveStructureSettings());
                    holder.advance(CubeStatus.FEATURES);
                }
            }
        }
        indexHeights(cube);
        lightEngine.queueCube(pos, !hasSavedLight);
        lightEngine.propagate(1_000_000);
        holder.advance(CubeStatus.LIGHT);
        holder.complete(cube);
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
        boolean wasDirty = cube.takeDirty();
        if (!force && !wasDirty) {
            return;
        }
        try {
            var write = ioScheduler.write(cube.pos(), CubeRecordCodec.encode(cube, world));
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
        taskScheduler.close();
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
        if (failure != null) {
            throw failure;
        }
    }

    private record ColumnPos(int x, int z) {
    }

    private record BlockColumnPos(int x, int z) {
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
