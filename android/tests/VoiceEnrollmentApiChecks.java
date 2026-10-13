package com.avatar.nputest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;

public final class VoiceEnrollmentApiChecks {
    private static final String ID = "cosyvoice-v2-nanoavatar-0123456789";
    private static final String KEY = "test-secret-api-key";

    public static void main(String[] args) throws Exception {
        try (Fixture fixture = new Fixture()) {
            try {
                createsVoiceUsingPrivateUpload(fixture);
                queriesAndDeletesVoice(fixture);
                listsAllPagesAndOnlyAppVoices(fixture);
                rejectsErrorsAndMalformedResponses(fixture);
                rejectsUntrustedUploadHosts(fixture);
                validatesFilesBeforeUploading(fixture);
                closePreventsFurtherRequests(fixture);
                closeCancelsInFlightRequest(fixture);
            } catch (Exception error) {
                fixture.assertDone();
                throw error;
            }
            fixture.assertDone();
        }
        System.out.println("VoiceEnrollmentApiChecks: all checks passed");
    }

    private static void createsVoiceUsingPrivateUpload(Fixture f) throws Exception {
        File audio = Files.createTempFile("voice-api-check-", ".wav").toFile();
        byte[] bytes = "RIFF-test-recording".getBytes(StandardCharsets.US_ASCII);
        Files.write(audio.toPath(), bytes);
        AtomicReference<String> uploadedKey = new AtomicReference<>();
        try (VoiceEnrollmentApi api = f.api()) {
            f.policy(f.url + "/oss");
            f.expect("POST", "/oss", 200, "", (exchange, body) -> {
                check(exchange.getRequestHeaders().getFirst("Authorization") == null,
                        "OSS upload must not receive the API key");
                check(exchange.getRequestHeaders().getFirst("Content-Type").startsWith("multipart/form-data;"),
                        "Upload must use multipart form data");
                String multipart = new String(body, StandardCharsets.ISO_8859_1);
                check("access-id".equals(formField(multipart, "OSSAccessKeyId")), "Missing OSS key");
                check("signature".equals(formField(multipart, "Signature")), "Missing OSS signature");
                check("encoded-policy".equals(formField(multipart, "policy")), "Missing OSS policy");
                check("private".equals(formField(multipart, "x-oss-object-acl")), "Upload must remain private");
                check("true".equals(formField(multipart, "x-oss-forbid-overwrite")), "Missing overwrite guard");
                check("200".equals(formField(multipart, "success_action_status")), "Missing upload status");
                String key = formField(multipart, "key");
                check(key.matches("uploads/[A-Za-z0-9-]+\\.wav"), "Upload must use an opaque filename");
                uploadedKey.set(key);
                check(multipart.lastIndexOf("Content-Disposition:") < multipart.indexOf("name=\"file\""), "File must be the last field");
                check(multipart.contains("RIFF-test-recording"), "Actual file bytes must be uploaded");
            });
            f.voice("create_voice", 200, new JSONObject().put("output", new JSONObject().put("voice_id", ID)),
                    (exchange, input) -> {
                        check("enable".equals(exchange.getRequestHeaders().getFirst("X-DashScope-OssResourceResolve")),
                                "Private OSS URL must be resolvable");
                        check("cosyvoice-v2".equals(input.getString("target_model")), "Wrong target model");
                        check("nanoavatar".equals(input.getString("prefix")), "Wrong app prefix");
                        check(("oss://" + uploadedKey.get()).equals(input.getString("url")), "Wrong uploaded object URL");
                    });
            check(ID.equals(api.create(audio)), "Creation must return its voice ID");
            f.assertDone();
        } finally {
            Files.deleteIfExists(audio.toPath());
        }
    }

    private static void queriesAndDeletesVoice(Fixture f) throws Exception {
        try (VoiceEnrollmentApi api = f.api()) {
            for (String status : new String[]{"DEPLOYING", "OK", "UNDEPLOYED"}) {
                f.voice("query_voice", 200, output("cosyvoice-v2", status),
                        (exchange, input) -> check(ID.equals(input.getString("voice_id")), "Wrong queried ID"));
                check(status.equals(api.status(ID)), "Status must be preserved");
            }
            f.voice("delete_voice", 200, new JSONObject().put("output", JSONObject.NULL),
                    (exchange, input) -> check(ID.equals(input.getString("voice_id")), "Wrong deleted ID"));
            api.delete(ID);
            f.assertDone();
        }
    }

