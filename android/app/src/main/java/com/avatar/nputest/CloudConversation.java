package com.avatar.nputest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.EventListener;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okio.BufferedSource;
import okio.ByteString;

/** Streams one Qwen reply into one duplex CosyVoice task. */
public final class CloudConversation implements AutoCloseable {
    private static final String LLM_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String TTS_URL =
            "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
    private static final String LLM_MODEL = "qwen-plus";
    private static final String TTS_MODEL = "cosyvoice-v2";
    private static final String TTS_VOICE = "longxiaochun_v2";
    private static final int MAX_TTS_SAMPLES = 16_000 * 90;
    private static final Object TEXT_END = new Object();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public interface Listener {
        void onText(long id, String delta);
        void onPcm(long id, float[] samples);
        void onAudioEnd(long id);
        void onFailure(long id, String safeMessage);
        /** First occurrence of a fixed protocol stage, without request or response content. */
        default void onTiming(long id, String stage) { }
    }

    public static final class Message {
        public final String role;
        public final String text;

        public Message(String role, String text) {
            this.role = Objects.requireNonNull(role, "role");
            this.text = Objects.requireNonNull(text, "text");
        }
    }

    public interface Reply {
        void cancel();
    }

    private final Listener listener;
    private final OkHttpClient client;
    private final ScheduledExecutorService workers;
    private final Set<ReplyImpl> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    public CloudConversation(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        workers = Executors.newScheduledThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "cloud-conversation");
            thread.setDaemon(true);
            return thread;
        });
        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .eventListener(new EventListener() {
                    @Override
                    public void requestHeadersEnd(Call call, Request request) {
                        ReplyImpl reply = request.tag(ReplyImpl.class);
                        if (reply != null) {
                            reply.recordTiming("llm_request_started");
                        }
                    }
                })
                .build();
    }

    public Reply start(long id, String prompt, List<Message> history, String apiKey) {
        if (closed) {
            throw new IllegalStateException("CloudConversation is closed");
        }
        String cleanPrompt = Objects.requireNonNull(prompt, "prompt").trim();
        String cleanKey = Objects.requireNonNull(apiKey, "apiKey").trim();
        if (cleanPrompt.isEmpty()) {
            throw new IllegalArgumentException("prompt is empty");
        }
        if (cleanKey.isEmpty() || containsWhitespace(cleanKey)) {
            throw new IllegalArgumentException("apiKey is empty or malformed");
        }

        ReplyImpl reply = new ReplyImpl(id);
        active.add(reply);
        List<Message> recent = recentHistory(history == null ? Collections.emptyList() : history);
        try {
            Request llmRequest = llmRequest(cleanPrompt, recent, cleanKey).newBuilder()
                    .tag(ReplyImpl.class, reply).build();
            reply.llmCall = client.newCall(llmRequest);

            Request ttsRequest = new Request.Builder()
                    .url(TTS_URL)
                    .header("Authorization", "Bearer " + cleanKey)
                    .build();
            WebSocket socket = client.newWebSocket(ttsRequest, new TtsListener(reply));
            reply.webSocket = socket;
            if (reply.gate.isOpen()) {
                reply.llmCall.enqueue(new LlmCallback(reply));
            } else {
                reply.llmCall.cancel();
                socket.cancel();
            }
        } catch (RuntimeException exception) {
            reply.fail("无法启动云端对话，请检查输入后重试。");
        }
        return reply;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (ReplyImpl reply : new ArrayList<>(active)) {
            reply.cancel();
        }
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
        workers.shutdownNow();
        client.dispatcher().executorService().shutdown();
    }

    private Request llmRequest(String prompt, List<Message> history, String apiKey) {
        try {
            JSONArray messages = new JSONArray();
            messages.put(jsonMessage("system",
                    "你是 NanoAvatar 项目的对外代言人，也是一位亲切自然的语音助手。你为这个项目感到自豪，介绍时真诚、自信、有热情。"
                            + "NanoAvatar 是语音转视频模型，根据语音实时生成高保真嘴部画面，让数字人的口型与语音协调，呈现自然、生动的说话效果。"
                            + "项目通过在设备端实时生成高清视频，减少数字人应用对云端 GPU 服务器的依赖；即使用于云端部署，也能显著减少视频生成的算力消耗。"
                            + "项目 README 的性能参考：Z60 Ultra，骁龙 8 Gen 3、Android 14，模型生成吞吐 32 FPS，首帧 127 毫秒（约 0.13 秒），内存约 785 MiB；"
                            + "RTX 4090、Windows CUDA 上，全精度版 224 FPS、首帧 37 毫秒、显存 1119 MiB，量化版 333 FPS、首帧 18 毫秒、显存 834 MiB。"
                            + "团队另提供较低端适配手机约 12.5 FPS 的运行参考，未提供具体机型，不将其说成 README 的实测数据。具体表现取决于设备和运行条件，"
                            + "不要把模型生成基准说成所有手机的固定表现，也不要把首帧生成延迟说成从用户提问到回答的完整延迟。"
                            + "模型生成人脸区域为 256×256，最终按原视频尺寸合成高清画面，不宣称模型直接生成全幅高清。"
                            + "提供全精度、量化和 Android QNN 编译版本，Android 编译版面向骁龙 8 Gen 3 / HTP v75。"
                            + "口播模型采用 CC BY-NC 4.0，可按许可条件用于学术研究和其他非商业用途；HuBERT 及其衍生版本继承 MIT 许可。"
                            + "商业授权联系 wupingyu@mail.ustc.edu.cn，源码与使用地址 https://github.com/wpydcr/NanoAvatar。仅在被问及授权或获取方式时提供。"
                            + "介绍项目时围绕低算力、端侧实时生成、高保真口型和自然表达展开，按用户问题选择重点，不必每次重复全部介绍；"
                            + "其他话题正常提供帮助，不强行宣传项目。不编造未提供的测试机型、分辨率、算力降幅或行业排名。"
                            + "使用用户提问的语言回答，用户指定语言时遵从指定。默认只用一到两句简短口语，先回答重点，不复述问题、不重复总结。"
                            + "每次只选与问题最相关的一两项数据，不堆砌参数；用户明确要求详细介绍或对比时再展开。"
                            + "直接回答，不使用 Markdown、列表符号或表情；不要描述任何动作。"));
            for (Message message : history) {
                messages.put(jsonMessage(message.role, message.text));
            }
            messages.put(jsonMessage("user", prompt));
            JSONObject body = new JSONObject()
                    .put("model", LLM_MODEL)
                    .put("stream", true)
                    .put("enable_thinking", false)
                    .put("max_tokens", 384)
                    .put("messages", messages);
            return new Request.Builder()
                    .url(LLM_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "text/event-stream")
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();
        } catch (JSONException exception) {
            throw new IllegalArgumentException("invalid conversation input");
        }
    }

    private static JSONObject jsonMessage(String role, String text) throws JSONException {
        return new JSONObject().put("role", role).put("content", text);
    }

    private final class LlmCallback implements Callback {
        private final ReplyImpl reply;

        LlmCallback(ReplyImpl reply) {
            this.reply = reply;
        }

        @Override
        public void onFailure(Call call, IOException exception) {
            if (reply.gate.isOpen()) {
                reply.fail("阿里云大模型连接失败或超时，请检查网络和服务状态。");
            }
        }

        @Override
        public void onResponse(Call call, Response response) {
            try (Response closeable = response) {
                requireLlmSuccess(closeable.code());
                ResponseBody body = closeable.body();
                if (body == null) {
                    reply.fail("大模型没有返回数据，请重新发送。");
                    return;
                }
                readSse(reply, body.source());
            } catch (ProtocolException exception) {
                reply.fail(exception.getMessage());
            } catch (IOException exception) {
                if (reply.gate.isOpen()) {
                    reply.fail("阿里云大模型连接失败或超时，请检查网络和服务状态。");
                }
            } catch (RuntimeException exception) {
                if (reply.gate.isOpen()) {
                    reply.fail("大模型响应格式异常，请重新发送。");
                }
            }
        }
    }

    private void readSse(ReplyImpl reply, BufferedSource source) throws IOException {
        SseParser parser = new SseParser(event -> handleSseEvent(reply, event));
        byte[] chunk = new byte[4096];
        while (reply.gate.isOpen()) {
            int count = source.read(chunk);
            if (count == -1) {
                break;
            }
            parser.accept(chunk, count);
            if (parser.isTerminal()) {
                break;
            }
        }
        if (!reply.gate.isOpen()) {
            return;
        }
        if (!parser.isTerminal()) {
            parser.finish();
        }
        if (!reply.sawDone && !reply.sawFinishReason) {
            throw new ProtocolException("大模型流提前中断，请重新发送。");
        }
        if (reply.textLength == 0) {
            throw new ProtocolException("大模型没有返回文本，请重新发送。");
        }
        reply.finishLlmInput();
    }

    private void handleSseEvent(ReplyImpl reply, String event) {
        if (!reply.gate.isOpen()) {
            return;
        }
        if ("[DONE]".equals(event)) {
            reply.sawDone = true;
            return;
        }
        final JSONObject data;
        try {
            data = new JSONObject(event);
        } catch (JSONException exception) {
            throw new ProtocolException("大模型响应格式异常，请重新发送。");
        }
        if (data.has("error")) {
            throw new ProtocolException("大模型返回错误，请检查账号额度与模型权限。");
        }
        JSONArray choices = data.optJSONArray("choices");
        if (choices == null) {
            return;
        }
        for (int index = 0; index < choices.length(); index++) {
            JSONObject choice = choices.optJSONObject(index);
            if (choice == null) {
                continue;
            }
            JSONObject delta = choice.optJSONObject("delta");
            Object content = delta == null ? null : delta.opt("content");
            if (content instanceof String && !((String) content).isEmpty()) {
                reply.acceptText((String) content);
            }
            Object reason = choice.opt("finish_reason");
            if (reason != null && reason != JSONObject.NULL) {
                reply.sawFinishReason = true;
            }
        }
    }

    private final class TtsListener extends okhttp3.WebSocketListener {
        private final ReplyImpl reply;
        private final String taskId = UUID.randomUUID().toString();
        private final Pcm16Decoder decoder = new Pcm16Decoder();
        private final AtomicBoolean senderStarted = new AtomicBoolean();
        private boolean taskFinished;
        private int sampleCount;

        TtsListener(ReplyImpl reply) {
            this.reply = reply;
            reply.ttsTaskId = taskId;
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            if (!reply.gate.isOpen()) {
                webSocket.cancel();
                return;
            }
            reply.recordTiming("tts_websocket_open");
            try {
                if (!webSocket.send(runTaskJson(taskId))) {
                    reply.fail("语音合成连接已关闭，请重新发送。");
                    return;
                }
                workers.schedule(() -> {
                    if (reply.gate.isOpen() && !senderStarted.get()) {
                        reply.fail("语音合成启动超时，请重新发送。");
                    }
                }, 20, TimeUnit.SECONDS);
            } catch (JSONException exception) {
                reply.fail("无法启动语音合成，请重新发送。");
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            if (!reply.gate.isOpen()) {
                return;
            }
            try {
                JSONObject message = new JSONObject(text);
                JSONObject header = message.optJSONObject("header");
                String event = header == null ? "" : header.optString("event", "");
                if ("task-started".equals(event)) {
                    if (senderStarted.compareAndSet(false, true)) {
                        reply.recordTiming("tts_task_started");
                        workers.execute(() -> sendTtsText(reply, webSocket, taskId));
                    }
                } else if ("task-failed".equals(event)) {
                    reply.fail("语音合成失败，请检查音色、模型权限和额度。");
                } else if ("task-finished".equals(event)) {
                    taskFinished = true;
                    decoder.finish();
                    if (sampleCount == 0) {
                        reply.fail("语音合成没有返回音频，请重新发送。");
                        return;
                    }
                    webSocket.close(1000, "finished");
                    reply.completeAudio();
                }
            } catch (JSONException exception) {
                reply.fail("语音合成响应格式异常，请重新发送。");
            } catch (ProtocolException exception) {
                reply.fail(exception.getMessage());
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            if (!reply.gate.isOpen()) {
                return;
            }
            float[] samples = decoder.accept(bytes.toByteArray());
            if (samples.length == 0) {
                return;
            }
            sampleCount += samples.length;
            if (sampleCount > MAX_TTS_SAMPLES) {
                reply.fail("本次回答超过 90 秒，请缩短问题后重试。");
                return;
            }
            reply.deliverPcm(samples);
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            if (!reply.gate.isOpen()) {
                return;
            }
            try {
                requireNormalTtsEnd(taskFinished);
            } catch (ProtocolException exception) {
                reply.fail(exception.getMessage());
            }
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            if (!reply.gate.isOpen()) {
                return;
            }
            try {
                requireNormalTtsEnd(taskFinished);
            } catch (ProtocolException exception) {
                reply.fail(exception.getMessage());
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable throwable, Response response) {
            if (!reply.gate.isOpen()) {
                return;
            }
            if (response != null) {
                reply.fail("语音合成连接失败（HTTP " + response.code() + "），请检查 Key 和模型权限。");
            } else {
                reply.fail("阿里云语音合成连接失败或超时，请检查网络和服务状态。");
            }
        }
    }

    private void sendTtsText(ReplyImpl reply, WebSocket socket, String taskId) {
        try {
            while (reply.gate.isOpen()) {
                Object item = reply.textQueue.poll(250, TimeUnit.MILLISECONDS);
                if (item == null) {
                    continue;
                }
                String message = item == TEXT_END
                        ? finishTaskJson(taskId, false)
                        : continueTaskJson(taskId, (String) item);
                if (!socket.send(message)) {
                    reply.fail("语音合成连接已关闭，请重新发送。");
                    return;
                }
                if (item != TEXT_END) {
                    // OkHttp acknowledges queue admission here, not a network frame write.
                    reply.recordTiming("tts_first_text_queued");
                }
                if (item == TEXT_END) {
                    workers.schedule(() -> {
                        if (reply.gate.isOpen()) {
                            reply.fail("TTS 流未正常结束，请重新发送。");
                        }
                    }, 45, TimeUnit.SECONDS);
                    return;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (reply.gate.isOpen()) {
                reply.fail("语音合成任务被中断，请重新发送。");
            }
        } catch (JSONException exception) {
            reply.fail("无法发送语音合成文本，请重新发送。");
        }
    }

    private static String runTaskJson(String taskId) throws JSONException {
        JSONObject parameters = new JSONObject()
                .put("text_type", "PlainText")
                .put("voice", TTS_VOICE)
                .put("format", "pcm")
                .put("sample_rate", 16_000)
                .put("volume", 50)
                .put("rate", 1.0)
                .put("pitch", 1.0);
        JSONObject payload = new JSONObject()
                .put("task_group", "audio")
                .put("task", "tts")
                .put("function", "SpeechSynthesizer")
                .put("model", TTS_MODEL)
                .put("parameters", parameters)
                .put("input", new JSONObject());
        return envelope("run-task", taskId, payload).toString();
    }

    private static String continueTaskJson(String taskId, String text) throws JSONException {
        JSONObject payload = new JSONObject().put("input", new JSONObject().put("text", text));
        return envelope("continue-task", taskId, payload).toString();
    }

    private static String finishTaskJson(String taskId, boolean cancel) throws JSONException {
        JSONObject input = new JSONObject();
        if (cancel) {
            input.put("directive", "cancel");
        }
        JSONObject payload = new JSONObject().put("input", input);
        return envelope("finish-task", taskId, payload).toString();
    }

    private static JSONObject envelope(String action, String taskId, JSONObject payload)
            throws JSONException {
        JSONObject header = new JSONObject()
                .put("action", action)
                .put("task_id", taskId)
                .put("streaming", "duplex");
        return new JSONObject().put("header", header).put("payload", payload);
    }

    private final class ReplyImpl implements Reply {
        final long id;
        final DeliveryGate gate = new DeliveryGate();
        final ArrayBlockingQueue<Object> textQueue = new ArrayBlockingQueue<>(16);
        final TextChunks textChunks = new TextChunks();
        final AtomicBoolean llmInputFinished = new AtomicBoolean();
        final Set<String> timingStages = ConcurrentHashMap.newKeySet();
        volatile Call llmCall;
        volatile WebSocket webSocket;
        volatile String ttsTaskId;
        volatile boolean sawDone;
        volatile boolean sawFinishReason;
        int textLength;

        ReplyImpl(long id) {
            this.id = id;
        }

        void acceptText(String text) {
            recordTiming("llm_first_text");
            if (!gate.deliver(() -> listener.onText(id, text))) {
                return;
            }
            textLength += text.length();
            for (String chunk : textChunks.feed(text, false)) {
                putText(chunk);
            }
        }

        void finishLlmInput() {
            if (!llmInputFinished.compareAndSet(false, true)) {
                return;
            }
            recordTiming("llm_complete");
            for (String chunk : textChunks.feed("", true)) {
                putText(chunk);
            }
            putText(TEXT_END);
        }

        private void putText(Object value) {
            try {
                while (gate.isOpen() && !textQueue.offer(value, 250, TimeUnit.MILLISECONDS)) {
                    // The bounded queue intentionally slows the LLM reader when TTS is behind.
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                if (gate.isOpen()) {
                    fail("云端文本处理被中断，请重新发送。");
                }
            }
        }

        void deliverPcm(float[] samples) {
            recordTiming("tts_first_pcm");
            gate.deliver(() -> listener.onPcm(id, samples));
        }

        void completeAudio() {
            recordTiming("tts_complete");
            if (gate.complete(() -> listener.onAudioEnd(id))) {
                active.remove(this);
            }
        }

        void recordTiming(String stage) {
            gate.deliver(() -> {
                if (timingStages.add(stage)) {
                    listener.onTiming(id, stage);
                }
            });
        }

        void fail(String safeMessage) {
            if (!gate.complete(() -> listener.onFailure(id, safeMessage))) {
                return;
            }
            active.remove(this);
            stopNetwork(false);
        }

        @Override
        public void cancel() {
            if (!gate.cancel()) {
                return;
            }
            active.remove(this);
            stopNetwork(true);
        }

        private void stopNetwork(boolean sendCancel) {
            Call call = llmCall;
            if (call != null) {
                call.cancel();
            }
            WebSocket socket = webSocket;
            if (socket != null) {
                String taskId = ttsTaskId;
                if (sendCancel && taskId != null) {
                    try {
                        socket.send(finishTaskJson(taskId, true));
                    } catch (JSONException ignored) {
                        // Fixed JSON values cannot fail to encode.
                    }
                }
                socket.cancel();
            }
            textQueue.clear();
        }
    }

    static List<Message> recentHistory(List<Message> history) {
        Objects.requireNonNull(history, "history");
        int from = Math.max(0, history.size() - 8);
        while (from < history.size() && !"user".equals(history.get(from).role)) {
            from++;
        }
        return Collections.unmodifiableList(new ArrayList<>(history.subList(from, history.size())));
    }

    static String safeLlmHttpError(int status) {
        return "大模型请求失败（HTTP " + status + "），请检查 Key、地域和模型权限。";
    }

    static void requireLlmSuccess(int status) {
        if (status < 200 || status >= 300) {
            throw new ProtocolException(safeLlmHttpError(status));
        }
    }

    static void requireNormalTtsEnd(boolean taskFinished) {
        if (!taskFinished) {
            throw new ProtocolException("TTS 流未正常结束，请重新发送。");
        }
    }

    private static boolean containsWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    static final class ProtocolException extends RuntimeException {
        ProtocolException(String safeMessage) {
            super(safeMessage);
        }
    }

    interface SseSink {
        void onEvent(String event);
    }

    static final class SseParser {
        private final SseSink sink;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private final List<String> data = new ArrayList<>();
        private boolean terminal;

        SseParser(SseSink sink) {
            this.sink = sink;
        }

        void accept(byte[] bytes, int count) {
            if (count < 0 || count > bytes.length) {
                throw new IllegalArgumentException("invalid byte count");
            }
            for (int index = 0; index < count; index++) {
                if (terminal) {
                    return;
                }
                int value = bytes[index] & 0xff;
                if (value == '\n') {
                    consumeLine();
                } else {
                    line.write(value);
                }
            }
        }

        void finish() {
            if (terminal) {
                return;
            }
            if (line.size() > 0) {
                consumeLine();
            }
            dispatch();
        }

        boolean isTerminal() {
            return terminal;
        }

        private void consumeLine() {
            byte[] bytes = line.toByteArray();
            line.reset();
            int length = bytes.length;
            if (length > 0 && bytes[length - 1] == '\r') {
                length--;
            }
            String value = new String(bytes, 0, length, StandardCharsets.UTF_8);
            if (value.isEmpty()) {
                dispatch();
            } else if (value.startsWith("data:")) {
                String item = value.substring(5);
                data.add(item.startsWith(" ") ? item.substring(1) : item);
            }
        }

        private void dispatch() {
            if (data.isEmpty()) {
                return;
            }
            String event = String.join("\n", data);
            data.clear();
            sink.onEvent(event);
            if ("[DONE]".equals(event)) {
                terminal = true;
            }
        }
    }

    static final class Pcm16Decoder {
        private int pending = -1;

        float[] accept(byte[] bytes) {
            int sampleCount = (bytes.length + (pending < 0 ? 0 : 1)) / 2;
            float[] samples = new float[sampleCount];
            int source = 0;
            int target = 0;
            if (pending >= 0 && bytes.length > 0) {
                samples[target++] = sample(pending, bytes[source++] & 0xff);
                pending = -1;
            }
            while (source + 1 < bytes.length) {
                samples[target++] = sample(bytes[source] & 0xff, bytes[source + 1] & 0xff);
                source += 2;
            }
            if (source < bytes.length) {
                pending = bytes[source] & 0xff;
            }
            return samples;
        }

        void finish() {
            if (pending >= 0) {
                throw new ProtocolException("TTS PCM 音频被截断。");
            }
        }

        private static float sample(int low, int high) {
            short signed = (short) (low | (high << 8));
            return signed / 32768.0f;
        }
    }

    static final class DeliveryGate {
        private final AtomicBoolean open = new AtomicBoolean(true);

        boolean isOpen() {
            return open.get();
        }

        boolean deliver(Runnable delivery) {
            if (!open.get()) {
                return false;
            }
            delivery.run();
            return true;
        }

        boolean complete(Runnable delivery) {
            if (!open.compareAndSet(true, false)) {
                return false;
            }
            delivery.run();
            return true;
        }

        boolean cancel() {
            return open.compareAndSet(true, false);
        }
    }

    private static final class TextChunks {
        private final StringBuilder pending = new StringBuilder();

        List<String> feed(String text, boolean finish) {
            pending.append(text);
            List<String> result = new ArrayList<>();
            while (pending.length() > 0) {
                int cut = boundary();
                if (cut < 0 && pending.length() >= 48) {
                    cut = 48;
                }
                if (cut < 0 && finish) {
                    cut = pending.length();
                }
                if (cut < 0) {
                    break;
                }
                String chunk = pending.substring(0, cut).trim();
                pending.delete(0, cut);
                if (!chunk.isEmpty()) {
                    result.add(chunk);
                }
            }
            return result;
        }

        private int boundary() {
            for (int index = 0; index < pending.length(); index++) {
                char value = pending.charAt(index);
                if ("。！？!?；;\n".indexOf(value) >= 0
                        || (index >= 9 && "，,：:".indexOf(value) >= 0)) {
                    return index + 1;
                }
            }
            return -1;
        }
    }
}
