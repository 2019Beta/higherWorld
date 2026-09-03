package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.tick.TickPriority;
import org.devt.higherworld.storage.CubePos;
import org.junit.jupiter.api.Test;

class CubeScheduledTickQueueTest {
    @Test
    void ordersByTriggerPriorityAndSubTickOrderAndDeduplicates() {
        CubeScheduledTickQueue queue = new CubeScheduledTickQueue();
        CubeScheduledTick first = CubeScheduledTick.block(
                new BlockPos(0, 0, 0), "minecraft:stone", 4L, TickPriority.NORMAL, 10L);
        CubeScheduledTick duplicate = CubeScheduledTick.block(
                new BlockPos(0, 0, 0), "minecraft:stone", 9L, TickPriority.LOW, 11L);
        CubeScheduledTick highPriority = CubeScheduledTick.fluid(
                new BlockPos(1, 0, 0), "minecraft:water", 4L, TickPriority.HIGH, 12L);
        CubeScheduledTick earlierOrder = CubeScheduledTick.block(
                new BlockPos(2, 0, 0), "minecraft:dirt", 4L, TickPriority.NORMAL, 9L);

        assertTrue(queue.schedule(first));
        assertFalse(queue.schedule(duplicate));
        assertTrue(queue.schedule(highPriority));
        assertTrue(queue.schedule(earlierOrder));

        List<CubeScheduledTick> seen = new ArrayList<>();
        CubeScheduledTickQueue.DrainResult result = queue.drain(
                4L, 8, tick -> {
                    seen.add(tick);
                    return CubeScheduledTickQueue.Execution.CONSUMED;
                });

        assertEquals(3, result.executed());
        assertEquals(List.of(highPriority, earlierOrder, first), seen);
        assertEquals(0, queue.size());
    }

    @Test
    void enforcesPerPassBudget() {
        CubeScheduledTickQueue queue = new CubeScheduledTickQueue();
        for (int index = 0; index < 4; index++) {
            assertTrue(queue.schedule(CubeScheduledTick.block(
                    new BlockPos(index, 0, 0), "minecraft:stone", 0L,
                    TickPriority.NORMAL, index)));
        }

        CubeScheduledTickQueue.DrainResult result = queue.drain(
                0L, 2, tick -> CubeScheduledTickQueue.Execution.CONSUMED);

        assertEquals(2, result.executed());
        assertEquals(2, queue.size());
    }

    @Test
    void deferredTickSurvivesUntilCubeCanExecute() {
        CubeScheduledTickQueue queue = new CubeScheduledTickQueue();
        CubeScheduledTick event = CubeScheduledTick.block(
                new BlockPos(16, 16, 16), "minecraft:stone", 0L, TickPriority.NORMAL, 0L);
        assertTrue(queue.schedule(event));

        CubeScheduledTickQueue.DrainResult deferred = queue.drain(
                0L, 1, tick -> CubeScheduledTickQueue.Execution.DEFERRED);
        assertEquals(0, deferred.executed());
        assertTrue(deferred.deferred());
        assertEquals(1, queue.size());

        CubeScheduledTickQueue.DrainResult executed = queue.drain(
                1L, 1, tick -> CubeScheduledTickQueue.Execution.CONSUMED);
        assertEquals(1, executed.executed());
        assertEquals(0, queue.size());
    }

    @Test
    void snapshotsAndRestoresOnlyOneCube() {
        CubeScheduledTickQueue queue = new CubeScheduledTickQueue();
        CubeScheduledTick inCube = CubeScheduledTick.fluid(
                new BlockPos(15, 15, 15), "minecraft:water", 10L, TickPriority.LOW, 2L);
        CubeScheduledTick otherCube = CubeScheduledTick.block(
                new BlockPos(16, 15, 15), "minecraft:stone", 10L, TickPriority.LOW, 3L);
        queue.schedule(inCube);
        queue.schedule(otherCube);

        List<CubeScheduledTick> snapshot = queue.snapshot(new CubePos(0, 0, 0));
        CubeScheduledTickQueue restored = new CubeScheduledTickQueue();
        assertEquals(1, restored.restore(new CubePos(0, 0, 0), snapshot));
        assertEquals(List.of(inCube), restored.snapshot(new CubePos(0, 0, 0)));
        assertEquals(1, queue.size(new CubePos(1, 0, 0)));
    }

    @Test
    void reloadRestoreDoesNotDuplicateAnEventStillQueuedAcrossUnload() {
        CubePos cubePos = new CubePos(0, 3, 0);
        CubeScheduledTick event = CubeScheduledTick.fluid(
                new BlockPos(15, 63, 15), "minecraft:water", Long.MAX_VALUE,
                TickPriority.NORMAL, 44L);
        CubeScheduledTickQueue live = new CubeScheduledTickQueue();
        CubeScheduledTickQueue reloaded = new CubeScheduledTickQueue();
        assertTrue(live.schedule(event));

        List<CubeScheduledTick> persisted = live.snapshot(cubePos);
        assertEquals(1, reloaded.restore(cubePos, persisted));
        assertEquals(0, reloaded.restore(cubePos, persisted));
        assertEquals(List.of(event), reloaded.snapshot(cubePos));

        CubeScheduledTickQueue.DrainResult result = reloaded.drain(
                Long.MAX_VALUE, 1, tick -> CubeScheduledTickQueue.Execution.CONSUMED);
        assertEquals(1, result.executed());
        assertEquals(0, reloaded.size());
    }

    @Test
    void consumesExecutorFailureWithoutLeavingAHotLoop() {
        CubeScheduledTickQueue queue = new CubeScheduledTickQueue();
        CubeScheduledTick event = CubeScheduledTick.block(
                new BlockPos(0, 0, 0), "minecraft:stone", 0L,
                TickPriority.NORMAL, 1L);
        assertTrue(queue.schedule(event));

        CubeScheduledTickQueue.DrainResult result = queue.drain(
                0L, 1, tick -> { throw new IllegalStateException("broken tick"); });
        assertEquals(1, result.executed());
        assertEquals(0, queue.size());
    }

    @Test
    void roundTripsBinaryTickAndRejectsTruncation() throws IOException {
        CubeScheduledTick original = CubeScheduledTick.block(
                new BlockPos(Integer.MIN_VALUE, Integer.MAX_VALUE, -17),
                "example:very_specific_block", Long.MAX_VALUE,
                TickPriority.EXTREMELY_HIGH, 123456L);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            original.write(output);
        }

        CubeScheduledTick decoded;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            decoded = CubeScheduledTick.read(input);
        }
        assertEquals(original, decoded);

        byte[] full = bytes.toByteArray();
        assertThrows(IOException.class, () -> CubeScheduledTick.read(
                new DataInputStream(new ByteArrayInputStream(full, 0, full.length - 1))));
    }
}
