package org.devt.higherworld.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ProgressListener;
import net.minecraft.util.math.BlockPos;
import org.devt.higherworld.world.CubeWatchManager;
import org.devt.higherworld.world.CubicWorldManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Routes listener notifications outside the vanilla section array to cube clients. */
@Mixin(ServerWorld.class)
abstract class ServerWorldMixin {
    /**
     * Routes ordinary entities outside the vanilla height band to the cube
     * runtime.  This is intentionally a HEAD cancellation: the entity never
     * enters ServerWorld's fixed-height entity manager, so CubeEntityRuntime's
     * tick and tracker are its single owners.  Admission is idempotent for the
     * same object and rejects a conflicting UUID.
     */
    @Inject(method = "spawnEntity", at = @At("HEAD"), cancellable = true)
    private void higherworld$spawnCubeEntity(
            Entity entity, CallbackInfoReturnable<Boolean> callbackInfo) {
        ServerWorld world = (ServerWorld) (Object) this;
        if (CubicWorldManager.shouldManageEntity(world, entity)) {
            callbackInfo.setReturnValue(CubicWorldManager.addEntity(world, entity));
        }
    }

    /**
     * ServerWorld.tryLoadEntity calls its private addEntity path directly and
     * therefore does not pass through spawnEntity.  Keep loaded outside-height
     * entities owned by the same runtime instead of allowing a second vanilla
     * registration during a dimension/chunk load.
     */
    @Inject(method = "tryLoadEntity", at = @At("HEAD"), cancellable = true)
    private void higherworld$loadCubeEntity(
            Entity entity, CallbackInfoReturnable<Boolean> callbackInfo) {
        ServerWorld world = (ServerWorld) (Object) this;
        if (CubicWorldManager.shouldManageEntity(world, entity)) {
            callbackInfo.setReturnValue(CubicWorldManager.addEntity(world, entity));
        }
    }

    /** Covers the passenger-aware spawn entry point when it bypasses spawnEntity. */
    @Inject(method = "spawnNewEntityAndPassengers", at = @At("HEAD"), cancellable = true)
    private void higherworld$spawnCubeEntityFamily(
            Entity entity, CallbackInfoReturnable<Boolean> callbackInfo) {
        ServerWorld world = (ServerWorld) (Object) this;
        if (CubicWorldManager.shouldManageEntity(world, entity)) {
            callbackInfo.setReturnValue(CubicWorldManager.addEntity(world, entity));
        }
    }

    /** Flushes HWE1 entity records at the same save barrier as the world. */
    @Inject(method = "save", at = @At("HEAD"))
    private void higherworld$saveCubeEntities(
            ProgressListener progressListener, boolean flush, boolean savingDisabled,
            CallbackInfo callbackInfo) {
        if (!savingDisabled) {
            CubicWorldManager.saveScheduledTicksAtWorldSave((ServerWorld) (Object) this);
            CubicWorldManager.saveEntitiesAtWorldSave((ServerWorld) (Object) this);
        }
    }

    @Inject(method = "updateListeners", at = @At("HEAD"), cancellable = true)
    private void higherworld$updateCubeListeners(
            BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo callbackInfo) {
        ServerWorld world = (ServerWorld) (Object) this;
        if (pos.getY() < world.getBottomY() || pos.getY() > world.getTopYInclusive()) {
            if (CubicWorldManager.suppressingGenerationUpdates(world)) {
                // Structure block entities (notably mob spawners) may notify
                // their world while a sparse cube is being generated. The
                // completed cube payload represents this notification, so
                // forwarding it would synchronously re-enter FULL generation.
                callbackInfo.cancel();
                return;
            }
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
