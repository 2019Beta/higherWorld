package org.devt.higherworld.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** Indexed heap. Callers may hold a holder lock; this queue never calls holders. */
final class CubeReadyQueue<K, E> {
    private final ArrayList<E> heap = new ArrayList<>();
    private final Map<K, Integer> indices = new HashMap<>();
    private final Function<E, K> key;
    private final Comparator<E> comparator;

    CubeReadyQueue(Function<E, K> key, Comparator<E> comparator) {
        this.key = key;
        this.comparator = comparator;
    }

    synchronized void offer(E entry) {
        Integer index = indices.get(key.apply(entry));
        if (index == null) {
            index = heap.size();
            heap.add(entry);
        } else {
            heap.set(index, entry);
        }
        indices.put(key.apply(entry), index);
        repair(index);
    }

    synchronized E poll() {
        return heap.isEmpty() ? null : removeAt(0);
    }

    synchronized void remove(E entry) {
        Integer index = indices.get(key.apply(entry));
        if (index != null && heap.get(index) == entry) removeAt(index);
    }

    synchronized int size() { return heap.size(); }

    synchronized void clear() {
        heap.clear();
        indices.clear();
    }

    private E removeAt(int index) {
        E removed = heap.get(index);
        E last = heap.remove(heap.size() - 1);
        indices.remove(key.apply(removed));
        if (index < heap.size()) {
            heap.set(index, last);
            indices.put(key.apply(last), index);
            repair(index);
        }
        return removed;
    }

    private void repair(int index) {
        while (index > 0) {
            int parent = (index - 1) / 2;
            if (comparator.compare(heap.get(index), heap.get(parent)) >= 0) break;
            swap(index, parent);
            index = parent;
        }
        while (index < heap.size() / 2) {
            int child = index * 2 + 1;
            if (child + 1 < heap.size()
                    && comparator.compare(heap.get(child + 1), heap.get(child)) < 0) child++;
            if (comparator.compare(heap.get(index), heap.get(child)) <= 0) break;
            swap(index, child);
            index = child;
        }
    }

    private void swap(int a, int b) {
        E first = heap.get(a);
        E second = heap.get(b);
        heap.set(a, second);
        heap.set(b, first);
        indices.put(key.apply(second), a);
        indices.put(key.apply(first), b);
    }
}
