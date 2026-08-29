package org.devt.higherworld.client.mixin;

import java.util.Queue;

import net.minecraft.client.render.ChunkRenderingDataPreparer;
import net.minecraft.client.render.BuiltChunkStorage;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.HeightLimitView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents occlusion propagation from clipping rays at the vanilla dimension caps. */
@Mixin(ChunkRenderingDataPreparer.class)
abstract class ChunkRenderingDataPreparerMixin {
    @Shadow
    private BuiltChunkStorage builtChunkStorage;

    @Unique
    private int higherworld$cameraSectionY;

    /**
     * Vanilla starts the occlusion graph at the dimension edge. The storage is
     * camera-centered instead, so use the top/bottom edge of that storage ring
     * as the graph's starting plane.
     */
    @Inject(method = "scheduleLater", at = @At("HEAD"))
    private void higherworld$captureCameraSection(Camera camera, Queue<?> queue, CallbackInfo callbackInfo) {
        higherworld$cameraSectionY = ChunkSectionPos.getSectionCoord(camera.getBlockPos().getY());
    }

    @Redirect(
            method = "scheduleLater",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/HeightLimitView;getTopSectionCoord()I"))
    private int higherworld$occlusionRingTop(HeightLimitView world) {
        return higherworld$cameraSectionY + higherworld$verticalRadius();
    }

    @Redirect(
            method = "scheduleLater",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/HeightLimitView;getBottomSectionCoord()I"))
    private int higherworld$occlusionRingBottom(HeightLimitView world) {
        return higherworld$cameraSectionY - higherworld$verticalRadius();
    }

    @Redirect(
            method = "update",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/HeightLimitView;getTopYInclusive()I"))
    private int higherworld$occlusionTop(HeightLimitView world) {
        return Integer.MAX_VALUE;
    }

    @Redirect(
            method = "update",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/HeightLimitView;getBottomY()I"))
    private int higherworld$occlusionBottom(HeightLimitView world) {
        return Integer.MIN_VALUE;
    }

    @Unique
    private int higherworld$verticalRadius() {
        return Math.min(builtChunkStorage.getViewDistance(), 8);
    }
}
