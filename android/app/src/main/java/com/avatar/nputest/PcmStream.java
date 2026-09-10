package com.avatar.nputest;

import java.util.Arrays;
import java.util.Objects;

public final class PcmStream {
    private static final int HOP = 640;
    private static final int BLOCK_FRAMES = 10;
    private static final int BLOCK_SAMPLES = BLOCK_FRAMES * HOP;
    private static final int LEFT_SAMPLES = 16_000;
    private static final int RIGHT_SAMPLES = 6_400;
    private static final int MIN_EXTRACT_SAMPLES = 400;
    private static final int FEATURE_STEPS = 10;
    private static final int FEATURE_SIZE = 1024;

    public interface FeatureExtractor {
        float[][] extract(float[] actualPcmWindow) throws Exception;
    }

    public interface Sink {
        void onBlock(Block block) throws Exception;
    }

    public static final class Block {
        public final int firstFrame;
        public final float[] windows;
        public final float[][] audio;

        private Block(int firstFrame, float[] windows, float[][] audio) {
            this.firstFrame = firstFrame;
            this.windows = windows;
            this.audio = audio;
        }

        public int count() {
            return audio.length;
        }
    }

    private enum State {
        OPEN,
        FINISHED,
        CANCELLED,
        FAILED
    }

    private final FeatureExtractor extractor;
    private final Sink sink;
    private volatile State state = State.OPEN;
    private boolean busy;
    private float[] pcm = new float[0];
    private long baseSample;
    private long receivedSamples;
    private int nextFrame;

