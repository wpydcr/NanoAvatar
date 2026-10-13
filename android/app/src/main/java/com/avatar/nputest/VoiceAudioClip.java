package com.avatar.nputest;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class VoiceAudioClip {
    static final long CLIP_US = 20_000_000L;
    static final long MIN_US = 5_000_000L;

    static final class AudioException extends IOException {
        final String code;

        AudioException(String code) {
            super(code);
            this.code = code;
        }

        AudioException(String code, Throwable cause) {
            super(code, cause);
            this.code = code;
        }
    }

    static final class Info {
        final long durationUs;
        final int sampleRate;
        final int channels;

        Info(MediaFormat format) throws AudioException {
            durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) : 0;
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            if (durationUs < MIN_US) throw new AudioException("TooShort");
            if (sampleRate < 16_000) throw new AudioException("SampleRate");
            if (channels < 1 || channels > 2) throw new AudioException("Channels");
        }
    }

    static Info inspect(File source) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            return new Info(selectAudio(extractor, source));
        } catch (AudioException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw new AudioException("UnsupportedFormat", error);
        } finally {
            extractor.release();
        }
    }

    private static MediaFormat selectAudio(MediaExtractor extractor, File source) throws IOException {
        extractor.setDataSource(source.getAbsolutePath());
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                extractor.selectTrack(i);
                return format;
            }
        }
        throw new AudioException("UnsupportedFormat");
    }

    static File extract(File source, File cacheDir, long startUs) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        File target = null;
        boolean complete = false;
        try {
            MediaFormat format = selectAudio(extractor, source);
            Info info = new Info(format);
            if (startUs < 0 || startUs > info.durationUs - MIN_US)
                throw new AudioException("TooShort");
            long endUs = Math.min(info.durationUs, startUs + CLIP_US);
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            target = File.createTempFile("voice-clip-", ".wav", cacheDir);
            try (PcmWriter writer = new PcmWriter(target, info, startUs, endUs)) {
                String mime = format.getString(MediaFormat.KEY_MIME);
                if ("audio/raw".equals(mime)) {
                    int size = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                            ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 64 * 1024;
                    ByteBuffer buffer = ByteBuffer.allocate(Math.max(64 * 1024, size));
                    while (extractor.getSampleTime() >= 0 && extractor.getSampleTime() < endUs) {
                        buffer.clear();
                        int count = extractor.readSampleData(buffer, 0);
                        if (count < 0) break;
                        buffer.position(0).limit(count);
                        writer.write(buffer, extractor.getSampleTime(), format);
                        if (!extractor.advance()) break;
                    }
                } else {
                    if (startUs > 0) format.setInteger(MediaFormat.KEY_ENCODER_DELAY, 0);
                    format.setInteger(MediaFormat.KEY_ENCODER_PADDING, 0);
                    codec = MediaCodec.createDecoderByType(mime);
                    codec.configure(format, null, null, 0);
                    codec.start();
                    MediaFormat outputFormat = format;
                    MediaCodec.BufferInfo outputInfo = new MediaCodec.BufferInfo();
                    boolean inputEnded = false, outputEnded = false;
                    long lastProgress = System.nanoTime();
                    while (!outputEnded) {
                        if (Thread.currentThread().isInterrupted()) throw new AudioException("DecodeFailed");
                        if (!inputEnded) {
                            int inputIndex = codec.dequeueInputBuffer(10_000);
                            if (inputIndex >= 0) {
                                ByteBuffer input = codec.getInputBuffer(inputIndex);
                                if (input == null) throw new AudioException("DecodeFailed");
                                input.clear();
                                long timeUs = extractor.getSampleTime();
                                int count = extractor.readSampleData(input, 0);
                                if (count < 0) {
                                    codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                    inputEnded = true;
                                } else {
                                    codec.queueInputBuffer(inputIndex, 0, count, timeUs, 0);
                                    extractor.advance();
                                }
                            }
                        }
                        int outputIndex = codec.dequeueOutputBuffer(outputInfo, 10_000);
                        if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            outputFormat = codec.getOutputFormat();
                            lastProgress = System.nanoTime();
                        } else if (outputIndex >= 0) {
                            try {
                                ByteBuffer output = codec.getOutputBuffer(outputIndex);
                                if (outputInfo.size > 0 && (outputInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                    if (output == null) throw new AudioException("DecodeFailed");
                                    output.position(outputInfo.offset).limit(outputInfo.offset + outputInfo.size);
                                    writer.write(output.slice(), outputInfo.presentationTimeUs, outputFormat);
                                }
                                outputEnded = (outputInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                                        || outputInfo.presentationTimeUs >= endUs || writer.full();
                                lastProgress = System.nanoTime();
                            } finally {
                                codec.releaseOutputBuffer(outputIndex, false);
                            }
                        } else if (System.nanoTime() - lastProgress > 10_000_000_000L) {
                            throw new AudioException("DecodeFailed");
                        }
                    }
                }
                writer.finish();
            }
            complete = true;
            return target;
        } catch (AudioException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw new AudioException("DecodeFailed", error);
        } finally {
            if (codec != null) {
                try { codec.release(); } catch (RuntimeException ignored) { }
            }
            extractor.release();
            if (!complete && target != null) target.delete();
        }
    }

    private static final class PcmWriter implements AutoCloseable {
        final RandomAccessFile output;
        final Info info;
        final long startUs, endUs, maxFrames;
        long frames;

        PcmWriter(File target, Info info, long startUs, long endUs) throws IOException {
            this.info = info;
            this.startUs = startUs;
            this.endUs = endUs;
            maxFrames = (endUs - startUs) * info.sampleRate / 1_000_000;
            output = new RandomAccessFile(target, "rw");
            output.write(new byte[44]);
        }

        boolean full() { return frames >= maxFrames; }

        void write(ByteBuffer pcm, long timeUs, MediaFormat format) throws IOException {
            if (format.getInteger(MediaFormat.KEY_SAMPLE_RATE) != info.sampleRate
                    || format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != info.channels)
                throw new AudioException("UnsupportedFormat");
            int encoding = format.containsKey(MediaFormat.KEY_PCM_ENCODING)
                    ? format.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
            int bytes;
            switch (encoding) {
                case AudioFormat.ENCODING_PCM_8BIT: bytes = 1; break;
                case AudioFormat.ENCODING_PCM_16BIT: bytes = 2; break;
                case AudioFormat.ENCODING_PCM_24BIT_PACKED: bytes = 3; break;
                case AudioFormat.ENCODING_PCM_32BIT:
                case AudioFormat.ENCODING_PCM_FLOAT: bytes = 4; break;
                default: throw new AudioException("UnsupportedFormat");
            }
            int frameBytes = bytes * info.channels;
            int available = pcm.remaining() / frameBytes;
            long skip = Math.max(0, ceilFrames(startUs - timeUs));
            long untilEnd = Math.max(0, ceilFrames(endUs - timeUs));
            int count = (int) Math.min(maxFrames - frames, Math.min(available, untilEnd) - skip);
            if (count <= 0) return;
            if (44 + (frames + count) * info.channels * 2 > VoiceSample.MAX_BYTES)
                throw new AudioException("TooLarge");
            pcm.order(ByteOrder.LITTLE_ENDIAN);
            pcm.position(pcm.position() + (int) skip * frameBytes);
            ByteBuffer converted = ByteBuffer.allocate(count * info.channels * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count * info.channels; i++) {
                int value;
                switch (encoding) {
                    case AudioFormat.ENCODING_PCM_8BIT: value = ((pcm.get() & 255) - 128) << 8; break;
                    case AudioFormat.ENCODING_PCM_16BIT: value = pcm.getShort(); break;
                    case AudioFormat.ENCODING_PCM_24BIT_PACKED:
                        pcm.get();
                        value = (pcm.get() & 255) | (pcm.get() << 8);
                        break;
                    case AudioFormat.ENCODING_PCM_32BIT: value = pcm.getInt() >> 16; break;
                    default:
                        float sample = pcm.getFloat();
                        if (!Float.isFinite(sample)) throw new AudioException("DecodeFailed");
                        value = Math.max(-32768, Math.min(32767, Math.round(sample * 32768)));
                }
                converted.putShort((short) value);
            }
            output.write(converted.array());
            frames += count;
        }

        private long ceilFrames(long timeUs) {
            return timeUs <= 0 ? 0 : (timeUs * info.sampleRate + 999_999) / 1_000_000;
        }

        void finish() throws IOException {
            if (frames < info.sampleRate * 5L) throw new AudioException("TooShort");
            int dataBytes = (int) (frames * info.channels * 2);
            ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            header.putInt(0x46464952).putInt(36 + dataBytes).putInt(0x45564157);
            header.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) info.channels);
            header.putInt(info.sampleRate).putInt(info.sampleRate * info.channels * 2);
            header.putShort((short) (info.channels * 2)).putShort((short) 16);
            header.putInt(0x61746164).putInt(dataBytes);
            output.seek(0);
            output.write(header.array());
        }

        @Override public void close() throws IOException { output.close(); }
    }
}
