package org.devt.higherworld.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import net.minecraft.block.Block;
import net.minecraft.fluid.Fluid;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.tick.TickPriority;
import org.devt.higherworld.storage.CubePos;

/**
 * Bounded, deterministic scheduled tick storage for the sparse cube world.
 *
 * <p>The queue is deliberately independent of vanilla's ChunkTickScheduler:
 * its keys are full integer block positions, so Y values outside the vanilla
 * height array do not get truncated or indexed into a fixed-height section
 * table. A queue belongs to one server world and remains alive while a cube is
 * evicted; only the cube payload snapshot is filtered at save time.</p>
 */
public final class CubeScheduledTickQueue {
    public static final int MAX_TICKS_PER_WORLD_TICK = 256;

    private static final Map<ServerWorld, CubeScheduledTickQueue> WORLDS =
            new ConcurrentHashMap<>();
    private static final Comparator<CubeScheduledTick> ORDER = (first, second) -> {
        int byTime = Long.compare(first.triggerTick(), second.triggerTick());
        if (byTime != 0) return byTime;
        int byPriority = Integer.compare(first.priority(), second.priority());
        if (byPriority != 0) return byPriority;
        int byOrder = Long.compare(first.subTickOrder(), second.subTickOrder());
        if (byOrder != 0) return byOrder;
        int byY = Integer.compare(first.pos().getY(), second.pos().getY());
        if (byY != 0) return byY;
        int byZ = Integer.compare(first.pos().getZ(), second.pos().getZ());
        if (byZ != 0) return byZ;
        int byX = Integer.compare(first.pos().getX(), second.pos().getX());
        if (byX != 0) return byX;
        int byKind = Integer.compare(first.kind().ordinal(), second.kind().ordinal());
        if (byKind != 0) return byKind;
        return first.typeId().compareTo(second.typeId());
    };

    private final Map<Key, CubeScheduledTick> ticks = new HashMap<>();
    private final Consumer<CubePos> dirtySink;
    private long nextSubTickOrder;

    public CubeScheduledTickQueue() {
        this(ignored -> { });
    }

    CubeScheduledTickQueue(Consumer<CubePos> dirtySink) {
        this.dirtySink = dirtySink;
    }

    /** Installs the queue used by the WorldAccess mixin for one cubic world. */
    static void register(ServerWorld world, CubeScheduledTickQueue queue) {
        WORLDS.put(world, queue);
    }

    /** Removes only the queue instance that belongs to this lifecycle. */
    static void unregister(ServerWorld world, CubeScheduledTickQueue queue) {
        WORLDS.remove(world, queue);
    }

    /** Returns false when the world is not managed by HigherWorld. */
    public static boolean scheduleBlockTick(
            ServerWorld world, BlockPos pos, Block block, int delay, TickPriority priority) {
        CubeScheduledTickQueue queue = WORLDS.get(world);
        if (queue == null) return false;
        Identifier id = Registries.BLOCK.getId(block);
        if (id == null) return false;
        return queue.schedule(
                CubeScheduledTick.block(pos, id.toString(), safeTrigger(world.getTime(), delay),
                        priority, queue.nextOrder()));
    }

    /** Returns false when the world is not managed by HigherWorld. */
    public static boolean scheduleFluidTick(
            ServerWorld world, BlockPos pos, Fluid fluid, int delay, TickPriority priority) {
        CubeScheduledTickQueue queue = WORLDS.get(world);
        if (queue == null) return false;
        Identifier id = Registries.FLUID.getId(fluid);
        if (id == null) return false;
        return queue.schedule(
                CubeScheduledTick.fluid(pos, id.toString(), safeTrigger(world.getTime(), delay),
                        priority, queue.nextOrder()));
    }

    private static long safeTrigger(long now, int delay) {
        long nonNegativeDelay = Math.max(0L, delay);
        if (nonNegativeDelay > 0L && now > Long.MAX_VALUE - nonNegativeDelay) {
            return Long.MAX_VALUE;
        }
        return now + nonNegativeDelay;
    }

    private synchronized long nextOrder() {
        return nextSubTickOrder++;
    }

    synchronized long nextOrderForState() {
        return nextOrder();
    }

    /** Adds a prepared event, preserving vanilla's one-key dedup semantics. */
    public synchronized boolean schedule(CubeScheduledTick tick) {
        Key key = Key.of(tick);
        if (ticks.containsKey(key)) return false;
        ticks.put(key, tick);
        nextSubTickOrder = Math.max(nextSubTickOrder, saturatingIncrement(tick.subTickOrder()));
        dirtySink.accept(CubePos.fromBlock(
                tick.pos().getX(), tick.pos().getY(), tick.pos().getZ()));
        return true;
    }

