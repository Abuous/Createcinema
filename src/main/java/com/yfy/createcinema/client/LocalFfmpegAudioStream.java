package com.yfy.createcinema.client;

import net.minecraft.client.sounds.AudioStream;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.file.Path;

public class LocalFfmpegAudioStream implements AudioStream {
    private final FFmpegFrameGrabber grabber;
    private final AudioFormat format;
    private ByteBuffer pending = ByteBuffer.allocate(0);
    private boolean closed;

    public LocalFfmpegAudioStream(Path source, double startSeconds) throws IOException {
        try {
            ClientVideoBurner.awaitFfmpeg();
            grabber = new FFmpegFrameGrabber(source.toFile());
            grabber.setVideoStream(-1);
            grabber.start();
            if (startSeconds > 0.0) grabber.setTimestamp((long) (startSeconds * 1_000_000L));
            int sampleRate = grabber.getSampleRate() > 0 ? grabber.getSampleRate() : 48_000;
            int channels = Math.max(1, Math.min(2, grabber.getAudioChannels()));
            format = new AudioFormat(sampleRate, 16, channels, true, false);
        } catch (Exception error) {
            throw new IOException("Failed to open local film audio", error);
        }
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public ByteBuffer read(int requestedBytes) throws IOException {
        ByteBuffer output = MemoryUtil.memAlloc(Math.max(1, requestedBytes)).order(ByteOrder.LITTLE_ENDIAN);
        try {
            while (output.hasRemaining() && !closed) {
                if (pending.hasRemaining()) {
                    int count = Math.min(output.remaining(), pending.remaining());
                    int limit = pending.limit();
                    pending.limit(pending.position() + count);
                    output.put(pending);
                    pending.limit(limit);
                    continue;
                }
                Frame frame = nextSamples();
                if (frame == null) break;
                pending = convert(frame, format.getChannels());
            }
            return output.flip();
        } catch (Throwable error) {
            MemoryUtil.memFree(output);
            if (error instanceof IOException io) throw io;
            throw new IOException("Failed to decode local film audio", error);
        }
    }

    private Frame nextSamples() throws Exception {
        Frame frame;
        while (!closed && (frame = grabber.grabSamples()) != null) {
            if (frame.samples != null && frame.samples.length > 0) return frame;
        }
        if (closed) return null;
        grabber.setTimestamp(0L);
        while (!closed && (frame = grabber.grabSamples()) != null) {
            if (frame.samples != null && frame.samples.length > 0) return frame;
        }
        return null;
    }

    private static ByteBuffer convert(Frame frame, int channels) {
        Buffer[] samples = frame.samples;
        boolean planar = samples.length >= channels;
        int frames = planar ? samples[0].remaining() : samples[0].remaining() / Math.max(1, frame.audioChannels);
        ByteBuffer pcm = ByteBuffer.allocate(Math.max(0, frames * channels * 2)).order(ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index < frames; index++) {
            for (int channel = 0; channel < channels; channel++) {
                Buffer buffer = planar ? samples[channel] : samples[0];
                int sampleIndex = planar ? buffer.position() + index
                        : buffer.position() + index * Math.max(1, frame.audioChannels) + channel;
                pcm.putShort((short) Math.round(Math.max(-1, Math.min(1, sample(buffer, sampleIndex))) * 32767));
            }
        }
        return pcm.flip();
    }

    private static double sample(Buffer buffer, int index) {
        if (buffer instanceof FloatBuffer value) return value.get(index);
        if (buffer instanceof DoubleBuffer value) return value.get(index);
        if (buffer instanceof ShortBuffer value) return value.get(index) / 32768.0;
        if (buffer instanceof IntBuffer value) return value.get(index) / 2147483648.0;
        if (buffer instanceof ByteBuffer value) return value.get(index) / 128.0;
        return 0.0;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            grabber.close();
        } catch (Exception ignored) {
        }
    }
}
