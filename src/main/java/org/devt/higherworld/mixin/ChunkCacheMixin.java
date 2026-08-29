package org.devt.higherworld.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Makes mob pathfinding and cached collision views read external cubes. */
@Mixin(ChunkCache.class)
abstract class ChunkCacheMixin {
    @Shadow @Final protected World world;

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void higherworld$getBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> callbackInfo) {
        if (outsideVanillaHeight(pos)) {
            callbackInfo.setReturnValue(world.getBlockState(pos));
        }
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void higherworld$getFluidState(BlockPos pos, CallbackInfoReturnable<FluidState> callbackInfo) {
        if (outsideVanillaHeight(pos)) {
            callbackInfo.setReturnValue(world.getFluidState(pos));
        }
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void higherworld$getBlockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> callbackInfo) {
        if (outsideVanillaHeight(pos)) {
            callbackInfo.setReturnValue(world.getBlockEntity(pos));
        }
    }

    private boolean outsideVanillaHeight(BlockPos pos) {
        return pos.getY() < world.getBottomY() || pos.getY() > world.getTopYInclusive();
    }
}
