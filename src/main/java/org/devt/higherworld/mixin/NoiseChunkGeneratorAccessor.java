package org.devt.higherworld.mixin;

import java.util.function.Supplier;

import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.gen.chunk.AquiferSampler;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Replaces the per-world generator's immutable noise settings as one unit. */
@Mixin(NoiseChunkGenerator.class)
public interface NoiseChunkGeneratorAccessor {
    @Mutable
    @Accessor("settings")
    void higherworld$setSettings(RegistryEntry<ChunkGeneratorSettings> settings);

    @Mutable
    @Accessor("fluidLevelSampler")
    void higherworld$setFluidLevelSampler(
            Supplier<AquiferSampler.FluidLevelSampler> fluidLevelSampler);
}
