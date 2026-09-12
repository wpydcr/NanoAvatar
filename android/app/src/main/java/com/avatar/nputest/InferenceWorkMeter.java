package com.avatar.nputest;

/** Counts only explicitly delimited model work; time between calls is intentionally absent. */
final class InferenceWorkMeter {
    private long computeNanos;
    private long freshFrames;
    private boolean complete;
    private boolean cancelled;

    void recordCall(long startedNanos, long finishedNanos) {
        if (complete || cancelled) return;
        if (finishedNanos < startedNanos) throw new IllegalArgumentException("Clock moved backwards");
        computeNanos = Math.addExact(computeNanos, finishedNanos - startedNanos);
    }

    void recordFreshFrame() {
        if (!complete && !cancelled) freshFrames++;
    }

    void finish() {
        if (!cancelled) complete = true;
    }

    void cancel() {
        if (!complete) cancelled = true;
    }

    boolean inferenceComplete() { return complete; }
    long computeNanos() { return computeNanos; }
    long freshFrames() { return freshFrames; }

    double completedFps() {
        return complete && computeNanos > 0 && freshFrames > 0
                ? freshFrames * 1_000_000_000d / computeNanos : -1;
    }
}
