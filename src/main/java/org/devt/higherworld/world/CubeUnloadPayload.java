package org.devt.higherworld.world;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import org.devt.higherworld.Higherworld;
import org.devt.higherworld.storage.CubePos;

public record CubeUnloadPayload(CubePos pos, long revision) implements CustomPayload {
    public static final Id<CubeUnloadPayload> ID = new Id<>(Identifier.of(Higherworld.MOD_ID, "cube_unload"));
    public static final PacketCodec<RegistryByteBuf, CubeUnloadPayload> CODEC = CustomPayload.codecOf(
            CubeUnloadPayload::write, CubeUnloadPayload::read);

    public CubeUnloadPayload(CubePos pos) {
        this(pos, 0L);
    }

    private void write(RegistryByteBuf buffer) {
        buffer.writeInt(pos.x());
        buffer.writeInt(pos.y());
        buffer.writeInt(pos.z());
        buffer.writeVarLong(revision);
    }

    private static CubeUnloadPayload read(RegistryByteBuf buffer) {
        return new CubeUnloadPayload(
                new CubePos(buffer.readInt(), buffer.readInt(), buffer.readInt()), buffer.readVarLong());
    }

    @Override
    public Id<CubeUnloadPayload> getId() {
        return ID;
    }
}
