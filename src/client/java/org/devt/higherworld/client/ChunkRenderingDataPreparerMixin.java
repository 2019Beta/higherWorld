package org.devt.higherworld.client;

import net.minecraft.client.render.ChunkRenderingDataPreparer;
import net.minecraft.world.HeightLimitView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Prevents occlusion propagation from clipping rays at the vanilla dimension caps. */
@Mixin(ChunkRenderingDataPreparer.class)
abstract class ChunkRenderingDataPreparerMixin {
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
}
