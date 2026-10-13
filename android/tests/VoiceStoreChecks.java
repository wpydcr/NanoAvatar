package com.avatar.nputest;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class VoiceStoreChecks {
    public static void main(String[] args) throws Exception {
        Path output = new File(args.length == 0 ? ".tools/voice-settings-20261012" : args[0]).toPath();
        Files.createDirectories(output);
        Path root = Files.createTempDirectory(output, "store-checks-");
        File directory = root.resolve("voices").toFile();
        VoiceStore store = new VoiceStore(directory, "test-key-one");
        check(store.list().isEmpty(), "new account starts empty");
        check(VoiceStore.DEFAULT_VOICE.equals(store.selected()), "default voice starts selected");

        store.put("voice-a", "\u6211\u7684\u97f3\u8272", "OK");
        store.put("voice-b", "Second voice", "DEPLOYING");
        store.select("voice-a");
        expect(IllegalArgumentException.class, () -> store.select("voice-b"));
        expect(IllegalArgumentException.class, () -> store.select("missing"));
        expect(IllegalArgumentException.class, () -> store.put("", "Name", "OK"));
        expect(IllegalArgumentException.class, () -> store.put("voice-c", "  ", "OK"));
        expect(IllegalArgumentException.class, () -> store.put("voice-c", "Name", "bad"));

        VoiceStore restored = new VoiceStore(directory, "test-key-one");
        check(restored.list().size() == 2, "multiple voices survive restart");
        check("voice-a".equals(restored.selected()), "selection survives restart");
        check(restored.list().stream().anyMatch(v -> v.id.equals("voice-a")
                && v.name.equals("\u6211\u7684\u97f3\u8272") && v.status.equals("OK")),
                "Chinese names and status survive restart");
        check(restored.list().stream().anyMatch(v -> v.id.equals("voice-b")
                && v.status.equals("DEPLOYING")), "pending voice survives restart");

        VoiceStore otherAccount = new VoiceStore(directory, "test-key-two");
        check(otherAccount.list().isEmpty(), "API keys isolate voice lists");
        otherAccount.put("voice-other", "Other account", "OK");
        otherAccount.select("voice-other");
        check(new VoiceStore(directory, "test-key-one").list().size() == 2,
                "other account writes do not change original account");
        for (File file : directory.listFiles()) {
            check(!file.getName().contains("test-key"), "filename does not expose API key");
            check(!Files.readString(file.toPath()).contains("test-key"), "contents do not expose API key");
        }

        restored.put("voice-b", "Second voice", "OK");
        restored.select("voice-b");
        restored.put("voice-b", "Second voice", "UNDEPLOYED");
        check(VoiceStore.DEFAULT_VOICE.equals(restored.selected()), "unavailable voice resets selection");
        expect(IllegalArgumentException.class, () -> restored.select("voice-b"));
        restored.select("voice-a");
        restored.remove("voice-a");
        VoiceStore afterDelete = new VoiceStore(directory, "test-key-one");
        check(VoiceStore.DEFAULT_VOICE.equals(afterDelete.selected()), "deleting selected voice resets selection");
        check(afterDelete.list().size() == 1, "delete persists and preserves other voices");

        File failureDirectory = root.resolve("write-failure").toFile();
        VoiceStore failure = new VoiceStore(failureDirectory, "failure-key");
        failure.put("old", "Original", "OK");
        failure.select("old");
        Path savedDirectory = root.resolve("saved-write-failure");
        Files.move(failureDirectory.toPath(), savedDirectory);
        Files.writeString(failureDirectory.toPath(), "blocks-directory");
        expect(IOException.class, () -> failure.put("new", "New", "OK"));
        expect(IOException.class, () -> failure.select(VoiceStore.DEFAULT_VOICE));
        expect(IOException.class, () -> failure.remove("old"));
        check(failure.list().size() == 1 && failure.list().get(0).id.equals("old"),
                "failed writes preserve in-memory voices");
        check(failure.selected().equals("old"), "failed writes preserve in-memory selection");
        Files.delete(failureDirectory.toPath());
        Files.move(savedDirectory, failureDirectory.toPath());
        VoiceStore afterFailure = new VoiceStore(failureDirectory, "failure-key");
        check(afterFailure.list().size() == 1 && afterFailure.selected().equals("old"),
                "failed writes preserve on-disk state");

        Path originalFile = failureDirectory.listFiles()[0].toPath();
        Path savedFile = root.resolve("saved-voice-file.properties");
        Files.move(originalFile, savedFile);
        Files.createDirectory(originalFile);
        Path blocker = originalFile.resolve("occupied");
        Files.writeString(blocker, "blocks-replacement");
        expect(IOException.class, () -> afterFailure.put("new", "New", "OK"));
        check(afterFailure.list().size() == 1 && afterFailure.selected().equals("old"),
                "failed atomic replacement preserves in-memory state");
        check(failureDirectory.listFiles().length == 1, "failed replacement removes temporary file");
        Files.delete(blocker);
        Files.delete(originalFile);
        Files.move(savedFile, originalFile);
        check(new VoiceStore(failureDirectory, "failure-key").selected().equals("old"),
                "original file remains readable after replacement failure");

        File corruptDirectory = root.resolve("corrupt").toFile();
        VoiceStore beforeCorruption = new VoiceStore(corruptDirectory, "corrupt-key");
        beforeCorruption.put("old", "Original", "OK");
        File corruptFile = corruptDirectory.listFiles()[0];
        byte[] corruptBytes = "not-a-voice-store=true\n".getBytes(StandardCharsets.UTF_8);
        Files.write(corruptFile.toPath(), corruptBytes);
        expect(IOException.class, () -> new VoiceStore(corruptDirectory, "corrupt-key"));
        check(Arrays.equals(corruptBytes, Files.readAllBytes(corruptFile.toPath())),
                "damaged store is rejected without being overwritten");
        System.out.println("PASS VoiceStoreChecks: persistence, selection, isolation, corrupt data, write failures");
        System.out.println("Evidence: " + root.toAbsolutePath());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expect(Class<? extends Exception> type, Throwing action) throws Exception {
        try {
            action.run();
        } catch (Exception exception) {
            if (type.isInstance(exception)) return;
            throw exception;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private interface Throwing { void run() throws Exception; }
}
