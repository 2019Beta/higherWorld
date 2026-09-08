package org.devt.higherworld.client.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.chunk.ChunkRendererRegion;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import org.devt.higherworld.client.ClientCubeCache;
import org.devt.higherworld.world.CubeLightMath;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Provides deterministic light values while the cubic light graph is sparse. */
@Mixin(ChunkRendererRegion.class)
abstract class ChunkRendererRegionMixin {
    @Shadow @Final private World world;
    @Unique private ClientCubeCache.RenderAccess higherworld$cubeAccess;

    @Unique
    private ClientCubeCache.RenderAccess higherworld$cubeAccess() {
        if (higherworld$cubeAccess == null) {
            higherworld$cubeAccess = new ClientCubeCache.RenderAccess((ClientWorld) world);
        }
        return higherworld$cubeAccess;
    }

    /**
     * SectionBuilder reads through this view. Reading only from RenderedChunk's
     * vanilla-height snapshot can leave an out-of-range section permanently air
     * when the cube payload arrives after that snapshot was created.
     */
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void higherworld$getCubeBlockState(
            BlockPos pos, CallbackInfoReturnable<BlockState> callbackInfo) {
        if (outsideVanillaHeight(pos)) {
            callbackInfo.setReturnValue(higherworld$cubeAccess().blockState(pos));
        }
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void higherworld$getCubeFluidState(
            BlockPos pos, CallbackInfoReturnable<FluidState> callbackInfo) {
        if (outsideVanillaHeight(pos)) {
            callbackInfo.setReturnValue(higherworld$cubeAccess().blockState(pos).getFluidState());
        }
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void higherworld$getCubeBlockEntity(
            BlockPos pos, CallbackInfoReturnable<BlockEntity> callbackInfo) {
        if (outsideVanillaHeight(pos)) {
            callbackInfo.setReturnValue(higherworld$cubeAccess().blockEntity(pos));
        }
    }

    public int getLightLevel(LightType type, BlockPos pos) {
        if (!outsideVanillaHeight(pos)) {
            return world.getLightLevel(type, pos);
        }
        return higherworld$cubeAccess().lightLevel(type, pos);
    }

    public int getBaseLightLevel(BlockPos pos, int ambientDarkness) {
        if (!outsideVanillaHeight(pos)) {
            return world.getBaseLightLevel(pos, ambientDarkness);
        }
        return CubeLightMath.externalBaseLightLevel(
                getLightLevel(LightType.SKY, pos), ambientDarkness,
                higherworld$cubeAccess().blockState(pos).getLuminance());
    }

    public boolean isSkyVisible(BlockPos pos) {
        return outsideVanillaHeight(pos) ? getLightLevel(LightType.SKY, pos) >= 15 : world.isSkyVisible(pos);
    }

    @Unique
    private boolean outsideVanillaHeight(BlockPos pos) {
        return pos.getY() < world.getBottomY() || pos.getY() > world.getTopYInclusive();
    }
}
