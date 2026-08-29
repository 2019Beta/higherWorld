package org.devt.higherworld.client.mixin;

import net.minecraft.client.render.BuiltChunkStorage;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
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
    @Shadow @Final protected WorldRenderer worldRenderer;
    @Shadow public ChunkBuilder.BuiltChunk[] chunks;

    @Inject(method = "setViewDistance", at = @At("TAIL"))
    private void higherworld$setVerticalViewDistance(int distance, CallbackInfo callbackInfo) {
        int verticalRadius = Math.min(distance, 8);
        sizeY = verticalRadius * 2 + 1;
    }

    @Redirect(
            method = "createChunks",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getBottomSectionCoord()I"))
    private int higherworld$initialBottom(World world) {
        // Keep the initial slots compatible with the modulo lookup used below.
        return 0;
    }

    /**
     * Moves the render storage as a ring on all three axes.
     *
     * @author HigherWorld
     * @reason Vanilla has a fixed vertical range and therefore assigns its Y
     * slots linearly. A camera-centered range must use modulo slots, otherwise
     * crossing one section boundary relocates and rebuilds every rendered layer.
     */
    @Overwrite
    public void updateCameraPosition(ChunkSectionPos cameraPos) {
        int minX = cameraPos.getSectionX() - viewDistance;
        int minY = cameraPos.getSectionY() - sizeY / 2;
        int minZ = cameraPos.getSectionZ() - viewDistance;

        for (int slotX = 0; slotX < sizeX; slotX++) {
            int sectionX = minX + Math.floorMod(slotX - minX, sizeX);
            for (int slotZ = 0; slotZ < sizeZ; slotZ++) {
                int sectionZ = minZ + Math.floorMod(slotZ - minZ, sizeZ);
                for (int slotY = 0; slotY < sizeY; slotY++) {
                    int sectionY = minY + Math.floorMod(slotY - minY, sizeY);
                    ChunkBuilder.BuiltChunk chunk = chunks[higherworld$chunkIndex(slotX, slotY, slotZ)];
                    long targetPos = ChunkSectionPos.asLong(sectionX, sectionY, sectionZ);
                    if (chunk.getSectionPos() != targetPos) {
                        chunk.setSectionPos(targetPos);
                    }
                }
            }
        }

        sectionPos = cameraPos;
        worldRenderer.getChunkRenderingDataPreparer().scheduleTerrainUpdate();
    }

    @Redirect(
            method = "getRenderedChunk(III)Lnet/minecraft/client/render/chunk/ChunkBuilder$BuiltChunk;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getBottomSectionCoord()I"))
    private int higherworld$renderBottom(World world, int sectionX, int sectionY, int sectionZ) {
        // getRenderedChunk subtracts this value to obtain the array's Y index.
        // Returning the aligned multiple turns that linear index into floorMod.
        return sectionY - Math.floorMod(sectionY, sizeY);
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

    private int higherworld$chunkIndex(int slotX, int slotY, int slotZ) {
        return (slotZ * sizeY + slotY) * sizeX + slotX;
    }
}
