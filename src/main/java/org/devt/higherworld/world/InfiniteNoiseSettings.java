package org.devt.higherworld.world;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.gen.chunk.AquiferSampler;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.GenerationShapeConfig;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.noise.NoiseRouter;
import net.minecraft.world.gen.surfacebuilder.VanillaSurfaceRules;
import org.devt.higherworld.mixin.NoiseChunkGeneratorAccessor;
import org.devt.higherworld.mixin.NoiseConfigAccessor;

/** Complete per-world noise settings for the unbounded-downward overworld. */
final class InfiniteNoiseSettings {
    private static final int VANILLA_LAVA_LEVEL = -54;

    private InfiniteNoiseSettings() {
    }

    static void rewriteWorldGenerator(ServerWorld world, NoiseChunkGenerator generator) {
        ChunkGeneratorSettings original = generator.getSettings().value();
        NoiseRouter settingsRouter = InfiniteDownwardGenerator.removeVanillaBottomSlide(
                original.noiseRouter());
        ChunkGeneratorSettings rewritten = copy(
                original, original.generationShapeConfig(),
                original.defaultBlock(), original.defaultFluid(),
                settingsRouter, original.aquifers());

        NoiseChunkGeneratorAccessor accessor = (NoiseChunkGeneratorAccessor) (Object) generator;
        accessor.higherworld$setSettings(RegistryEntry.of(rewritten));
        // Do not let the finite vanilla lava floor leak into the unbounded
        // extension. The original band keeps its water aquifer, while samples
        // below the vanilla lava cutoff become air.
        accessor.higherworld$setFluidLevelSampler(() -> dryDeepFluidSampler(
                rewritten.seaLevel(), rewritten.defaultFluid()));

        NoiseConfig noiseConfig = world.getChunkManager().getNoiseConfig();
        NoiseRouter sampledRouter = InfiniteDownwardGenerator.removeVanillaBottomSlide(
                noiseConfig.getNoiseRouter());
        ((NoiseConfigAccessor) (Object) noiseConfig).higherworld$setNoiseRouter(sampledRouter);
    }

    static ChunkGeneratorSettings createSparseSettings(
            ChunkGeneratorSettings source, GenerationShapeConfig shape, NoiseRouter router) {
        return copy(source, shape, Blocks.DEEPSLATE.getDefaultState(),
                Blocks.AIR.getDefaultState(), router, false);
    }

    static void makeSparseGeneratorDry(NoiseChunkGenerator generator) {
        AquiferSampler.FluidLevel air = airLevel();
        ((NoiseChunkGeneratorAccessor) (Object) generator)
                .higherworld$setFluidLevelSampler(() -> (x, y, z) -> air);
    }

    private static ChunkGeneratorSettings copy(
            ChunkGeneratorSettings source, GenerationShapeConfig shape,
            net.minecraft.block.BlockState defaultBlock,
            net.minecraft.block.BlockState defaultFluid, NoiseRouter router, boolean aquifers) {
        return new ChunkGeneratorSettings(
                shape,
                defaultBlock,
                defaultFluid,
                router,
                VanillaSurfaceRules.createDefaultRule(true, false, false),
                source.spawnTarget(),
                source.seaLevel(),
                source.mobGenerationDisabled(),
                aquifers,
                source.oreVeins(),
                source.usesLegacyRandom());
    }

    static AquiferSampler.FluidLevelSampler dryDeepFluidSampler(
            int seaLevel, BlockState defaultFluid) {
        AquiferSampler.FluidLevel air = new AquiferSampler.FluidLevel(
                Integer.MAX_VALUE, Blocks.AIR.getDefaultState());
        AquiferSampler.FluidLevel water = new AquiferSampler.FluidLevel(
                seaLevel, defaultFluid);
        return (x, y, z) -> y < VANILLA_LAVA_LEVEL ? air : water;
    }

    private static AquiferSampler.FluidLevel airLevel() {
        return new AquiferSampler.FluidLevel(Integer.MAX_VALUE, Blocks.AIR.getDefaultState());
    }
}
