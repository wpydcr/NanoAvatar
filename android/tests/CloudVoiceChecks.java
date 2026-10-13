package com.avatar.nputest;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONObject;

public final class CloudVoiceChecks {
    public static void main(String[] args) throws Exception {
        Method runTask;
        try {
            runTask = CloudConversation.class.getDeclaredMethod("runTaskJson", String.class, String.class);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("TTS task must accept the voice chosen for this reply", missing);
        }
        runTask.setAccessible(true);
        JSONObject first = new JSONObject((String) runTask.invoke(null, "task-a", "voice-a"));
        JSONObject second = new JSONObject((String) runTask.invoke(null, "task-b", "voice-b"));
        JSONObject defaults = new JSONObject((String) runTask.invoke(null, "task-c", "longxiaochun_v2"));
        check(voice(first).equals("voice-a"), "first task retains chosen voice");
        check(voice(second).equals("voice-b"), "second task uses its own voice");
        check(voice(defaults).equals("longxiaochun_v2"), "default voice remains usable");
        check(first.getJSONObject("header").getString("task_id").equals("task-a"), "task id is preserved");
        check(first.getJSONObject("payload").getString("model").equals("cosyvoice-v2"), "model remains compatible");
        JSONObject parameters = first.getJSONObject("payload").getJSONObject("parameters");
        check(parameters.getString("format").equals("pcm") && parameters.getInt("sample_rate") == 16000,
                "custom voices preserve the playback audio format");
        CloudConversation.class.getMethod("start", long.class, String.class, List.class, String.class);
        CloudConversation.class.getMethod("start", long.class, String.class, List.class, String.class, String.class);

        try (CloudConversation conversation = new CloudConversation(new CloudConversation.Listener() {
            public void onText(long id, String delta) { }
            public void onPcm(long id, float[] samples) { }
            public void onAudioEnd(long id) { }
            public void onFailure(long id, String message) { throw new AssertionError(message); }
        })) {
            Class<?> replyClass = Class.forName("com.avatar.nputest.CloudConversation$ReplyImpl");
            Constructor<?> replyConstructor = replyClass.getDeclaredConstructor(CloudConversation.class, long.class, String.class);
            replyConstructor.setAccessible(true);
            Object replyA = replyConstructor.newInstance(conversation, 1L, "voice-a");
            Object replyB = replyConstructor.newInstance(conversation, 2L, "voice-b");
            Class<?> listenerClass = Class.forName("com.avatar.nputest.CloudConversation$TtsListener");
            Constructor<?> listenerConstructor = listenerClass.getDeclaredConstructor(CloudConversation.class, replyClass);
            listenerConstructor.setAccessible(true);
            WebSocketListener listenerA = (WebSocketListener) listenerConstructor.newInstance(conversation, replyA);
            WebSocketListener listenerB = (WebSocketListener) listenerConstructor.newInstance(conversation, replyB);
            List<String> sent = new ArrayList<>();
            WebSocket socket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
                    new Class<?>[]{WebSocket.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("send")) {
                            sent.add((String) arguments[0]);
                            return true;
                        }
                        throw new AssertionError("Unexpected socket call: " + method.getName());
                    });
            listenerB.onOpen(socket, null);
            listenerA.onOpen(socket, null);
            check(sent.size() == 2, "each opened reply sends its own task");
            check(voice(new JSONObject(sent.get(0))).equals("voice-b"), "later reply sends its chosen voice");
            check(voice(new JSONObject(sent.get(1))).equals("voice-a"), "delayed earlier reply retains its chosen voice");
        }
        System.out.println("PASS CloudVoiceChecks: task JSON and interleaved reply voice snapshots (no network)");
    }

    private static String voice(JSONObject task) {
        return task.getJSONObject("payload").getJSONObject("parameters").getString("voice");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
