package org.devt.higherworld.world;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import org.devt.higherworld.Higherworld;

/** Server-authoritative terrain backend information shown by the client F3 HUD. */
public record TerrainGeneratorStatusPayload(boolean openCl, String detail)
        implements CustomPayload {
    private static final int MAX_DETAIL_LENGTH = 256;

    public static final Id<TerrainGeneratorStatusPayload> ID =
            new Id<>(Identifier.of(Higherworld.MOD_ID, "terrain_generator_status"));
    public static final PacketCodec<RegistryByteBuf, TerrainGeneratorStatusPayload> CODEC =
            CustomPayload.codecOf(
                    TerrainGeneratorStatusPayload::write,
                    TerrainGeneratorStatusPayload::read);

    public TerrainGeneratorStatusPayload {
        if (detail == null) throw new NullPointerException("detail");
        if (detail.length() > MAX_DETAIL_LENGTH) {
            throw new IllegalArgumentException("Terrain generator status detail is too long");
        }
    }

    private void write(RegistryByteBuf buffer) {
        buffer.writeBoolean(openCl);
        buffer.writeString(detail, MAX_DETAIL_LENGTH);
    }

    private static TerrainGeneratorStatusPayload read(RegistryByteBuf buffer) {
        return new TerrainGeneratorStatusPayload(
                buffer.readBoolean(), buffer.readString(MAX_DETAIL_LENGTH));
    }

    @Override
    public Id<TerrainGeneratorStatusPayload> getId() {
        return ID;
    }
}
