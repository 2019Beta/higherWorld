package org.devt.higherworld.mixin;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.encoding.VarInts;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/** Extends every vanilla BlockPos packet field with an escape-coded full integer Y. */
@Mixin(PacketByteBuf.class)
public abstract class PacketByteBufMixin {
    /**
     * @author HigherWorld
     * @reason The vanilla packed long has only twelve Y bits.
     */
    @Overwrite
    public static BlockPos readBlockPos(ByteBuf buffer) {
        BlockPos packed = BlockPos.fromLong(buffer.readLong());
        if (packed.getY() == -2048) {
            return new BlockPos(packed.getX(), VarInts.read(buffer), packed.getZ());
        }
        return packed;
    }

    /**
     * @author HigherWorld
     * @reason Preserve the vanilla eight-byte encoding in range and escape large Y values.
     */
    @Overwrite
    public static void writeBlockPos(ByteBuf buffer, BlockPos pos) {
        int y = pos.getY();
        if (y >= -2047 && y <= 2047) {
            buffer.writeLong(pos.asLong());
            return;
        }
        buffer.writeLong(BlockPos.asLong(pos.getX(), -2048, pos.getZ()));
        VarInts.write(buffer, y);
    }
}
