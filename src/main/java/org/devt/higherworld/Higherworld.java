package org.devt.higherworld;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.gen.WorldPreset;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.devt.higherworld.gpu.GpuAccelerationConfig;
import org.devt.higherworld.gpu.GpuTerrainAccelerator;
import org.devt.higherworld.world.CubeBlockUpdatePayload;
import org.devt.higherworld.world.CubeDataPayload;
import org.devt.higherworld.world.CubeLightUpdatePayload;
import org.devt.higherworld.world.CubeUnloadPayload;
import org.devt.higherworld.world.CubeWatchManager;
import org.devt.higherworld.world.CubicWorldManager;
import org.devt.higherworld.world.TerrainGeneratorStatusPayload;
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
    private static TerrainGeneratorStatusPayload lastTerrainGeneratorStatus;

    @Override
    public void onInitialize() {
        GpuAccelerationConfig.initialize();
        PayloadTypeRegistry.playS2C().register(CubeDataPayload.ID, CubeDataPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CubeLightUpdatePayload.ID, CubeLightUpdatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CubeUnloadPayload.ID, CubeUnloadPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(CubeBlockUpdatePayload.ID, CubeBlockUpdatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(
                TerrainGeneratorStatusPayload.ID, TerrainGeneratorStatusPayload.CODEC);

        ServerWorldEvents.LOAD.register(CubicWorldManager::open);
        ServerWorldEvents.UNLOAD.register(CubicWorldManager::close);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            lastTerrainGeneratorStatus = null;
            GpuTerrainAccelerator.start();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            GpuTerrainAccelerator.stop();
            lastTerrainGeneratorStatus = null;
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                sendTerrainGeneratorStatus(handler.player));
        ServerTickEvents.END_SERVER_TICK.register(Higherworld::syncTerrainGeneratorStatus);
        ServerTickEvents.START_WORLD_TICK.register(CubeWatchManager::midTick);
        ServerTickEvents.END_WORLD_TICK.register(CubeWatchManager::tick);

        LOGGER.info("HigherWorld sparse cubic runtime is enabled");
    }

    private static void syncTerrainGeneratorStatus(MinecraftServer server) {
        TerrainGeneratorStatusPayload status = terrainGeneratorStatus();
        if (status.equals(lastTerrainGeneratorStatus)) return;
        lastTerrainGeneratorStatus = status;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            sendTerrainGeneratorStatus(player, status);
        }
    }

    private static void sendTerrainGeneratorStatus(ServerPlayerEntity player) {
        sendTerrainGeneratorStatus(player, terrainGeneratorStatus());
    }

    private static void sendTerrainGeneratorStatus(
            ServerPlayerEntity player, TerrainGeneratorStatusPayload status) {
        if (ServerPlayNetworking.canSend(player, TerrainGeneratorStatusPayload.ID)) {
            ServerPlayNetworking.send(player, status);
        }
    }

    private static TerrainGeneratorStatusPayload terrainGeneratorStatus() {
        GpuTerrainAccelerator.Status status = GpuTerrainAccelerator.status();
        return new TerrainGeneratorStatusPayload(status.openCl(), status.detail());
    }
}
