package org.devt.higherworld.world;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.rule.GameRules;
import org.devt.higherworld.Higherworld;
import org.devt.higherworld.storage.CubePos;
import org.devt.higherworld.storage.CubeStorage;

/** Runtime cube cache and persistence boundary for one server dimension. */
final class CubicWorldState implements AutoCloseable {
    private static final byte[] EMPTY_PAYLOAD = new byte[0];
    private final ServerWorld world;
    private final CubeStorage storage;
    private final boolean generateInfinitelyDownward;
    private final boolean generateStructures;
    private final StructureGenerationSettings structureSettings;
    private final CustomWorldSettings customWorldSettings;
    private final ConcurrentMap<ColumnPos, CubeColumn<LoadedCube>> columns = new ConcurrentHashMap<>();
    private final ConcurrentMap<BlockColumnPos, Integer> highestBlocks = new ConcurrentHashMap<>();

    CubicWorldState(
            ServerWorld world, CubeStorage storage, boolean generateStructures,
            StructureGenerationSettings structureSettings,
            CustomWorldSettings customWorldSettings) {
        this.world = world;
        this.storage = storage;
        this.generateInfinitelyDownward = CubicWorldManager.generatesInfinitelyDownward(world);
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

    BlockState setBlockState(BlockPos pos, BlockState state) throws IOException {
        LoadedCube cube = cube(pos);
        BlockState previous = cube.setBlockState(pos, state);
        if (previous != state) {
            updateHeight(pos, state);
        }
        return previous;
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

        return loadOrRegister(pos, storage.read(pos).orElse(null));
    }

    int loadedCubeCount() {
        return columns.values().stream().mapToInt(CubeColumn::size).sum();
    }

    Integer highestBlockY(int blockX, int blockZ) {
        return highestBlocks.get(new BlockColumnPos(blockX, blockZ));
    }

    byte[] cubePayload(CubePos pos) throws IOException {
        CubeColumn<LoadedCube> column = columns.get(new ColumnPos(pos.x(), pos.z()));
        LoadedCube loaded = column == null ? null : column.get(pos.y());
        if (loaded != null) {
            return CubeRecordCodec.encode(loaded, world);
        }

        // A missing sparse cube is implicitly air unless this world's selected
        // preset asks HigherWorld to lazily generate the terrain below it.
        Optional<byte[]> stored = storage.read(pos);
        if (stored.isEmpty()) {
            if (!shouldGenerate(pos)) {
                return EMPTY_PAYLOAD;
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
                save(cube, false);
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    void evictExcept(Set<CubePos> retained) throws IOException {
        IOException failure = null;
        for (Map.Entry<ColumnPos, CubeColumn<LoadedCube>> entry : columns.entrySet()) {
            CubeColumn<LoadedCube> column = entry.getValue();
            for (LoadedCube cube : List.copyOf(column.loaded())) {
                if (!isOutsideVanillaHeight(cube.pos()) || retained.contains(cube.pos())) {
                    continue;
                }
                try {
                    save(cube, false);
                    column.remove(cube.pos().y());
                    removeIndexedHeights(cube);
                } catch (IOException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
            if (column.isEmpty()) {
                columns.remove(entry.getKey(), column);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    void tick() {
        int randomTickSpeed = world.getGameRules().getValue(GameRules.RANDOM_TICK_SPEED);
        for (CubeColumn<LoadedCube> column : columns.values()) {
            column.forEach(cube -> {
                cube.tickBlockEntities(world);
                cube.tickRandomly(world, randomTickSpeed);
            });
        }
    }

    boolean isOutsideVanillaHeight(CubePos pos) {
        return pos.y() < world.getBottomSectionCoord() || pos.y() >= world.getTopSectionCoord();
    }

    private LoadedCube loadOrRegister(CubePos pos, byte[] payload) throws IOException {
        ColumnPos columnPos = new ColumnPos(pos.x(), pos.z());
        while (true) {
            CubeColumn<LoadedCube> column = columns.computeIfAbsent(
                    columnPos, ignored -> new CubeColumn<>());
            LoadedCube loaded = column.get(pos.y());
            if (loaded != null) {
                return loaded;
            }

            // Register the placeholder before generation. Feature code and block
            // entities can call World#getBlockState while this cube is loading.
            LoadedCube created = new LoadedCube(
                    pos, new ChunkSection(world.getPalettesFactory()));
            try {
                column.put(pos.y(), created);
            } catch (IllegalArgumentException raced) {
                continue;
            }

            try {
                load(created, payload);
                return created;
            } catch (IOException | RuntimeException | Error exception) {
                column.remove(pos.y(), created);
                if (column.isEmpty()) {
                    columns.remove(columnPos, column);
                }
                throw exception;
            }
        }
    }

    private void load(LoadedCube cube, byte[] payload) throws IOException {
        CubePos pos = cube.pos();
        if (payload != null) {
            cube.setGenerationVersion(CubeRecordCodec.generationVersion(payload));
            for (BlockEntity blockEntity : CubeRecordCodec.decode(payload, cube.section(), world)) {
                cube.putLoadedBlockEntity(blockEntity);
            }
            if (shouldGenerate(pos) && InfiniteDownwardGenerator.upgradeLegacyTerrain(
                    world, cube, effectiveStructureSettings())) {
                Higherworld.LOGGER.debug("Upgraded untouched generated terrain cube {}", pos);
            }
        } else if (shouldGenerate(pos)) {
            InfiniteDownwardGenerator.generate(world, cube, effectiveStructureSettings());
        }
        indexHeights(cube);
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
        return columns.values().stream()
                .map(CubeColumn::loaded)
                .flatMap(Collection::stream)
                .toList();
    }

    private void save(LoadedCube cube, boolean force) throws IOException {
        boolean wasDirty = cube.takeDirty();
        if (!force && !wasDirty) {
            return;
        }
        try {
            storage.write(cube.pos(), CubeRecordCodec.encode(cube, world));
        } catch (IOException | RuntimeException exception) {
            if (wasDirty) {
                cube.restoreDirty();
            }
            throw exception;
        }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (LoadedCube cube : List.copyOf(loadedCubes())) {
            try {
                save(cube, false);
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
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
        if (failure != null) {
            throw failure;
        }
    }

    private record ColumnPos(int x, int z) {
    }

    private record BlockColumnPos(int x, int z) {
    }
}
