package com.avatar.nputest;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

final class VoiceSettings implements AutoCloseable {
    private static final int PICK_AUDIO = 72, RECORD_PERMISSION = 73;
    private final Activity activity;
    private final CredentialStore credentials;
    private final BooleanSupplier english;
    private final Runnable changed;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Map<String, VoiceStore> stores = new HashMap<>();
    private AlertDialog library, creation, detail;
    private TextView progress, source;
    private ListView voices;
    private Button record, choose, preview;
    private EditText name;
    private File sample;
    private File clipSource;
    private long clipDurationUs;
    private SeekBar clipStart;
    private TextView clipRange;
    private boolean draggingClip;
    private String sampleLabel = "";
    private VoiceRecorder recorder;
    private MediaPlayer player;
    private long recordStarted;
    private boolean busy, foreground = true, closed;
    private String operation = "";
    private String creationResult = "", creationResultKey = "";
    private AlertDialog resultDialog;

    VoiceSettings(Activity activity, CredentialStore credentials, BooleanSupplier english, Runnable changed) {
        this.activity = activity;
        this.credentials = credentials;
        this.english = english;
        this.changed = changed;
    }

    private String t(String zh, String en) { return english.getAsBoolean() ? en : zh; }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private void toast(String message) { if (!closed && foreground) Toast.makeText(activity, message, Toast.LENGTH_LONG).show(); }

    private VoiceStore store(String key) throws IOException {
        synchronized (stores) {
            VoiceStore result = stores.get(key);
            if (result == null) {
                result = new VoiceStore(new File(activity.getFilesDir(), "voices"), key);
                stores.put(key, result);
            }
            return result;
        }
    }

    String selectedVoice() throws IOException {
        String key = credentials.get();
        return key.isEmpty() ? VoiceStore.DEFAULT_VOICE : store(key).selected();
    }

    String selectedName() {
        String key = credentials.get();
        if (key.isEmpty()) return t("默认音色", "Default voice");
        try {
            VoiceStore catalog = store(key);
            for (VoiceStore.Voice voice : catalog.list()) if (voice.id.equals(catalog.selected())) return voice.name;
            return t("默认音色", "Default voice");
        } catch (IOException error) { return t("音色列表无法读取", "Voice list unavailable"); }
    }

