package org.devt.higherworld.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import net.minecraft.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.devt.higherworld.world.CubicBlockView;
import org.devt.higherworld.world.CubicWorldManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Routes positions outside the vanilla section array through sparse runtime cubes. */
@Mixin(World.class)
abstract class WorldMixin {
    private static final int HORIZONTAL_LIMIT = 30_000_000;

    @Inject(method = "isInBuildLimit", at = @At("HEAD"), cancellable = true)
    private void higherworld$isInBuildLimit(BlockPos pos, CallbackInfoReturnable<Boolean> callbackInfo) {
        if (((Object) this instanceof ServerWorld world && CubicWorldManager.isCubic(world))
                || (Object) this instanceof CubicBlockView) {
            callbackInfo.setReturnValue(isValidHorizontally(pos));
        }
    }

    @Inject(method = "isInLoadLimit", at = @At("HEAD"), cancellable = true)
    private void higherworld$isInLoadLimit(BlockPos pos, CallbackInfoReturnable<Boolean> callbackInfo) {
        if (((Object) this instanceof ServerWorld world && CubicWorldManager.isCubic(world))
                || (Object) this instanceof CubicBlockView) {
            callbackInfo.setReturnValue(isLoadableHorizontally(pos));
        }
    }

    @Inject(method = "isValid", at = @At("HEAD"), cancellable = true)
    private static void higherworld$isValid(BlockPos pos, CallbackInfoReturnable<Boolean> callbackInfo) {
        callbackInfo.setReturnValue(isValidHorizontally(pos));
    }

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void higherworld$getBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> callbackInfo) {
        if ((Object) this instanceof ServerWorld world && isOutsideVanillaHeight(world, pos)) {
            callbackInfo.setReturnValue(CubicWorldManager.getBlockState(world, pos));
        } else if ((Object) this instanceof CubicBlockView view && isOutsideVanillaHeight((World) (Object) this, pos)) {
            callbackInfo.setReturnValue(view.higherworld$getCubeBlockState(pos));
        }
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void higherworld$getFluidState(BlockPos pos, CallbackInfoReturnable<FluidState> callbackInfo) {
        if ((Object) this instanceof ServerWorld world && isOutsideVanillaHeight(world, pos)) {
            callbackInfo.setReturnValue(CubicWorldManager.getFluidState(world, pos));
        } else if ((Object) this instanceof CubicBlockView view && isOutsideVanillaHeight((World) (Object) this, pos)) {
            callbackInfo.setReturnValue(view.higherworld$getCubeFluidState(pos));
        }
    }

    @Inject(method = "getTopY", at = @At("RETURN"), cancellable = true)
    private void higherworld$getTopY(
            Heightmap.Type heightmap, int blockX, int blockZ, CallbackInfoReturnable<Integer> callbackInfo) {
        if ((Object) this instanceof ServerWorld world) {
            Integer cubicTop = CubicWorldManager.highestBlockY(world, blockX, blockZ);
            if (cubicTop != null) {
                int exclusiveTop = cubicTop == Integer.MAX_VALUE ? Integer.MAX_VALUE : cubicTop + 1;
                if (exclusiveTop > callbackInfo.getReturnValueI()) {
                    callbackInfo.setReturnValue(exclusiveTop);
                }
            }
        }
    }

    @Inject(
            method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void higherworld$setBlockState(
            BlockPos pos, BlockState state, int flags, int maxUpdateDepth,
            CallbackInfoReturnable<Boolean> callbackInfo) {
        if ((Object) this instanceof ServerWorld world && isOutsideVanillaHeight(world, pos)) {
            callbackInfo.setReturnValue(CubicWorldManager.setBlockState(world, pos, state, flags, maxUpdateDepth));
        } else if ((Object) this instanceof CubicBlockView view && isOutsideVanillaHeight((World) (Object) this, pos)) {
            callbackInfo.setReturnValue(view.higherworld$setCubeBlockState(pos, state));
        }
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void higherworld$getBlockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> callbackInfo) {
        if ((Object) this instanceof ServerWorld world && isOutsideVanillaHeight(world, pos)) {
            callbackInfo.setReturnValue(CubicWorldManager.getBlockEntity(world, pos));
        } else if ((Object) this instanceof CubicBlockView view && isOutsideVanillaHeight((World) (Object) this, pos)) {
            callbackInfo.setReturnValue(view.higherworld$getCubeBlockEntity(pos));
        }
    }

    @Inject(method = "addBlockEntity", at = @At("HEAD"), cancellable = true)
    private void higherworld$addBlockEntity(BlockEntity blockEntity, CallbackInfo callbackInfo) {
        if ((Object) this instanceof ServerWorld world && isOutsideVanillaHeight(world, blockEntity.getPos())) {
            CubicWorldManager.putBlockEntity(world, blockEntity);
            callbackInfo.cancel();
        }
    }

    @Inject(method = "removeBlockEntity", at = @At("HEAD"), cancellable = true)
    private void higherworld$removeBlockEntity(BlockPos pos, CallbackInfo callbackInfo) {
        if ((Object) this instanceof ServerWorld world && isOutsideVanillaHeight(world, pos)) {
            CubicWorldManager.removeBlockEntity(world, pos);
            callbackInfo.cancel();
        }
    }

    @Inject(method = "markDirty", at = @At("HEAD"), cancellable = true)
    private void higherworld$markDirty(BlockPos pos, CallbackInfo callbackInfo) {
        if ((Object) this instanceof ServerWorld world && isOutsideVanillaHeight(world, pos)) {
            CubicWorldManager.markDirty(world, pos);
            callbackInfo.cancel();
        }
    }

    public int getLightLevel(LightType type, BlockPos pos) {
        World world = (World) (Object) this;
        if (!isOutsideVanillaHeight(world, pos)) {
            return world.getLightingProvider().get(type).getLightLevel(pos);
        }
        if (type == LightType.BLOCK) {
            return world.getBlockState(pos).getLuminance();
        }
        if (!world.getDimension().hasSkyLight()) {
            return 0;
        }
        Integer highest = world instanceof ServerWorld serverWorld
                ? CubicWorldManager.highestBlockY(serverWorld, pos.getX(), pos.getZ()) : null;
        int vanillaHighest = world.getTopY(Heightmap.Type.MOTION_BLOCKING, pos.getX(), pos.getZ()) - 1;
        int effectiveHighest = highest == null ? vanillaHighest : Math.max(vanillaHighest, highest);
        return pos.getY() > effectiveHighest ? 15 : 0;
    }

    public int getBaseLightLevel(BlockPos pos, int ambientDarkness) {
        return Math.max(getLightLevel(LightType.BLOCK, pos),
                Math.max(0, getLightLevel(LightType.SKY, pos) - ambientDarkness));
    }

    public boolean isSkyVisible(BlockPos pos) {
        return getLightLevel(LightType.SKY, pos) >= 15;
    }

    private static boolean isOutsideVanillaHeight(World world, BlockPos pos) {
        return pos.getY() < world.getBottomY() || pos.getY() > world.getTopYInclusive();
    }

    private static boolean isValidHorizontally(BlockPos pos) {
        return pos.getX() >= -HORIZONTAL_LIMIT && pos.getX() < HORIZONTAL_LIMIT
                && pos.getZ() >= -HORIZONTAL_LIMIT && pos.getZ() < HORIZONTAL_LIMIT;
    }

    private static boolean isLoadableHorizontally(BlockPos pos) {
        int chunkX = Math.floorDiv(pos.getX(), 16);
        int chunkZ = Math.floorDiv(pos.getZ(), 16);
        return Math.abs(chunkX) <= 1_875_000 && Math.abs(chunkZ) <= 1_875_000;
    }
}
