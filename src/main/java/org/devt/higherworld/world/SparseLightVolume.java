package org.devt.higherworld.world;

import java.util.Arrays;

/**
 * A lazily allocated 16x16x16 nibble volume.
 *
 * <p>The mutable copy has a single writer. Readers only observe an immutable,
 * volatile snapshot, following the same publication model used by ScalableLux's
 * SWMR light arrays. Uniform zero and uniform sky-light volumes allocate no
 * 2048-byte backing array.</p>
 */
public final class SparseLightVolume {
    public static final int BLOCK_COUNT = 16 * 16 * 16;
    public static final int BYTE_COUNT = BLOCK_COUNT / 2;

    private int workingUniform;
    private byte[] workingData;
    private volatile Snapshot visible;

    public SparseLightVolume() {
        this(Snapshot.uniform(0));
    }

    public SparseLightVolume(Snapshot initial) {
        load(initial);
    }

    public int getVisible(int x, int y, int z) {
        return visible.get(index(x, y, z));
    }

    int getWorking(int x, int y, int z) {
        return getWorking(index(x, y, z));
    }

    boolean setWorking(int x, int y, int z, int value) {
        checkLight(value);
        int index = index(x, y, z);
        int previous = getWorking(index);
        if (previous == value) {
            return false;
        }
        if (workingData == null) {
            workingData = filled(workingUniform);
        }
        int byteIndex = index >>> 1;
        int shift = (index & 1) << 2;
        workingData[byteIndex] = (byte) ((workingData[byteIndex] & ~(0xF << shift)) | value << shift);
        return true;
    }

    public Snapshot snapshot() {
        return visible;
    }

    /** Publishes the writer's current state and compacts uniform arrays. */
    boolean publish() {
        Snapshot next = compactWorking();
        Snapshot previous = visible;
        if (previous.equals(next)) {
            return false;
        }
        visible = next;
        return true;
    }

    public void load(Snapshot snapshot) {
        visible = snapshot;
        workingUniform = snapshot.uniformValue();
        workingData = snapshot.data() == null ? null : snapshot.data().clone();
    }

    private Snapshot compactWorking() {
        if (workingData == null) {
            return Snapshot.uniform(workingUniform);
        }
        int first = workingData[0] & 0xF;
        int repeated = first | first << 4;
        boolean uniform = true;
        for (byte value : workingData) {
            if ((value & 0xFF) != repeated) {
                uniform = false;
                break;
            }
        }
        if (uniform) {
            workingUniform = first;
            workingData = null;
            return Snapshot.uniform(first);
        }
        return Snapshot.packed(workingData);
    }

    private int getWorking(int index) {
        if (workingData == null) {
            return workingUniform;
        }
        return workingData[index >>> 1] >>> ((index & 1) << 2) & 0xF;
    }

    private static int index(int x, int y, int z) {
        if ((x | y | z) < 0 || x >= 16 || y >= 16 || z >= 16) {
            throw new IndexOutOfBoundsException("Light coordinate outside 16-cube: " + x + "," + y + "," + z);
        }
        return x | z << 4 | y << 8;
    }

    private static byte[] filled(int value) {
        byte[] data = new byte[BYTE_COUNT];
        Arrays.fill(data, (byte) (value | value << 4));
        return data;
    }

    private static void checkLight(int value) {
        if (value < 0 || value > 15) {
            throw new IllegalArgumentException("Light level must be in 0..15: " + value);
        }
    }

    /** Immutable reader/serialization view. */
    public static final class Snapshot {
        private final int uniformValue;
        private final byte[] data;

        private Snapshot(int uniformValue, byte[] data) {
            this.uniformValue = uniformValue;
            this.data = data;
        }

        public static Snapshot uniform(int value) {
            checkLight(value);
            return new Snapshot(value, null);
        }

        public static Snapshot packed(byte[] data) {
            if (data.length != BYTE_COUNT) {
                throw new IllegalArgumentException("Expected " + BYTE_COUNT + " light bytes, got " + data.length);
            }
            return new Snapshot(0, data.clone());
        }

        public boolean isUniform() {
            return data == null;
        }

        public int uniformValue() {
            return uniformValue;
        }

        public byte[] packedCopy() {
            return data == null ? null : data.clone();
        }

        byte[] data() {
            return data;
        }

        int get(int index) {
            return data == null ? uniformValue : data[index >>> 1] >>> ((index & 1) << 2) & 0xF;
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof Snapshot other
                    && uniformValue == other.uniformValue && Arrays.equals(data, other.data);
        }

        @Override
        public int hashCode() {
            return 31 * uniformValue + Arrays.hashCode(data);
        }
    }
}
