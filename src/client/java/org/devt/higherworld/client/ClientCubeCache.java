package org.devt.higherworld.client;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import net.minecraft.block.entity.BlockEntity;
import org.devt.higherworld.storage.CubePos;
import org.devt.higherworld.world.CubeLightData;
import org.devt.higherworld.world.CubeRecordCodec;
import org.devt.higherworld.world.CubeRevisionGate;
import org.devt.higherworld.world.SparseCubeLightEngine;

/** Sparse client-side mirror of the cubes sent by the server. */
public final class ClientCubeCache {
    private static final ConcurrentMap<CubePos, CubeEntry> CUBES = new ConcurrentHashMap<>();
    private static volatile ClientWorld owner;
    private static final ConcurrentMap<BlockPos, BlockEntity> BLOCK_ENTITIES = new ConcurrentHashMap<>();
    private static final SparseCubeLightEngine LIGHT_ENGINE = new SparseCubeLightEngine(new ClientLightAccess());

    private ClientCubeCache() {
    }

    public static void put(ClientWorld world, CubePos pos, byte[] payload) {
        put(world, pos, 0L, payload);
    }

    public static void put(ClientWorld world, CubePos pos, long revision, byte[] payload) {
        ensureOwner(world);
        if (!pos.isBlockRangeRepresentable()) return;
        CubeEntry current = CUBES.get(pos);
        if (current != null && !CubeRevisionGate.snapshot(current.revision(), revision).accepted()) {
            return;
        }
        ChunkSection section = new ChunkSection(world.getPalettesFactory());
        CubeRecordCodec.DecodedCube decoded;
        try {
            decoded = CubeRecordCodec.decode(payload, section, world);
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Invalid cube payload for " + pos, exception);
        }
        CubeEntry replacement = new CubeEntry(section, new CubeLightData(decoded.light()), revision);
        CubeEntry accepted = CUBES.compute(pos, (ignored, existing) ->
                existing != null && !CubeRevisionGate.snapshot(existing.revision(), revision).accepted()
                        ? existing : replacement);
        if (accepted != replacement) return;
        removeBlockEntities(pos);
        for (BlockEntity blockEntity : decoded.blockEntities()) {
            BLOCK_ENTITIES.put(blockEntity.getPos().toImmutable(), blockEntity);
        }
        LIGHT_ENGINE.queueCube(pos, !decoded.hasLight());
        LIGHT_ENGINE.propagate(1_000_000);
        scheduleRenderNeighborhood(pos);
    }

    public static void unload(ClientWorld world, CubePos pos) {
        unload(world, pos, 0L);
    }

    /** Removes a cube unless a newer authoritative snapshot is already present. */
    public static void unload(ClientWorld world, CubePos pos, long revision) {
        ensureOwner(world);
        if (!pos.isBlockRangeRepresentable()) return;
        AtomicBoolean removed = new AtomicBoolean();
        CUBES.compute(pos, (ignored, current) -> {
            if (current == null || !CubeRevisionGate.removal(current.revision(), revision).accepted()) {
                return current;
            }
            removed.set(true);
            return null;
        });
        if (!removed.get()) return;
        removeBlockEntities(pos);
        queueLoadedNeighbours(pos);
        LIGHT_ENGINE.propagate(250_000);
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
        // Prediction and block updates are valid only for an already streamed
        // cube.  Creating a client-only placeholder here resurrects a cube after
        // an unload packet (or lets a delayed packet leak blocks outside the view).
        CubeEntry entry = CUBES.get(cubePos);
        if (entry == null) return false;
        ChunkSection section = entry.section();
        BlockState previous = section.setBlockState(local(pos.getX()), local(pos.getY()), local(pos.getZ()), state);
        if (previous != state) {
            // Keep the server revision authoritative.  Local prediction changes
            // the section immediately, but must not make a later authoritative
            // packet look stale (or make a matching block update get rejected).
            LIGHT_ENGINE.queueBlock(pos.getX(), pos.getY(), pos.getZ());
            queueLoadedSkyColumn(pos.getX(), pos.getZ());
            LIGHT_ENGINE.propagate(250_000);
            scheduleRenderNeighborhood(cubePos);
            return true;
        }
        return false;
    }

