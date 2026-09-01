package org.devt.higherworld.world;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.devt.higherworld.Higherworld;

/** Block update that encodes Y as a full signed integer instead of BlockPos's packed long. */
public record CubeBlockUpdatePayload(int x, int y, int z, int rawStateId, long revision) implements CustomPayload {
    public static final Id<CubeBlockUpdatePayload> ID = new Id<>(Identifier.of(Higherworld.MOD_ID, "cube_block_update"));
    public static final PacketCodec<RegistryByteBuf, CubeBlockUpdatePayload> CODEC = CustomPayload.codecOf(
            CubeBlockUpdatePayload::write, CubeBlockUpdatePayload::read);

    public CubeBlockUpdatePayload(int x, int y, int z, int rawStateId) {
        this(x, y, z, rawStateId, 0L);
    }

    public CubeBlockUpdatePayload(BlockPos pos, BlockState state) {
        this(pos, state, 0L);
    }

    public CubeBlockUpdatePayload(BlockPos pos, BlockState state, long revision) {
        this(pos.getX(), pos.getY(), pos.getZ(), Block.getRawIdFromState(state), revision);
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
        buffer.writeVarLong(revision);
    }

    private static CubeBlockUpdatePayload read(RegistryByteBuf buffer) {
        return new CubeBlockUpdatePayload(
                buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readVarInt(), buffer.readVarLong());
    }

    @Override
    public Id<CubeBlockUpdatePayload> getId() {
        return ID;
    }
}
