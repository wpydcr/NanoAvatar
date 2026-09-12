package com.avatar.nputest;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import java.util.Arrays;
import java.util.Objects;

final class VoiceRecorder implements AutoCloseable {
    interface Listener {
        void onFinished(float[] pcm);
        void onError();
    }

    private static final int SAMPLE_RATE = 16_000;
    private static final int MAX_SAMPLES = SAMPLE_RATE * 30;

    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean finishRequested;
    private volatile boolean closed;
    private Thread thread;

    VoiceRecorder(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    synchronized void start() {
        if (thread != null || closed) return;
        thread = new Thread(this::record, "voice-recorder");
        thread.start();
    }

    void finish() {
        finishRequested = true;
    }

    @Override public void close() {
        Thread active;
        synchronized (this) {
            closed = true;
            active = thread;
        }
        if (active != null) active.interrupt();
    }

    private void record() {
        AudioRecord recorder = null;
        float[] result = null;
        boolean failed = false;
        boolean reachedLimit = false;
        try {
            if (finishRequested) {
                result = new float[0];
            } else {
                int minimum = AudioRecord.getMinBufferSize(
                        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) throw new IllegalStateException("AudioRecord buffer size");
                int bufferBytes = Math.max(minimum, SAMPLE_RATE / 5);
                recorder = new AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build())
                        .setBufferSizeInBytes(bufferBytes)
                        .build();
                if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
                    throw new IllegalStateException("AudioRecord initialization");
                if (!closed && !finishRequested) {
                    recorder.startRecording();
                    if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                        throw new IllegalStateException("AudioRecord start");
                }

                float[] pcm = new float[MAX_SAMPLES];
                short[] input = new short[Math.max(320, bufferBytes / 2)];
                int count = 0;
                while (!closed && !finishRequested && count < MAX_SAMPLES) {
                    int read = recorder.read(input, 0,
                            Math.min(input.length, MAX_SAMPLES - count), AudioRecord.READ_NON_BLOCKING);
                    if (read < 0) throw new IllegalStateException("AudioRecord read: " + read);
                    if (read == 0) {
                        Thread.sleep(5);
                        continue;
                    }
                    for (int i = 0; i < read; i++) pcm[count++] = input[i] / 32768f;
                }
                if (!closed) {
                    result = Arrays.copyOf(pcm, count);
                    reachedLimit = count == MAX_SAMPLES && !finishRequested;
                }
            }
        } catch (InterruptedException error) {
            if (!closed) failed = true;
            Thread.currentThread().interrupt();
        } catch (RuntimeException error) {
            if (!closed) failed = true;
        } finally {
            if (recorder != null) {
                try {
                    if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop();
                } catch (RuntimeException ignored) {}
                try { recorder.release(); } catch (RuntimeException ignored) {}
            }
        }

        if (closed) return;
        if (failed) {
            postError();
            return;
        }
        if (reachedLimit) {
            try {
                while (!closed && !finishRequested) Thread.sleep(5);
            } catch (InterruptedException error) {
                if (!closed) postError();
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!closed) postFinished(result == null ? new float[0] : result);
    }

    private void postFinished(float[] pcm) {
        main.post(() -> {
            synchronized (this) {
                if (!closed) listener.onFinished(pcm);
            }
        });
    }

    private void postError() {
        main.post(() -> {
            synchronized (this) {
                if (!closed) listener.onError();
            }
        });
    }
}
