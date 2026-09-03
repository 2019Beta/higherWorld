package org.devt.higherworld.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.tick.TickPriority;
import org.devt.higherworld.storage.CubeEntityIndex;
import org.devt.higherworld.storage.CubeEntityRecord;
import org.devt.higherworld.storage.CubePos;
import org.devt.higherworld.world.CubeDependencyRadius;
import org.devt.higherworld.world.CubePathNode;
import org.devt.higherworld.world.CubePathQuery;
import org.devt.higherworld.world.CubePathfindingAccess;
import org.devt.higherworld.world.CubePoiIndex;
import org.devt.higherworld.world.CubePoiRecord;
import org.devt.higherworld.world.CubeSpawnPolicy;
import org.devt.higherworld.world.CubeStatus;
import org.devt.higherworld.world.CubeScheduledTick;
import org.devt.higherworld.world.CubeScheduledTickQueue;

/**
 * Reproducible microbenchmark harness for the sparse-world seams.
 *
 * <p>This is deliberately a normal Java entry point rather than a unit test:
 * timing is reported as data and never used as a correctness assertion.  Run
 * it with {@code ./gradlew cubeMicrobenchmark}; the optional first argument
 * is the output CSV path.</p>
 */
public final class CubeMicroBenchmark {
    private static final long SEED = 0x4D595DF4D0F33173L;
    private static final int WARMUP = 3;
    private static final int BATCH = 256;
    private static volatile long blackhole;

    private CubeMicroBenchmark() {
    }

    public static void main(String[] args) throws IOException {
        Path output = args.length == 0
                ? Path.of("build", "reports", "higherworld-microbenchmark.csv")
                : Path.of(args[0]);
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);

        CubePoiIndex poiIndex = makePoiIndex();
        CubePathfindingAccess pathAccess = new BenchmarkPathAccess();
        CubePathQuery.Request pathRequest = new CubePathQuery.Request(
                new CubePathNode(0, 0, 0), new CubePathNode(31, 0, 31),
                new CubePos(0, 0, 0), new CubeDependencyRadius(2, 0, 2),
                CubeStatus.FULL, 20_000);
        CubeSpawnPolicy.SimulationWindow window = new CubeSpawnPolicy.SimulationWindow(
                new CubePos(0, 0, 0), 4, 1);
        CubeEntityIndex entityIndex = makeEntityIndex();
        CubeScheduledTickQueue scheduledTicks = makeScheduledTicks();

        List<Row> rows = new ArrayList<>();
        rows.add(measure("poi_query", () -> poiIndex.query(
                new CubePos(0, 0, 0), new CubeDependencyRadius(2, 0, 2),
                poi -> poi.type().equals("higherworld:village"))
                .size()));
        rows.add(measure("poi_roundtrip", () -> CubePoiIndex.decode(poiIndex.encode()).size()));
        rows.add(measure("path_query", () -> CubePathQuery.search(pathAccess, pathRequest).expandedNodes()));
        rows.add(measure("spawn_candidates", () -> CubeSpawnPolicy.candidates(
                SEED, 120L, new CubePos(0, 0, 0), CubeStatus.FULL, window, 32).size()));
        rows.add(measure("entity_index_snapshot", () -> entityIndex.snapshotAll().size()));
        rows.add(measure("entity_index_roundtrip", () -> CubeEntityIndex.decode(
                entityIndex.encode()).snapshotAll().size()));
        rows.add(measure("scheduled_tick_snapshot", () -> scheduledTicks.snapshot(
                new CubePos(0, 0, 0)).size()));

        StringBuilder csv = new StringBuilder();
        csv.append("operation,seed,warmup,batch,iterations,total_nanos,nanos_per_op,result_checksum\n");
        for (Row row : rows) {
            csv.append(row.operation()).append(',')
                    .append(SEED).append(',')
                    .append(WARMUP).append(',')
                    .append(BATCH).append(',')
                    .append(row.iterations()).append(',')
                    .append(row.totalNanos()).append(',')
                    .append(String.format(Locale.ROOT, "%.2f", row.nanosPerOperation())).append(',')
                    .append(row.checksum()).append('\n');
        }
        Files.writeString(output, csv.toString());
        System.out.print(csv);
    }

    private static Row measure(String operation, LongSupplier operationCall) {
        long checksum = 0L;
        for (int i = 0; i < WARMUP * BATCH; i++) checksum ^= operationCall.getAsLong();
        long started = System.nanoTime();
        for (int i = 0; i < BATCH; i++) checksum = Long.rotateLeft(checksum, 1) ^ operationCall.getAsLong();
        long elapsed = System.nanoTime() - started;
        blackhole ^= checksum;
        return new Row(operation, BATCH, elapsed, elapsed / (double) BATCH, checksum);
    }

    private static CubePoiIndex makePoiIndex() {
        CubePoiIndex index = new CubePoiIndex();
        for (int cubeX = -2; cubeX <= 2; cubeX++) {
            for (int cubeZ = -2; cubeZ <= 2; cubeZ++) {
                CubePos cube = new CubePos(cubeX, 0, cubeZ);
                for (int local = 0; local < 8; local++) {
                    String type = local % 2 == 0 ? "higherworld:village" : "higherworld:job_site";
                    index.add(new CubePoiRecord(cube, local, 1, 15 - local, type, 1));
                }
            }
        }
        return index;
    }

    private static CubeEntityIndex makeEntityIndex() {
        CubeEntityIndex index = new CubeEntityIndex();
        CubePos cube = new CubePos(0, 0, 0);
        for (int entity = 0; entity < 32; entity++) {
            index.add(new CubeEntityRecord(
                    cube, new java.util.UUID(SEED, entity + 1L),
                    new byte[] {(byte) entity, (byte) (entity >>> 1)}));
        }
        return index;
    }

    private static CubeScheduledTickQueue makeScheduledTicks() {
        CubeScheduledTickQueue queue = new CubeScheduledTickQueue();
        CubePos cube = new CubePos(0, 0, 0);
        for (int tick = 0; tick < 32; tick++) {
            queue.schedule(new CubeScheduledTick(
                    CubeScheduledTick.Kind.BLOCK, "minecraft:stone",
                    new BlockPos(cube.minBlockX() + tick, cube.minBlockY(), cube.minBlockZ()),
                    120L + tick, TickPriority.NORMAL.getIndex(), tick));
        }
        return queue;
    }

    private record Row(
            String operation, int iterations, long totalNanos,
            double nanosPerOperation, long checksum) {
    }

    private static final class BenchmarkPathAccess implements CubePathfindingAccess {
        @Override
        public CubeStatus status(CubePos cube) {
            return CubeStatus.FULL;
        }

        @Override
        public boolean isPassable(CubePathNode node) {
            return node.y() == 0;
        }
    }
}
