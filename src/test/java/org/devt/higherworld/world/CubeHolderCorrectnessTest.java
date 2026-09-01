package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.devt.higherworld.storage.CubePos;
import org.junit.jupiter.api.Test;

class CubeHolderCorrectnessTest {
    @Test
    void lateStageCompletionCannotResurrectAFailedEpoch() {
        CubeHolder holder = new CubeHolder(new CubePos(1, 2, 3));
        holder.request(CubeStatus.FULL);
        holder.fail(new IllegalStateException("synthetic failure"));

        holder.advance(CubeStatus.FULL);
        holder.complete(null);
        holder.materialize(null);

        assertEquals(CubeStatus.EMPTY, holder.status());
        assertTrue(holder.failed());
        assertTrue(holder.fullFuture().isCompletedExceptionally());
    }
}
