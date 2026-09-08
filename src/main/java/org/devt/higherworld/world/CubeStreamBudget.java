package org.devt.higherworld.world;

/** One player's byte window. Only server-thread calls are allowed. */
final class CubeStreamBudget {
    static final long WINDOW_BYTES = 4L * 1024 * 1024;
    private long streamId;
    private boolean enabled;
    private long sentBytes;
    private long processedBytes;
    private long lastFeedbackTick;
    private CubeStreamFeedbackPayload feedback;

    void start(long id, boolean supported, long tick) {
        streamId = id;
        enabled = supported;
        sentBytes = processedBytes = 0;
        lastFeedbackTick = tick;
        feedback = null;
    }

    void feedback(CubeStreamFeedbackPayload update, long tick) {
        if (!enabled || update.streamId() != streamId || !update.valid()
                || update.processedBytes() < processedBytes || update.processedBytes() > sentBytes) return;
        processedBytes = update.processedBytes();
        feedback = update;
        lastFeedbackTick = tick;
    }

    boolean canSend(int bytes, long tick) {
        if (bytes < 0) return false;
        if (!enabled) return true;
        if (tick - lastFeedbackTick > 40) return false;
        if (feedback != null && feedback.hasBackpressure()) return false;
        long outstanding = sentBytes - processedBytes;
        return outstanding < WINDOW_BYTES && bytes <= WINDOW_BYTES - outstanding;
    }

    void recordSent(int bytes) {
        if (enabled) sentBytes = Math.addExact(sentBytes, bytes);
    }
}