    /**
     * Checks a server block update before it enters ClientWorld's normal update
     * path.  Revision zero is retained as a compatibility value for packets
     * produced by older servers; revisioned packets cannot overwrite a newer
     * full-cube snapshot after a rapid unload/reload.
     */
    public static boolean acceptsBlockUpdate(ClientWorld world, BlockPos pos, long revision) {
        ensureOwner(world);
        CubePos cubePos = CubePos.fromBlock(pos.getX(), pos.getY(), pos.getZ());
        AtomicBoolean accepted = new AtomicBoolean();
        CUBES.computeIfPresent(cubePos, (ignored, entry) -> {
            CubeRevisionGate.Decision decision = CubeRevisionGate.delta(entry.revision(), revision);
            accepted.set(decision.accepted());
            if (!decision.accepted() || decision.revision() == entry.revision()) return entry;
            return new CubeEntry(entry.section(), entry.light(), decision.revision());
        });
        return accepted.get();
    }

    public static void clear() {
        CUBES.clear();
        BLOCK_ENTITIES.clear();
        LIGHT_ENGINE.clear();
        owner = null;
    }

    public static int loadedCubeCount() {
        return CUBES.size();
    }

    /**
     * Drains a bounded slice of cross-cube propagation every client tick.  A
     * large view can enqueue more nodes than the packet handler should process
     * synchronously; without this drain, the remaining queue would only be
     * revisited when another cube packet happened to arrive.
     */
    public static void tickLighting() {
        if (owner != null) LIGHT_ENGINE.propagate(50_000);
    }

    public static ChunkSection getSection(ClientWorld world, int sectionX, int sectionY, int sectionZ) {
        return section(world, new CubePos(sectionX, sectionY, sectionZ));
    }

    public static BlockEntity getBlockEntity(ClientWorld world, BlockPos pos) {
        ensureOwner(world);
        return BLOCK_ENTITIES.get(pos);
    }

    public static int getLightLevel(ClientWorld world, LightType type, BlockPos pos) {
        ensureOwner(world);
        CubeEntry entry = CUBES.get(CubePos.fromBlock(pos.getX(), pos.getY(), pos.getZ()));
        if (entry == null) return 0;
        return type == LightType.BLOCK
                ? entry.light().block(local(pos.getX()), local(pos.getY()), local(pos.getZ()))
                : entry.light().sky(local(pos.getX()), local(pos.getY()), local(pos.getZ()));
    }

