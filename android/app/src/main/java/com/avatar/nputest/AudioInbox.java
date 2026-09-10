package com.avatar.nputest;

import java.util.ArrayDeque;
import java.util.Objects;

/** Copies admitted PCM without blocking the cloud voice socket reader. */
final class AudioInbox implements AutoCloseable {
    static final int BEGIN = 0, AUDIO = 1, END = 2;
    static final int MAX_PENDING_SAMPLES = 90 * 16000;
    static final class Item {
        final long id;
        final int kind;
        final float[] pcm;
        Item(long id, int kind, float[] pcm) { this.id = id; this.kind = kind; this.pcm = pcm; }
    }
    private final ArrayDeque<Item> items = new ArrayDeque<>();
    private long active;
    private int pending;
    private boolean accepting, closed;

    synchronized void begin(long id) {
        if (closed) throw new IllegalStateException("Session is closed");
        if (id <= 0) throw new IllegalArgumentException("Utterance id must be positive");
        cancel(); active = id; accepting = true;
        items.add(new Item(id, BEGIN, null)); notifyAll();
    }
    synchronized boolean push(long id, float[] pcm) {
        if (closed || !accepting || id != active) return false;
        Objects.requireNonNull(pcm, "pcm");
        if (pcm.length > MAX_PENDING_SAMPLES - pending)
            throw new IllegalArgumentException("More than 90 seconds of PCM is waiting for playback");
        for (float value : pcm) if (!Float.isFinite(value) || value < -1f || value > 1f)
            throw new IllegalArgumentException("PCM must be finite and normalized to [-1, 1]");
        if (pcm.length > 0) {
            items.add(new Item(id, AUDIO, pcm.clone())); pending += pcm.length; notifyAll();
        }
        return true;
    }
    synchronized boolean end(long id) {
        if (closed || !accepting || active != id) return false;
        accepting = false; items.add(new Item(id, END, null)); notifyAll(); return true;
    }
    synchronized Item poll(long timeoutMs) throws InterruptedException {
        if (items.isEmpty() && !closed && timeoutMs > 0) wait(timeoutMs);
        Item item = items.poll();
        if (item != null && item.pcm != null) pending -= item.pcm.length;
        return item;
    }
    synchronized void cancel() { active = 0; accepting = false; items.clear(); pending = 0; notifyAll(); }
    @Override public synchronized void close() { closed = true; cancel(); }
}
