package org.devt.higherworld.client;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Map;
import java.util.HashMap;
import net.minecraft.block.entity.BlockEntity;
import org.devt.higherworld.storage.CubePos;

/** Supplies render snapshots from the cubic cache when a section index is outside a vanilla column. */
@Mixin(targets = "net.minecraft.client.render.chunk.RenderedChunk")
abstract class RenderedChunkMixin {
    @Shadow @Final @Mutable
    private PalettedContainer<BlockState> blockPalette;
    @Shadow @Final @Mutable
    private Map<net.minecraft.util.math.BlockPos, BlockEntity> blockEntities;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void higherworld$copyCubePalette(WorldChunk column, int sectionIndex, CallbackInfo callbackInfo) {
        int sectionY = column.sectionIndexToCoord(sectionIndex);
        ClientWorld world = (ClientWorld) column.getWorld();
        ChunkSection section = ClientCubeCache.getSection(world, column.getPos().x, sectionY, column.getPos().z);
        if (section != null) {
            blockPalette = section.isEmpty() ? null : section.getBlockStateContainer().copy();
            Map<net.minecraft.util.math.BlockPos, BlockEntity> entities = new HashMap<>();
            for (BlockEntity blockEntity : ClientCubeCache.getBlockEntities(
                    world, new CubePos(column.getPos().x, sectionY, column.getPos().z))) {
                entities.put(blockEntity.getPos(), blockEntity);
            }
            blockEntities = Map.copyOf(entities);
        }
    }
}