    private static void listsAllPagesAndOnlyAppVoices(Fixture f) throws Exception {
        JSONArray page = new JSONArray();
        for (int i = 0; i < 100; i++) {
            page.put(new JSONObject().put("voice_id", i == 4 ? ID : "cosyvoice-v1-other-" + i)
                    .put("status", "OK"));
        }
        try (VoiceEnrollmentApi api = f.api()) {
            f.voice("list_voice", 200, new JSONObject().put("output", new JSONObject().put("voice_list", page)),
                    (exchange, input) -> checkListInput(input, 0));
            JSONArray last = new JSONArray().put(new JSONObject().put("voice_id", ID + "2").put("status", "DEPLOYING"));
            f.voice("list_voice", 200, new JSONObject().put("output", new JSONObject().put("voice_list", last)),
                    (exchange, input) -> checkListInput(input, 1));
            List<VoiceEnrollmentApi.RemoteVoice> voices = api.list();
            check(voices.size() == 2, "Must load later pages and filter other models and prefixes");
            check(ID.equals(voices.get(0).id) && "OK".equals(voices.get(0).status), "Wrong first remote voice");
            check((ID + "2").equals(voices.get(1).id) && "DEPLOYING".equals(voices.get(1).status), "Missing second-page voice");
            f.assertDone();
        }
    }

    private static void rejectsErrorsAndMalformedResponses(Fixture f) throws Exception {
        try (VoiceEnrollmentApi api = f.api()) {
            f.voice("query_voice", 401, new JSONObject().put("code", "InvalidApiKey").put("message", KEY), null);
            VoiceEnrollmentApi.ApiException denied = expectApiError(() -> api.status(ID));
            check(denied.statusCode == 401 && "InvalidApiKey".equals(denied.code), "Preserve safe API error classification");
            check(!denied.toString().contains(KEY) && denied.getCause() == null, "Do not expose error body or key");
            f.voice("query_voice", 200, new JSONObject().put("code", "InvalidParameter").put("message", KEY), null);
            expectApiError(() -> api.status(ID));
            f.voice("query_voice", 200, output("cosyvoice-v3-plus", "OK"), null);
            expectApiError(() -> api.status(ID));
            f.voice("query_voice", 200, output("cosyvoice-v2", "unknown"), null);
            expectApiError(() -> api.status(ID));
            f.expect("POST", "/api/v1/services/audio/tts/customization", 200, "<html>" + KEY + "</html>", null);
            check(!expectApiError(() -> api.status(ID)).toString().contains(KEY), "Malformed body must remain private");
            f.voice("list_voice", 200, new JSONObject().put("output", new JSONObject()), null);
            expectApiError(api::list);
            f.assertDone();
        }
    }

    private static void rejectsUntrustedUploadHosts(Fixture f) throws Exception {
        File audio = Files.createTempFile("voice-api-check-", ".wav").toFile();
        Files.write(audio.toPath(), new byte[]{1, 2, 3});
        try (VoiceEnrollmentApi api = f.api()) {
            for (String host : new String[]{"https://aliyuncs.com.attacker.invalid", "http://bucket.aliyuncs.com", "https://attacker.invalid"}) {
                f.policy(host);
                expectApiError(() -> api.create(audio));
                f.assertDone();
            }
            f.policy(f.url + "/oss");
            f.expect("POST", "/oss", 302, "", (exchange, body) ->
                    exchange.getResponseHeaders().set("Location", f.url + "/unexpected-redirect"));
            expectApiError(() -> api.create(audio));
            f.assertDone();
        } finally {
            Files.deleteIfExists(audio.toPath());
        }
    }

    private static void validatesFilesBeforeUploading(Fixture f) throws Exception {
        File empty = Files.createTempFile("voice-api-empty-", ".wav").toFile();
        File wrong = Files.createTempFile("voice-api-wrong-", ".txt").toFile();
        Files.write(wrong.toPath(), new byte[]{1});
        try (VoiceEnrollmentApi api = f.api()) {
            expectApiError(() -> api.create(empty));
            expectApiError(() -> api.create(wrong));
            expectApiError(() -> api.create(new File(empty.getParentFile(), "missing-voice-api.wav")));
            f.assertDone();
        } finally {
            Files.deleteIfExists(empty.toPath());
            Files.deleteIfExists(wrong.toPath());
        }
    }

    private static void closePreventsFurtherRequests(Fixture f) throws Exception {
        VoiceEnrollmentApi api = f.api();
        api.close();
        expectApiError(() -> api.status(ID));
        f.assertDone();
    }

