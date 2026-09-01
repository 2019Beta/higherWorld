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
import org.devt.higherworld.world.CubeRecordCodec;

/** Sparse client-side mirror of the cubes sent by the server. */
public final class ClientCubeCache {
    private static final ConcurrentMap<CubePos, CubeEntry> CUBES = new ConcurrentHashMap<>();
    private static volatile ClientWorld owner;
    private static final ConcurrentMap<BlockPos, BlockEntity> BLOCK_ENTITIES = new ConcurrentHashMap<>();

    private ClientCubeCache() {
    }

    public static void put(ClientWorld world, CubePos pos, byte[] payload) {
        put(world, pos, 0L, payload);
    }

    public static void put(ClientWorld world, CubePos pos, long revision, byte[] payload) {
        ensureOwner(world);
        CubeEntry current = CUBES.get(pos);
        if (current != null && current.revision() > revision) {
            return;
        }
        ChunkSection section = new ChunkSection(world.getPalettesFactory());
        List<BlockEntity> blockEntities;
        try {
            blockEntities = CubeRecordCodec.decode(payload, section, world);
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Invalid cube payload for " + pos, exception);
        }
        CUBES.compute(pos, (ignored, existing) -> existing != null && existing.revision() > revision
                ? existing : new CubeEntry(section, revision));
        removeBlockEntities(pos);
        for (BlockEntity blockEntity : blockEntities) {
            BLOCK_ENTITIES.put(blockEntity.getPos().toImmutable(), blockEntity);
        }
        scheduleRenderNeighborhood(pos);
    }

    public static void unload(ClientWorld world, CubePos pos) {
        ensureOwner(world);
        CUBES.remove(pos);
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
        CubeEntry entry = CUBES.computeIfAbsent(cubePos,
                ignored -> new CubeEntry(new ChunkSection(world.getPalettesFactory()), 0L));
        ChunkSection section = entry.section();
        BlockState previous = section.setBlockState(local(pos.getX()), local(pos.getY()), local(pos.getZ()), state);
        if (previous != state) {
            CUBES.computeIfPresent(cubePos,
                    (ignored, current) -> new CubeEntry(current.section(), current.revision() + 1L));
            scheduleRenderNeighborhood(cubePos);
            return true;
        }
        return false;
    }

    public static void clear() {
        CUBES.clear();
        BLOCK_ENTITIES.clear();
        owner = null;
    }

    public static int loadedCubeCount() {
        return CUBES.size();
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
        int cubeX = Math.floorDiv(blockX, CubePos.SIZE);
        int cubeZ = Math.floorDiv(blockZ, CubePos.SIZE);
        int localX = local(blockX);
        int localZ = local(blockZ);
        Integer highest = null;
        for (Map.Entry<CubePos, CubeEntry> entry : CUBES.entrySet()) {
            if (entry.getKey().x() != cubeX || entry.getKey().z() != cubeZ) continue;
            int sectionY = entry.getKey().y();
            ChunkSection section = entry.getValue().section();
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
        CubeEntry entry = CUBES.get(pos);
        return entry == null ? null : entry.section();
    }

    private static void ensureOwner(ClientWorld world) {
        if (owner != world) {
            synchronized (ClientCubeCache.class) {
                if (owner != world) {
                    CUBES.clear();
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

    private record CubeEntry(ChunkSection section, long revision) {}
}
