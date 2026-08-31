package org.devt.higherworld.world;

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
        accessor.higherworld$setFluidLevelSampler(() ->
                continuousAquiferFluidSampler(rewritten));

        NoiseConfig noiseConfig = world.getChunkManager().getNoiseConfig();
        NoiseRouter sampledRouter = InfiniteDownwardGenerator.removeVanillaBottomSlide(
                noiseConfig.getNoiseRouter());
        ((NoiseConfigAccessor) (Object) noiseConfig).higherworld$setNoiseRouter(sampledRouter);
    }

    static ChunkGeneratorSettings createSparseSettings(
            ChunkGeneratorSettings source, GenerationShapeConfig shape, NoiseRouter router) {
        return copy(source, shape, Blocks.DEEPSLATE.getDefaultState(),
                source.defaultFluid(), router, true);
    }

    static void useContinuousAquifers(NoiseChunkGenerator generator) {
        ChunkGeneratorSettings settings = generator.getSettings().value();
        ((NoiseChunkGeneratorAccessor) (Object) generator)
                .higherworld$setFluidLevelSampler(() ->
                        continuousAquiferFluidSampler(settings));
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
     * Vanilla's generator substitutes a fixed lava level below Y=-54. That
     * state bypasses the aquifer noises entirely and becomes an infinite lava
     * volume when the terrain has no lower bound. A single base level lets the
     * floodedness, spread and lava-type noises decide every underground cell,
     * so the field stays continuous across the former Y=-64 boundary.
     */
    private static AquiferSampler.FluidLevelSampler continuousAquiferFluidSampler(
            ChunkGeneratorSettings settings) {
        AquiferSampler.FluidLevel base = new AquiferSampler.FluidLevel(
                settings.seaLevel(), settings.defaultFluid());
        return (x, y, z) -> base;
    }
}
