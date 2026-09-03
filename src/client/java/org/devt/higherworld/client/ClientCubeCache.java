package org.devt.higherworld.client;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
    /**
     * Light propagation is deliberately drained from the client tick instead
     * of from packet handlers.  A packet can add several thousand nodes when a
     * cube has no saved light; doing an unbounded propagation here freezes the
     * render/client thread while a view is streaming in.
     */
    private static final int LIGHT_STEPS_PER_TICK = 8_192;
    private static final long LIGHT_BUDGET_NANOS = 1_000_000L;
    private static final ConcurrentMap<CubePos, CubeEntry> CUBES = new ConcurrentHashMap<>();
    /**
     * A light packet can arrive in the same network tick as the first cube
     * payload. Retain the newest one until that payload installs the cube;
     * otherwise an early eventual-light packet would be discarded and the cube
     * would remain dark until another light change happened.
     */
    private static final ConcurrentMap<CubePos, PendingLight> PENDING_LIGHTS =
            new ConcurrentHashMap<>();
    private static final int MAX_PENDING_LIGHTS = 4_096;
    /** Height of each loaded cube's 16 x 16 block columns, indexed by cube X/Z. */
    private static final ConcurrentMap<CubeColumnPos, Map<Integer, HeightIndex>> CUBE_HEIGHTS =
            new ConcurrentHashMap<>();
    /** Highest non-air block for an absolute block X/Z column. */
    private static final ConcurrentMap<BlockColumnPos, Integer> HIGHEST_BLOCKS =
            new ConcurrentHashMap<>();
    private static volatile ClientWorld owner;
    private static final ConcurrentMap<BlockPos, BlockEntity> BLOCK_ENTITIES = new ConcurrentHashMap<>();
    private static final SparseCubeLightEngine LIGHT_ENGINE = new SparseCubeLightEngine(new ClientLightAccess());
    /**
     * Render invalidations are produced only on the client thread. Keep them in
     * one tick-local set so a burst of cube packets, block updates and light
     * publications rebuilds each affected section at most once.
     */
    private static final Set<CubePos> PENDING_RENDER_CUBES = new HashSet<>();

    private ClientCubeCache() {
    }

    public static void put(ClientWorld world, CubePos pos, byte[] payload) {
        put(world, pos, 0L, payload);
    }

    public static void put(ClientWorld world, CubePos pos, long revision, byte[] payload) {
        ensureOwner(world);
        if (!pos.isBlockRangeRepresentable()) return;
        CubeEntry observed = CUBES.get(pos);
        if (observed != null && !CubeRevisionGate.snapshot(observed.revision(), revision).accepted()) {
            return;
        }
        ChunkSection section = new ChunkSection(world.getPalettesFactory());
        CubeRecordCodec.DecodedCube decoded;
        try {
            decoded = CubeRecordCodec.decode(payload, section, world);
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Invalid cube payload for " + pos, exception);
        }
        CubeEntry replacement = new CubeEntry(
                section, new CubeLightData(decoded.light()), revision, computeCubeHeights(pos, section));
        synchronized (ClientCubeCache.class) {
            CubeEntry current = CUBES.get(pos);
            if (current != null && !CubeRevisionGate.snapshot(current.revision(), revision).accepted()) {
                return;
            }
            if (current != null) {
                removeCubeHeightsLocked(pos);
            }
            CUBES.put(pos, replacement);
            putCubeHeightsLocked(pos, replacement.heightIndex());
            PendingLight pending = PENDING_LIGHTS.remove(pos);
            if (pending != null) {
                CubeRevisionGate.Decision decision = CubeRevisionGate.delta(
                        replacement.revision(), pending.revision());
                if (decision.accepted()) {
                    replacement.light().load(pending.light());
                    replacement = replacement.withRevision(decision.revision());
                    CUBES.put(pos, replacement);
                }
            }
        }
        removeBlockEntities(pos);
        for (BlockEntity blockEntity : decoded.blockEntities()) {
            BLOCK_ENTITIES.put(blockEntity.getPos().toImmutable(), blockEntity);
        }
        LIGHT_ENGINE.queueCube(pos, !decoded.hasLight());
        queueRenderNeighborhood(pos);
    }

    /** Applies an eventual server light snapshot without re-sending block data. */
    public static void updateLight(ClientWorld world, CubePos pos, long revision, byte[] payload) {
        ensureOwner(world);
        if (!pos.isBlockRangeRepresentable()) return;
        CubeLightData.Snapshot light;
        try {
            light = CubeLightData.decodeSnapshot(payload);
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Invalid cube light payload for " + pos, exception);
        }
        synchronized (ClientCubeCache.class) {
            CubeEntry current = CUBES.get(pos);
            if (current == null) {
                PENDING_LIGHTS.compute(pos, (ignored, existing) -> {
                    if (existing == null || CubeRevisionGate.delta(
                            existing.revision(), revision).accepted()) {
                        return new PendingLight(revision, light);
                    }
                    return existing;
                });
                trimPendingLights();
                return;
            }
            CubeRevisionGate.Decision decision = CubeRevisionGate.delta(current.revision(), revision);
            if (!decision.accepted()) return;
            current.light().load(light);
            if (decision.revision() != current.revision()) {
                CUBES.put(pos, current.withRevision(decision.revision()));
            }
        }
        // The authoritative snapshot is already installed. Queue only the
        // boundary so adjacent loaded cubes can reconcile without recreating
        // the old 4096-node initialization storm.
        LIGHT_ENGINE.queueCube(pos, false);
        queueRenderNeighborhood(pos);
    }

    public static void unload(ClientWorld world, CubePos pos) {
        unload(world, pos, 0L);
    }

    /** Removes a cube unless a newer authoritative snapshot is already present. */
    public static void unload(ClientWorld world, CubePos pos, long revision) {
        ensureOwner(world);
        if (!pos.isBlockRangeRepresentable()) return;
        boolean removed;
        synchronized (ClientCubeCache.class) {
            PENDING_LIGHTS.remove(pos);
            CubeEntry current = CUBES.get(pos);
            if (current == null || !CubeRevisionGate.removal(current.revision(), revision).accepted()) {
                return;
            }
            removeCubeHeightsLocked(pos);
            removed = CUBES.remove(pos, current);
        }
        if (!removed) return;
        removeBlockEntities(pos);
        queueLoadedNeighbours(pos);
        queueRenderNeighborhood(pos);
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
        synchronized (ClientCubeCache.class) {
            CubeEntry entry = CUBES.get(cubePos);
            if (entry == null) return false;
            ChunkSection section = entry.section();
            int localX = local(pos.getX());
            int localY = local(pos.getY());
            int localZ = local(pos.getZ());
            BlockState previous = section.setBlockState(localX, localY, localZ, state);
            if (previous == state) return false;

            // Keep the server revision authoritative.  Local prediction changes
            // the section immediately, but must not make a later authoritative
            // packet look stale (or make a matching block update get rejected).
            CubeEntry updated = entry.withBlockHeight(cubePos, localX, localY, localZ, state);
            if (updated != entry) {
                CUBES.put(cubePos, updated);
                putCubeHeightsLocked(cubePos, updated.heightIndex());
            }
        }
        LIGHT_ENGINE.queueBlock(pos.getX(), pos.getY(), pos.getZ());
        queueLoadedSkyColumn(pos.getX(), pos.getZ());
        queueRenderNeighborhood(cubePos);
        return true;
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
        synchronized (ClientCubeCache.class) {
            CubeEntry entry = CUBES.get(cubePos);
            if (entry == null) return false;
            CubeRevisionGate.Decision decision = CubeRevisionGate.delta(entry.revision(), revision);
            if (!decision.accepted()) return false;
            if (decision.revision() != entry.revision()) {
                CUBES.put(cubePos, entry.withRevision(decision.revision()));
            }
            return true;
        }
    }

    public static void clear() {
        synchronized (ClientCubeCache.class) {
            CUBES.clear();
            PENDING_LIGHTS.clear();
            CUBE_HEIGHTS.clear();
            HIGHEST_BLOCKS.clear();
            BLOCK_ENTITIES.clear();
            LIGHT_ENGINE.clear();
            PENDING_RENDER_CUBES.clear();
            owner = null;
        }
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
        if (owner == null) return;
        LIGHT_ENGINE.propagate(LIGHT_STEPS_PER_TICK, LIGHT_BUDGET_NANOS);
        flushRenderUpdates();
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
        return HIGHEST_BLOCKS.get(new BlockColumnPos(blockX, blockZ));
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
                    PENDING_LIGHTS.clear();
                    CUBE_HEIGHTS.clear();
                    HIGHEST_BLOCKS.clear();
                    BLOCK_ENTITIES.clear();
                    LIGHT_ENGINE.clear();
                    PENDING_RENDER_CUBES.clear();
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
    private static void queueRenderNeighborhood(CubePos pos) {
        PENDING_RENDER_CUBES.add(pos);
        addRepresentableRenderCube((long) pos.x() - 1L, pos.y(), pos.z());
        addRepresentableRenderCube((long) pos.x() + 1L, pos.y(), pos.z());
        addRepresentableRenderCube(pos.x(), (long) pos.y() - 1L, pos.z());
        addRepresentableRenderCube(pos.x(), (long) pos.y() + 1L, pos.z());
        addRepresentableRenderCube(pos.x(), pos.y(), (long) pos.z() - 1L);
        addRepresentableRenderCube(pos.x(), pos.y(), (long) pos.z() + 1L);
    }

    private static void addRepresentableRenderCube(long x, long y, long z) {
        if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE
                || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE
                || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) return;
        PENDING_RENDER_CUBES.add(new CubePos((int) x, (int) y, (int) z));
    }

    /** Flushes the de-duplicated invalidations once at the end of the client tick. */
    private static void flushRenderUpdates() {
        if (PENDING_RENDER_CUBES.isEmpty()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.worldRenderer == null) return;
        for (CubePos pos : PENDING_RENDER_CUBES) {
            client.worldRenderer.scheduleChunkRender(pos.x(), pos.y(), pos.z());
        }
        PENDING_RENDER_CUBES.clear();
        client.worldRenderer.scheduleTerrainUpdate();
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
        int[] loadedCubeYs;
        synchronized (ClientCubeCache.class) {
            Map<Integer, HeightIndex> heights = CUBE_HEIGHTS.get(new CubeColumnPos(cubeX, cubeZ));
            if (heights == null || heights.isEmpty()) return;
            loadedCubeYs = heights.keySet().stream().mapToInt(Integer::intValue).toArray();
        }
        for (int cubeY : loadedCubeYs) {
            int minY = new CubePos(cubeX, cubeY, cubeZ).minBlockY();
            for (int localY = 0; localY < CubePos.SIZE; localY++) {
                LIGHT_ENGINE.queueBlock(blockX, minY + localY, blockZ);
            }
        }
    }

    private static HeightIndex computeCubeHeights(CubePos pos, ChunkSection section) {
        int[] tops = new int[CubePos.SIZE * CubePos.SIZE];
        boolean[] present = new boolean[tops.length];
        int baseY = pos.minBlockY();
        for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
            for (int localX = 0; localX < CubePos.SIZE; localX++) {
                int index = localX | localZ << 4;
                for (int localY = CubePos.SIZE - 1; localY >= 0; localY--) {
                    if (!section.getBlockState(localX, localY, localZ).isAir()) {
                        tops[index] = baseY + localY;
                        present[index] = true;
                        break;
                    }
                }
            }
        }
        return new HeightIndex(tops, present);
    }

    private static void putCubeHeightsLocked(CubePos pos, HeightIndex heights) {
        CubeColumnPos column = new CubeColumnPos(pos.x(), pos.z());
        CUBE_HEIGHTS.computeIfAbsent(column, ignored -> new java.util.HashMap<>())
                .put(pos.y(), heights);
        rebuildHighestBlocksLocked(pos, CUBE_HEIGHTS.get(column));
    }

    private static void removeCubeHeightsLocked(CubePos pos) {
        CubeColumnPos column = new CubeColumnPos(pos.x(), pos.z());
        Map<Integer, HeightIndex> heights = CUBE_HEIGHTS.get(column);
        if (heights == null) return;
        heights.remove(pos.y());
        if (heights.isEmpty()) {
            CUBE_HEIGHTS.remove(column, heights);
        }
        rebuildHighestBlocksLocked(pos, heights);
    }

    private static void rebuildHighestBlocksLocked(CubePos pos, Map<Integer, HeightIndex> heights) {
        int baseX = pos.minBlockX();
        int baseZ = pos.minBlockZ();
        for (int localZ = 0; localZ < CubePos.SIZE; localZ++) {
            for (int localX = 0; localX < CubePos.SIZE; localX++) {
                int index = localX | localZ << 4;
                boolean found = false;
                int highest = Integer.MIN_VALUE;
                if (heights != null) {
                    for (HeightIndex cube : heights.values()) {
                        if (cube.present()[index]
                                && (!found || cube.tops()[index] > highest)) {
                            highest = cube.tops()[index];
                            found = true;
                        }
                    }
                }
                BlockColumnPos column = new BlockColumnPos(baseX + localX, baseZ + localZ);
                if (found) {
                    HIGHEST_BLOCKS.put(column, highest);
                } else {
                    HIGHEST_BLOCKS.remove(column);
                }
            }
        }
    }

    private static int local(int coordinate) {
        return Math.floorMod(coordinate, CubePos.SIZE);
    }

    private static void trimPendingLights() {
        while (PENDING_LIGHTS.size() > MAX_PENDING_LIGHTS) {
            var iterator = PENDING_LIGHTS.keySet().iterator();
            if (!iterator.hasNext()) return;
            PENDING_LIGHTS.remove(iterator.next());
        }
    }

    private record CubeEntry(ChunkSection section, CubeLightData light, long revision, HeightIndex heightIndex) {
        private CubeEntry {
            if (heightIndex == null) throw new NullPointerException("heightIndex");
        }

        private CubeEntry withRevision(long nextRevision) {
            return new CubeEntry(section, light, nextRevision, heightIndex);
        }

        private CubeEntry withBlockHeight(
                CubePos pos, int localX, int localY, int localZ, BlockState state) {
            int index = localX | localZ << 4;
            int blockY = pos.minBlockY() + localY;
            boolean oldPresent = heightIndex.present()[index];
            int oldTop = heightIndex.tops()[index];
            boolean nextPresent = oldPresent;
            int nextTop = oldTop;
            if (!state.isAir() && (!oldPresent || blockY > oldTop)) {
                nextTop = blockY;
                nextPresent = true;
            } else if (state.isAir() && oldPresent && blockY == oldTop) {
                nextPresent = false;
                nextTop = Integer.MIN_VALUE;
                for (int y = localY - 1; y >= 0; y--) {
                    if (!section.getBlockState(localX, y, localZ).isAir()) {
                        nextTop = pos.minBlockY() + y;
                        nextPresent = true;
                        break;
                    }
                }
            }
            if (nextPresent == oldPresent && nextTop == oldTop) return this;
            int[] updatedTops = heightIndex.tops().clone();
            boolean[] updatedPresent = heightIndex.present().clone();
            updatedTops[index] = nextTop;
            updatedPresent[index] = nextPresent;
            return new CubeEntry(section, light, revision, new HeightIndex(updatedTops, updatedPresent));
        }
    }

    private record PendingLight(long revision, CubeLightData.Snapshot light) {}

    private record HeightIndex(int[] tops, boolean[] present) {
        private HeightIndex {
            if (tops == null || present == null || tops.length != CubePos.SIZE * CubePos.SIZE
                    || present.length != CubePos.SIZE * CubePos.SIZE) {
                throw new IllegalArgumentException("Invalid cube height index");
            }
            tops = tops.clone();
            present = present.clone();
        }
    }

    private record CubeColumnPos(int x, int z) {}

    private record BlockColumnPos(int x, int z) {}

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
            if (y < world.getBottomY()) return false;
            if (y > world.getTopYInclusive()) return cubicHighest == null || y > cubicHighest;
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
            if (entry != null && entry.light().publish()) queueRenderNeighborhood(pos);
        }

        private static BlockState state(int x, int y, int z) {
            CubeEntry entry = CUBES.get(CubePos.fromBlock(x, y, z));
            return entry == null ? Blocks.VOID_AIR.getDefaultState()
                    : entry.section().getBlockState(local(x), local(y), local(z));
        }
    }
}
