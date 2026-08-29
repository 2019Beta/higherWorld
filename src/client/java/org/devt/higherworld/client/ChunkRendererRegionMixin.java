package org.devt.higherworld.client.mixin;

import net.minecraft.client.render.chunk.ChunkRendererRegion;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import org.devt.higherworld.client.ClientCubeCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Provides deterministic light values while the cubic light graph is sparse. */
@Mixin(ChunkRendererRegion.class)
abstract class ChunkRendererRegionMixin {
    @Shadow @Final private World world;

    public int getLightLevel(LightType type, BlockPos pos) {
        if (!outsideVanillaHeight(pos)) {
            return world.getLightLevel(type, pos);
        }
        if (type == LightType.SKY) {
            if (!world.getDimension().hasSkyLight()) {
                return 0;
            }
            Integer highest = ClientCubeCache.highestBlockY((net.minecraft.client.world.ClientWorld) world,
                    pos.getX(), pos.getZ());
            int vanillaHighest = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING,
                    pos.getX(), pos.getZ()) - 1;
            int effectiveHighest = highest == null ? vanillaHighest : Math.max(vanillaHighest, highest);
            return pos.getY() > effectiveHighest ? 15 : 0;
        }
        return world.getBlockState(pos).getLuminance();
    }

    public int getBaseLightLevel(BlockPos pos, int ambientDarkness) {
        if (!outsideVanillaHeight(pos)) {
            return world.getBaseLightLevel(pos, ambientDarkness);
        }
        int sky = Math.max(0, getLightLevel(LightType.SKY, pos) - ambientDarkness);
        return Math.max(sky, world.getBlockState(pos).getLuminance());
    }

    public boolean isSkyVisible(BlockPos pos) {
        return outsideVanillaHeight(pos) ? getLightLevel(LightType.SKY, pos) >= 15 : world.isSkyVisible(pos);
    }

    @Unique
    private boolean outsideVanillaHeight(BlockPos pos) {
        return pos.getY() < world.getBottomY() || pos.getY() > world.getTopYInclusive();
    }
}
