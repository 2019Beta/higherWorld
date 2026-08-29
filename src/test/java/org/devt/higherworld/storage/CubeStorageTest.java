package org.devt.higherworld.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CubeStorageTest {
    @TempDir
    Path directory;

    @Test
    void persistsLatestCubeRecordAcrossReopen() throws Exception {
        CubePos position = new CubePos(-17, 12345, 31);
        byte[] first = "first".getBytes(StandardCharsets.UTF_8);
        byte[] latest = "latest payload".getBytes(StandardCharsets.UTF_8);

        try (CubeStorage storage = new CubeStorage(directory)) {
            storage.write(position, first);
            storage.write(position, latest);
            assertArrayEquals(latest, storage.read(position).orElseThrow());
        }

        try (CubeStorage storage = new CubeStorage(directory)) {
            assertArrayEquals(latest, storage.read(position).orElseThrow());
            assertTrue(storage.read(new CubePos(100, 100, 100)).isEmpty());
        }
    }

    @Test
    void truncatesInterruptedTailBeforeAppendingAgain() throws Exception {
        CubePos firstPos = new CubePos(3, -65_000, 9);
        CubePos secondPos = new CubePos(4, -65_000, 9);
        byte[] first = "before crash".getBytes(StandardCharsets.UTF_8);
        byte[] second = "after restart".getBytes(StandardCharsets.UTF_8);

        try (CubeStorage storage = new CubeStorage(directory)) {
            storage.write(firstPos, first);
        }
        Path region = directory.resolve(firstPos.region().fileName());
        Files.write(region, new byte[] {0x43, 0x55, 0x42}, StandardOpenOption.APPEND);

        try (CubeStorage storage = new CubeStorage(directory)) {
            assertArrayEquals(first, storage.read(firstPos).orElseThrow());
            storage.write(secondPos, second);
        }
        try (CubeStorage storage = new CubeStorage(directory)) {
            assertArrayEquals(first, storage.read(firstPos).orElseThrow());
            assertArrayEquals(second, storage.read(secondPos).orElseThrow());
        }
    }

    @Test
    void boundsOpenRegionHandlesDuringLongDistanceTravel() throws Exception {
        try (CubeStorage storage = new CubeStorage(directory)) {
            for (int regionX = 0; regionX < 80; regionX++) {
                storage.write(new CubePos(regionX * CubePos.REGION_DIAMETER, 0, 0), new byte[] {(byte) regionX});
            }
            assertEquals(64, storage.openRegionCount());
            assertArrayEquals(new byte[] {0}, storage.read(new CubePos(0, 0, 0)).orElseThrow());
            assertEquals(64, storage.openRegionCount());
        }
    }
}
