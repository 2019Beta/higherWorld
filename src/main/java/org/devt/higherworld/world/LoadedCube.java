package org.devt.higherworld.world;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.ChunkSection;
import org.devt.higherworld.storage.CubePos;

/** One lazily loaded 16 x 16 x 16 runtime cube. */
final class LoadedCube {
    private final CubePos pos;
    private final ChunkSection section;
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final AtomicLong revision = new AtomicLong();
    private int generationVersion;
    private final Map<BlockPos, BlockEntity> blockEntities = new ConcurrentHashMap<>();

    LoadedCube(CubePos pos, ChunkSection section) {
        this.pos = pos;
        this.section = section;
    }

    CubePos pos() {
        return pos;
    }

    ChunkSection section() {
        return section;
    }

    int generationVersion() {
        return generationVersion;
    }

    long revision() {
        return revision.get();
    }

    void setGenerationVersion(int generationVersion) {
        this.generationVersion = generationVersion;
    }

    BlockState getBlockState(BlockPos blockPos) {
        return section.getBlockState(local(blockPos.getX()), local(blockPos.getY()), local(blockPos.getZ()));
    }

    FluidState getFluidState(BlockPos blockPos) {
        return section.getFluidState(local(blockPos.getX()), local(blockPos.getY()), local(blockPos.getZ()));
    }

    BlockState setBlockState(BlockPos blockPos, BlockState state) {
        BlockState previous = section.setBlockState(
                local(blockPos.getX()), local(blockPos.getY()), local(blockPos.getZ()), state);
        if (previous != state) {
            dirty.set(true);
            revision.incrementAndGet();
        }
        return previous;
    }

    void setGeneratedBlockState(int localX, int localY, int localZ, BlockState state) {
        section.setBlockState(localX, localY, localZ, state);
    }

    void markDirty() {
        dirty.set(true);
        revision.incrementAndGet();
    }

    boolean takeDirty() {
        return dirty.getAndSet(false);
    }

    boolean isDirty() {
        return dirty.get();
    }

    void restoreDirty() {
        dirty.set(true);
    }

    BlockEntity getBlockEntity(BlockPos pos) {
        return blockEntities.get(pos);
    }

    void putBlockEntity(BlockEntity blockEntity) {
        blockEntities.put(blockEntity.getPos().toImmutable(), blockEntity);
        dirty.set(true);
        revision.incrementAndGet();
    }

    void putLoadedBlockEntity(BlockEntity blockEntity) {
        blockEntities.put(blockEntity.getPos().toImmutable(), blockEntity);
    }

    BlockEntity removeBlockEntity(BlockPos pos) {
        BlockEntity removed = blockEntities.remove(pos);
        if (removed != null) {
            dirty.set(true);
            revision.incrementAndGet();
        }
        return removed;
    }

    Collection<BlockEntity> blockEntities() {
        return blockEntities.values();
    }

    void tickBlockEntities(ServerWorld world) {
        if (blockEntities.isEmpty()) {
            return;
        }
        for (BlockEntity blockEntity : List.copyOf(blockEntities.values())) {
            if (blockEntity.isRemoved()) {
                if (blockEntities.remove(blockEntity.getPos(), blockEntity)) {
                    dirty.set(true);
                }
                continue;
            }
            BlockState state = getBlockState(blockEntity.getPos());
            tickBlockEntity(world, state, blockEntity);
        }
    }

    void tickRandomly(ServerWorld world, int randomTickSpeed) {
        if (randomTickSpeed <= 0 || !section.hasRandomTicks()) {
            return;
        }
        int baseX = pos.minBlockX();
        int baseY = pos.minBlockY();
        int baseZ = pos.minBlockZ();
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int tick = 0; tick < randomTickSpeed; tick++) {
            int localX = world.getRandom().nextInt(CubePos.SIZE);
            int localY = world.getRandom().nextInt(CubePos.SIZE);
            int localZ = world.getRandom().nextInt(CubePos.SIZE);
            mutable.set(baseX + localX, baseY + localY, baseZ + localZ);

            BlockState blockState = section.getBlockState(localX, localY, localZ);
            if (blockState.hasRandomTicks()) {
                blockState.randomTick(world, mutable, world.getRandom());
            }
            FluidState fluidState = section.getFluidState(localX, localY, localZ);
            if (fluidState.hasRandomTicks()) {
                fluidState.onRandomTick(world, mutable, world.getRandom());
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void tickBlockEntity(ServerWorld world, BlockState state, BlockEntity blockEntity) {
        BlockEntityTicker ticker = state.getBlockEntityTicker(world, blockEntity.getType());
        if (ticker != null) {
            ticker.tick(world, blockEntity.getPos(), state, blockEntity);
        }
    }

    private static int local(int blockCoordinate) {
        return Math.floorMod(blockCoordinate, CubePos.SIZE);
    }
}
