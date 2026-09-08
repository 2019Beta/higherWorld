package org.devt.higherworld.world;

/** A resume position across ordered, independently seeded feature entries. */
final class FeatureWorkCursor {
    private FeatureWorkCursor() {}

    @FunctionalInterface
    interface Work {
        void run(int stage, int entry);
    }

    static int run(int progress, int[] sizes, long deadlineNanos, Work work) {
        if (progress < 0) return -1;
        int completed = 0;
        boolean worked = false;
        for (int stage = 0; stage < sizes.length; stage++) {
            int skipped = Math.min(sizes[stage], Math.max(0, progress - completed));
            completed += skipped;
            for (int entry = skipped; entry < sizes[stage]; entry++, completed++) {
                // Preserve forward progress even when selection consumed the budget.
                if (worked && System.nanoTime() >= deadlineNanos) return completed;
                work.run(stage, entry);
                worked = true;
            }
        }
        return -1;
    }
}
