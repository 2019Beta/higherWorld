package org.devt.higherworld.mixin;

import net.minecraft.world.BlockCollisionSpliterator;
import net.minecraft.world.BlockView;
import net.minecraft.world.CollisionView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Keeps collision scans on the cubic-aware World view instead of a fixed-height WorldChunk view. */
@Mixin(BlockCollisionSpliterator.class)
abstract class BlockCollisionSpliteratorMixin {
    @Redirect(
            method = "getChunk",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/CollisionView;getChunkAsView(II)Lnet/minecraft/world/BlockView;"))
    private BlockView higherworld$useCubicWorldView(CollisionView world, int chunkX, int chunkZ) {
        return world;
    }
}
