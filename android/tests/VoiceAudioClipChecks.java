package com.avatar.nputest;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class VoiceAudioClipChecks {
    public static void main(String[] args) throws Exception {
        try {
            run(args);
        } catch (Throwable error) {
            error.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        File source = new File(args[0]);
        long startUs = args.length > 1 ? Long.parseLong(args[1]) : 0;
        File directory = new File("/data/local/tmp");
        long sourceLength = source.length();
        long modified = source.lastModified();
        VoiceAudioClip.Info original = VoiceAudioClip.inspect(source);
        System.out.println("INSPECTED durationUs=" + original.durationUs + " rate=" + original.sampleRate);
        File clipped = VoiceAudioClip.extract(source, directory, startUs);
        System.out.println("EXTRACTED " + clipped + " bytes=" + clipped.length());
        VoiceAudioClip.Info result = VoiceAudioClip.inspect(clipped);
        long expected = Math.min(20_000_000L, original.durationUs - startUs);
        // AAC priming may remove the initial codec frames from a clip starting at zero.
        if (Math.abs(result.durationUs - expected) > (startUs == 0 ? 100_000 : 50_000))
            throw new AssertionError("Wrong clip duration: " + result.durationUs + " expected=" + expected);
        if (result.sampleRate != original.sampleRate || result.channels != original.channels)
            throw new AssertionError("Changed PCM format");
        if (result.durationUs < 5_000_000 || result.durationUs > 20_000_000)
            throw new AssertionError("Clip outside duration limits");
        if (clipped.length() > VoiceSample.MAX_BYTES) throw new AssertionError("Clip too large");
        if (source.length() != sourceLength || source.lastModified() != modified)
            throw new AssertionError("Source changed");
        long samples = 0, nonzero = 0;
        double squares = 0;
        try (RandomAccessFile input = new RandomAccessFile(clipped, "r")) {
            byte[] header = new byte[44];
            input.readFully(header);
            ByteBuffer fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            if (fields.getInt(0) != 0x46464952 || fields.getShort(34) != 16
                    || fields.getInt(40) != input.length() - 44) throw new AssertionError("Invalid WAV header");
            while (input.getFilePointer() < input.length()) {
                short sample = Short.reverseBytes(input.readShort());
                samples++;
                if (sample != 0) nonzero++;
                squares += sample * (double) sample;
            }
        }
        if (nonzero < samples / 100 || squares == 0) throw new AssertionError("Blank PCM");
        System.out.println("PASS source=" + source.getName() + " originalUs=" + original.durationUs
                + " startUs=" + startUs + " durationUs=" + result.durationUs
                + " rate=" + result.sampleRate + " channels=" + result.channels
                + " bytes=" + clipped.length() + " nonzero=" + nonzero + "/" + samples
                + " rms=" + Math.sqrt(squares / samples) + " output=" + clipped);
        try {
            VoiceAudioClip.extract(source, directory, original.durationUs);
            throw new AssertionError("Accepted empty tail");
        } catch (VoiceAudioClip.AudioException expectedError) {
            if (!"TooShort".equals(expectedError.code)) throw expectedError;
        }
    }
}
