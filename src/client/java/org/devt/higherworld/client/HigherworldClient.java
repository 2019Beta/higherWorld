package org.devt.higherworld.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.devt.higherworld.world.CubeBlockUpdatePayload;
import org.devt.higherworld.world.CubeDataPayload;
import org.devt.higherworld.world.CubeUnloadPayload;

public class HigherworldClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientCubeCache.clear());
        ClientPlayNetworking.registerGlobalReceiver(CubeDataPayload.ID, (payload, context) -> {
            if (context.client().world != null) {
                ClientCubeCache.put(context.client().world, payload.pos(), payload.data());
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(CubeUnloadPayload.ID, (payload, context) -> {
            if (context.client().world != null) {
                ClientCubeCache.unload(context.client().world, payload.pos());
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(CubeBlockUpdatePayload.ID, (payload, context) -> {
            if (context.client().world != null) {
                ClientCubeCache.setBlockState(context.client().world, payload.blockPos(), payload.blockState());
            }
        });
    }
}
