package org.devt.higherworld.client;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.block.entity.BlockEntity;
import org.devt.higherworld.storage.CubePos;
import org.devt.higherworld.world.CubeColumn;
import org.devt.higherworld.world.CubeRecordCodec;

/** Sparse client-side mirror of the cubes sent by the server. */
public final class ClientCubeCache {
    private static final ConcurrentMap<ColumnPos, CubeColumn<ChunkSection>> COLUMNS = new ConcurrentHashMap<>();
    private static volatile ClientWorld owner;
    private static final ConcurrentMap<BlockPos, BlockEntity> BLOCK_ENTITIES = new ConcurrentHashMap<>();

    private ClientCubeCache() {
    }

    public static void put(ClientWorld world, CubePos pos, byte[] payload) {
        ensureOwner(world);
        ChunkSection section = new ChunkSection(world.getPalettesFactory());
        List<BlockEntity> blockEntities;
        try {
            blockEntities = CubeRecordCodec.decode(payload, section, world);
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Invalid cube payload for " + pos, exception);
        }
        CubeColumn<ChunkSection> column = COLUMNS.computeIfAbsent(
                new ColumnPos(pos.x(), pos.z()), ignored -> new CubeColumn<>());
        column.remove(pos.y());
        column.put(pos.y(), section);
        removeBlockEntities(pos);
        for (BlockEntity blockEntity : blockEntities) {
            BLOCK_ENTITIES.put(blockEntity.getPos().toImmutable(), blockEntity);
        }
        scheduleRenderNeighborhood(pos);
    }

    public static void unload(ClientWorld world, CubePos pos) {
        ensureOwner(world);
        ColumnPos columnPos = new ColumnPos(pos.x(), pos.z());
        CubeColumn<ChunkSection> column = COLUMNS.get(columnPos);
        if (column != null) {
            column.remove(pos.y());
            if (column.isEmpty()) {
                COLUMNS.remove(columnPos, column);
            }
        }
        removeBlockEntities(pos);
        scheduleRenderNeighborhood(pos);
    }

    public static BlockState getBlockState(ClientWorld world, BlockPos pos) {
        ChunkSection section = section(world, CubePos.fromBlock(pos.getX(), pos.getY(), pos.getZ()));
        return section == null ? Blocks.VOID_AIR.getDefaultState()
                : section.getBlockState(local(pos.getX()), local(pos.getY()), local(pos.getZ()));
    }

    public static FluidState getFluidState(ClientWorld world, BlockPos pos) {
        ChunkSection section = section(world, CubePos.fromBlock(pos.getX(), pos.getY(), pos.getZ()));
        return section == null ? Blocks.VOID_AIR.getDefaultState().getFluidState()
                : section.getFluidState(local(pos.getX()), local(pos.getY()), local(pos.getZ()));
    }

    public static boolean setBlockState(ClientWorld world, BlockPos pos, BlockState state) {
        ensureOwner(world);
        CubePos cubePos = CubePos.fromBlock(pos.getX(), pos.getY(), pos.getZ());
        CubeColumn<ChunkSection> column = COLUMNS.computeIfAbsent(
                new ColumnPos(cubePos.x(), cubePos.z()), ignored -> new CubeColumn<>());
        ChunkSection section = column.getOrCreate(cubePos.y(), ignored -> new ChunkSection(world.getPalettesFactory()));
        BlockState previous = section.setBlockState(local(pos.getX()), local(pos.getY()), local(pos.getZ()), state);
        if (previous != state) {
            scheduleRenderNeighborhood(cubePos);
            return true;
        }
        return false;
    }

    public static void clear() {
        COLUMNS.clear();
        BLOCK_ENTITIES.clear();
        owner = null;
    }

    public static int loadedCubeCount() {
        return COLUMNS.values().stream().mapToInt(CubeColumn::size).sum();
    }

    public static ChunkSection getSection(ClientWorld world, int sectionX, int sectionY, int sectionZ) {
        return section(world, new CubePos(sectionX, sectionY, sectionZ));
    }

