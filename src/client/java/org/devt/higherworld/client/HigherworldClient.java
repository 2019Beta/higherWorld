package org.devt.higherworld.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.devt.higherworld.world.CubeBlockUpdatePayload;
import org.devt.higherworld.world.CubeDataPayload;
import org.devt.higherworld.world.CubeUnloadPayload;

public class HigherworldClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> ClientCubeCache.tickLighting());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(ClientCubeCache::clear));
        ClientPlayNetworking.registerGlobalReceiver(CubeDataPayload.ID, (payload, context) -> {
            // Custom payload receivers may run on the network thread.  Decode,
            // publish light and invalidate render chunks on the client thread so
            // the sparse cache has the same single-writer guarantee as the server.
            context.client().execute(() -> {
                if (context.client().world != null) {
                    ClientCubeCache.put(context.client().world, payload.pos(), payload.revision(), payload.data());
                }
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(CubeUnloadPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                if (context.client().world != null) {
                    ClientCubeCache.unload(
                            context.client().world, payload.pos(), payload.revision());
                }
            });
        });
        ClientPlayNetworking.registerGlobalReceiver(CubeBlockUpdatePayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                if (context.client().world != null
                        && ClientCubeCache.acceptsBlockUpdate(
                                context.client().world, payload.blockPos(), payload.revision())) {
                    // Use the same path as vanilla BlockUpdateS2CPacket. In particular,
                    // this replaces the state stored by PendingUpdateManager for a
                    // predicted break. Writing the cube cache directly leaves the old
                    // state pending, so the sequence acknowledgement resurrects a
                    // server-side deleted block until the client rejoins.
                    context.client().world.handleBlockUpdate(
                            payload.blockPos(), payload.blockState(), 19);
                }
            });
        });
    }
}
