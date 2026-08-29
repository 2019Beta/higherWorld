package org.devt.higherworld.client.mixin;

import net.minecraft.client.render.BuiltChunkStorage;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps the render octree vertically centered on the camera section ring. */
@Mixin(targets = "net.minecraft.client.render.ChunkRenderingDataPreparer$RenderableChunks")
abstract class RenderableChunksMixin {
    /**
     * RenderableChunks passes the dimension bottom into Octree. When the
     * horizontal octree power is taller than the 17-section vertical ring,
     * Octree uses that value verbatim and clips every section below/above the
     * vanilla dimension. Supply the actual bottom of the camera-centered ring.
     */
    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getBottomY()I"))
    private int higherworld$cameraRingBottom(World world, BuiltChunkStorage storage) {
        int verticalRadius = Math.min(storage.getViewDistance(), 8);
        return ChunkSectionPos.getBlockCoord(storage.getSectionPos().getSectionY() - verticalRadius);
    }
}
