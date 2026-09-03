package org.devt.higherworld.world;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import org.devt.higherworld.Higherworld;
import org.devt.higherworld.storage.CubePos;

/** Authoritative light-only update sent after a cube's first payload. */
public record CubeLightUpdatePayload(CubePos pos, long revision, byte[] data)
        implements CustomPayload {
    private static final int MAX_PAYLOAD_BYTES = 8_192;
    public static final Id<CubeLightUpdatePayload> ID =
            new Id<>(Identifier.of(Higherworld.MOD_ID, "cube_light_update"));
    public static final PacketCodec<RegistryByteBuf, CubeLightUpdatePayload> CODEC =
            CustomPayload.codecOf(CubeLightUpdatePayload::write, CubeLightUpdatePayload::read);

    public CubeLightUpdatePayload {
        if (pos == null) throw new NullPointerException("pos");
        if (!pos.isBlockRangeRepresentable()) {
            throw new IllegalArgumentException("Cube position is outside the signed block range: " + pos);
        }
        if (data == null) throw new NullPointerException("data");
        if (data.length == 0 || data.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Invalid cube light payload length: " + data.length);
        }
        data = data.clone();
    }

    private void write(RegistryByteBuf buffer) {
        buffer.writeInt(pos.x());
        buffer.writeInt(pos.y());
        buffer.writeInt(pos.z());
        buffer.writeVarLong(revision);
        buffer.writeByteArray(data);
    }

    private static CubeLightUpdatePayload read(RegistryByteBuf buffer) {
        CubePos pos = new CubePos(buffer.readInt(), buffer.readInt(), buffer.readInt());
        return new CubeLightUpdatePayload(
                pos, buffer.readVarLong(), buffer.readByteArray(MAX_PAYLOAD_BYTES));
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    @Override
    public Id<CubeLightUpdatePayload> getId() {
        return ID;
    }
}
