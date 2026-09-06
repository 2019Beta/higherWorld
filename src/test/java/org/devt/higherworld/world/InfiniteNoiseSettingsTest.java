package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Blocks;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class InfiniteNoiseSettingsTest {
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @Test
    void sparseSettingsCannotGenerateAquiferLava() {
        ChunkGeneratorSettings source = ChunkGeneratorSettings.createMissingSettings();

        ChunkGeneratorSettings sparse = InfiniteNoiseSettings.createSparseSettings(
                source, source.generationShapeConfig(), source.noiseRouter());

        assertFalse(sparse.aquifers());
        assertTrue(sparse.defaultFluid().isAir());
        assertTrue(sparse.defaultBlock().isOf(Blocks.DEEPSLATE));
    }

    @Test
    void transitionSamplerIsDryBelowVanillaLavaLevel() {
        var sampler = InfiniteNoiseSettings.dryDeepFluidSampler(
                63, Blocks.WATER.getDefaultState());

        assertTrue(sampler.getFluidLevel(0, -55, 0).getBlockState(-55).isAir());
        assertTrue(sampler.getFluidLevel(0, -54, 0).getBlockState(-54)
                .isOf(Blocks.WATER));
    }
}
