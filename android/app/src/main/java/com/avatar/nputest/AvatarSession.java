package com.avatar.nputest;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Local avatar inference and playback, independent of cloud replies and chat history.
 * Create/prepare/begin/cancel/pause/close on the UI thread. Feed PCM/end from any thread.
 * One session owns one view and the device inference engine. Call close when its host is destroyed.
 */
public final class AvatarSession implements AutoCloseable {
    public interface Listener {
        default void onReady() { }
        default void onProgress(String message) { }
        default void onSurfaceAvailable(boolean available) { }
        default void onEnded(long id) { }
        default void onError(long id, String message, Throwable cause) { }
    }

    /**
     * First-frame computation runs from the first ready feature window to its first generated RGB,
     * excluding PCM accumulation, earlier cloud waits and presentation. A negative value means no RGB yet.
     * hasAudio means feature extraction has started, not merely that a PCM packet was accepted.
     */
    public static final class Metrics {
        public final double mouthFps, firstFrameMs, generatingMs;
        public final boolean hasAudio;
        public final String state;
        private Metrics(double fps, double first, double waiting, boolean audio, String state) {
            mouthFps = fps; firstFrameMs = first; generatingMs = waiting; hasAudio = audio; this.state = state;
        }
    }

    private static final class Stats {
        final long id, sent;
        // firstPcm records packet acceptance; modelInput records the first ready extraction window.
        long firstPcm, modelInput, firstGenerated, received, submitted, displayed, lastPhase, blockStarted;
        String state = "waiting";
        final List<Double> blocks = new ArrayList<>(), presentations = new ArrayList<>(), generations = new ArrayList<>();
        final ArrayDeque<Long> recent = new ArrayDeque<>();
        Map<String, Double> stages = Collections.emptyMap();
        Stats(long id, long sent) { this.id = id; this.sent = sent; }
        synchronized Map<String, Object> snapshot() {
            Map<String, Object> value = new LinkedHashMap<>();
            int count = presentations.size();
            Double first = count == 0 ? null : presentations.get(0), last = count == 0 ? null : presentations.get(count - 1);
            value.put("id", id); value.put("state", state);
            value.put("received_samples", received); value.put("submitted_samples", submitted);
            value.put("displayed_frames", displayed); value.put("generated_presented", count); value.put("last_phase", lastPhase);
            value.put("first_frame_ms", modelInput > 0 && firstGenerated > 0 ? (firstGenerated - modelInput) / 1e6 : null);
            value.put("first_frame_definition", "feature_extraction_start_to_first_generated_rgb");
            value.put("send_to_first_visible_ms", first);
            value.put("first_cloud_pcm_ms", firstPcm == 0 ? null : (firstPcm - sent) / 1e6);
            value.put("send_to_model_input_ms", modelInput == 0 ? null : (modelInput - sent) / 1e6);
            value.put("send_to_first_generated_ms", firstGenerated == 0 ? null : (firstGenerated - sent) / 1e6);
            value.put("elapsed_ms", (SystemClock.elapsedRealtimeNanos() - sent) / 1e6);
            value.put("block_produce_ms", new ArrayList<>(blocks));
            value.put("new_frame_presented_ms", new ArrayList<>(presentations));
            value.put("new_frame_generated_ms", new ArrayList<>(generations));
            value.put("npu_stage_ms", new LinkedHashMap<>(stages));
            value.put("new_mouth_fps", count > 1 && last > first ? (count - 1) * 1000d / (last - first) : null);
            return value;
        }
    }

    private final Context context;
    private final AvatarView view;
    private final File avatar;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AudioInbox inbox = new AudioInbox();
    private volatile boolean ready, closed, paused;
    private boolean prepared;
    private volatile long active;
    private long sequence = System.currentTimeMillis();
    private volatile Stats stats;
    private volatile PcmStream stream;
    private PlaybackEngine player;
    private NpuBackend backend;
    private Thread producer;

    public AvatarSession(Context context, AvatarView view, File avatarDirectory, Listener listener) {
        requireUi();
        this.context = Objects.requireNonNull(context).getApplicationContext();
        this.view = Objects.requireNonNull(view);
        this.avatar = Objects.requireNonNull(avatarDirectory);
        this.listener = Objects.requireNonNull(listener);
    }

