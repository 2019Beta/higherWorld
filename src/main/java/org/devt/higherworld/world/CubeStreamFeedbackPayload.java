package org.devt.higherworld.world;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import org.devt.higherworld.Higherworld;

/** Cumulative processed bytes, plus downstream pressure sampled by the client. */
public record CubeStreamFeedbackPayload(
        long streamId, long processedBytes, long pendingBytes, int pendingUpdates,
        int oldestMillis, int pendingRenders, int pendingLightCubes) implements CustomPayload {
    public static final Id<CubeStreamFeedbackPayload> ID =
            new Id<>(Identifier.of(Higherworld.MOD_ID, "cube_stream_feedback"));
    public static final PacketCodec<RegistryByteBuf, CubeStreamFeedbackPayload> CODEC =
            CustomPayload.codecOf(CubeStreamFeedbackPayload::write, CubeStreamFeedbackPayload::read);

    private void write(RegistryByteBuf buffer) {
        buffer.writeLong(streamId);
        buffer.writeVarLong(processedBytes);
        buffer.writeVarLong(pendingBytes);
        buffer.writeVarInt(pendingUpdates);
        buffer.writeVarInt(oldestMillis);
        buffer.writeVarInt(pendingRenders);
        buffer.writeVarInt(pendingLightCubes);
    }

    private static CubeStreamFeedbackPayload read(RegistryByteBuf buffer) {
        return new CubeStreamFeedbackPayload(buffer.readLong(), buffer.readVarLong(),
                buffer.readVarLong(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readVarInt());
    }

    boolean valid() {
        return processedBytes >= 0 && pendingBytes >= 0 && pendingUpdates >= 0
                && oldestMillis >= 0 && pendingRenders >= 0 && pendingLightCubes >= 0;
    }

    /** Shared by sender throttling and client transition-triggered feedback. */
    public boolean hasBackpressure() {
        return pendingBytes >= CubeStreamBudget.WINDOW_BYTES / 2
                || pendingUpdates >= 512 || oldestMillis >= 250
                || pendingRenders >= 768 || pendingLightCubes >= 512;
    }

    @Override public Id<CubeStreamFeedbackPayload> getId() { return ID; }
}
