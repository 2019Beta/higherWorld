package org.devt.higherworld.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.devt.higherworld.world.CubeWatchManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Routes listener notifications outside the vanilla section array to cube clients. */
@Mixin(ServerWorld.class)
abstract class ServerWorldMixin {
    @Inject(method = "updateListeners", at = @At("HEAD"), cancellable = true)
    private void higherworld$updateCubeListeners(
            BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo callbackInfo) {
        ServerWorld world = (ServerWorld) (Object) this;
        if (pos.getY() < world.getBottomY() || pos.getY() > world.getTopYInclusive()) {
            if (oldState == newState) {
                // Same-state notifications can carry block-entity NBT changes.
                CubeWatchManager.broadcastCubeUpdate(world, pos);
            } else {
                CubeWatchManager.broadcastBlockUpdate(world, pos, newState);
            }
            // Vanilla indexes ChunkHolder's fixed-height section array here. Letting
            // an out-of-range Y continue produces an AIOOBE before clients are told
            // about the change.
            callbackInfo.cancel();
        }
    }
}
