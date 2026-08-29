package org.devt.higherworld.mixin;

import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import org.devt.higherworld.world.CubicWorldManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mirrors every real vanilla chunk save into HigherWorld's cube store. */
@Mixin(ServerChunkLoadingManager.class)
abstract class ServerChunkLoadingManagerMixin {
    @Shadow
    @Final
    private ServerWorld world;

    @Inject(method = "save(Lnet/minecraft/world/chunk/Chunk;)Z", at = @At("HEAD"))
    private void higherworld$saveCubes(Chunk chunk, CallbackInfoReturnable<Boolean> callbackInfo) {
        if (chunk instanceof WorldChunk worldChunk) {
            CubicWorldManager.save(world, worldChunk);
        }
    }
}
