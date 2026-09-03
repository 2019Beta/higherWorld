package org.devt.higherworld.world;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;

/** Block and sky light attached to one sparse cube. */
public final class CubeLightData {
    private static final int UNIFORM = 0;
    private static final int PACKED = 1;

    private final SparseLightVolume block;
    private final SparseLightVolume sky;

    public CubeLightData() {
        this(SparseLightVolume.Snapshot.uniform(0), SparseLightVolume.Snapshot.uniform(0));
    }

    public CubeLightData(Snapshot snapshot) {
        this(snapshot.block(), snapshot.sky());
    }

    private CubeLightData(SparseLightVolume.Snapshot block, SparseLightVolume.Snapshot sky) {
        this.block = new SparseLightVolume(block);
        this.sky = new SparseLightVolume(sky);
    }

    public int block(int x, int y, int z) {
        return block.getVisible(x, y, z);
    }

    public int sky(int x, int y, int z) {
        return sky.getVisible(x, y, z);
    }

    public int workingBlock(int x, int y, int z) {
        return block.getWorking(x, y, z);
    }

    public int workingSky(int x, int y, int z) {
        return sky.getWorking(x, y, z);
    }

    public boolean setWorkingBlock(int x, int y, int z, int value) {
        return block.setWorking(x, y, z, value);
    }

    public boolean setWorkingSky(int x, int y, int z, int value) {
        return sky.setWorking(x, y, z, value);
    }

    public boolean publish() {
        boolean blockChanged = block.publish();
        boolean skyChanged = sky.publish();
        return blockChanged || skyChanged;
    }

    public Snapshot snapshot() {
        return new Snapshot(block.snapshot(), sky.snapshot());
    }

    public void load(Snapshot snapshot) {
        block.load(snapshot.block());
        sky.load(snapshot.sky());
    }

    static void write(DataOutput output, Snapshot light) throws IOException {
        writeVolume(output, light.block());
        writeVolume(output, light.sky());
    }

    static Snapshot read(DataInput input) throws IOException {
        return new Snapshot(readVolume(input), readVolume(input));
    }

    /** Encodes only the two light volumes for an eventual light delta packet. */
    public static byte[] encodeSnapshot(Snapshot light) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(4_104);
            DataOutputStream output = new DataOutputStream(bytes);
            write(output, light);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot encode cube light", exception);
        }
    }

    /** Decodes a light-only packet and rejects trailing or malformed data. */
    public static Snapshot decodeSnapshot(byte[] data) throws IOException {
        if (data == null || data.length == 0 || data.length > 8_192) {
            throw new IOException("Invalid cube light payload length");
        }
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(data));
        Snapshot snapshot = read(input);
        if (input.available() != 0) throw new IOException("Trailing cube light payload data");
        return snapshot;
    }

    private static void writeVolume(DataOutput output, SparseLightVolume.Snapshot volume) throws IOException {
        if (volume.isUniform()) {
            output.writeByte(UNIFORM);
            output.writeByte(volume.uniformValue());
        } else {
            output.writeByte(PACKED);
            output.write(volume.packedCopy());
        }
    }

    private static SparseLightVolume.Snapshot readVolume(DataInput input) throws IOException {
        int kind = input.readUnsignedByte();
        if (kind == UNIFORM) {
            int value = input.readUnsignedByte();
            if (value > 15) throw new IOException("Invalid uniform light level: " + value);
            return SparseLightVolume.Snapshot.uniform(value);
        }
        if (kind == PACKED) {
            byte[] data = new byte[SparseLightVolume.BYTE_COUNT];
            input.readFully(data);
            return SparseLightVolume.Snapshot.packed(data);
        }
        throw new IOException("Unknown light volume encoding: " + kind);
    }

    public record Snapshot(SparseLightVolume.Snapshot block, SparseLightVolume.Snapshot sky) {
        public static Snapshot dark() {
            return new Snapshot(SparseLightVolume.Snapshot.uniform(0), SparseLightVolume.Snapshot.uniform(0));
        }
    }
}
