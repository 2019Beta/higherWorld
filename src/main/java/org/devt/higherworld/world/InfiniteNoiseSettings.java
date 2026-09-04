package org.devt.higherworld.world;

import net.minecraft.SharedConstants;
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
        // NoiseChunkGenerator's vanilla sampler uses a fixed lava level below
        // Y=-54.  That level is safe only inside the finite vanilla band: when
        // reused below it, it bypasses the aquifer noise and fills every deep
        // opening with lava. Use one continuous base level instead.
        accessor.higherworld$setFluidLevelSampler(() -> unboundedVanillaFluidSampler(rewritten));

        NoiseConfig noiseConfig = world.getChunkManager().getNoiseConfig();
        NoiseRouter sampledRouter = InfiniteDownwardGenerator.removeVanillaBottomSlide(
                noiseConfig.getNoiseRouter());
        ((NoiseConfigAccessor) (Object) noiseConfig).higherworld$setNoiseRouter(sampledRouter);
    }

    static ChunkGeneratorSettings createSparseSettings(
            ChunkGeneratorSettings source, GenerationShapeConfig shape, NoiseRouter router) {
        return copy(source, shape, Blocks.DEEPSLATE.getDefaultState(),
                source.defaultFluid(), router, source.aquifers());
    }

    static void useUnboundedAquifers(NoiseChunkGenerator generator) {
        ChunkGeneratorSettings settings = generator.getSettings().value();
        ((NoiseChunkGeneratorAccessor) (Object) generator)
                .higherworld$setFluidLevelSampler(() -> unboundedVanillaFluidSampler(settings));
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

    /**
     * Keep the aquifer picker independent of the finite vanilla dimension
     * bottom. A fixed lava level would short-circuit the aquifer noise below
     * Y=-54; a continuous base lets the floodedness, spread, and lava noises
     * choose the fluid type and surface at every generated Y coordinate.
     */
    private static AquiferSampler.FluidLevelSampler unboundedVanillaFluidSampler(
            ChunkGeneratorSettings settings) {
        AquiferSampler.FluidLevel base = new AquiferSampler.FluidLevel(
                settings.seaLevel(), settings.defaultFluid());
        AquiferSampler.FluidLevel air = new AquiferSampler.FluidLevel(
                Integer.MAX_VALUE, Blocks.AIR.getDefaultState());
        return (x, y, z) -> SharedConstants.DISABLE_FLUID_GENERATION
                ? air : base;
    }
}
