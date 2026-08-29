package org.devt.higherworld.world;

import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.entity.BlockEntity;

/** Implemented by a client world backed by the cubic packet cache. */
public interface CubicBlockView {
    BlockState higherworld$getCubeBlockState(BlockPos pos);

    FluidState higherworld$getCubeFluidState(BlockPos pos);

    boolean higherworld$setCubeBlockState(BlockPos pos, BlockState state);

    BlockEntity higherworld$getCubeBlockEntity(BlockPos pos);
}
