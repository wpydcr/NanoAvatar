package com.avatar.nputest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class VoiceEnrollmentApi implements AutoCloseable {
    static final String MODEL = "cosyvoice-v2";
    private static final String PREFIX = "nanoavatar";
    private static final String VOICE_PREFIX = MODEL + "-" + PREFIX + "-";
    private static final String BASE_URL = "https://dashscope.aliyuncs.com";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final String apiKey;
    private final HttpUrl baseUrl;
    private final OkHttpClient client;
    private final boolean allowLocalUpload;
    private final Set<Call> active = new HashSet<>();
    private boolean closed;

    static final class RemoteVoice {
        final String id;
        final String status;

        RemoteVoice(String id, String status) {
            this.id = id;
            this.status = status;
        }
    }

    static final class ApiException extends IOException {
        final int statusCode;
        final String code;

        ApiException(int statusCode, String code) {
            super(code);
            this.statusCode = statusCode;
            this.code = code;
        }
    }

    VoiceEnrollmentApi(String apiKey) {
        this(apiKey, new OkHttpClient(), BASE_URL, false);
    }

    VoiceEnrollmentApi(String apiKey, OkHttpClient client, String baseUrl) {
        this(apiKey, client, baseUrl, true);
    }

    private VoiceEnrollmentApi(String apiKey, OkHttpClient client, String baseUrl, boolean allowLocalUpload) {
        if (apiKey == null || apiKey.trim().isEmpty() || !apiKey.trim().matches("[!-~]+")) {
            throw new IllegalArgumentException("Invalid API key");
        }
        this.apiKey = apiKey.trim();
        this.baseUrl = HttpUrl.get(baseUrl);
        this.allowLocalUpload = allowLocalUpload;
        this.client = client.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(90, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    String create(File audio) throws IOException {
        return create(audio, stage -> {});
    }

    String create(File audio, Consumer<String> progress) throws IOException {
        if (audio == null || !audio.isFile() || !audio.canRead() || audio.length() == 0 || audio.length() > 10L * 1024 * 1024) {
            throw new ApiException(0, "InvalidAudioFile");
        }
        String name = audio.getName().toLowerCase(Locale.ROOT);
        String extension = name.endsWith(".wav") ? ".wav" : name.endsWith(".mp3") ? ".mp3" : name.endsWith(".m4a") ? ".m4a" : "";
        if (extension.isEmpty()) throw new ApiException(0, "InvalidAudioFile");
        String mime = extension.equals(".wav") ? "audio/wav" : extension.equals(".mp3") ? "audio/mpeg" : "audio/mp4";
        try {
            progress.accept("policy");
            JSONObject policy = executeJson(authorized("/api/v1/uploads?action=getPolicy&model=voice-enrollment").get().build())
                    .getJSONObject("data");
            HttpUrl host = HttpUrl.parse(requiredString(policy, "upload_host"));
            if (!validUploadHost(host)) throw new ApiException(0, "InvalidUploadHost");
            String directory = requiredString(policy, "upload_dir");
            String filename = UUID.randomUUID().toString() + extension;
            String objectKey = directory + (directory.endsWith("/") ? "" : "/") + filename;
            MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("OSSAccessKeyId", requiredString(policy, "oss_access_key_id"))
                    .addFormDataPart("Signature", requiredString(policy, "signature"))
                    .addFormDataPart("policy", requiredString(policy, "policy"))
                    .addFormDataPart("x-oss-object-acl", requiredString(policy, "x_oss_object_acl"))
                    .addFormDataPart("x-oss-forbid-overwrite", requiredString(policy, "x_oss_forbid_overwrite"))
                    .addFormDataPart("key", objectKey)
                    .addFormDataPart("success_action_status", "200")
                    .addFormDataPart("file", filename, RequestBody.create(audio, MediaType.get(mime)))
                    .build();
            progress.accept("upload");
            execute(new Request.Builder().url(host).post(body).build(), false);
            JSONObject input = action("create_voice").put("target_model", MODEL)
                    .put("prefix", PREFIX).put("url", "oss://" + objectKey);
            progress.accept("enroll");
            String id = requiredString(enroll(input).getJSONObject("output"), "voice_id");
            requireVoiceId(id);
            return id;
        } catch (JSONException exception) {
            throw new ApiException(0, "InvalidResponse");
        }
    }

    String status(String voiceId) throws IOException {
        requireVoiceId(voiceId);
        try {
            JSONObject output = enroll(action("query_voice").put("voice_id", voiceId)).getJSONObject("output");
            if (!MODEL.equals(requiredString(output, "target_model"))) throw new ApiException(0, "ModelMismatch");
            return readStatus(output);
        } catch (JSONException exception) {
            throw new ApiException(0, "InvalidResponse");
        }
    }

    void delete(String voiceId) throws IOException {
        requireVoiceId(voiceId);
        try {
            enroll(action("delete_voice").put("voice_id", voiceId));
        } catch (JSONException exception) {
            throw new ApiException(0, "InvalidResponse");
        }
    }

    List<RemoteVoice> list() throws IOException {
        List<RemoteVoice> result = new ArrayList<>();
        try {
            for (int page = 0; ; page++) {
                JSONObject input = action("list_voice").put("prefix", PREFIX).put("page_size", 100).put("page_index", page);
                JSONArray voices = enroll(input).getJSONObject("output").getJSONArray("voice_list");
                for (int i = 0; i < voices.length(); i++) {
                    JSONObject voice = voices.getJSONObject(i);
                    String id = requiredString(voice, "voice_id");
                    if (id.startsWith(VOICE_PREFIX)) {
                        requireVoiceId(id);
                        result.add(new RemoteVoice(id, readStatus(voice)));
                    }
                }
                if (voices.length() < 100) return result;
            }
        } catch (JSONException exception) {
            throw new ApiException(0, "InvalidResponse");
        }
    }

    private JSONObject enroll(JSONObject input) throws IOException, JSONException {
        JSONObject body = new JSONObject().put("model", "voice-enrollment").put("input", input);
        return executeJson(authorized("/api/v1/services/audio/tts/customization")
                .header("X-DashScope-OssResourceResolve", "enable")
                .post(RequestBody.create(body.toString(), JSON)).build());
    }

    private Request.Builder authorized(String path) {
        return new Request.Builder().url(baseUrl.resolve(path)).header("Authorization", "Bearer " + apiKey);
    }

    private JSONObject executeJson(Request request) throws IOException {
        return execute(request, true);
    }

    private JSONObject execute(Request request, boolean parseJson) throws IOException {
        Call call = client.newCall(request);
        synchronized (this) {
            if (closed) throw new ApiException(0, "Closed");
            active.add(call);
        }
        try (Response response = call.execute()) {
            if (!parseJson && response.isSuccessful()) return new JSONObject();
            ResponseBody body = response.body();
            JSONObject json;
            try {
                json = new JSONObject(body == null ? "" : body.string());
            } catch (JSONException exception) {
                throw new ApiException(response.code(), response.isSuccessful() ? "InvalidResponse" : "HttpError");
            }
            String code = json.optString("code", "");
            if (!response.isSuccessful() || !code.isEmpty()) {
                throw new ApiException(response.code(), safeErrorCode(code));
            }
            return json;
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ApiException(0, "NetworkError");
        } finally {
            synchronized (this) { active.remove(call); }
        }
    }

    private boolean validUploadHost(HttpUrl host) {
        if (host == null || !host.username().isEmpty() || !host.password().isEmpty()) return false;
        if ("https".equals(host.scheme()) && host.port() == 443 && host.host().endsWith(".aliyuncs.com")) return true;
        return allowLocalUpload && "http".equals(host.scheme()) && "127.0.0.1".equals(host.host())
                && host.port() == baseUrl.port() && host.host().equals(baseUrl.host());
    }

    private static JSONObject action(String value) throws JSONException {
        return new JSONObject().put("action", value);
    }

    private static String requiredString(JSONObject object, String key) throws JSONException, ApiException {
        Object value = object.get(key);
        if (!(value instanceof String) || ((String) value).isEmpty()) throw new ApiException(0, "InvalidResponse");
        return (String) value;
    }

    private static String readStatus(JSONObject object) throws JSONException, ApiException {
        String status = requiredString(object, "status");
        if (!"OK".equals(status) && !"DEPLOYING".equals(status) && !"UNDEPLOYED".equals(status)) {
            throw new ApiException(0, "InvalidResponse");
        }
        return status;
    }

    private static void requireVoiceId(String id) throws ApiException {
        if (id == null || !id.startsWith(VOICE_PREFIX) || id.length() > 200 || !id.matches("[A-Za-z0-9-]+")) {
            throw new ApiException(0, "InvalidVoiceId");
        }
    }

    private static String safeErrorCode(String code) {
        switch (code) {
            case "InvalidApiKey":
            case "InvalidParameter":
            case "InvalidParameter.ValueError":
            case "AccessDenied":
            case "AccessDenied.Unpurchased":
            case "VoiceNotFound":
            case "InvalidVoiceId":
            case "Throttling":
            case "Throttling.RateQuota":
            case "QuotaExceeded":
            case "InternalError":
            case "InternalError.Algo":
            case "ServiceUnavailable":
                return code;
            default:
                return "ServiceError";
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Call call : active) call.cancel();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }
}
