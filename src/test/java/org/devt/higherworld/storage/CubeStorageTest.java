package org.devt.higherworld.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

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

    @Test
    void supportsConcurrentIoAcrossIndependentRegions() throws Exception {
        try (CubeStorage storage = new CubeStorage(directory);
                var executor = Executors.newFixedThreadPool(4)) {
            List<Callable<Void>> operations = new ArrayList<>();
            for (int region = 0; region < 24; region++) {
                int id = region;
                operations.add(() -> {
                    CubePos pos = new CubePos(id * CubePos.REGION_DIAMETER, id, -id);
                    byte[] payload = new byte[] {(byte) id, (byte) (id * 3)};
                    storage.write(pos, payload);
                    assertArrayEquals(payload, storage.read(pos).orElseThrow());
                    return null;
                });
            }
            for (var future : executor.invokeAll(operations)) {
                future.get();
            }
            assertTrue(storage.openRegionCount() <= 64);
        }
    }

    @Test
    void readsOneRegionAsABatch() throws Exception {
        CubePos first = new CubePos(1, 2, 3);
        CubePos second = new CubePos(4, 5, 6);
        try (CubeStorage storage = new CubeStorage(directory)) {
            storage.write(first, new byte[] {1});
            Map<CubePos, java.util.Optional<byte[]>> result = storage.readBatch(Set.of(first, second));
            assertArrayEquals(new byte[] {1}, result.get(first).orElseThrow());
            assertTrue(result.get(second).isEmpty());
        }
    }

    @Test
    void compactsStaleAppendRecordsWithoutChangingTheLatestPayload() throws Exception {
        CubePos position = new CubePos(2, -40, 3);
        byte[] payload = new byte[100_000];
        new java.util.Random(41L).nextBytes(payload);

        try (CubeStorage storage = new CubeStorage(directory)) {
            for (int version = 0; version < 128; version++) {
                payload[0] = (byte) version;
                storage.write(position, payload);
            }
            Path region = directory.resolve(position.region().fileName());
            long before = Files.size(region);

            storage.compactIfNeeded(position.region());

            long after = Files.size(region);
            assertTrue(before > 8L * 1024L * 1024L);
            assertTrue(after < before);
            assertArrayEquals(payload, storage.read(position).orElseThrow());
        }
    }
}
