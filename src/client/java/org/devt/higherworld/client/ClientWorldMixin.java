package org.devt.higherworld.client;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import org.devt.higherworld.world.CubicBlockView;
import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.block.entity.BlockEntity;

@Mixin(ClientWorld.class)
abstract class ClientWorldMixin implements CubicBlockView {
    @Override
    public BlockState higherworld$getCubeBlockState(BlockPos pos) {
        return ClientCubeCache.getBlockState((ClientWorld) (Object) this, pos);
    }

    @Override
    public FluidState higherworld$getCubeFluidState(BlockPos pos) {
        return ClientCubeCache.getFluidState((ClientWorld) (Object) this, pos);
    }

    @Override
    public boolean higherworld$setCubeBlockState(BlockPos pos, BlockState state) {
        return ClientCubeCache.setBlockState((ClientWorld) (Object) this, pos, state);
    }

    @Override
    public BlockEntity higherworld$getCubeBlockEntity(BlockPos pos) {
        return ClientCubeCache.getBlockEntity((ClientWorld) (Object) this, pos);
    }
}