    public PcmStream(FeatureExtractor extractor, Sink sink) {
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    public void push(float[] samples) throws Exception {
        beginOperation();
        try {
            validateSamples(samples);
            for (int offset = 0; offset < samples.length && isOpen(); offset += BLOCK_SAMPLES) {
                int count = Math.min(BLOCK_SAMPLES, samples.length - offset);
                append(samples, offset, count);
                while (isFullBlockReady()) {
                    if (!emit(BLOCK_FRAMES, false)) {
                        return;
                    }
                }
            }
        } catch (Exception | Error error) {
            fail();
            throw error;
        } finally {
            endOperation();
        }
    }

    public void finish() throws Exception {
        synchronized (this) {
            if (state == State.FINISHED) {
                return;
            }
        }
        beginOperation();
        try {
            int targetFrames;
            synchronized (this) {
                targetFrames = (int) ((receivedSamples + HOP - 1) / HOP);
            }
            while (isOpen()) {
                int remaining;
                synchronized (this) {
                    remaining = targetFrames - nextFrame;
                }
                if (remaining <= 0 || !emit(Math.min(BLOCK_FRAMES, remaining), true)) {
                    break;
                }
            }
            synchronized (this) {
                if (state == State.OPEN) {
                    state = State.FINISHED;
                    pcm = new float[0];
                }
            }
        } catch (Exception | Error error) {
            fail();
            throw error;
        } finally {
            endOperation();
        }
    }

    public synchronized void cancel() {
        if (state == State.OPEN) {
            state = State.CANCELLED;
            pcm = new float[0];
        }
    }

    public synchronized long receivedSamples() {
        return receivedSamples;
    }

    public synchronized int bufferedSamples() {
        return pcm.length;
    }

    private synchronized void beginOperation() {
        if (state != State.OPEN) {
            throw new IllegalStateException("PCM stream is not open");
        }
        if (busy) {
            throw new IllegalStateException("Another push or finish is active");
        }
        busy = true;
    }

    private synchronized void endOperation() {
        busy = false;
    }

    private boolean isOpen() {
        return state == State.OPEN;
    }

    private static void validateSamples(float[] samples) {
        Objects.requireNonNull(samples, "samples");
        for (float sample : samples) {
            if (!Float.isFinite(sample) || Math.abs(sample) > 1.001f) {
                throw new IllegalArgumentException("PCM samples must be finite and in [-1, 1]");
            }
        }
    }

    private synchronized void append(float[] samples, int offset, int count) {
        if (state != State.OPEN) {
            return;
        }
        int oldLength = pcm.length;
        pcm = Arrays.copyOf(pcm, oldLength + count);
        System.arraycopy(samples, offset, pcm, oldLength, count);
        receivedSamples += count;
    }

    private synchronized boolean isFullBlockReady() {
        return state == State.OPEN
                && receivedSamples >= (long) nextFrame * HOP + BLOCK_SAMPLES + RIGHT_SAMPLES;
    }

    private boolean emit(int count, boolean isFinal) throws Exception {
        PendingBlock pending = snapshot(count);
        if (pending == null) {
            return false;
        }

        float[][] features = extractor.extract(pending.extractWindow);
        if (!isOpen()) {
            return false;
        }
        features = validateAndPadFeatures(features);
        float[] windows = buildWindows(features, pending.firstFrame, pending.leftSample, count, isFinal);
        Block block = new Block(pending.firstFrame, windows, pending.audio);

        synchronized (this) {
            if (state != State.OPEN) {
                return false;
            }
        }
        sink.onBlock(block);
        synchronized (this) {
            if (state != State.OPEN) {
                return false;
            }
            nextFrame += count;
            trimConsumedContext();
            return true;
        }
    }

    private synchronized PendingBlock snapshot(int count) {
        if (state != State.OPEN) {
            return null;
        }
        int firstFrame = nextFrame;
        long start = (long) firstFrame * HOP;
        long left = Math.max(0L, start - LEFT_SAMPLES);
        long right = Math.min(receivedSamples, start + BLOCK_SAMPLES + RIGHT_SAMPLES);
        float[] extractWindow = copyAbsoluteRange(left, right);
        if (extractWindow.length < MIN_EXTRACT_SAMPLES) {
            extractWindow = Arrays.copyOf(extractWindow, MIN_EXTRACT_SAMPLES);
        }

        float[][] audio = new float[count][];
        for (int offset = 0; offset < count; offset++) {
            long audioStart = (long) (firstFrame + offset) * HOP;
            long audioEnd = Math.min(audioStart + HOP, receivedSamples);
            audio[offset] = copyAbsoluteRange(audioStart, audioEnd);
        }
        return new PendingBlock(firstFrame, left, extractWindow, audio);
    }

    private float[] copyAbsoluteRange(long from, long to) {
        int localFrom = Math.toIntExact(from - baseSample);
        int localTo = Math.toIntExact(to - baseSample);
        if (localFrom < 0 || localTo < localFrom || localTo > pcm.length) {
            throw new IllegalStateException("PCM context is outside the retained buffer");
        }
        return Arrays.copyOfRange(pcm, localFrom, localTo);
    }

    private static float[][] validateAndPadFeatures(float[][] features) {
        if (features == null || features.length == 0) {
            throw new IllegalArgumentException("Feature extractor must return nonempty [steps, 1024]");
        }
        for (float[] step : features) {
            if (step == null || step.length != FEATURE_SIZE) {
                throw new IllegalArgumentException("Feature extractor must return nonempty [steps, 1024]");
            }
        }
        if (features.length >= FEATURE_STEPS) {
            return features;
        }
        float[][] padded = new float[FEATURE_STEPS][FEATURE_SIZE];
        for (int step = 0; step < features.length; step++) {
            System.arraycopy(features[step], 0, padded[step], 0, FEATURE_SIZE);
        }
        return padded;
    }

    private static float[] buildWindows(
            float[][] features, int firstFrame, long leftSample, int count, boolean isFinal) {
        float[] result = new float[Math.multiplyExact(count, FEATURE_STEPS * FEATURE_SIZE)];
        for (int frameOffset = 0; frameOffset < count; frameOffset++) {
            int frame = firstFrame + frameOffset;
            int featureStart = Math.toIntExact(((long) frame * HOP - leftSample) / 320L);
            if (featureStart + FEATURE_STEPS > features.length) {
                if (!isFinal) {
                    throw new IllegalStateException("Insufficient right context for an unfinalized block");
                }
                featureStart = features.length - FEATURE_STEPS;
            }
            int target = frameOffset * FEATURE_STEPS * FEATURE_SIZE;
            for (int step = 0; step < FEATURE_STEPS; step++) {
                System.arraycopy(features[featureStart + step], 0, result,
                        target + step * FEATURE_SIZE, FEATURE_SIZE);
            }
        }
        return result;
    }

    private synchronized void trimConsumedContext() {
        long keepFrom = Math.max(0L, (long) nextFrame * HOP - LEFT_SAMPLES);
        int remove = (int) Math.max(0L, Math.min((long) pcm.length, keepFrom - baseSample));
        if (remove > 0) {
            pcm = Arrays.copyOfRange(pcm, remove, pcm.length);
            baseSample += remove;
        }
    }

    private synchronized void fail() {
        if (state == State.OPEN) {
            state = State.FAILED;
            pcm = new float[0];
        }
    }

    private static final class PendingBlock {
        final int firstFrame;
        final long leftSample;
        final float[] extractWindow;
        final float[][] audio;

        PendingBlock(int firstFrame, long leftSample, float[] extractWindow, float[][] audio) {
            this.firstFrame = firstFrame;
            this.leftSample = leftSample;
            this.extractWindow = extractWindow;
            this.audio = audio;
        }
    }
}