    /** Loads models and prepares fixed reference features asynchronously, before accepting audio. */
    public void prepare() {
        requireUi();
        if (closed || prepared) throw new IllegalStateException("prepare may be called once");
        prepared = true;
        producer = new Thread(() -> {
            try {
                post(() -> listener.onProgress("正在准备人物和模型，首次启动需要一些时间"));
                android.content.res.AssetManager assets = context.getAssets();
                BundledAssets.ensure(new BundledAssets.Source() {
                    public String[] list(String path) throws java.io.IOException { return assets.list(path); }
                    public java.io.InputStream open(String path) throws java.io.IOException { return assets.open(path); }
                }, context.getExternalFilesDir(null));
                post(this::openAvatar);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Exception error) {
                String message = error instanceof BundledAssets.MissingBundleException
                    ? "缺少人物或模型，请下载完整版安装包或按源码说明配置"
                    : "人物和模型准备失败，请检查存储空间后退出并重新打开应用";
                post(() -> { close(); listener.onError(0, message, error); });
            }
        }, "avatar-prepare");
        producer.start();
    }

    private void openAvatar() {
        view.open(avatar, new AvatarView.Listener() {
            public void onReady() { post(() -> {
                if (player == null) startEngine();
                else { player.setSurfaceReady(true); player.pause(paused); }
                listener.onSurfaceAvailable(true);
            }); }
            public void onUnavailable() { post(() -> {
                if (player != null) { player.setSurfaceReady(false); player.pause(true); }
                listener.onSurfaceAvailable(false);
            }); }
            public void onError(String message) { post(() -> {
                close(); listener.onError(0, message, null);
            }); }
        });
    }

    public boolean isReady() { return ready && !closed && !paused && view.isReadyForRendering(); }

    /** Starts a new utterance and immediately cancels any previous one. Pass its id to every callback. */
    public long begin() {
        requireUi();
        if (!isReady()) throw new IllegalStateException("Avatar is not ready");
        cancel();
        long id = ++sequence;
        stats = new Stats(id, SystemClock.elapsedRealtimeNanos());
        active = id; player.active(id); inbox.begin(id); return id;
    }

    /** 16 kHz mono float PCM in [-1,1]. Copied before return. False means a stale or ended utterance. */
    public boolean pushAudio(long id, float[] pcm) {
        if (id != active || closed) return false;
        boolean accepted = inbox.push(id, pcm);
        Stats s = stats;
        if (accepted && pcm.length > 0 && s != null) synchronized (s) {
            if (s.id == id && id == active) {
                if (s.firstPcm == 0) s.firstPcm = SystemClock.elapsedRealtimeNanos();
                s.received += pcm.length;
            }
        }
        return accepted;
    }

    /** Explicit end-of-audio, including an empty response. The buffered tail is generated and played. */
    public boolean endAudio(long id) { return id == active && !closed && inbox.end(id); }

    public void cancel() {
        requireUi();
        active = 0;
        if (player != null) player.active(0);
        inbox.cancel();
        PcmStream current = stream; if (current != null) current.cancel();
        Stats s = stats; if (s != null) synchronized (s) {
            if (s.state.equals("waiting")) s.state = "cancelled";
        }
    }

    /** Host backgrounding cancels speech. Resume preserves the continuous source-video phase. */
    public void setPaused(boolean value) {
        requireUi(); paused = value;
        if (value) cancel();
        if (player != null) player.pause(value || !view.isReadyForRendering());
        view.setKeepScreenOn(!value && !closed);
    }

    public Metrics metrics() {
        Stats s = stats;
        if (s == null) return new Metrics(0, -1, 0, false, "idle");
        long now = SystemClock.elapsedRealtimeNanos();
        synchronized (s) {
            while (!s.recent.isEmpty() && s.recent.peekFirst() < now - 1_000_000_000L) s.recent.removeFirst();
            int count = s.recent.size();
            long span = count > 1 ? s.recent.peekLast() - s.recent.peekFirst() : 0;
            double fps = span > 0 ? (count - 1) * 1e9 / span : 0;
            return new Metrics(fps, s.firstGenerated > 0 && s.modelInput > 0 ? (s.firstGenerated - s.modelInput) / 1e6 : -1,
                    s.modelInput > 0 ? (now - s.modelInput) / 1e6 : 0, s.modelInput > 0, s.state);
        }
    }

    /** Full diagnostic snapshot. Use at turn end, not on every rendered frame. */
    public Map<String, Object> snapshot() {
        Stats s = stats;
        if (s == null) return Collections.emptyMap();
        Map<String, Object> value = s.snapshot();
        if (player != null) {
            value.put("audio_underruns", player.underruns()); value.put("queued_frames", player.queued());
            List<Map<String, Object>> events = new ArrayList<>();
            for (long[] event : player.underrunEvents()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("since_send_ms", (event[0] - s.sent) / 1e6); e.put("total", event[1]); events.add(e);
            }
            value.put("audio_underrun_events", events);
        }
        return value;
    }

