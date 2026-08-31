package org.devt.higherworld.mixin;

import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.noise.NoiseRouter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Changes only the per-world sampled router, never the shared registry settings. */
@Mixin(NoiseConfig.class)
public interface NoiseConfigAccessor {
    @Mutable
    @Accessor("noiseRouter")
    void higherworld$setNoiseRouter(NoiseRouter router);
}
