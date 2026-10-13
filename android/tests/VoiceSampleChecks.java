package com.avatar.nputest;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;

public final class VoiceSampleChecks {
    public static void main(String[] args) throws Exception {
        File directory = Files.createTempDirectory("voice-sample-checks").toFile();
        Class<?> sample;
        try { sample = Class.forName("com.avatar.nputest.VoiceSample"); }
        catch (ClassNotFoundException missing) { throw new AssertionError("Recording/file conversion is not implemented", missing); }
        var fromPcm = sample.getDeclaredMethod("fromPcm", File.class, float[].class);
        float[] pcm = new float[16_000 * 5];
        pcm[0] = -1; pcm[1] = 1; pcm[2] = .5f;
        File wav = (File) fromPcm.invoke(null, directory, pcm);
        byte[] bytes = Files.readAllBytes(wav.toPath());
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        check(bytes.length == 44 + pcm.length * 2, "WAV size");
        check(buffer.getInt(24) == 16_000 && buffer.getShort(22) == 1 && buffer.getShort(34) == 16, "WAV format");
        check(buffer.getShort(44) == -32768 && buffer.getShort(46) == 32767 && buffer.getShort(48) == 16384, "PCM samples");
        reject(() -> fromPcm.invoke(null, directory, new float[100]), "Short recording accepted");
        var copy = sample.getDeclaredMethod("copy", File.class, java.io.InputStream.class, String.class);
        File copied = (File) copy.invoke(null, directory, new ByteArrayInputStream(bytes), ".wav");
        check(java.util.Arrays.equals(bytes, Files.readAllBytes(copied.toPath())), "Imported bytes changed");
        reject(() -> copy.invoke(null, directory, new ByteArrayInputStream(new byte[10 * 1024 * 1024 + 1]), ".mp3"), "Oversized file accepted");
        reject(() -> copy.invoke(null, directory, new ByteArrayInputStream(new byte[0]), ".wav"), "Empty file accepted");
        var metadata = sample.getDeclaredMethod("validate", long.class, int.class, int.class);
        metadata.invoke(null, 10_000_000L, 24_000, 1);
        metadata.invoke(null, 60_000_000L, 16_000, 2);
        reject(() -> metadata.invoke(null, 60_100_000L, 16_000, 1), "Long audio accepted");
        reject(() -> metadata.invoke(null, 10_000_000L, 8_000, 1), "Low sample rate accepted");
        reject(() -> metadata.invoke(null, 10_000_000L, 16_000, 6), "Surround audio accepted");
        check(directory.listFiles().length == 2, "Failed import left temporary files");
        System.out.println("VoiceSampleChecks: PASS");
    }
    interface Action { void run() throws Exception; }
    static void reject(Action action, String message) throws Exception {
        try { action.run(); }
        catch (java.lang.reflect.InvocationTargetException exception) {
            if (exception.getCause() instanceof IOException) return;
            throw exception;
        }
        throw new AssertionError(message);
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
