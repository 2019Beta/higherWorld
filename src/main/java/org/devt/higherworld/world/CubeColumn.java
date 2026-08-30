package org.devt.higherworld.world;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.IntFunction;
import java.util.function.Consumer;

/**
 * Sparse, vertically ordered cube storage for one x/z column.
 *
 * <p>This deliberately uses an integer key instead of a packed block position.
 * Loading a cube at a very large positive or negative Y therefore never grows a
 * section array and never aliases another cube.</p>
 */
public final class CubeColumn<T> {
    private final ConcurrentNavigableMap<Integer, T> cubes = new ConcurrentSkipListMap<>();

    public T get(int cubeY) {
        return cubes.get(cubeY);
    }

    public T getOrCreate(int cubeY, IntFunction<? extends T> factory) {
        return cubes.computeIfAbsent(cubeY, factory::apply);
    }

    public void put(int cubeY, T cube) {
        T previous = cubes.putIfAbsent(cubeY, cube);
        if (previous != null && previous != cube) {
            throw new IllegalArgumentException("Cube at Y " + cubeY + " is already loaded");
        }
    }

    public T remove(int cubeY) {
        return cubes.remove(cubeY);
    }

    public boolean remove(int cubeY, T expected) {
        return cubes.remove(cubeY, expected);
    }

    /** Returns a stable snapshot ordered from the lower cube to the higher cube. */
    public List<T> between(int minCubeY, int maxCubeY) {
        if (minCubeY > maxCubeY) {
            throw new IllegalArgumentException("minCubeY must not exceed maxCubeY");
        }
        return List.copyOf(cubes.subMap(minCubeY, true, maxCubeY, true).values());
    }

    /** Returns a stable snapshot of every currently loaded cube. */
    public Collection<T> loaded() {
        return List.copyOf(cubes.values());
    }

    /**
     * Returns a stable snapshot of cube coordinates and values.
     *
     * <p>Keeping each coordinate paired with its value is important for readers
     * that run concurrently with cube unloads. Taking separate snapshots of the
     * keys and values can otherwise leave a value with no discoverable key.</p>
     */
    public List<Map.Entry<Integer, T>> entries() {
        return cubes.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
    }

    public Collection<Integer> sectionCoordinates() {
        return List.copyOf(cubes.keySet());
    }

    public boolean isEmpty() {
        return cubes.isEmpty();
    }

    public int size() {
        return cubes.size();
    }

    /** Iterates the weakly-consistent live view without allocating a snapshot. */
    public void forEach(Consumer<? super T> action) {
        cubes.values().forEach(action);
    }
}
