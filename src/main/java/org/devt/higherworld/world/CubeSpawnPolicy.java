package org.devt.higherworld.world;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.devt.higherworld.storage.CubePos;

/**
 * Safe natural-spawn policy for sparse cubes.
 *
 * <p>This class does not call {@code SpawnHelper}.  Vanilla's natural spawn
 * loop takes a {@code WorldChunk} and therefore assumes a finite horizontal
 * section array.  It supplies the deterministic candidate and ticket seam
 * that a version-specific spawn adapter can invoke after proving that the
 * cube is FULL and inside simulation distance.</p>
 */
public final class CubeSpawnPolicy {
    public static final int MAX_ATTEMPTS = 64;

    private CubeSpawnPolicy() {
    }

    /** The 3D simulation-distance envelope for one spawn pass. */
    public record SimulationWindow(CubePos center, int horizontalRadius, int verticalRadius) {
        public SimulationWindow {
            Objects.requireNonNull(center, "center");
            if (horizontalRadius < 0 || verticalRadius < 0) {
                throw new IllegalArgumentException("simulation radii cannot be negative");
            }
        }

        public boolean contains(CubePos cube) {
            Objects.requireNonNull(cube, "cube");
            return Math.abs((long) cube.x() - center.x()) <= horizontalRadius
                    && Math.abs((long) cube.z() - center.z()) <= horizontalRadius
                    && Math.abs((long) cube.y() - center.y()) <= verticalRadius;
        }

        public int priority(CubePos cube) {
            long dx = Math.abs((long) cube.x() - center.x());
            long dy = Math.abs((long) cube.y() - center.y());
            long dz = Math.abs((long) cube.z() - center.z());
            long shell = Math.max(dx, dz);
            long priority = saturatingAdd(
                    saturatingMultiply(saturatingMultiply(shell, shell), verticalRadius + 1L),
                    saturatingMultiply(dy, dy));
            return (int) Math.min(Integer.MAX_VALUE, priority);
        }

        private static long saturatingMultiply(long first, long second) {
            if (first == 0L || second == 0L) return 0L;
            return first > Long.MAX_VALUE / second ? Long.MAX_VALUE : first * second;
        }

        private static long saturatingAdd(long first, long second) {
            return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
        }
    }

    /** One reproducible block-local spawn attempt. */
    public record Candidate(
            CubePos cube, int attempt, int localX, int localY, int localZ, long candidateSeed) {
        public Candidate {
            Objects.requireNonNull(cube, "cube");
            if (!cube.isBlockRangeRepresentable()) {
                throw new IllegalArgumentException("spawn cube is outside block range");
            }
            if (attempt < 0 || localX < 0 || localX >= CubePos.SIZE
                    || localY < 0 || localY >= CubePos.SIZE
                    || localZ < 0 || localZ >= CubePos.SIZE) {
                throw new IllegalArgumentException("invalid spawn candidate");
            }
        }

        public int blockX() {
            return Math.addExact(cube.minBlockX(), localX);
        }

        public int blockY() {
            return Math.addExact(cube.minBlockY(), localY);
        }

        public int blockZ() {
            return Math.addExact(cube.minBlockZ(), localZ);
        }
    }

    /** A policy result which can be translated into the scheduler's entity ticket. */
    public record Ticket(Object owner, CubePos cube, int priority) {
        public Ticket {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(cube, "cube");
            if (!cube.isBlockRangeRepresentable() || priority < 0) {
                throw new IllegalArgumentException("invalid spawn ticket");
            }
        }

        public CubeTicket asCubeTicket() {
            return new CubeTicket(
                    owner, CubeTicketType.ENTITY, cube,
                    CubeDependencyRadius.NONE, CubeStatus.FULL, priority);
        }
    }

    /** Spawn is legal only for a fully committed cube inside simulation distance. */
    public static boolean eligible(
            CubePos cube, CubeStatus status, SimulationWindow simulationWindow) {
        Objects.requireNonNull(cube, "cube");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(simulationWindow, "simulationWindow");
        return status == CubeStatus.FULL && simulationWindow.contains(cube);
    }

    /**
     * Creates a short-lived entity ticket only when spawning is legal.  The
     * ticket's target is always FULL, so a caller cannot accidentally spawn in
     * a TERRAIN/FEATURES-only dependency cube.
     */
    public static Optional<Ticket> ticketFor(
            Object owner, CubePos cube, CubeStatus status, SimulationWindow simulationWindow) {
        Objects.requireNonNull(owner, "owner");
        if (!eligible(cube, status, simulationWindow)) return Optional.empty();
        return Optional.of(new Ticket(owner, cube, simulationWindow.priority(cube)));
    }

    /**
     * Generates deterministic local candidates.  Candidate positions are
     * unique within the batch and are independent of thread scheduling.
     */
    public static List<Candidate> candidates(
            long worldSeed, long gameTime, CubePos cube, CubeStatus status,
            SimulationWindow simulationWindow, int attempts) {
        return candidates(worldSeed, gameTime, cube, status, simulationWindow, attempts, ignored -> true);
    }

    /** Generates candidates and applies a pure passability/biome predicate. */
    public static List<Candidate> candidates(
            long worldSeed, long gameTime, CubePos cube, CubeStatus status,
            SimulationWindow simulationWindow, int attempts,
            java.util.function.Predicate<Candidate> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        if (attempts < 0 || attempts > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("spawn attempts are out of bounds");
        }
        if (!eligible(cube, status, simulationWindow)) return List.of();

        long seed = mix(worldSeed)
                ^ mix(gameTime + 0x9E3779B97F4A7C15L)
                ^ mix(((long) cube.x() << 32) ^ (cube.y() & 0xFFFFFFFFL))
                ^ mix(cube.z());
        Set<Long> occupiedLocals = new HashSet<>();
        List<Candidate> result = new ArrayList<>(attempts);
        for (int attempt = 0; attempt < attempts; attempt++) {
            long candidateSeed = mix(seed + attempt * 0xD1342543DE82EF95L);
            int localX = (int) Math.floorMod(candidateSeed, CubePos.SIZE);
            int localY = (int) Math.floorMod(candidateSeed >>> 21, CubePos.SIZE);
            int localZ = (int) Math.floorMod(candidateSeed >>> 42, CubePos.SIZE);
            long localKey = localX | ((long) localY << 8) | ((long) localZ << 16);
            if (!occupiedLocals.add(localKey)) continue;
            Candidate candidate = new Candidate(
                    cube, attempt, localX, localY, localZ, candidateSeed);
            if (predicate.test(candidate)) result.add(candidate);
        }
        return List.copyOf(result);
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
