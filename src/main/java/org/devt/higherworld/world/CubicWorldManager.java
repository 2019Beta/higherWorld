package org.devt.higherworld.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.WorldSavePath;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.chunk.WorldChunk;
import org.devt.higherworld.Higherworld;
import org.devt.higherworld.storage.CubePos;
import org.devt.higherworld.storage.CubeStorage;

/** Owns sparse cube state outside the vanilla dimension height range. */
public final class CubicWorldManager {
    private static final RegistryKey<DimensionType> INFINITE_OVERWORLD = RegistryKey.of(
            RegistryKeys.DIMENSION_TYPE, Identifier.of(Higherworld.MOD_ID, "infinite_overworld"));
    private static final Map<ServerWorld, CubicWorldState> WORLDS = new ConcurrentHashMap<>();

    private CubicWorldManager() {
    }

    public static void open(MinecraftServer server, ServerWorld world) {
        Path root = server.getSavePath(WorldSavePath.ROOT)
                .resolve("cubic_chunks")
                .resolve(world.getRegistryKey().getValue().getNamespace())
                .resolve(world.getRegistryKey().getValue().getPath())
                .resolve("region3d");
        try {
            CubicWorldState previous = WORLDS.put(world, new CubicWorldState(world, new CubeStorage(root)));
            if (previous != null) {
                previous.close();
            }
            Higherworld.LOGGER.info("Opened cubic storage for {} at {}", world.getRegistryKey().getValue(), root);
        } catch (IOException exception) {
            Higherworld.LOGGER.error("Cannot open cubic storage for {}", world.getRegistryKey().getValue(), exception);
        }
    }

    public static void close(MinecraftServer server, ServerWorld world) {
        CubeWatchManager.removeWorld(world);
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

    static boolean generatesInfinitelyDownward(ServerWorld world) {
        return world.getDimensionEntry().matchesKey(INFINITE_OVERWORLD);
    }

    /** Removes the obsolete vanilla floor for both new and already-created chunks. */
    public static void onChunkLoad(ServerWorld world, WorldChunk chunk) {
        if (generatesInfinitelyDownward(world)) {
            InfiniteDownwardGenerator.openVanillaFloor(world, chunk);
        }
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
            BlockState previous = state.setBlockState(pos, blockState);
            boolean changed = previous != blockState;
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

    public static void flushDirty(ServerWorld world) {
        CubicWorldState state = WORLDS.get(world);
        if (state == null) {
            return;
        }
        try {
            state.flushDirty();
        } catch (IOException exception) {
            Higherworld.LOGGER.error("Cannot flush cubic storage for {}", world.getRegistryKey().getValue(), exception);
        }
    }

    public static Integer highestBlockY(ServerWorld world, int blockX, int blockZ) {
        CubicWorldState state = WORLDS.get(world);
        return state == null ? null : state.highestBlockY(blockX, blockZ);
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
    }

    public static void evictExcept(ServerWorld world, Set<CubePos> retained) {
        CubicWorldState state = WORLDS.get(world);
        if (state == null) {
            return;
        }
        try {
            state.evictExcept(retained);
        } catch (IOException exception) {
            Higherworld.LOGGER.error("Cannot evict cubic cache for {}", world.getRegistryKey().getValue(), exception);
        }
    }
}
