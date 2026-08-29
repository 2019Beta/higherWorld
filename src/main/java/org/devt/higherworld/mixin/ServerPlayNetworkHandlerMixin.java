package org.devt.higherworld.mixin;

import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.world.ServerWorld;
import org.devt.higherworld.world.CubicWorldManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps player and vehicle movement usable throughout the signed block-coordinate range. */
@Mixin(ServerPlayNetworkHandler.class)
abstract class ServerPlayNetworkHandlerMixin {
    @Inject(method = "clampVertical", at = @At("HEAD"), cancellable = true)
    private static void higherworld$clampVertical(double value, CallbackInfoReturnable<Double> callbackInfo) {
        callbackInfo.setReturnValue(Math.max(Integer.MIN_VALUE + 1.0, Math.min(Integer.MAX_VALUE - 1.0, value)));
    }

    /**
     * Vanilla passes getTopYInclusive() into the mining state machine and rejects
     * every START/STOP_DESTROY_BLOCK packet above it before World#getBlockState
     * can reach the cube runtime.
     */
    @Redirect(
            method = "onPlayerAction",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/world/ServerWorld;getTopYInclusive()I"))
    private int higherworld$miningTop(ServerWorld world) {
        return CubicWorldManager.isCubic(world) ? Integer.MAX_VALUE : world.getTopYInclusive();
    }

    /** Applies the same uncapped limit to right-click block placement. */
    @Redirect(
            method = "onPlayerInteractBlock",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/world/ServerWorld;getTopYInclusive()I"))
    private int higherworld$placementTop(ServerWorld world) {
        return CubicWorldManager.isCubic(world) ? Integer.MAX_VALUE : world.getTopYInclusive();
    }
}