    private void startEngine() {
        player = new PlaybackEngine(view, new PlaybackEngine.Listener() {
            public void onPresented(long id, long phase, boolean generated) {
                long now = SystemClock.elapsedRealtimeNanos(); Stats s = stats;
                if (s != null) synchronized (s) { if (s.id == id && id == active) {
                    s.displayed++; s.lastPhase = phase;
                    if (generated) { s.presentations.add((now - s.sent) / 1e6); s.recent.addLast(now); }
                } }
            }
            public void onEnded(long id) { post(() -> {
                if (id != active) return;
                Stats s = stats; if (s != null) synchronized (s) { s.state = "complete"; }
                listener.onEnded(id);
            }); }
            public void onFailure(String message, Throwable error) { post(() -> {
                close(); listener.onError(0, message, error);
            }); }
        });
        player.setSurfaceReady(view.isReadyForRendering()); player.pause(paused || !view.isReadyForRendering());
        producer = new Thread(() -> {
            try {
                NpuBackend.Progress progress = message -> post(() -> listener.onProgress(message));
                backend = new NpuBackend(context, new File(context.getExternalFilesDir(null), "payload"), avatar, progress);
                backend.prepareReferences(message -> post(() -> listener.onProgress(message)));
                if (closed) return;
                ready = true; post(() -> { view.setKeepScreenOn(!paused); listener.onReady(); });
                produce();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Throwable error) { ready = false; post(() -> { close(); listener.onError(0, "人物准备失败", error); }); }
            finally { if (backend != null) backend.close(); }
        }, "avatar-inference");
        producer.start();
    }

    private void produce() throws Exception {
        while (!closed) {
            if (paused) { Thread.sleep(20); continue; }
            AudioInbox.Item command = inbox.poll(10);
            if (command == null) { if (player.queued() < 4) player.idle(); continue; }
            long id = command.id;
            if (id != active) continue;
            try {
                if (command.kind == AudioInbox.BEGIN) {
                    if (stream != null) stream.cancel();
                    backend.beginSpeech();
                    stream = new PcmStream(actual -> {
                        Stats s = stats; if (s != null) synchronized (s) { if (s.id == id && id == active) {
                            long now = SystemClock.elapsedRealtimeNanos();
                            s.blockStarted = now;
                            if (s.modelInput == 0) s.modelInput = now;
                        } }
                        return backend.extract(actual);
                    }, block -> produceBlock(id, block));
                } else if (command.kind == AudioInbox.AUDIO) {
                    stream.push(command.pcm);
                } else {
                    stream.finish(); if (id == active) player.end(id);
                }
            } catch (Exception error) { post(() -> {
                if (id != active) return;
                cancel(); Stats s = stats; if (s != null) synchronized (s) { s.state = "failed"; }
                listener.onError(id, "本次口播未能完成", error);
            }); }
        }
    }

    // Extracted from the accepted ChatActivity producer with the same sample clock and scheduling.
    private void produceBlock(long id, PcmStream.Block block) throws Exception {
        if (id != active) return;
        Stats timing = stats; long started = 0;
        if (timing != null) synchronized (timing) { if (timing.id == id && id == active) started = timing.blockStarted; }
        float[] encoded = backend.audio(block);
        if (id != active) return;
        for (int i = 0; i < block.count(); i++) {
            if (id != active) return;
            byte[] rgb = backend.generate(encoded, i, player.nextPhase());
            Stats generatedStats = stats; if (generatedStats != null) synchronized (generatedStats) {
                if (generatedStats.id == id && active == id) {
                    long now = SystemClock.elapsedRealtimeNanos();
                    if (generatedStats.firstGenerated == 0) generatedStats.firstGenerated = now;
                    generatedStats.generations.add((now - generatedStats.sent) / 1e6);
                }
            }
            if (id != active) return;
            if (!player.submit(id, block.audio[i], rgb, Math.min(1, (block.firstFrame + i + 1) / 5f), true)) return;
            Stats s = stats; if (s != null) synchronized (s) { if (s.id == id && id == active) s.submitted += block.audio[i].length; }
        }
        Stats s = stats; if (s != null && started != 0) {
            Map<String, Double> stages = new LinkedHashMap<>(backend.stageMs);
            synchronized (s) { if (s.id == id && id == active) {
                s.blocks.add((SystemClock.elapsedRealtimeNanos() - started) / 1e6); s.blockStarted = 0; s.stages = stages;
            } }
        }
    }

    private void post(Runnable task) { ui.post(() -> { if (!closed) task.run(); }); }
    private static void requireUi() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Call lifecycle methods on the UI thread");
    }
    @Override public void close() {
        requireUi(); if (closed) return;
        cancel(); closed = true; ready = false; inbox.close(); ui.removeCallbacksAndMessages(null);
        if (player != null) player.close(); if (producer != null) producer.interrupt(); view.close();
    }
}
