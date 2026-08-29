package org.devt.higherworld.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.devt.higherworld.world.CubicBlockView;
import org.devt.higherworld.world.CubicWorldManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Removes the dimension-bottom void kill plane in cubic worlds. */
@Mixin(Entity.class)
abstract class EntityMixin {
    @Shadow
    public abstract World getEntityWorld();

    @Inject(method = "attemptTickInVoid", at = @At("HEAD"), cancellable = true)
    private void higherworld$disableVoidPlane(CallbackInfo callbackInfo) {
        World world = getEntityWorld();
        if ((world instanceof ServerWorld serverWorld && CubicWorldManager.isCubic(serverWorld))
                || world instanceof CubicBlockView) {
            callbackInfo.cancel();
        }
    }
}
