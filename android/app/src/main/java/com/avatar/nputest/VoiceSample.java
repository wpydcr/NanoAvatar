package com.avatar.nputest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class VoiceSample {
    static final int MAX_BYTES = 10 * 1024 * 1024;

    static void validate(long durationUs, int sampleRate, int channels) throws IOException {
        if (durationUs < 5_000_000 || durationUs > 60_000_000 || sampleRate < 16_000
                || channels < 1 || channels > 2) throw new IOException("Invalid voice sample");
    }

    static File fromPcm(File directory, float[] pcm) throws IOException {
        validate(pcm.length * 1_000_000L / 16_000, 16_000, 1);
        ByteBuffer wav = ByteBuffer.allocate(44 + pcm.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        wav.putInt(0x46464952).putInt(36 + pcm.length * 2).putInt(0x45564157);
        wav.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1);
        wav.putInt(16_000).putInt(32_000).putShort((short) 2).putShort((short) 16);
        wav.putInt(0x61746164).putInt(pcm.length * 2);
        for (float value : pcm) {
            if (!Float.isFinite(value)) throw new IOException("Invalid PCM sample");
            wav.putShort((short) Math.max(-32768, Math.min(32767, Math.round(value * 32768))));
        }
        return copy(directory, new java.io.ByteArrayInputStream(wav.array()), ".wav");
    }

    static File copy(File directory, InputStream input, String suffix) throws IOException {
        if (!".wav".equals(suffix) && !".mp3".equals(suffix) && !".m4a".equals(suffix))
            throw new IOException("Unsupported audio file");
        File file = File.createTempFile("voice-sample-", suffix, directory);
        boolean complete = false;
        try (FileOutputStream output = new FileOutputStream(file)) {
            byte[] buffer = new byte[8192];
            int total = 0, count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_BYTES) throw new IOException("Audio file exceeds 10 MB");
                output.write(buffer, 0, count);
            }
            if (total == 0) throw new IOException("Empty audio file");
            complete = true;
            return file;
        } finally {
            if (!complete) file.delete();
        }
    }
}
