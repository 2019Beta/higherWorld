package org.devt.higherworld.world;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import org.devt.higherworld.Higherworld;
import org.devt.higherworld.storage.CubePos;

/** Full block and biome palette data for one 16-cubed cube. */
public record CubeDataPayload(CubePos pos, long revision, byte[] data) implements CustomPayload {
    private static final int MAX_PAYLOAD_BYTES = 2 * 1024 * 1024;
    public static final Id<CubeDataPayload> ID = new Id<>(Identifier.of(Higherworld.MOD_ID, "cube_data"));
    public static final PacketCodec<RegistryByteBuf, CubeDataPayload> CODEC = CustomPayload.codecOf(
            CubeDataPayload::write, CubeDataPayload::read);

    public CubeDataPayload {
        if (pos == null) {
            throw new NullPointerException("pos");
        }
        if (!pos.isBlockRangeRepresentable()) {
            throw new IllegalArgumentException("Cube position is outside the signed block range: " + pos);
        }
        if (data == null) {
            throw new NullPointerException("data");
        }
        if (!canEncode(data)) {
            throw new IllegalArgumentException("Cube payload is too large: " + data.length);
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

    private static CubeDataPayload read(RegistryByteBuf buffer) {
        CubePos pos = new CubePos(buffer.readInt(), buffer.readInt(), buffer.readInt());
        return new CubeDataPayload(pos, buffer.readVarLong(), buffer.readByteArray(MAX_PAYLOAD_BYTES));
    }

    public CubeDataPayload(CubePos pos, byte[] data) {
        this(pos, 0L, data);
    }

    /** Returns a snapshot so callers cannot mutate an already queued packet. */
    @Override
    public byte[] data() {
        return data.clone();
    }

    public static boolean canEncode(byte[] data) {
        return data != null && data.length <= MAX_PAYLOAD_BYTES;
    }

    @Override
    public Id<CubeDataPayload> getId() {
        return ID;
    }
}
