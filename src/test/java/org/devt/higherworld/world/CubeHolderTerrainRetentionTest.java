package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import org.devt.higherworld.storage.CubePos;
import org.junit.jupiter.api.Test;

class CubeHolderTerrainRetentionTest {
    @Test
    void preparedOutputStaysUntilCommitThenHolderReleasesItsFuture() {
        CubeHolder holder = new CubeHolder(new CubePos(0, -30, 0));
        holder.request(CubeStatus.FULL);
        CompletableFuture<CubeTerrainSnapshot> preparation = new CompletableFuture<>();
        assertSame(preparation, holder.startTerrain(() -> preparation));
        holder.advance(CubeStatus.IO_READY);
        assertSame(preparation, holder.terrainPreparationFuture());

        preparation.complete(null);
        // Worker completion alone must not drop output before the server applies it.
        assertSame(preparation, holder.terrainPreparationFuture());
        holder.advance(CubeStatus.TERRAIN);
        assertNull(holder.terrainPreparationFuture());
        assertTrue(holder.terrainFuture().isDone());
        assertEquals(CubeStatus.TERRAIN, holder.status());
        assertEquals(CubeStatus.FULL, holder.target());
    }

    @Test
    void cancellationReleasesPreparedOutputAndRestartUsesANewFuture() {
        CubeHolder holder = new CubeHolder(new CubePos(0, -30, 0));
        holder.request(CubeStatus.TERRAIN);
        CompletableFuture<CubeTerrainSnapshot> preparation = new CompletableFuture<>();
        holder.startTerrain(() -> preparation);
        holder.cancel();
        assertTrue(preparation.isCancelled());
        assertNull(holder.terrainPreparationFuture());

        holder.request(CubeStatus.TERRAIN);
        CompletableFuture<CubeTerrainSnapshot> restarted = new CompletableFuture<>();
        assertSame(restarted, holder.startTerrain(() -> restarted));
        assertSame(restarted, holder.terrainPreparationFuture());
    }
}
