package org.devt.higherworld.world;

import java.util.EnumMap;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.devt.higherworld.storage.CubePos;

/** Deduplicated, restartable lifecycle and cancellation boundary for one cube. */
final class CubeHolder {
    private final CubePos pos;
    private volatile Lifecycle lifecycle = new Lifecycle(0L);
    private volatile CompletableFuture<Void> saveFuture = CompletableFuture.completedFuture(null);

    CubeHolder(CubePos pos) {
        this.pos = pos;
    }

    CubePos pos() { return pos; }
    CubeStatus status() { return lifecycle.status; }
    CubeStatus target() { return lifecycle.target; }
    long epoch() { return lifecycle.epoch; }
    CompletableFuture<Optional<byte[]>> ioFuture() { return lifecycle.ioFuture; }
    CompletableFuture<CustomCubeGenerator.TerrainSnapshot> terrainPreparationFuture() {
        return lifecycle.terrainPreparationFuture;
    }
    CompletableFuture<Void> terrainFuture() { return lifecycle.stage(CubeStatus.TERRAIN); }
    CompletableFuture<Void> featureFuture() { return lifecycle.stage(CubeStatus.FEATURES); }
    CompletableFuture<Void> lightFuture() { return lifecycle.stage(CubeStatus.LIGHT); }
    CompletableFuture<Optional<LoadedCube>> fullFuture() { return lifecycle.fullFuture; }
    CompletableFuture<Void> saveFuture() { return saveFuture; }

    synchronized void request(CubeStatus requested) {
        Lifecycle current = lifecycle;
        if (current.cancelled) {
            current = lifecycle = new Lifecycle(current.epoch + 1L);
        }
        if (requested.ordinal() > current.target.ordinal()) current.target = requested;
    }

    synchronized void lowerTarget(CubeStatus requested) {
        lifecycle.target = requested;
    }

    void advance(CubeStatus reached) {
        Lifecycle current = lifecycle;
        synchronized (current) {
            if (current.cancelled || reached.ordinal() <= current.status.ordinal()) return;
            for (CubeStatus stage : CubeStatus.values()) {
                if (stage == CubeStatus.EMPTY) continue;
                if (stage == CubeStatus.FULL || stage.ordinal() > reached.ordinal()) break;
                current.stage(stage).complete(null);
            }
            current.status = reached;
        }
    }

    synchronized CompletableFuture<Optional<byte[]>> startIo(
            Supplier<CompletableFuture<Optional<byte[]>>> starter) {
        Lifecycle current = lifecycle;
        if (current.ioFuture == null) {
            long epoch = current.epoch;
            current.ioFuture = starter.get();
            current.ioFuture.whenComplete((payload, throwable) -> {
                if (!isCurrent(epoch)) return;
                if (throwable == null) advance(CubeStatus.IO_READY);
                else fail(epoch, throwable);
            });
        }
        return current.ioFuture;
    }

    synchronized CompletableFuture<CustomCubeGenerator.TerrainSnapshot> startTerrain(
            Supplier<CompletableFuture<CustomCubeGenerator.TerrainSnapshot>> starter) {
        Lifecycle current = lifecycle;
        if (current.terrainPreparationFuture == null || current.terrainPreparationFuture.isCancelled()) {
            current.terrainPreparationFuture = starter.get();
        }
        return current.terrainPreparationFuture;
    }

    synchronized void complete(LoadedCube cube) {
        Lifecycle current = lifecycle;
        advance(CubeStatus.FULL);
        current.fullFuture.complete(Optional.ofNullable(cube));
    }

    synchronized void materialize(LoadedCube cube) {
        Lifecycle current = lifecycle;
        Optional<LoadedCube> completed = current.fullFuture.isCompletedExceptionally()
                || current.fullFuture.isCancelled() ? null : current.fullFuture.getNow(null);
        if (current.fullFuture.isCompletedExceptionally() || current.fullFuture.isCancelled()
                || (completed != null && completed.isEmpty())) {
            current = lifecycle = new Lifecycle(current.epoch + 1L);
            current.target = CubeStatus.FULL;
        }
        if (!current.fullFuture.isDone()) complete(cube);
    }

    void fail(Throwable throwable) {
        fail(lifecycle.epoch, throwable);
    }

    void fail(long epoch, Throwable throwable) {
        Lifecycle current = lifecycle;
        if (current.epoch != epoch || current.cancelled) return;
        current.stageFutures.values().forEach(future -> future.completeExceptionally(throwable));
        current.fullFuture.completeExceptionally(throwable);
    }

    synchronized void cancel() {
        Lifecycle current = lifecycle;
        if (current.cancelled) return;
        current.cancelled = true;
        current.target = CubeStatus.EMPTY;
        current.status = CubeStatus.EMPTY;
        CancellationException cancelled = new CancellationException("Cube lifecycle cancelled: " + pos);
        if (current.ioFuture != null) current.ioFuture.cancel(false);
        if (current.terrainPreparationFuture != null) current.terrainPreparationFuture.cancel(false);
        current.stageFutures.values().forEach(future -> future.completeExceptionally(cancelled));
        current.fullFuture.completeExceptionally(cancelled);
    }

    synchronized void trackSave(CompletableFuture<Void> future) {
        saveFuture = future;
    }

    boolean isCurrent(long epoch) {
        Lifecycle current = lifecycle;
        return current.epoch == epoch && !current.cancelled;
    }

    CompletableFuture<Void> localStageFuture(CubeStatus stage) {
        if (stage == CubeStatus.EMPTY) return CompletableFuture.completedFuture(null);
        return lifecycle.stage(stage);
    }

    private static final class Lifecycle {
        private final long epoch;
        private final EnumMap<CubeStatus, CompletableFuture<Void>> stageFutures =
                new EnumMap<>(CubeStatus.class);
        private final CompletableFuture<Optional<LoadedCube>> fullFuture = new CompletableFuture<>();
        private volatile CubeStatus status = CubeStatus.EMPTY;
        private volatile CubeStatus target = CubeStatus.EMPTY;
        private volatile boolean cancelled;
        private volatile CompletableFuture<Optional<byte[]>> ioFuture;
        private volatile CompletableFuture<CustomCubeGenerator.TerrainSnapshot> terrainPreparationFuture;

        private Lifecycle(long epoch) {
            this.epoch = epoch;
            for (CubeStatus status : CubeStatus.values()) {
                if (status != CubeStatus.EMPTY && status != CubeStatus.FULL) {
                    stageFutures.put(status, new CompletableFuture<>());
                }
            }
        }

        private CompletableFuture<Void> stage(CubeStatus status) {
            if (status == CubeStatus.FULL) return fullFuture.thenApply(ignored -> null);
            return stageFutures.get(status);
        }
    }
}
