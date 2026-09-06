package org.devt.higherworld.world;

import java.util.EnumMap;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.devt.higherworld.storage.CubePos;

/** Deduplicated, restartable lifecycle and cancellation boundary for one cube. */
final class CubeHolder {
    private final CubePos pos;
    private final Consumer<CubeHolder> changeListener;
    private final AtomicLong changeVersion = new AtomicLong();
    private volatile Lifecycle lifecycle = new Lifecycle(0L);
    private volatile CompletableFuture<Void> saveFuture = CompletableFuture.completedFuture(null);

    CubeHolder(CubePos pos) {
        this(pos, null);
    }

    CubeHolder(CubePos pos, Consumer<CubeHolder> changeListener) {
        this.pos = pos;
        this.changeListener = changeListener;
    }

    CubePos pos() { return pos; }
    CubeStatus status() { return lifecycle.status; }
    CubeStatus target() { return lifecycle.target; }
    boolean failed() { return lifecycle.failed; }
    long epoch() { return lifecycle.epoch; }
    CompletableFuture<Optional<byte[]>> ioFuture() { return lifecycle.ioFuture; }
    CompletableFuture<CubeTerrainSnapshot> terrainPreparationFuture() {
        return lifecycle.terrainPreparationFuture;
    }
    CompletableFuture<Void> terrainFuture() { return lifecycle.stage(CubeStatus.TERRAIN); }
    CompletableFuture<Void> featureFuture() { return lifecycle.stage(CubeStatus.FEATURES); }
    CompletableFuture<Void> payloadFuture() { return lifecycle.stage(CubeStatus.PAYLOAD); }
    CompletableFuture<Void> lightFuture() { return lifecycle.stage(CubeStatus.LIGHT); }
    CompletableFuture<Optional<LoadedCube>> fullFuture() { return lifecycle.fullFuture; }
    CompletableFuture<Void> saveFuture() { return saveFuture; }
    long changeVersion() { return changeVersion.get(); }

    synchronized void request(CubeStatus requested) {
        Lifecycle current = lifecycle;
        boolean changed = false;
        if (current.cancelled) {
            current = lifecycle = new Lifecycle(current.epoch + 1L);
            changed = true;
        }
        if (requested.ordinal() > current.target.ordinal()) {
            current.target = requested;
            changed = true;
        }
        if (changed) notifyChanged();
    }

    synchronized void lowerTarget(CubeStatus requested) {
        if (requested.ordinal() >= lifecycle.target.ordinal()) return;
        lifecycle.target = requested;
        notifyChanged();
    }

    void advance(CubeStatus reached) {
        Lifecycle current = lifecycle;
        boolean changed = false;
        synchronized (current) {
            // A failed epoch is terminal.  In particular, do not let a late
            // dependency completion turn an exceptional lifecycle back into a
            // successful one; callers must cancel and request a fresh epoch.
            if (current.cancelled || current.failed || reached.ordinal() <= current.status.ordinal()) return;
            for (CubeStatus stage : CubeStatus.values()) {
                if (stage == CubeStatus.EMPTY) continue;
                if (stage == CubeStatus.FULL || stage.ordinal() > reached.ordinal()) break;
                current.stage(stage).complete(null);
            }
            current.status = reached;
            if (reached.isAtLeast(CubeStatus.TERRAIN)) {
                // The section now owns the committed terrain. Keeping the
                // completed future here pins its raw snapshot even after the
                // terrain batch LRU evicts it (often 64 KiB per deep batch).
                current.terrainPreparationFuture = null;
            }
            changed = true;
        }
        if (changed) notifyChanged();
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

    synchronized CompletableFuture<CubeTerrainSnapshot> startTerrain(
            Supplier<CompletableFuture<CubeTerrainSnapshot>> starter) {
        Lifecycle current = lifecycle;
        if (current.terrainPreparationFuture == null || current.terrainPreparationFuture.isCancelled()) {
            long epoch = current.epoch;
            CompletableFuture<CubeTerrainSnapshot> started = starter.get();
            current.terrainPreparationFuture = started;
            started.whenComplete((ignored, failure) -> {
                if (failure != null && !isCancellation(failure)) {
                    fail(epoch, failure);
                }
                notifyChanged();
            });
        }
        return current.terrainPreparationFuture;
    }

    synchronized void complete(LoadedCube cube) {
        Lifecycle current = lifecycle;
        if (current.cancelled || current.failed) return;
        advance(CubeStatus.FULL);
        current.fullFuture.complete(Optional.ofNullable(cube));
    }

    synchronized void materialize(LoadedCube cube) {
        Lifecycle current = lifecycle;
        if (current.cancelled || current.failed) return;
        Optional<LoadedCube> completed = current.fullFuture.isCompletedExceptionally()
                || current.fullFuture.isCancelled() ? null : current.fullFuture.getNow(null);
        if (completed != null && completed.isEmpty()) {
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
        current.failed = true;
        current.stageFutures.values().forEach(future -> future.completeExceptionally(throwable));
        current.fullFuture.completeExceptionally(throwable);
        notifyChanged();
    }

    synchronized void cancel() {
        Lifecycle current = lifecycle;
        if (current.cancelled) return;
        current.cancelled = true;
        current.target = CubeStatus.EMPTY;
        current.status = CubeStatus.EMPTY;
        CancellationException cancelled = new CancellationException("Cube lifecycle cancelled: " + pos);
        if (current.ioFuture != null) current.ioFuture.cancel(false);
        CompletableFuture<CubeTerrainSnapshot> preparation = current.terrainPreparationFuture;
        current.terrainPreparationFuture = null;
        if (preparation != null) preparation.cancel(false);
        current.stageFutures.values().forEach(future -> future.completeExceptionally(cancelled));
        current.fullFuture.completeExceptionally(cancelled);
        notifyChanged();
    }

    synchronized void trackSave(CompletableFuture<Void> future) {
        saveFuture = future;
    }

    boolean isCurrent(long epoch) {
        Lifecycle current = lifecycle;
        return current.epoch == epoch && !current.cancelled;
    }

    private static boolean isCancellation(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof CancellationException) return true;
            current = current.getCause();
        }
        return false;
    }

    private void notifyChanged() {
        changeVersion.incrementAndGet();
        if (changeListener != null) changeListener.accept(this);
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
        private volatile boolean failed;
        private volatile CompletableFuture<Optional<byte[]>> ioFuture;
        private volatile CompletableFuture<CubeTerrainSnapshot> terrainPreparationFuture;

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
