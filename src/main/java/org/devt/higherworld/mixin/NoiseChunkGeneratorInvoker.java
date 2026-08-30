package org.devt.higherworld.mixin;

import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes vanilla's synchronous noise fill without scheduling a nested future. */
@Mixin(NoiseChunkGenerator.class)
public interface NoiseChunkGeneratorInvoker {
    @Invoker("populateNoise")
    Chunk higherworld$populateNoiseSynchronously(
            Blender blender, StructureAccessor structureAccessor, NoiseConfig noiseConfig,
            Chunk chunk, int minimumCellY, int cellHeight);
}
