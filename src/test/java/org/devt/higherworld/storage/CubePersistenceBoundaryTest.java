package org.devt.higherworld.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.DeflaterOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CubePersistenceBoundaryTest {
    private static final int REGION_MAGIC = 0x48575231; // HWR1
    private static final int REGION_VERSION = 1;
    private static final int RECORD_MAGIC = 0x43554245; // CUBE

    @TempDir
    Path directory;

    @Test
    void persistsCubesAtSignedCoordinateExtremesAcrossReopen() throws Exception {
        CubePos minimum = new CubePos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        CubePos maximum = new CubePos(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        byte[] lowPayload = {0x01, 0x02};
        byte[] highPayload = {0x7E, 0x7F};

        try (CubeStorage storage = new CubeStorage(directory)) {
            storage.write(minimum, lowPayload);
            storage.write(maximum, highPayload);
            assertArrayEquals(lowPayload, storage.read(minimum).orElseThrow());
            assertArrayEquals(highPayload, storage.read(maximum).orElseThrow());
        }

        try (CubeStorage storage = new CubeStorage(directory)) {
            assertArrayEquals(lowPayload, storage.read(minimum).orElseThrow());
            assertArrayEquals(highPayload, storage.read(maximum).orElseThrow());
        }
    }

    @Test
    void boundsDecompressionBeforeAcceptingADeclaredLengthMismatch() throws Exception {
        CubePos position = new CubePos(0, 0, 0);
        byte[] expanded = new byte[1024 * 1024];
        byte[] compressed = compress(expanded);
        Path region = directory.resolve("r.0.0.0.hwr");

        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(region))) {
            output.writeInt(REGION_MAGIC);
            output.writeInt(REGION_VERSION);
            output.writeInt(0);
            output.writeInt(0);
            output.writeInt(0);
            output.writeInt(RECORD_MAGIC);
            output.writeInt(position.localIndex());
            output.writeInt(compressed.length);
            output.writeInt(1); // Deliberately smaller than the compressed stream expands to.
            output.writeInt(0);
            output.write(compressed);
        }

        try (CubeStorage storage = new CubeStorage(directory)) {
            IOException failure = assertThrows(IOException.class, () -> storage.read(position));
            assertTrue(failure.getMessage().contains("declared length")
                    || failure.getMessage().contains("length mismatch"));
        }
    }

    private static byte[] compress(byte[] payload) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (DeflaterOutputStream output = new DeflaterOutputStream(bytes)) {
            output.write(payload);
        }
        return bytes.toByteArray();
    }
}