    public static BlockEntity getBlockEntity(ClientWorld world, BlockPos pos) {
        ensureOwner(world);
        return BLOCK_ENTITIES.get(pos);
    }

    public static Collection<BlockEntity> getBlockEntities(ClientWorld world, CubePos pos) {
        ensureOwner(world);
        int minX = pos.minBlockX();
        int minY = pos.minBlockY();
        int minZ = pos.minBlockZ();
        return BLOCK_ENTITIES.entrySet().stream()
                .filter(entry -> entry.getKey().getX() >= minX && entry.getKey().getX() < minX + 16
                        && entry.getKey().getY() >= minY && entry.getKey().getY() < minY + 16
                        && entry.getKey().getZ() >= minZ && entry.getKey().getZ() < minZ + 16)
                .map(Map.Entry::getValue)
                .toList();
    }

    public static Integer highestBlockY(ClientWorld world, int blockX, int blockZ) {
        ensureOwner(world);
        CubeColumn<ChunkSection> column = COLUMNS.get(new ColumnPos(
                Math.floorDiv(blockX, CubePos.SIZE), Math.floorDiv(blockZ, CubePos.SIZE)));
        if (column == null) {
            return null;
        }
        int localX = local(blockX);
        int localZ = local(blockZ);
        Integer highest = null;
        for (Map.Entry<Integer, ChunkSection> entry : column.entries()) {
            int sectionY = entry.getKey();
            ChunkSection section = entry.getValue();
            for (int localY = CubePos.SIZE - 1; localY >= 0; localY--) {
                if (!section.getBlockState(localX, localY, localZ).isAir()) {
                    int y = Math.addExact(Math.multiplyExact(sectionY, CubePos.SIZE), localY);
                    highest = highest == null ? y : Math.max(highest, y);
                    break;
                }
            }
        }
        return highest;
    }

    private static ChunkSection section(ClientWorld world, CubePos pos) {
        ensureOwner(world);
        CubeColumn<ChunkSection> column = COLUMNS.get(new ColumnPos(pos.x(), pos.z()));
        return column == null ? null : column.get(pos.y());
    }

    private static void ensureOwner(ClientWorld world) {
        if (owner != world) {
            synchronized (ClientCubeCache.class) {
                if (owner != world) {
                    COLUMNS.clear();
                    BLOCK_ENTITIES.clear();
                    owner = world;
                }
            }
        }
    }

    /**
     * A cube mesh owns the faces on its six boundaries. When a neighboring cube
     * appears or disappears, rebuilding only the changed cube leaves the old
     * neighbor's full boundary face in the scene as a visible 16-block sheet.
     */
    private static void scheduleRenderNeighborhood(CubePos pos) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.worldRenderer != null) {
            client.worldRenderer.scheduleChunkRender(pos.x(), pos.y(), pos.z());
            client.worldRenderer.scheduleChunkRender(pos.x() - 1, pos.y(), pos.z());
            client.worldRenderer.scheduleChunkRender(pos.x() + 1, pos.y(), pos.z());
            client.worldRenderer.scheduleChunkRender(pos.x(), pos.y() - 1, pos.z());
            client.worldRenderer.scheduleChunkRender(pos.x(), pos.y() + 1, pos.z());
            client.worldRenderer.scheduleChunkRender(pos.x(), pos.y(), pos.z() - 1);
            client.worldRenderer.scheduleChunkRender(pos.x(), pos.y(), pos.z() + 1);
            client.worldRenderer.scheduleTerrainUpdate();
        }
    }

    private static void removeBlockEntities(CubePos pos) {
        int minX = pos.minBlockX();
        int minY = pos.minBlockY();
        int minZ = pos.minBlockZ();
        BLOCK_ENTITIES.keySet().removeIf(blockPos -> blockPos.getX() >= minX && blockPos.getX() < minX + 16
                && blockPos.getY() >= minY && blockPos.getY() < minY + 16
                && blockPos.getZ() >= minZ && blockPos.getZ() < minZ + 16);
    }

    private static int local(int coordinate) {
        return Math.floorMod(coordinate, CubePos.SIZE);
    }

    private record ColumnPos(int x, int z) {
    }
}