    private static void closeCancelsInFlightRequest(Fixture f) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        VoiceEnrollmentApi api = f.api();
        f.voice("query_voice", 200, output("cosyvoice-v2", "OK"), (exchange, input) -> {
            entered.countDown();
            check(release.await(5, TimeUnit.SECONDS), "Cancelled request must release server");
        });
        try {
            Future<VoiceEnrollmentApi.ApiException> call = worker.submit(() -> expectApiError(() -> api.status(ID)));
            check(entered.await(2, TimeUnit.SECONDS), "Request did not reach the server");
            api.close();
            check("NetworkError".equals(call.get(2, TimeUnit.SECONDS).code), "Close must cancel the running call promptly");
        } finally {
            api.close();
            release.countDown();
            worker.shutdownNow();
        }
    }

    private static JSONObject output(String model, String status) {
        return new JSONObject().put("output", new JSONObject().put("voice_id", ID)
                .put("target_model", model).put("status", status));
    }

    private static String formField(String multipart, String name) {
        int header = multipart.indexOf("name=\"" + name + "\"");
        check(header >= 0, "Missing multipart field " + name);
        int start = multipart.indexOf("\r\n\r\n", header) + 4;
        return multipart.substring(start, multipart.indexOf("\r\n", start));
    }

    private static void checkListInput(JSONObject input, int page) {
        check("nanoavatar".equals(input.getString("prefix")), "List must request the app prefix");
        check(input.getInt("page_size") == 100 && input.getInt("page_index") == page, "Wrong list pagination");
    }

    private static VoiceEnrollmentApi.ApiException expectApiError(Action action) throws Exception {
        try {
            action.run();
        } catch (VoiceEnrollmentApi.ApiException expected) {
            return expected;
        }
        throw new AssertionError("Expected an API failure");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private interface Action { void run() throws Exception; }
    private interface RequestCheck { void check(HttpExchange exchange, byte[] body) throws Exception; }
    private interface InputCheck { void check(HttpExchange exchange, JSONObject input) throws Exception; }

    private static final class Fixture implements AutoCloseable {
        private final HttpServer server;
        private final Queue<Expected> requests = new ArrayDeque<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        final String url;

        Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            url = "http://127.0.0.1:" + server.getAddress().getPort();
            server.createContext("/", exchange -> {
                try {
                    Expected expected;
                    synchronized (requests) { expected = requests.poll(); }
                    check(expected != null, "Unexpected request: " + exchange.getRequestURI());
                    check(expected.method.equals(exchange.getRequestMethod()), "Wrong request method");
                    check(expected.path.equals(exchange.getRequestURI().getPath()), "Wrong request path");
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    if (expected.check != null) expected.check.check(exchange, body);
                    byte[] response = expected.response.getBytes(StandardCharsets.UTF_8);
                    try {
                        exchange.sendResponseHeaders(expected.status, response.length == 0 ? -1 : response.length);
                        if (response.length > 0) exchange.getResponseBody().write(response);
                    } catch (IOException disconnected) {
                        // A cancellation check deliberately closes its response socket.
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                    exchange.sendResponseHeaders(500, -1);
                } finally {
                    exchange.close();
                }
            });
            server.start();
        }

        VoiceEnrollmentApi api() {
            return new VoiceEnrollmentApi(KEY, new OkHttpClient.Builder().build(), url);
        }

        void policy(String host) {
            JSONObject data = new JSONObject().put("upload_host", host).put("upload_dir", "uploads")
                    .put("oss_access_key_id", "access-id").put("signature", "signature").put("policy", "encoded-policy")
                    .put("x_oss_object_acl", "private").put("x_oss_forbid_overwrite", "true");
            expect("GET", "/api/v1/uploads", 200, new JSONObject().put("data", data).toString(), (exchange, body) -> {
                check("action=getPolicy&model=voice-enrollment".equals(exchange.getRequestURI().getRawQuery()), "Wrong policy request");
                check(("Bearer " + KEY).equals(exchange.getRequestHeaders().getFirst("Authorization")), "Missing API authorization");
            });
        }

        void voice(String action, int status, JSONObject response, InputCheck extra) {
            expect("POST", "/api/v1/services/audio/tts/customization", status, response.toString(), (exchange, body) -> {
                check(("Bearer " + KEY).equals(exchange.getRequestHeaders().getFirst("Authorization")), "Missing API authorization");
                JSONObject json = new JSONObject(new String(body, StandardCharsets.UTF_8));
                check("voice-enrollment".equals(json.getString("model")), "Wrong enrollment model");
                JSONObject input = json.getJSONObject("input");
                check(action.equals(input.getString("action")), "Wrong voice action");
                if (extra != null) extra.check(exchange, input);
            });
        }

        void expect(String method, String path, int status, String response, RequestCheck check) {
            synchronized (requests) { requests.add(new Expected(method, path, status, response, check)); }
        }

        void assertDone() {
            if (failure.get() != null) throw new AssertionError("HTTP request contract failed", failure.get());
            synchronized (requests) { check(requests.isEmpty(), "Expected requests were not sent"); }
        }

        @Override public void close() { server.stop(0); }
    }

    private static final class Expected {
        final String method, path, response;
        final int status;
        final RequestCheck check;
        Expected(String method, String path, int status, String response, RequestCheck check) {
            this.method = method;
            this.path = path;
            this.status = status;
            this.response = response;
            this.check = check;
        }
    }
}
