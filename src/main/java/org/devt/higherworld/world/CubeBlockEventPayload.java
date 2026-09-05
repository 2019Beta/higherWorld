package org.devt.higherworld.world;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.devt.higherworld.Higherworld;

/** A vanilla block event with an uncompressed, full-range block position. */
public record CubeBlockEventPayload(
        int x, int y, int z, int rawStateId, int type, int data) implements CustomPayload {
    public static final Id<CubeBlockEventPayload> ID =
            new Id<>(Identifier.of(Higherworld.MOD_ID, "cube_block_event"));
    public static final PacketCodec<RegistryByteBuf, CubeBlockEventPayload> CODEC =
            CustomPayload.codecOf(CubeBlockEventPayload::write, CubeBlockEventPayload::read);

    public CubeBlockEventPayload(BlockPos pos, BlockState state, int type, int data) {
        this(pos.getX(), pos.getY(), pos.getZ(), Block.getRawIdFromState(state), type, data);
    }

    public BlockPos blockPos() {
        return new BlockPos(x, y, z);
    }

    public BlockState blockState() {
        return Block.getStateFromRawId(rawStateId);
    }

    public Block block() {
        return blockState().getBlock();
    }

    private void write(RegistryByteBuf buffer) {
        buffer.writeInt(x);
        buffer.writeInt(y);
        buffer.writeInt(z);
        buffer.writeVarInt(rawStateId);
        buffer.writeVarInt(type);
        buffer.writeVarInt(data);
    }

    private static CubeBlockEventPayload read(RegistryByteBuf buffer) {
        return new CubeBlockEventPayload(
                buffer.readInt(), buffer.readInt(), buffer.readInt(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt());
    }

    @Override
    public Id<CubeBlockEventPayload> getId() {
        return ID;
    }
}
