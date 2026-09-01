package org.devt.higherworld;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.gen.WorldPreset;
import org.devt.higherworld.world.CubeBlockUpdatePayload;
import org.devt.higherworld.world.CubeDataPayload;
import org.devt.higherworld.world.CubeUnloadPayload;
import org.devt.higherworld.world.CubeWatchManager;
import org.devt.higherworld.world.CubicWorldManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Higherworld implements ModInitializer {
    public static final String MOD_ID = "higherworld";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final RegistryKey<DimensionType> INFINITE_OVERWORLD = RegistryKey.of(
            RegistryKeys.DIMENSION_TYPE, Identifier.of(MOD_ID, "infinite_overworld"));
    public static final RegistryKey<DimensionType> CUSTOM_OVERWORLD = RegistryKey.of(
            RegistryKeys.DIMENSION_TYPE, Identifier.of(MOD_ID, "custom_overworld"));
    public static final RegistryKey<WorldPreset> CUSTOM_WORLD = RegistryKey.of(
            RegistryKeys.WORLD_PRESET, Identifier.of(MOD_ID, "custom"));

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playS2C().register(CubeDataPayload.ID, CubeDataPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CubeUnloadPayload.ID, CubeUnloadPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CubeBlockUpdatePayload.ID, CubeBlockUpdatePayload.CODEC);

        ServerWorldEvents.LOAD.register(CubicWorldManager::open);
        ServerWorldEvents.UNLOAD.register(CubicWorldManager::close);
        ServerTickEvents.START_WORLD_TICK.register(CubeWatchManager::midTick);
        ServerTickEvents.END_WORLD_TICK.register(CubeWatchManager::tick);

        LOGGER.info("HigherWorld sparse cubic runtime is enabled");
    }
}
