package org.devt.higherworld.world;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.devt.higherworld.storage.CubePos;

/** Deduplicated lifecycle and cancellation boundary for one cube. */
final class CubeHolder {
    private final CubePos pos;
    private final AtomicReference<CubeStatus> status = new AtomicReference<>(CubeStatus.EMPTY);
    private final AtomicReference<CubeStatus> target = new AtomicReference<>(CubeStatus.EMPTY);
    private final CompletableFuture<LoadedCube> fullFuture = new CompletableFuture<>();
    private volatile CompletableFuture<CustomCubeGenerator.TerrainSnapshot> terrainFuture;

    CubeHolder(CubePos pos) {
        this.pos = pos;
    }

    CubePos pos() { return pos; }
    CubeStatus status() { return status.get(); }
    CubeStatus target() { return target.get(); }
    CompletableFuture<LoadedCube> fullFuture() { return fullFuture; }
    CompletableFuture<CustomCubeGenerator.TerrainSnapshot> terrainFuture() { return terrainFuture; }

    void request(CubeStatus requested) {
        target.accumulateAndGet(requested, (current, next) -> current.ordinal() >= next.ordinal() ? current : next);
    }

    void lowerTarget(CubeStatus requested) {
        target.set(requested);
    }

    void advance(CubeStatus reached) {
        status.accumulateAndGet(reached, (current, next) -> current.ordinal() >= next.ordinal() ? current : next);
    }

    synchronized CompletableFuture<CustomCubeGenerator.TerrainSnapshot> startTerrain(
            java.util.function.Supplier<CompletableFuture<CustomCubeGenerator.TerrainSnapshot>> starter) {
        if (terrainFuture == null || terrainFuture.isCancelled()) terrainFuture = starter.get();
        return terrainFuture;
    }

    void complete(LoadedCube cube) {
        advance(CubeStatus.FULL);
        fullFuture.complete(cube);
    }

    void fail(Throwable throwable) {
        fullFuture.completeExceptionally(throwable);
    }
}
