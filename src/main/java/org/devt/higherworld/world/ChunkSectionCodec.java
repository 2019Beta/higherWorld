package org.devt.higherworld.world;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.world.chunk.ChunkSection;

public final class ChunkSectionCodec {
    private ChunkSectionCodec() {
    }

    public static byte[] encode(ChunkSection section) {
        PacketByteBuf buffer = new PacketByteBuf(Unpooled.buffer(section.getPacketSize()));
        try {
            section.toPacket(buffer);
            byte[] payload = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), payload);
            return payload;
        } finally {
            buffer.release();
        }
    }

    public static void decodeInto(byte[] payload, ChunkSection section) {
        PacketByteBuf buffer = new PacketByteBuf(Unpooled.wrappedBuffer(payload));
        try {
            section.readDataPacket(buffer);
            if (buffer.isReadable()) {
                throw new IllegalArgumentException("Trailing bytes in cube payload: " + buffer.readableBytes());
            }
        } finally {
            buffer.release();
        }
    }
}
