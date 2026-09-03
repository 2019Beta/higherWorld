package org.devt.higherworld.mixin;

import net.minecraft.block.Block;
import net.minecraft.fluid.Fluid;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.tick.ScheduledTickView;
import net.minecraft.world.tick.TickPriority;
import org.devt.higherworld.world.CubeScheduledTickQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Routes cubic scheduled ticks away from vanilla's fixed-height scheduler. */
@Mixin(ScheduledTickView.class)
interface ScheduledTickViewMixin {
    @Inject(
            method = "scheduleBlockTick(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;I)V",
            at = @At("HEAD"), cancellable = true)
    private void higherworld$scheduleBlockTick(
            BlockPos pos, Block block, int delay, CallbackInfo callbackInfo) {
        Object self = this;
        if (self instanceof ServerWorld world && isOutsideVanillaHeight(world, pos)
                && CubeScheduledTickQueue.scheduleBlockTick(
                        world, pos, block, delay, TickPriority.NORMAL)) {
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = "scheduleBlockTick(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;ILnet/minecraft/world/tick/TickPriority;)V",
            at = @At("HEAD"), cancellable = true)
    private void higherworld$scheduleBlockTickWithPriority(
            BlockPos pos, Block block, int delay, TickPriority priority, CallbackInfo callbackInfo) {
        Object self = this;
        if (self instanceof ServerWorld world && isOutsideVanillaHeight(world, pos)
                && CubeScheduledTickQueue.scheduleBlockTick(world, pos, block, delay, priority)) {
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = "scheduleFluidTick(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/fluid/Fluid;I)V",
            at = @At("HEAD"), cancellable = true)
    private void higherworld$scheduleFluidTick(
            BlockPos pos, Fluid fluid, int delay, CallbackInfo callbackInfo) {
        Object self = this;
        if (self instanceof ServerWorld world && isOutsideVanillaHeight(world, pos)
                && CubeScheduledTickQueue.scheduleFluidTick(
                        world, pos, fluid, delay, TickPriority.NORMAL)) {
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = "scheduleFluidTick(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/fluid/Fluid;ILnet/minecraft/world/tick/TickPriority;)V",
            at = @At("HEAD"), cancellable = true)
    private void higherworld$scheduleFluidTickWithPriority(
            BlockPos pos, Fluid fluid, int delay, TickPriority priority, CallbackInfo callbackInfo) {
        Object self = this;
        if (self instanceof ServerWorld world && isOutsideVanillaHeight(world, pos)
                && CubeScheduledTickQueue.scheduleFluidTick(world, pos, fluid, delay, priority)) {
            callbackInfo.cancel();
        }
    }

    private static boolean isOutsideVanillaHeight(ServerWorld world, BlockPos pos) {
        return pos.getY() < world.getBottomY() || pos.getY() > world.getTopYInclusive();
    }
}
