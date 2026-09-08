package org.devt.higherworld.world;

/** Client-tick feedback: immediate pressure transitions, otherwise every five ticks. */
public final class CubeFeedbackCadence {
    private long streamId;
    private long reportedBytes;
    private boolean reportedPressure;
    private int ticks;

    public void reset() {
        streamId = 0;
        reportedBytes = 0;
        reportedPressure = false;
        ticks = 0;
    }

    /** Call only when the connection can send; true also records this report. */
    public boolean shouldSend(CubeStreamFeedbackPayload next) {
        if (next.streamId() == 0) {
            reset();
            return false;
        }
        boolean pressure = next.hasBackpressure();
        boolean changed = next.streamId() != streamId || pressure != reportedPressure;
        boolean reclaimedWindow = next.processedBytes() - reportedBytes >= CubeStreamBudget.WINDOW_BYTES / 4;
        if (!changed && !reclaimedWindow && ++ticks < 5) return false;
        streamId = next.streamId();
        reportedBytes = next.processedBytes();
        reportedPressure = pressure;
        ticks = 0;
        return true;
    }
}
