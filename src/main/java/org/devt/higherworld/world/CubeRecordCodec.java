package org.devt.higherworld.world;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;

/** Versioned cube payload containing palettes plus block entities. */
public final class CubeRecordCodec {
    private static final int MAGIC = 0x48574332; // HWC2
    private static final int MAX_SECTION_BYTES = 2 * 1024 * 1024;
    private static final int MAX_BLOCK_ENTITIES = 4096;

    private CubeRecordCodec() {
    }

    static byte[] encode(LoadedCube cube, World world) throws IOException {
        byte[] sectionPayload = ChunkSectionCodec.encode(cube.section());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(sectionPayload.length + 128);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(sectionPayload.length);
            output.write(sectionPayload);
            output.writeInt(cube.blockEntities().size());
            for (BlockEntity blockEntity : cube.blockEntities()) {
                NbtIo.writeCompound(blockEntity.createNbtWithIdentifyingData(world.getRegistryManager()), output);
            }
        }
        return bytes.toByteArray();
    }

    public static List<BlockEntity> decode(byte[] payload, ChunkSection section, World world) throws IOException {
        if (payload.length < Integer.BYTES || readMagic(payload) != MAGIC) {
            ChunkSectionCodec.decodeInto(payload, section);
            return List.of();
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            input.readInt();
            int sectionLength = input.readInt();
            if (sectionLength < 0 || sectionLength > MAX_SECTION_BYTES || sectionLength > input.available()) {
                throw new IOException("Invalid cube section payload length: " + sectionLength);
            }
            ChunkSectionCodec.decodeInto(input.readNBytes(sectionLength), section);

            int count = input.readInt();
            if (count < 0 || count > MAX_BLOCK_ENTITIES) {
                throw new IOException("Invalid cube block entity count: " + count);
            }
            List<BlockEntity> blockEntities = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                NbtCompound nbt = NbtIo.readCompound(input, NbtSizeTracker.ofUnlimitedBytes());
                BlockPos pos = new BlockPos(nbt.getInt("x", 0), nbt.getInt("y", 0), nbt.getInt("z", 0));
                BlockEntity blockEntity = BlockEntity.createFromNbt(
                        pos, section.getBlockState(Math.floorMod(pos.getX(), 16), Math.floorMod(pos.getY(), 16),
                                Math.floorMod(pos.getZ(), 16)), nbt, world.getRegistryManager());
                if (blockEntity != null) {
                    blockEntity.setWorld(world);
                    blockEntities.add(blockEntity);
                }
            }
            if (input.available() != 0) {
                throw new IOException("Trailing bytes in cube record: " + input.available());
            }
            return blockEntities;
        }
    }

    private static int readMagic(byte[] payload) {
        return (payload[0] & 0xFF) << 24 | (payload[1] & 0xFF) << 16
                | (payload[2] & 0xFF) << 8 | payload[3] & 0xFF;
    }
}