    void show() {
        if (credentials.get().isEmpty()) { toast(t("请先保存阿里云 API Key", "Save your Aliyun API key first")); return; }
        if (library != null && library.isShowing()) return;
        LinearLayout panel = panel();
        progress = new TextView(activity);
        progress.setPadding(0, dp(8), 0, dp(8));
        panel.addView(progress);
        voices = new ListView(activity);
        panel.addView(voices, new LinearLayout.LayoutParams(-1, dp(280)));
        library = new AlertDialog.Builder(activity).setTitle(t("选择音色", "Choose voice"))
                .setView(panel).setPositiveButton(t("关闭", "Close"), null)
                .setNeutralButton(t("创建音色", "Create voice"), null)
                .setNegativeButton(t("刷新", "Refresh"), null).create();
        library.setOnShowListener(d -> {
            library.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> createDialog());
            library.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> refresh());
            render();
        });
        library.show();
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(22), dp(8), dp(22), dp(8));
        return panel;
    }

    private void render() {
        if (closed || library == null || !library.isShowing()) return;
        voices.setEnabled(!busy);
        library.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(!busy);
        library.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!busy);
        String result = creationResultKey.equals(credentials.get()) ? creationResult : "";
        progress.setText(busy ? operation : (result.isEmpty() ? "" : result + "\n")
                + t("当前音色：", "Current voice: ") + selectedName());
        try {
            String key = credentials.get();
            if (key.isEmpty()) { library.dismiss(); return; }
            VoiceStore catalog = store(key);
            List<VoiceStore.Voice> entries = catalog.list();
            List<String> labels = new ArrayList<>();
            labels.add(t("默认音色", "Default voice") + (VoiceStore.DEFAULT_VOICE.equals(catalog.selected()) ? t(" · 使用中", " · Active") : ""));
            for (VoiceStore.Voice voice : entries) labels.add(voice.name + " · " +
                    (voice.id.equals(catalog.selected()) ? t("使用中", "Active") : stateLabel(voice.status)));
            voices.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, labels));
            voices.setOnItemClickListener((parent, view, position, id) -> {
                if (busy) return;
                if (position == 0) select(key, catalog, VoiceStore.DEFAULT_VOICE);
                else showVoice(key, catalog, entries.get(position - 1));
            });
        } catch (IOException error) { progress.setText(t("音色列表读取失败，请检查存储空间后重试", "Could not read voices. Check device storage and retry.")); }
    }

    private String stateLabel(String status) {
        if ("OK".equals(status)) return t("可使用", "Ready");
        return "UNDEPLOYED".equals(status) ? t("复刻未通过", "Enrollment rejected") : t("处理中", "Processing");
    }

    private void showVoice(String key, VoiceStore catalog, VoiceStore.Voice voice) {
        detail = new AlertDialog.Builder(activity).setTitle(voice.name).setMessage(stateLabel(voice.status))
                .setPositiveButton(t("使用", "Use"), (d, w) -> select(key, catalog, voice.id))
                .setNeutralButton(t("删除", "Delete"), (d, w) -> confirmDelete(key, catalog, voice))
                .setNegativeButton(t("取消", "Cancel"), null).create();
        detail.setOnShowListener(d -> detail.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled("OK".equals(voice.status)));
        detail.show();
    }

    private void select(String key, VoiceStore catalog, String id) {
        work(t("正在切换音色", "Changing voice"), () -> catalog.select(id), () -> {
            if (key.equals(credentials.get())) changed.run();
            render();
        });
    }

    private void confirmDelete(String key, VoiceStore catalog, VoiceStore.Voice voice) {
        detail = new AlertDialog.Builder(activity).setTitle(t("删除音色", "Delete voice"))
                .setMessage(t("删除“", "Delete \"") + voice.name + t("”？云端音色也会删除。", "\"? This also deletes the cloud voice."))
                .setNegativeButton(t("取消", "Cancel"), null)
                .setPositiveButton(t("删除", "Delete"), (d, w) -> work(t("正在删除音色", "Deleting voice"), () -> {
                    try (VoiceEnrollmentApi api = new VoiceEnrollmentApi(key)) { api.delete(voice.id); }
                    catalog.remove(voice.id);
                }, () -> { if (key.equals(credentials.get())) changed.run(); render(); })).show();
    }

    private void refresh() {
        if (busy || credentials.get().isEmpty()) return;
        String key = credentials.get();
        work(t("正在同步音色", "Syncing voices"), () -> {
            VoiceStore catalog = store(key);
            List<VoiceEnrollmentApi.RemoteVoice> remote;
            try (VoiceEnrollmentApi api = new VoiceEnrollmentApi(key)) { remote = api.list(); }
            Map<String, String> names = new HashMap<>();
            for (VoiceStore.Voice voice : catalog.list()) names.put(voice.id, voice.name);
            Set<String> ids = new HashSet<>();
            for (VoiceEnrollmentApi.RemoteVoice voice : remote) {
                ids.add(voice.id);
                catalog.put(voice.id, names.getOrDefault(voice.id, voice.id), voice.status);
            }
            for (VoiceStore.Voice voice : catalog.list()) if (!ids.contains(voice.id)) catalog.remove(voice.id);
        }, this::render);
    }

    private Button button(String label, int icon) {
        Button button = new Button(activity);
        button.setAllCaps(false);
        button.setText(label);
        button.setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0);
        button.setCompoundDrawablePadding(dp(8));
        return button;
    }

    private void createDialog() {
        if (busy) return;
        clearSample();
        LinearLayout panel = panel();
        name = new EditText(activity);
        name.setSingleLine(true);
        name.setHint(t("音色名称", "Voice name"));
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        panel.addView(name);
        source = new TextView(activity);
        source.setPadding(0, dp(12), 0, dp(12));
        source.setText(t("WAV / MP3 / M4A · 最多 10 MB\n至少 5 秒、16 kHz；长音频可选取 20 秒片段", "WAV / MP3 / M4A · Up to 10 MB\nAt least 5 seconds, 16 kHz. Long audio: choose a 20-second clip."));
        panel.addView(source);
        clipRange = new TextView(activity);
        clipRange.setVisibility(View.GONE);
        panel.addView(clipRange);
        clipStart = new SeekBar(activity);
        clipStart.setContentDescription(t("片段起点", "Clip start"));
        clipStart.setVisibility(View.GONE);
        clipStart.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                updateClipRange();
                if (fromUser && !draggingClip) extractClip();
            }
            public void onStartTrackingTouch(SeekBar bar) {
                draggingClip = true;
                stopPlayer();
                updateCreation();
            }
            public void onStopTrackingTouch(SeekBar bar) { draggingClip = false; extractClip(); }
        });
        panel.addView(clipStart);
        record = button(t("录音", "Record"), android.R.drawable.ic_btn_speak_now);
        choose = button(t("选择音频文件", "Choose audio file"), android.R.drawable.ic_menu_upload);
        preview = button(t("试听录音", "Play sample"), android.R.drawable.ic_media_play);
        record.setOnClickListener(v -> { if (recorder == null) startRecording(); else finishRecording(); });
        choose.setOnClickListener(v -> pickAudio());
        preview.setOnClickListener(v -> playSample());
        preview.setEnabled(false);
        panel.addView(record); panel.addView(choose); panel.addView(preview);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(panel);
        creation = new AlertDialog.Builder(activity).setTitle(t("创建音色", "Create voice"))
                .setView(scroll).setNegativeButton(t("取消", "Cancel"), null)
                .setPositiveButton(t("创建并使用", "Create and use"), null).create();
        creation.setOnShowListener(d -> creation.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> createVoice()));
        creation.setOnDismissListener(d -> { stopRecording(); stopPlayer(); clearSample(); });
        creation.show();
    }

    private void pickAudio() {
        stopPlayer();
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("audio/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { activity.startActivityForResult(intent, PICK_AUDIO); }
        catch (android.content.ActivityNotFoundException error) { toast(t("系统文件选择器不可用", "The system file picker is unavailable")); }
    }

    boolean onActivityResult(int request, int result, Intent data) {
        if (request != PICK_AUDIO) return false;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null || creation == null || !creation.isShowing()) return true;
        Uri uri = data.getData();
        final File[] imported = new File[1];
        final File[] prepared = new File[1];
        final VoiceAudioClip.Info[] info = new VoiceAudioClip.Info[1];
        final String[] label = new String[1];
        work(t("正在读取音频", "Reading audio"), () -> {
            String filename = "";
            try (Cursor cursor = activity.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) filename = cursor.getString(0);
            }
            String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
            String mime = activity.getContentResolver().getType(uri);
            String suffix = lower.endsWith(".wav") ? ".wav" : lower.endsWith(".mp3") ? ".mp3" : lower.endsWith(".m4a") ? ".m4a" :
                    "audio/mpeg".equals(mime) ? ".mp3" : "audio/mp4".equals(mime) ? ".m4a" :
                    "audio/wav".equals(mime) || "audio/x-wav".equals(mime) ? ".wav" : "";
            try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("Missing audio");
                imported[0] = VoiceSample.copy(activity.getCacheDir(), input, suffix);
            }
            info[0] = VoiceAudioClip.inspect(imported[0]);
            if (info[0].durationUs > 60_000_000) {
                prepared[0] = VoiceAudioClip.extract(imported[0], activity.getCacheDir(), 0);
            } else {
                validateFile(imported[0]);
                prepared[0] = imported[0];
            }
            label[0] = filename;
        }, () -> {
            if (creation != null && creation.isShowing()) {
                setSample(prepared[0], label[0]);
                if (prepared[0] != imported[0]) {
                    clipSource = imported[0];
                    clipDurationUs = info[0].durationUs;
                    clipStart.setMax((int) Math.min(Integer.MAX_VALUE, clipDurationUs / 1_000_000 - 20));
                    clipStart.setProgress(0);
                    clipStart.setVisibility(View.VISIBLE);
                    clipRange.setVisibility(View.VISIBLE);
                    updateClipRange();
                }
            } else {
                if (imported[0] != null) imported[0].delete();
                if (prepared[0] != null) prepared[0].delete();
            }
        }, () -> {
            if (imported[0] != null) imported[0].delete();
            if (prepared[0] != null) prepared[0].delete();
        });
        return true;
    }

    private String timestamp(long seconds) {
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private void updateClipRange() {
        if (clipSource == null) return;
        int start = clipStart.getProgress();
        clipRange.setText(t("片段：", "Clip: ") + timestamp(start) + " – " + timestamp(start + 20)
                + t(" / 总长 ", " / Total ") + timestamp(clipDurationUs / 1_000_000));
    }

    private void extractClip() {
        if (busy || closed || clipSource == null) return;
        stopPlayer();
        File original = clipSource;
        long startUs = clipStart.getProgress() * 1_000_000L;
        String label = sampleLabel;
        if (sample != null) { sample.delete(); sample = null; }
        File[] extracted = new File[1];
        work(t("正在准备音频片段", "Preparing audio clip"),
                () -> extracted[0] = VoiceAudioClip.extract(original, activity.getCacheDir(), startUs),
                () -> {
                    if (creation != null && creation.isShowing()) replaceSample(extracted[0], label);
                    else extracted[0].delete();
                }, () -> { if (extracted[0] != null) extracted[0].delete(); });
    }

    private void validateFile(File file) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            MediaFormat audio = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                if (format.getString(MediaFormat.KEY_MIME).startsWith("audio/")) { audio = format; break; }
            }
            if (audio == null) throw new IOException("No audio track");
            if (file.getName().endsWith(".wav") && audio.containsKey(MediaFormat.KEY_PCM_ENCODING)
                    && audio.getInteger(MediaFormat.KEY_PCM_ENCODING) != android.media.AudioFormat.ENCODING_PCM_16BIT)
                throw new IOException("WAV must contain 16-bit PCM");
            VoiceSample.validate(audio.getLong(MediaFormat.KEY_DURATION), audio.getInteger(MediaFormat.KEY_SAMPLE_RATE), audio.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
        } catch (RuntimeException error) { throw new IOException("Unreadable audio format", error); }
        finally { extractor.release(); }
    }

    private void startRecording() {
        if (busy || !foreground) return;
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, RECORD_PERMISSION);
            return;
        }
        stopPlayer();
        final VoiceRecorder[] owner = new VoiceRecorder[1];
        owner[0] = new VoiceRecorder(new VoiceRecorder.Listener() {
            public void onFinished(float[] pcm) {
                if (recorder != owner[0]) return;
                recorder = null;
                ui.removeCallbacks(recordTick);
                try { setSample(VoiceSample.fromPcm(activity.getCacheDir(), pcm), t("麦克风录音", "Microphone recording")); }
                catch (IOException error) { toast(t("录音至少需要 5 秒，请重新录制", "Record at least 5 seconds and try again")); }
                updateCreation();
            }
            public void onError() {
                if (recorder != owner[0]) return;
                stopRecording();
                toast(t("无法录音，请检查麦克风权限或占用情况", "Cannot record. Check microphone permission or other recording apps."));
            }
        });
        recorder = owner[0];
        recordStarted = SystemClock.elapsedRealtime();
        recorder.start();
        updateCreation();
        ui.post(recordTick);
    }

    private final Runnable recordTick = new Runnable() {
        public void run() {
            if (recorder == null) return;
            long elapsed = SystemClock.elapsedRealtime() - recordStarted;
            record.setText(String.format(Locale.ROOT, t("停止录音 · %.1f 秒", "Stop recording · %.1fs"), elapsed / 1000d));
            if (elapsed >= 30_000) finishRecording(); else ui.postDelayed(this, 200);
        }
    };

    private void finishRecording() {
        ui.removeCallbacks(recordTick);
        if (recorder != null) { record.setEnabled(false); recorder.finish(); }
    }

    private void stopRecording() {
        ui.removeCallbacks(recordTick);
        if (recorder != null) { recorder.close(); recorder = null; }
        updateCreation();
    }

    boolean onPermissionResult(int request, int[] grants) {
        if (request != RECORD_PERMISSION) return false;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            if (foreground && creation != null && creation.isShowing()) startRecording();
        } else toast(t("未获得麦克风权限，仍可选择音频文件", "Microphone permission denied. You can still choose an audio file."));
        return true;
    }

    private void setSample(File file, String label) {
        stopPlayer();
        clearSample();
        replaceSample(file, label);
    }

    private void replaceSample(File file, String label) {
        if (sample != null && sample != file) sample.delete();
        sample = file;
        sampleLabel = label == null || label.isEmpty() ? t("音频文件", "Audio file") : label;
        source.setText(sampleLabel);
        updateCreation();
    }

    private void clearSample() {
        if (sample != null) { sample.delete(); sample = null; }
        if (clipSource != null) { clipSource.delete(); clipSource = null; }
        draggingClip = false;
        if (clipStart != null) clipStart.setVisibility(View.GONE);
        if (clipRange != null) clipRange.setVisibility(View.GONE);
    }

    private void playSample() {
        if (player != null) { stopPlayer(); return; }
        if (sample == null) return;
        MediaPlayer next = new MediaPlayer();
        player = next;
        try {
            next.setDataSource(sample.getAbsolutePath());
            next.setOnPreparedListener(p -> { if (player == p && foreground) { p.start(); preview.setText(t("停止试听", "Stop sample")); } });
            next.setOnCompletionListener(p -> stopPlayer());
            next.setOnErrorListener((p, what, extra) -> { stopPlayer(); toast(t("音频无法播放", "Could not play this audio")); return true; });
            next.prepareAsync();
        } catch (IOException | RuntimeException error) { stopPlayer(); toast(t("音频无法播放", "Could not play this audio")); }
    }

    private void stopPlayer() {
        if (player != null) { player.release(); player = null; }
        if (preview != null) preview.setText(t("试听录音", "Play sample"));
    }

    private void updateCreation() {
        if (creation == null || !creation.isShowing()) return;
        boolean recording = recorder != null;
        record.setEnabled(!busy);
        if (!recording) record.setText(t("录音", "Record"));
        choose.setEnabled(!busy && !recording);
        preview.setEnabled(!busy && !recording && !draggingClip && sample != null);
        clipStart.setEnabled(!busy && !recording);
        name.setEnabled(!busy && !recording);
        creation.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy && !recording && !draggingClip && sample != null);
        if (busy) source.setText(operation);
    }

    private void createVoice() {
        if (busy || recorder != null) return;
        String voiceName = name.getText().toString().trim();
        if (voiceName.isEmpty()) { name.setError(t("请输入音色名称", "Enter a voice name")); return; }
        if (sample == null) { toast(t("请先录音或选择音频文件", "Record or choose an audio file first")); return; }
        String key = credentials.get();
        if (key.isEmpty()) { toast(t("请先保存 API Key", "Save your API key first")); return; }
        File upload = sample;
        String label = sampleLabel;
        String[] createdId = new String[1];
        String[] finalStatus = new String[]{"DEPLOYING"};
        boolean[] selected = new boolean[1];
        creationResultKey = key;
        creationResult = "";
        sample = null;
        stopPlayer();
        work(t("正在上传并创建音色", "Uploading and creating voice"), () -> {
            try (VoiceEnrollmentApi api = new VoiceEnrollmentApi(key)) {
                VoiceStore catalog = store(key);
                String id = api.create(upload, stage -> {
                    android.util.Log.i("NanoAvatar", "voice create stage: " + stage);
                    ui.post(() -> {
                        if (closed) return;
                        operation = "policy".equals(stage) ? t("正在连接上传服务", "Connecting to upload service") :
                                "upload".equals(stage) ? t("正在上传音频", "Uploading audio") : t("正在提交音色复刻", "Submitting voice enrollment");
                        updateCreation(); render();
                    });
                });
                createdId[0] = id;
                catalog.put(id, voiceName, "DEPLOYING");
                ui.post(() -> {
                    if (!closed) {
                        operation = t("正在复刻音色", "Enrolling voice");
                        updateCreation(); render();
                    }
                });
                for (int attempt = 0; attempt < 20; attempt++) {
                    String status = api.status(id);
                    finalStatus[0] = status;
                    catalog.put(id, voiceName, status);
                    if ("OK".equals(status)) { catalog.select(id); selected[0] = true; return; }
                    if ("UNDEPLOYED".equals(status)) return;
                    Thread.sleep(3000);
                }
            } finally { if (createdId[0] != null) upload.delete(); }
        }, () -> {
            if (creation != null) creation.dismiss();
            if (selected[0]) {
                creationResult = t("音色已创建并使用：", "Voice created and selected: ") + voiceName;
                if (key.equals(credentials.get())) changed.run();
            } else if ("UNDEPLOYED".equals(finalStatus[0])) {
                creationResult = t("音色复刻未通过，请换一段连续、清晰的单人人声", "Voice enrollment was rejected. Choose another clip with continuous, clear speech from one speaker.");
            } else {
                creationResult = t("音色已提交，云端仍在处理。稍后点击刷新查看结果", "Voice submitted and still processing. Refresh the list later to check the result.");
            }
            showResult(selected[0] ? t("创建成功", "Voice created") :
                    "UNDEPLOYED".equals(finalStatus[0]) ? t("创建失败", "Creation failed") : t("仍在处理", "Still processing"), creationResult);
            render();
        }, () -> {
            if (createdId[0] == null) {
                if (!closed && creation != null && creation.isShowing()) replaceSample(upload, label);
                else upload.delete();
                creationResult = !closed && creation != null && creation.isShowing()
                        ? t("创建未完成，音频已保留，可重试", "Creation did not finish. Your audio is retained for retry.")
                        : t("创建未完成，请重新选择音频后重试", "Creation did not finish. Choose the audio again and retry.");
            } else {
                creationResult = t("已收到云端音色编号，状态查询未完成，请点击刷新", "A cloud voice ID was received, but its status could not be confirmed. Refresh the list.");
                if (!closed && creation != null) creation.dismiss();
            }
        });
    }

    private interface Work { void run() throws Exception; }

    private void work(String message, Work task, Runnable done) {
        work(message, task, done, () -> {});
    }

    private void work(String message, Work task, Runnable done, Runnable cleanup) {
        if (busy || closed) return;
        busy = true;
        operation = message;
        updateCreation(); render();
        worker.execute(() -> {
            Exception failure = null;
            try { task.run(); } catch (Exception error) { failure = error; }
            Exception result = failure;
            ui.post(() -> {
                busy = false;
                if (closed) { cleanup.run(); return; }
                updateCreation();
                if (result == null) done.run();
                else {
                    cleanup.run();
                    String errorMessage = errorText(result);
                    android.util.Log.w("NanoAvatar", "voice operation failed: " + result.getClass().getSimpleName()
                            + (result instanceof VoiceEnrollmentApi.ApiException ? "/" + ((VoiceEnrollmentApi.ApiException) result).code : ""));
                    showResult(t("操作未完成", "Operation incomplete"), errorMessage);
                    if (source != null && creation != null && creation.isShowing()) source.setText(errorText(result));
                }
                render();
            });
        });
    }

    private void showResult(String title, String message) {
        if (closed || !foreground) return;
        if (resultDialog != null) resultDialog.dismiss();
        resultDialog = new AlertDialog.Builder(activity).setTitle(title).setMessage(message)
                .setPositiveButton(t("确定", "OK"), null).show();
    }

    private String errorText(Exception error) {
        if (error instanceof VoiceAudioClip.AudioException) {
            switch (((VoiceAudioClip.AudioException) error).code) {
                case "TooShort": return t("音频片段不足 5 秒，请选择更长的人声", "The audio clip is shorter than 5 seconds. Choose a longer speech sample.");
                case "SampleRate": return t("音频采样率低于 16 kHz，请选择更高清的音频", "The sample rate is below 16 kHz. Choose a higher-quality recording.");
                case "Channels": return t("音频需要为单声道或双声道", "Choose mono or stereo audio.");
                case "TooLarge": return t("音频文件超过 10 MB，请选择更小的文件", "The audio file exceeds 10 MB. Choose a smaller file.");
                default: return t("无法解码此音频，请选择有效的 WAV、MP3 或 M4A 文件", "Cannot decode this audio. Choose a valid WAV, MP3, or M4A file.");
            }
        }
        if (error instanceof java.io.FileNotFoundException || error instanceof SecurityException)
            return t("无法读取所选文件，请重新选择或先下载到本机", "Cannot read the selected file. Choose it again or download it to this device first.");
        if (error instanceof IOException) {
            if ("Audio file exceeds 10 MB".equals(error.getMessage())) return t("音频文件超过 10 MB，请选择更小的文件", "The audio file exceeds 10 MB. Choose a smaller file.");
            if ("Unsupported audio file".equals(error.getMessage())) return t("仅支持 WAV、MP3 和 M4A 音频文件", "Only WAV, MP3, and M4A audio files are supported.");
            if ("Empty audio file".equals(error.getMessage())) return t("所选音频文件为空", "The selected audio file is empty.");
            if ("WAV must contain 16-bit PCM".equals(error.getMessage())) return t("WAV 文件需要为 16-bit PCM 格式", "The WAV file must use 16-bit PCM.");
        }
        if (error instanceof VoiceEnrollmentApi.ApiException) {
            VoiceEnrollmentApi.ApiException api = (VoiceEnrollmentApi.ApiException) error;
            if (api.statusCode == 401 || api.statusCode == 403) return t("请求被拒绝，请检查 API Key 和模型权限", "Request denied. Check the API key and model access.");
            if ("NetworkError".equals(api.code)) return t("网络连接失败，请检查网络后重试或刷新音色列表", "Network connection failed. Check the connection, then retry or refresh the voice list.");
            return t("音色服务请求失败，请稍后刷新或重试", "Voice service request failed. Refresh or retry later.") + " (" + api.statusCode + ")";
        }
        return t("操作失败，请检查网络、存储空间及音频要求后重试", "Operation failed. Check network, device storage, and audio requirements, then retry.");
    }

    void onResume() { foreground = true; render(); }
    void onStop() { foreground = false; stopRecording(); stopPlayer(); }

    @Override public void close() {
        closed = true;
        onStop();
        if (creation != null) creation.dismiss();
        if (detail != null) detail.dismiss();
        if (library != null) library.dismiss();
        if (resultDialog != null) resultDialog.dismiss();
        clearSample();
        // 共用队列和目录实例，让 Activity 重建前后的云端操作按顺序落盘。
    }
}
