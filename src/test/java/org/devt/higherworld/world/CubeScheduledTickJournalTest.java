package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.tick.TickPriority;
import org.devt.higherworld.storage.CubePos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CubeScheduledTickJournalTest {
    @TempDir
    Path tempDir;

    @Test
    void roundTripsBoundedJournalAndQueueDeduplicatesCubeRecords() throws IOException {
        CubeScheduledTick first = CubeScheduledTick.block(
                new BlockPos(16, -1, 16), "minecraft:stone", 40L,
                TickPriority.HIGH, 3L);
        CubeScheduledTick second = CubeScheduledTick.fluid(
                new BlockPos(0, 0, 0), "minecraft:water", 41L,
                TickPriority.NORMAL, 4L);
        CubeScheduledTickJournal journal = new CubeScheduledTickJournal(
                tempDir.resolve(CubeScheduledTickJournal.FILE_NAME));
        journal.save(List.of(second, first));

        List<CubeScheduledTick> loaded = journal.load();
        assertEquals(List.of(first, second), loaded);

        CubeScheduledTickQueue queue = new CubeScheduledTickQueue();
        assertEquals(2, queue.restoreAll(loaded));
        assertEquals(0, queue.restore(new CubePos(1, -1, 1), loaded));
        assertEquals(0, queue.restoreAll(loaded));
        assertEquals(2, queue.size());
    }

    @Test
    void corruptOrOversizedJournalIsIgnoredWithoutThrowing() throws IOException {
        Path path = tempDir.resolve(CubeScheduledTickJournal.FILE_NAME);
        Files.write(path, new byte[] {0x48, 0x57, 0x54, 0x31, 0, 0, 0, 99});
        CubeScheduledTickJournal journal = new CubeScheduledTickJournal(path);
        assertTrue(journal.load().isEmpty());

        Files.write(path, new byte[CubeScheduledTickJournal.MAX_BYTES + 1]);
        assertTrue(journal.load().isEmpty());
        assertEquals(0, journal.load().size());
    }

    @Test
    void rejectsTrailingBytesAndInvalidHeader() throws IOException {
        CubeScheduledTick event = CubeScheduledTick.block(
                new BlockPos(0, 0, 0), "minecraft:stone", 0L,
                TickPriority.NORMAL, 0L);
        byte[] encoded = CubeScheduledTickJournal.encode(List.of(event));
        byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        trailing[trailing.length - 1] = 7;
        org.junit.jupiter.api.Assertions.assertThrows(
                IOException.class, () -> CubeScheduledTickJournal.decode(trailing));
        encoded[7] = 2;
        org.junit.jupiter.api.Assertions.assertThrows(
                IOException.class, () -> CubeScheduledTickJournal.decode(encoded));
    }
}
