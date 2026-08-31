package org.devt.higherworld.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.world.gen.chunk.ChunkNoiseSampler;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.surfacebuilder.MaterialRules;
import net.minecraft.world.gen.surfacebuilder.VanillaSurfaceRules;
import org.devt.higherworld.world.InfiniteWorldgenHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Applies the no-floor world settings only to HigherWorld's infinite generator. */
@Mixin(NoiseChunkGenerator.class)
abstract class NoiseChunkGeneratorMixin {
    @Unique
    private static final MaterialRules.MaterialRule HIGHERWORLD_NO_BEDROCK_SURFACE =
            VanillaSurfaceRules.createDefaultRule(true, false, false);

    @ModifyArg(
            method = "buildSurface(Lnet/minecraft/world/chunk/Chunk;"
                    + "Lnet/minecraft/world/gen/HeightContext;"
                    + "Lnet/minecraft/world/gen/noise/NoiseConfig;"
                    + "Lnet/minecraft/world/gen/StructureAccessor;"
                    + "Lnet/minecraft/world/biome/source/BiomeAccess;"
                    + "Lnet/minecraft/registry/Registry;"
                    + "Lnet/minecraft/world/gen/chunk/Blender;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/gen/surfacebuilder/SurfaceBuilder;buildSurface("
                            + "Lnet/minecraft/world/gen/noise/NoiseConfig;"
                            + "Lnet/minecraft/world/biome/source/BiomeAccess;"
                            + "Lnet/minecraft/registry/Registry;Z"
                            + "Lnet/minecraft/world/gen/HeightContext;"
                            + "Lnet/minecraft/world/chunk/Chunk;"
                            + "Lnet/minecraft/world/gen/chunk/ChunkNoiseSampler;"
                            + "Lnet/minecraft/world/gen/surfacebuilder/MaterialRules$MaterialRule;)V"),
            index = 7)
    private MaterialRules.MaterialRule higherworld$removeBedrockSurfaceRule(
            MaterialRules.MaterialRule original) {
        NoiseChunkGenerator generator = (NoiseChunkGenerator) (Object) this;
        return InfiniteWorldgenHooks.isInfinite(generator)
                ? HIGHERWORLD_NO_BEDROCK_SURFACE
                : original;
    }

    @Inject(
            method = "getBlockState(Lnet/minecraft/world/gen/chunk/ChunkNoiseSampler;IIILnet/"
                    + "minecraft/block/BlockState;)Lnet/minecraft/block/BlockState;",
            at = @At("RETURN"),
            cancellable = true)
    private void higherworld$keepVanillaFloorDry(
            ChunkNoiseSampler sampler, int x, int y, int z, BlockState state,
            CallbackInfoReturnable<BlockState> callbackInfo) {
        NoiseChunkGenerator generator = (NoiseChunkGenerator) (Object) this;
        BlockState generated = callbackInfo.getReturnValue();
        if (InfiniteWorldgenHooks.isBelowVanillaFloor(generator, y)
                && generated != null && !generated.getFluidState().isEmpty()) {
            callbackInfo.setReturnValue(Blocks.AIR.getDefaultState());
        }
    }
}
