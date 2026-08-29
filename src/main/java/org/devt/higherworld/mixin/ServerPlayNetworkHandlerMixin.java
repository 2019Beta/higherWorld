package org.devt.higherworld.mixin;

import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps player and vehicle movement usable throughout the signed block-coordinate range. */
@Mixin(ServerPlayNetworkHandler.class)
abstract class ServerPlayNetworkHandlerMixin {
    @Inject(method = "clampVertical", at = @At("HEAD"), cancellable = true)
    private static void higherworld$clampVertical(double value, CallbackInfoReturnable<Double> callbackInfo) {
        callbackInfo.setReturnValue(Math.max(Integer.MIN_VALUE + 1.0, Math.min(Integer.MAX_VALUE - 1.0, value)));
    }
}