    /** Restores only events belonging to one cube; malformed cross-cube records are ignored. */
    public synchronized int restore(CubePos cubePos, Collection<CubeScheduledTick> restored) {
        int added = 0;
        for (CubeScheduledTick tick : restored) {
            CubePos eventCube = CubePos.fromBlock(
                    tick.pos().getX(), tick.pos().getY(), tick.pos().getZ());
            if (!cubePos.equals(eventCube)) continue;
            Key key = Key.of(tick);
            if (ticks.putIfAbsent(key, tick) == null) {
                added++;
                nextSubTickOrder = Math.max(
                        nextSubTickOrder, saturatingIncrement(tick.subTickOrder()));
            }
        }
        return added;
    }

    /** Restores world-journal records without marking any cube dirty. */
    public synchronized int restoreAll(Collection<CubeScheduledTick> restored) {
        int added = 0;
        for (CubeScheduledTick tick : restored) {
            Key key = Key.of(tick);
            if (ticks.putIfAbsent(key, tick) == null) {
                added++;
                nextSubTickOrder = Math.max(
                        nextSubTickOrder, saturatingIncrement(tick.subTickOrder()));
            }
        }
        return added;
    }

    public synchronized int size() {
        return ticks.size();
    }

    public synchronized int size(CubePos cubePos) {
        int count = 0;
        for (CubeScheduledTick tick : ticks.values()) {
            if (cubePos.equals(CubePos.fromBlock(
                    tick.pos().getX(), tick.pos().getY(), tick.pos().getZ()))) count++;
        }
        return count;
    }

    public synchronized List<CubeScheduledTick> snapshot(CubePos cubePos) {
        List<CubeScheduledTick> result = new ArrayList<>();
        for (CubeScheduledTick tick : ticks.values()) {
            if (cubePos.equals(CubePos.fromBlock(
                    tick.pos().getX(), tick.pos().getY(), tick.pos().getZ()))) {
                result.add(tick);
            }
        }
        result.sort(ORDER);
        return List.copyOf(result);
    }

    /** Returns all queued records in the same deterministic order as a cube snapshot. */
    public synchronized List<CubeScheduledTick> snapshotAll() {
        List<CubeScheduledTick> result = new ArrayList<>(ticks.values());
        result.sort(ORDER);
        return List.copyOf(result);
    }

    /**
     * Executes due events in order. The executor returns DEFERRED when a cube
     * is currently unloaded or not ticketed; the event is retained for the
     * next tick instead of being lost.
     */
    public DrainResult drain(long now, int budget, TickExecutor executor) {
        if (budget <= 0) return new DrainResult(0, false);
        int executed = 0;
        boolean deferred = false;
        while (executed < Math.min(budget, MAX_TICKS_PER_WORLD_TICK)) {
            CubeScheduledTick tick;
            synchronized (this) {
                tick = peekDue(now);
                if (tick == null) break;
                ticks.remove(Key.of(tick));
            }
            Execution execution;
            try {
                execution = executor.execute(tick);
            } catch (RuntimeException exception) {
                // A broken block must not poison the queue or repeatedly run
                // forever. Vanilla also consumes a scheduled entry on failure.
                execution = Execution.CONSUMED;
            }
            if (execution == Execution.DEFERRED) {
                // Keep an overdue event due next tick without allowing it to
                // monopolize this drain pass while its cube is unloaded.
                CubeScheduledTick postponed = tick.withTriggerTick(safeTrigger(now, 1));
                synchronized (this) {
                    ticks.putIfAbsent(Key.of(postponed), postponed);
                }
                dirtySink.accept(CubePos.fromBlock(
                        tick.pos().getX(), tick.pos().getY(), tick.pos().getZ()));
                deferred = true;
                break;
            }
            dirtySink.accept(CubePos.fromBlock(
                    tick.pos().getX(), tick.pos().getY(), tick.pos().getZ()));
            executed++;
        }
        return new DrainResult(executed, deferred);
    }

    private synchronized CubeScheduledTick peekDue(long now) {
        CubeScheduledTick result = null;
        for (CubeScheduledTick candidate : ticks.values()) {
            if (candidate.triggerTick() > now) continue;
            if (result == null || ORDER.compare(candidate, result) < 0) result = candidate;
        }
        return result;
    }

    private static long saturatingIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    public static CubeScheduledTickQueue forWorld(ServerWorld world) {
        return WORLDS.get(world);
    }

    public record DrainResult(int executed, boolean deferred) {
    }

    public enum Execution {
        CONSUMED,
        DEFERRED
    }

    @FunctionalInterface
    public interface TickExecutor {
        Execution execute(CubeScheduledTick tick);
    }

    private record Key(CubeScheduledTick.Kind kind, String typeId, BlockPos pos) {
        private static Key of(CubeScheduledTick tick) {
            return new Key(tick.kind(), tick.typeId(), tick.pos());
        }
    }
}
