package org.devt.higherworld.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.devt.higherworld.storage.CubePos;
import org.devt.higherworld.storage.CubeStorage;

/** Small JDK-only regression harness for the HigherWorld cube region store. */
public final class StorageIoRegression {
    private StorageIoRegression() {}

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("higherworld-storage-regression-");
        try {
            verifyOffsetOrderedBatchRead(directory.resolve("batch"));
            verifyConcurrentFirstOpen(directory.resolve("concurrent"));
            verifyCloseRacesWithReaders(directory.resolve("close-race"));
            verifyInterruptedTailRecovery(directory.resolve("recovery"));
            System.out.println("Storage IO regression checks passed");
        } finally {
            try (var paths = Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (java.io.IOException exception) {
                        throw new java.io.UncheckedIOException(exception);
                    }
                });
            }
        }
    }

    private static void verifyOffsetOrderedBatchRead(Path directory) throws Exception {
        CubePos first = new CubePos(0, 0, 0);
        CubePos second = new CubePos(1, 0, 0);
        CubePos third = new CubePos(2, 0, 0);
        try (CubeStorage storage = new CubeStorage(directory)) {
            storage.write(first, new byte[] {1});
            storage.write(second, new byte[] {2});
            storage.write(third, new byte[] {3});
            var result = storage.readBatch(List.of(third, first, second));
            check(Arrays.equals(new byte[] {1}, result.get(first).orElseThrow()), "first batch value");
            check(Arrays.equals(new byte[] {2}, result.get(second).orElseThrow()), "second batch value");
            check(Arrays.equals(new byte[] {3}, result.get(third).orElseThrow()), "third batch value");
        }
    }

    private static void verifyConcurrentFirstOpen(Path directory) throws Exception {
        CubePos position = new CubePos(4, 5, 6);
        byte[] payload = new byte[256 * 1024];
        new java.util.Random(91L).nextBytes(payload);
        try (CubeStorage storage = new CubeStorage(directory)) {
            storage.write(position, payload);
        }

        try (CubeStorage storage = new CubeStorage(directory);
                var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Optional<byte[]>>> operations = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                operations.add(() -> storage.read(position));
            }
            for (var future : executor.invokeAll(operations)) {
                check(Arrays.equals(payload, future.get().orElseThrow()), "concurrent read value");
            }
            check(storage.openRegionCount() == 1, "concurrent region count");
        }
    }

    private static void verifyInterruptedTailRecovery(Path directory) throws Exception {
        CubePos first = new CubePos(3, -65_000, 9);
        CubePos second = new CubePos(4, -65_000, 9);
        Path region = directory.resolve(first.region().fileName());
        try (CubeStorage storage = new CubeStorage(directory)) {
            storage.write(first, new byte[] {1, 2, 3});
        }
        Files.write(region, new byte[] {0x43, 0x55, 0x42}, StandardOpenOption.APPEND);
        try (CubeStorage storage = new CubeStorage(directory)) {
            check(Arrays.equals(new byte[] {1, 2, 3}, storage.read(first).orElseThrow()),
                    "recovered first value");
            storage.write(second, new byte[] {4, 5, 6});
        }
        try (CubeStorage storage = new CubeStorage(directory)) {
            check(Arrays.equals(new byte[] {4, 5, 6}, storage.read(second).orElseThrow()),
                    "recovered second value");
        }
    }

    private static void verifyCloseRacesWithReaders(Path directory) throws Exception {
        CubePos position = new CubePos(7, 8, 9);
        byte[] payload = new byte[128 * 1024];
        new java.util.Random(17L).nextBytes(payload);
        try (CubeStorage storage = new CubeStorage(directory)) {
            storage.write(position, payload);
        }

        for (int iteration = 0; iteration < 20; iteration++) {
            CubeStorage storage = new CubeStorage(directory);
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(9)) {
                List<Callable<Void>> operations = new ArrayList<>();
                for (int index = 0; index < 8; index++) {
                    operations.add(() -> {
                        start.await();
                        try {
                            check(Arrays.equals(payload, storage.read(position).orElseThrow()),
                                    "close race read value");
                        } catch (java.io.IOException expectedAfterClose) {
                            // close may win before this reader acquires the handle.
                        }
                        return null;
                    });
                }
                operations.add(() -> {
                    start.await();
                    storage.close();
                    return null;
                });
                start.countDown();
                for (var future : executor.invokeAll(operations)) {
                    future.get(10, TimeUnit.SECONDS);
                }
            } finally {
                // close is idempotent after the racing closer completed.
                storage.close();
            }
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
