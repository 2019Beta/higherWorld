package org.devt.higherworld.world;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import org.devt.higherworld.Higherworld;

/** Ordered before a world's streamed data; separates acknowledgement counters. */
public record CubeStreamStartPayload(long streamId) implements CustomPayload {
    public static final Id<CubeStreamStartPayload> ID =
            new Id<>(Identifier.of(Higherworld.MOD_ID, "cube_stream_start"));
    public static final PacketCodec<RegistryByteBuf, CubeStreamStartPayload> CODEC =
            CustomPayload.codecOf((value, buffer) -> buffer.writeLong(value.streamId()),
                    buffer -> new CubeStreamStartPayload(buffer.readLong()));

    @Override public Id<CubeStreamStartPayload> getId() { return ID; }
}
