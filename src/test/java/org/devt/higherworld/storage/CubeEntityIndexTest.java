package org.devt.higherworld.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CubeEntityIndexTest {
    @TempDir
    Path directory;

    @Test
    void enteringAndLeavingKeepsUuidUnique() {
        CubePos owner = new CubePos(2, -30, 4);
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CubeEntityIndex index = new CubeEntityIndex();
        CubeEntityRecord record = new CubeEntityRecord(owner, uuid, new byte[] {1, 2, 3});

        assertTrue(index.add(record));
        assertThrows(IllegalArgumentException.class,
                () -> index.add(new CubeEntityRecord(owner, uuid, new byte[] {9})));
        assertEquals(List.of(record), index.snapshot(owner));
        assertEquals(record, index.remove(uuid).orElseThrow());
        assertTrue(index.snapshot(owner).isEmpty());
    }

    @Test
    void movingAcrossCubesChangesOnlyTheOwnerBucket() {
        CubePos from = new CubePos(-1, -2, -3);
        CubePos to = new CubePos(8, 9, 10);
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000002");
        CubeEntityIndex index = new CubeEntityIndex();
        CubeEntityRecord original = new CubeEntityRecord(from, uuid, new byte[] {4, 5});
        index.add(original);

        CubeEntityRecord moved = index.move(uuid, to).orElseThrow();
        assertEquals(to, moved.owner());
        assertTrue(index.snapshot(from).isEmpty());
        assertEquals(List.of(moved), index.snapshot(to));
        assertEquals(1, index.size());
    }

    @Test
    void duplicateRestoreIsRejectedWithoutPartialMutation() {
        CubePos owner = new CubePos(0, 0, 0);
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000003");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000004");
        CubeEntityIndex index = new CubeEntityIndex();
        index.add(new CubeEntityRecord(owner, first, new byte[] {1}));

        assertThrows(IllegalArgumentException.class, () -> index.restore(owner, List.of(
                new CubeEntityRecord(owner, second, new byte[] {2}),
                new CubeEntityRecord(owner, first, new byte[] {3}))));
        assertEquals(1, index.size());
        assertTrue(index.contains(first));
        assertFalse(index.contains(second));
    }

    @Test
    void payloadIsDefensivelyCopiedAndEncodingIsDeterministic() {
        CubePos owner = new CubePos(1, 2, 3);
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000005");
        byte[] source = {7, 8};
        CubeEntityRecord record = new CubeEntityRecord(owner, uuid, source);
        source[0] = 99;
        assertArrayEquals(new byte[] {7, 8}, record.nbtBytes());

        CubeEntityIndex index = new CubeEntityIndex();
        index.add(record);
        byte[] encoded = index.encode();
        byte[] encodedAgain = CubeEntityIndex.decode(encoded).encode();
        assertArrayEquals(encoded, encodedAgain);
        assertNotEquals(0, encoded.length);
        assertTrue(encoded.length <= CubeEntityIndex.MAX_ENCODED_BYTES);
    }

    @Test
    void storageAtomicallyPersistsAndReloadsHwe1() throws Exception {
        CubePos owner = new CubePos(11, -12, 13);
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000006");
        CubeEntityIndex index = new CubeEntityIndex();
        index.add(new CubeEntityRecord(owner, uuid, new byte[] {10, 20, 30}));

        try (CubeEntityStorage storage = new CubeEntityStorage(directory)) {
            storage.save(index);
            assertTrue(Files.exists(storage.fileForTest()));
            assertFalse(Files.exists(storage.fileForTest().resolveSibling("entities.hwe1.tmp")));
        }
        try (CubeEntityStorage storage = new CubeEntityStorage(directory)) {
            CubeEntityIndex restored = storage.load();
            assertArrayEquals(index.encode(), restored.encode());
        }
    }
}
