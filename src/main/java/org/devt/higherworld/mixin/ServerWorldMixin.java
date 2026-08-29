package org.devt.higherworld.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.devt.higherworld.world.CubeWatchManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mirrors listener notifications, including block-entity NBT changes, to cube clients. */
@Mixin(ServerWorld.class)
abstract class ServerWorldMixin {
    @Inject(method = "updateListeners", at = @At("TAIL"))
    private void higherworld$updateCubeListeners(
            BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo callbackInfo) {
        ServerWorld world = (ServerWorld) (Object) this;
        if (pos.getY() < world.getBottomY() || pos.getY() > world.getTopYInclusive()) {
            CubeWatchManager.broadcastCubeUpdate(world, pos);
        }
    }
}