    public static Collection<BlockEntity> getBlockEntities(ClientWorld world, CubePos pos) {
        ensureOwner(world);
        int minX = pos.minBlockX();
        int minY = pos.minBlockY();
        int minZ = pos.minBlockZ();
        return BLOCK_ENTITIES.entrySet().stream()
                .filter(entry -> (long) entry.getKey().getX() >= minX && entry.getKey().getX() < (long) minX + 16
                        && (long) entry.getKey().getY() >= minY && entry.getKey().getY() < (long) minY + 16
                        && (long) entry.getKey().getZ() >= minZ && entry.getKey().getZ() < (long) minZ + 16)
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
                    LIGHT_ENGINE.clear();
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
            if (pos.x() != Integer.MIN_VALUE) {
                client.worldRenderer.scheduleChunkRender(pos.x() - 1, pos.y(), pos.z());
            }
            if (pos.x() != Integer.MAX_VALUE) {
                client.worldRenderer.scheduleChunkRender(pos.x() + 1, pos.y(), pos.z());
            }
            if (pos.y() != Integer.MIN_VALUE) {
                client.worldRenderer.scheduleChunkRender(pos.x(), pos.y() - 1, pos.z());
            }
            if (pos.y() != Integer.MAX_VALUE) {
                client.worldRenderer.scheduleChunkRender(pos.x(), pos.y() + 1, pos.z());
            }
            if (pos.z() != Integer.MIN_VALUE) {
                client.worldRenderer.scheduleChunkRender(pos.x(), pos.y(), pos.z() - 1);
            }
            if (pos.z() != Integer.MAX_VALUE) {
                client.worldRenderer.scheduleChunkRender(pos.x(), pos.y(), pos.z() + 1);
            }
            client.worldRenderer.scheduleTerrainUpdate();
        }
    }

    private static void removeBlockEntities(CubePos pos) {
        int minX = pos.minBlockX();
        int minY = pos.minBlockY();
        int minZ = pos.minBlockZ();
        BLOCK_ENTITIES.keySet().removeIf(blockPos -> (long) blockPos.getX() >= minX
                && blockPos.getX() < (long) minX + 16
                && (long) blockPos.getY() >= minY && blockPos.getY() < (long) minY + 16
                && (long) blockPos.getZ() >= minZ && blockPos.getZ() < (long) minZ + 16);
    }

    private static void queueLoadedNeighbours(CubePos pos) {
        int[][] directions = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
        for (int[] direction : directions) {
            long x = (long) pos.x() + direction[0];
            long y = (long) pos.y() + direction[1];
            long z = (long) pos.z() + direction[2];
            if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE
                    || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE
                    || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) continue;
            CubePos neighbour = new CubePos((int) x, (int) y, (int) z);
            if (CUBES.containsKey(neighbour)) LIGHT_ENGINE.queueCube(neighbour, false);
        }
    }

    private static void queueLoadedSkyColumn(int blockX, int blockZ) {
        int cubeX = Math.floorDiv(blockX, CubePos.SIZE);
        int cubeZ = Math.floorDiv(blockZ, CubePos.SIZE);
        for (CubePos pos : CUBES.keySet()) {
            if (pos.x() != cubeX || pos.z() != cubeZ) continue;
            int minY = pos.minBlockY();
            for (int localY = 0; localY < CubePos.SIZE; localY++) {
                LIGHT_ENGINE.queueBlock(blockX, minY + localY, blockZ);
            }
        }
    }

    private static int local(int coordinate) {
        return Math.floorMod(coordinate, CubePos.SIZE);
    }

    private record CubeEntry(ChunkSection section, CubeLightData light, long revision) {}

    private static final class ClientLightAccess implements SparseCubeLightEngine.Access {
        @Override
        public boolean managed(int x, int y, int z) {
            return CUBES.containsKey(CubePos.fromBlock(x, y, z));
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
            ClientWorld world = owner;
            if (world == null || !world.getDimension().hasSkyLight() || opacity(x, y, z) >= 15) return false;
            Integer cubicHighest = highestBlockY(world, x, z);
            int vanillaHighest = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z) - 1;
            return y > (cubicHighest == null ? vanillaHighest : Math.max(vanillaHighest, cubicHighest));
        }

        @Override
        public int block(int x, int y, int z) {
            CubeEntry entry = CUBES.get(CubePos.fromBlock(x, y, z));
            if (entry != null) return entry.light().workingBlock(local(x), local(y), local(z));
            ClientWorld world = owner;
            if (world != null && y >= world.getBottomY() && y <= world.getTopYInclusive()) {
                return world.getLightingProvider().get(LightType.BLOCK).getLightLevel(new BlockPos(x, y, z));
            }
            return 0;
        }

        @Override
        public int sky(int x, int y, int z) {
            CubeEntry entry = CUBES.get(CubePos.fromBlock(x, y, z));
            if (entry != null) return entry.light().workingSky(local(x), local(y), local(z));
            ClientWorld world = owner;
            if (world != null && y >= world.getBottomY() && y <= world.getTopYInclusive()) {
                return world.getLightingProvider().get(LightType.SKY).getLightLevel(new BlockPos(x, y, z));
            }
            return 0;
        }

        @Override
        public boolean setBlock(int x, int y, int z, int value) {
            CubeEntry entry = CUBES.get(CubePos.fromBlock(x, y, z));
            return entry != null && entry.light().setWorkingBlock(local(x), local(y), local(z), value);
        }

        @Override
        public boolean setSky(int x, int y, int z, int value) {
            CubeEntry entry = CUBES.get(CubePos.fromBlock(x, y, z));
            return entry != null && entry.light().setWorkingSky(local(x), local(y), local(z), value);
        }

        @Override
        public void publish(CubePos pos) {
            CubeEntry entry = CUBES.get(pos);
            if (entry != null && entry.light().publish()) scheduleRenderNeighborhood(pos);
        }

        private static BlockState state(int x, int y, int z) {
            CubeEntry entry = CUBES.get(CubePos.fromBlock(x, y, z));
            return entry == null ? Blocks.VOID_AIR.getDefaultState()
                    : entry.section().getBlockState(local(x), local(y), local(z));
        }
    }
}
