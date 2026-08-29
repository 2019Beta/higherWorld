package org.devt.higherworld.world;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import org.devt.higherworld.Higherworld;
import org.devt.higherworld.storage.CubePos;

public record CubeUnloadPayload(CubePos pos) implements CustomPayload {
    public static final Id<CubeUnloadPayload> ID = CustomPayload.id(Higherworld.MOD_ID + ":cube_unload");
    public static final PacketCodec<RegistryByteBuf, CubeUnloadPayload> CODEC = CustomPayload.codecOf(
            CubeUnloadPayload::write, CubeUnloadPayload::read);

    private void write(RegistryByteBuf buffer) {
        buffer.writeInt(pos.x());
        buffer.writeInt(pos.y());
        buffer.writeInt(pos.z());
    }

    private static CubeUnloadPayload read(RegistryByteBuf buffer) {
        return new CubeUnloadPayload(new CubePos(buffer.readInt(), buffer.readInt(), buffer.readInt()));
    }

    @Override
    public Id<CubeUnloadPayload> getId() {
        return ID;
    }
}
