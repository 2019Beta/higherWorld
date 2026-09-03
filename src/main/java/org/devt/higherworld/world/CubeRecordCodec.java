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
    private static final int MAGIC_V2 = 0x48574332; // HWC2
    private static final int MAGIC_V3 = 0x48574333; // HWC3, includes generator version
    private static final int MAGIC_V4 = 0x48574334; // HWC4, includes sparse cube light
    private static final int MAGIC_V5 = 0x48574335; // HWC5, includes cube scheduled ticks
    private static final int MAX_SECTION_BYTES = 2 * 1024 * 1024;
    private static final int MAX_BLOCK_ENTITIES = 4096;
    private static final int MAX_SCHEDULED_TICKS = 8192;

    private CubeRecordCodec() {
    }

    static byte[] encode(LoadedCube cube, World world) throws IOException {
        byte[] sectionPayload = ChunkSectionCodec.encode(cube.section());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(sectionPayload.length + 128);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC_V5);
            output.writeInt(cube.generationVersion());
            output.writeInt(sectionPayload.length);
            output.write(sectionPayload);
            output.writeInt(cube.blockEntities().size());
            for (BlockEntity blockEntity : cube.blockEntities()) {
                NbtIo.writeCompound(blockEntity.createNbtWithIdentifyingData(world.getRegistryManager()), output);
            }
            CubeLightData.write(output, cube.light().snapshot());
            CubeScheduledTickQueue queue = world instanceof net.minecraft.server.world.ServerWorld serverWorld
                    ? CubeScheduledTickQueue.forWorld(serverWorld) : null;
            List<CubeScheduledTick> ticks = queue == null ? List.of() : queue.snapshot(cube.pos());
            if (ticks.size() > MAX_SCHEDULED_TICKS) {
                throw new IOException("Too many scheduled ticks in cube " + cube.pos());
            }
            output.writeInt(ticks.size());
            for (CubeScheduledTick tick : ticks) {
                tick.write(output);
            }
        }
        return bytes.toByteArray();
    }

    public static DecodedCube decode(byte[] payload, ChunkSection section, World world) throws IOException {
        int magic = payload.length < Integer.BYTES ? 0 : readMagic(payload);
        if (magic != MAGIC_V2 && magic != MAGIC_V3 && magic != MAGIC_V4 && magic != MAGIC_V5) {
            ChunkSectionCodec.decodeInto(payload, section);
            return new DecodedCube(List.of(), CubeLightData.Snapshot.dark(), false, List.of());
        }

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            input.readInt();
            if (magic == MAGIC_V3 || magic == MAGIC_V4 || magic == MAGIC_V5) {
                input.readInt();
            }
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
            CubeLightData.Snapshot light = CubeLightData.Snapshot.dark();
            boolean hasLight = magic == MAGIC_V4 || magic == MAGIC_V5;
            if (hasLight) {
                light = CubeLightData.read(input);
            }
            List<CubeScheduledTick> scheduledTicks = List.of();
            if (magic == MAGIC_V5) {
                int tickCount = input.readInt();
                if (tickCount < 0 || tickCount > MAX_SCHEDULED_TICKS) {
                    throw new IOException("Invalid scheduled tick count: " + tickCount);
                }
                List<CubeScheduledTick> decodedTicks = new ArrayList<>(tickCount);
                for (int index = 0; index < tickCount; index++) {
                    decodedTicks.add(CubeScheduledTick.read(input));
                }
                scheduledTicks = List.copyOf(decodedTicks);
            }
            if (input.available() != 0) {
                throw new IOException("Trailing bytes in cube record: " + input.available());
            }
            return new DecodedCube(List.copyOf(blockEntities), light, hasLight, scheduledTicks);
        }
    }

    static int generationVersion(byte[] payload) {
        int magic = payload.length < Integer.BYTES ? 0 : readMagic(payload);
        if (payload.length < Integer.BYTES * 2
                || (magic != MAGIC_V3 && magic != MAGIC_V4 && magic != MAGIC_V5)) {
            return 0;
        }
        return (payload[4] & 0xFF) << 24 | (payload[5] & 0xFF) << 16
                | (payload[6] & 0xFF) << 8 | payload[7] & 0xFF;
    }

    private static int readMagic(byte[] payload) {
        return (payload[0] & 0xFF) << 24 | (payload[1] & 0xFF) << 16
                | (payload[2] & 0xFF) << 8 | payload[3] & 0xFF;
    }

    public record DecodedCube(
            List<BlockEntity> blockEntities, CubeLightData.Snapshot light, boolean hasLight,
            List<CubeScheduledTick> scheduledTicks) {}
}
