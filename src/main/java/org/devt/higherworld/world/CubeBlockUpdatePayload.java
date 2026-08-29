package org.devt.higherworld.world;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;
import org.devt.higherworld.Higherworld;

/** Block update that encodes Y as a full signed integer instead of BlockPos's packed long. */
public record CubeBlockUpdatePayload(int x, int y, int z, int rawStateId) implements CustomPayload {
    public static final Id<CubeBlockUpdatePayload> ID = CustomPayload.id(Higherworld.MOD_ID + ":cube_block_update");
    public static final PacketCodec<RegistryByteBuf, CubeBlockUpdatePayload> CODEC = CustomPayload.codecOf(
            CubeBlockUpdatePayload::write, CubeBlockUpdatePayload::read);

    public CubeBlockUpdatePayload(BlockPos pos, BlockState state) {
        this(pos.getX(), pos.getY(), pos.getZ(), Block.getRawIdFromState(state));
    }

    public BlockPos blockPos() {
        return new BlockPos(x, y, z);
    }

    public BlockState blockState() {
        return Block.getStateFromRawId(rawStateId);
    }

    private void write(RegistryByteBuf buffer) {
        buffer.writeInt(x);
        buffer.writeInt(y);
        buffer.writeInt(z);
        buffer.writeVarInt(rawStateId);
    }

    private static CubeBlockUpdatePayload read(RegistryByteBuf buffer) {
        return new CubeBlockUpdatePayload(buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readVarInt());
    }

    @Override
    public Id<CubeBlockUpdatePayload> getId() {
        return ID;
    }
}
