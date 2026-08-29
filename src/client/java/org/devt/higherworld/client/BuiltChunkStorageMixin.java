package org.devt.higherworld.client.mixin;

import net.minecraft.client.render.BuiltChunkStorage;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Turns the vanilla full-height render array into a camera-centered 3D ring. */
@Mixin(BuiltChunkStorage.class)
abstract class BuiltChunkStorageMixin {
    @Shadow protected int sizeY;
    @Shadow protected int sizeX;
    @Shadow protected int sizeZ;
    @Shadow private int viewDistance;
    @Shadow private ChunkSectionPos sectionPos;

    @Inject(method = "setViewDistance", at = @At("TAIL"))
    private void higherworld$setVerticalViewDistance(int distance, CallbackInfo callbackInfo) {
        int verticalRadius = Math.min(distance, 8);
        sizeY = verticalRadius * 2 + 1;
    }

    @Redirect(
            method = "createChunks",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getBottomSectionCoord()I"))
    private int higherworld$initialBottom(World world) {
        return -sizeY / 2;
    }

    @Redirect(
            method = "updateCameraPosition",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getBottomSectionCoord()I"))
    private int higherworld$cameraBottom(World world, ChunkSectionPos cameraPos) {
        return cameraPos.getSectionY() - sizeY / 2;
    }

    @Redirect(
            method = "getRenderedChunk(III)Lnet/minecraft/client/render/chunk/ChunkBuilder$BuiltChunk;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getBottomSectionCoord()I"))
    private int higherworld$renderBottom(World world, int sectionX, int sectionY, int sectionZ) {
        return sectionPos.getSectionY() - sizeY / 2;
    }

    @Inject(method = "isSectionWithinViewDistance", at = @At("HEAD"), cancellable = true)
    private void higherworld$isSectionWithinViewDistance(
            int sectionX, int sectionY, int sectionZ, CallbackInfoReturnable<Boolean> callbackInfo) {
        int verticalRadius = sizeY / 2;
        callbackInfo.setReturnValue(
                Math.abs(sectionX - sectionPos.getSectionX()) <= viewDistance
                        && Math.abs(sectionY - sectionPos.getSectionY()) <= verticalRadius
                        && Math.abs(sectionZ - sectionPos.getSectionZ()) <= viewDistance);
    }
}
