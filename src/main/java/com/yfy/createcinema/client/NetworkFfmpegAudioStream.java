package com.yfy.createcinema.client;

import net.minecraft.client.sounds.AudioStream;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;

public class NetworkFfmpegAudioStream implements AudioStream {
    private static final int MAX_BUFFERED_PCM_CHUNKS = 96;
    private static final int STARTUP_BUFFER_MILLIS = 450;
    private static final int STARTUP_WAIT_TIMEOUT_MILLIS = 1_500;
    private static final int MAX_BUFFERED_PCM_MILLIS = 2_500;
    private static final int READ_WAIT_MILLIS = 30;
    private static final int SILENCE_FALLBACK_MILLIS = 20;

    private final FFmpegFrameGrabber grabber;
    private final AudioFormat format;
    private final double duration;
    private final InputStream input;
    private final ArrayBlockingQueue<ByteBuffer> decoded = new ArrayBlockingQueue<>(MAX_BUFFERED_PCM_CHUNKS);
    private final AtomicInteger bufferedBytes = new AtomicInteger();
    private final Thread decoderThread;

    private ByteBuffer pending = ByteBuffer.allocate(0);
    private volatile boolean closed;
    private volatile boolean decoderEnded;
    private long discardFrames;

    public NetworkFfmpegAudioStream(BilibiliResolver.ResolvedMedia source, DoubleSupplier startSeconds) throws IOException {
        FFmpegFrameGrabber opened = null;
        InputStream openedInput = null;
        try {
            ClientVideoBurner.awaitFfmpeg();
            boolean hlsUrl = HlsStreamCache.isHls(source.audioUrl());
            if (hlsUrl && !HlsStreamCache.isPrepared(source.audioUrl())) HlsStreamCache.prepare(source.audioUrl(), source.referer());
            boolean hls = hlsUrl && HlsStreamCache.isPrepared(source.audioUrl());
            double requestedStart = wrap(startSeconds.getAsDouble(), source.durationSeconds());
            openedInput = hls ? HlsStreamCache.open(source.audioUrl(), source.referer(), requestedStart) : null;
            opened = hls ? new FFmpegFrameGrabber(openedInput, 0) : new FFmpegFrameGrabber(source.audioUrl());
            ClientNetworkProjectorStreams.configure(opened, source.referer());
            if (hls) opened.setFormat("mpegts");
            opened.setVideoStream(-1);
            opened.start();
            double cachedDuration = HlsStreamCache.duration(source.audioUrl());
            double resolvedDuration = source.durationSeconds() > 0 ? source.durationSeconds()
                    : cachedDuration > 0 ? cachedDuration : opened.getLengthInTime() / 1_000_000.0;
            if (hls) {
                double corrected = wrap(startSeconds.getAsDouble(), resolvedDuration);
                if (corrected - requestedStart > 1) {
                    opened.close();
                    openedInput.close();
                    requestedStart = corrected;
                    openedInput = HlsStreamCache.open(source.audioUrl(), source.referer(), requestedStart);
                    opened = new FFmpegFrameGrabber(openedInput, 0);
                    ClientNetworkProjectorStreams.configure(opened, source.referer());
                    opened.setFormat("mpegts");
                    opened.setVideoStream(-1);
                    opened.start();
                }
            }
            grabber = opened;
            input = openedInput;
            duration = resolvedDuration;
            double currentStart = wrap(hlsUrl ? requestedStart : startSeconds.getAsDouble(), duration);
            if (!hlsUrl && currentStart > 0) grabber.setTimestamp((long) (currentStart * 1_000_000));
            int sampleRate = grabber.getSampleRate() > 0 ? grabber.getSampleRate() : 48_000;
            format = new AudioFormat(sampleRate, 16, Math.max(1, Math.min(2, grabber.getAudioChannels())), true, false);
            if (hls) {
                discardFrames = Math.max(0, Math.round((requestedStart
                        - HlsStreamCache.segmentStart(source.audioUrl(), requestedStart)) * sampleRate));
            }
            decoderThread = new Thread(this::decodeAudio, "CreateCinema Network Audio Decode");
            decoderThread.setDaemon(true);
            decoderThread.start();
            waitForStartupBuffer();
        } catch (Exception e) {
            if (opened != null) try { opened.close(); } catch (Exception ignored) { }
            if (openedInput != null) try { openedInput.close(); } catch (IOException ignored) { }
            throw new IOException("Failed to open network audio", e);
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
                ByteBuffer next = pollDecoded(output.position() == 0 && !decoderEnded ? READ_WAIT_MILLIS : 0);
                if (next == null) {
                    if (output.position() == 0 && !decoderEnded) writeSilence(output, SILENCE_FALLBACK_MILLIS);
                    break;
                }
                pending = next;
            }
            return output.flip();
        } catch (Throwable error) {
            MemoryUtil.memFree(output);
            if (error instanceof IOException io) throw io;
            throw new IOException("Failed to read network audio", error);
        }
    }

    private void decodeAudio() {
        try {
            while (!closed) {
                Frame frame = grabSamples();
                if (frame == null) break;
                ByteBuffer pcm = convert(frame, format.getChannels());
                enqueueDecoded(pcm);
            }
        } catch (Throwable ignored) {
        } finally {
            decoderEnded = true;
            closeResources();
        }
    }

    private void enqueueDecoded(ByteBuffer pcm) throws InterruptedException {
        if (!pcm.hasRemaining()) return;
        while (!closed && bufferedBytes.get() >= bytesForMillis(MAX_BUFFERED_PCM_MILLIS)) {
            TimeUnit.MILLISECONDS.sleep(10L);
        }
        while (!closed) {
            int bytes = pcm.remaining();
            bufferedBytes.addAndGet(bytes);
            if (decoded.offer(pcm, 100L, TimeUnit.MILLISECONDS)) return;
            bufferedBytes.addAndGet(-bytes);
        }
    }

    private ByteBuffer pollDecoded(int waitMillis) throws InterruptedException {
        ByteBuffer next = waitMillis <= 0 ? decoded.poll() : decoded.poll(waitMillis, TimeUnit.MILLISECONDS);
        if (next != null) bufferedBytes.addAndGet(-next.remaining());
        return next;
    }

    private void waitForStartupBuffer() {
        long deadline = System.currentTimeMillis() + STARTUP_WAIT_TIMEOUT_MILLIS;
        int targetBytes = bytesForMillis(STARTUP_BUFFER_MILLIS);
        while (!closed && !decoderEnded && bufferedBytes.get() < targetBytes && System.currentTimeMillis() < deadline) {
            try {
                TimeUnit.MILLISECONDS.sleep(10L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void writeSilence(ByteBuffer output, int millis) {
        int bytes = Math.min(output.remaining(), bytesForMillis(millis));
        for (int index = 0; index < bytes; index++) output.put((byte) 0);
    }

    private Frame grabSamples() throws Exception {
        Frame frame;
        while (!closed && (frame = grabber.grabSamples()) != null) {
            if (frame.samples != null && frame.samples.length > 0 && trimDiscardedSamples(frame)) return frame;
        }
        if (!closed) {
            try {
                grabber.setTimestamp(0);
                while (!closed && (frame = grabber.grabSamples()) != null) {
                    if (frame.samples != null && frame.samples.length > 0 && trimDiscardedSamples(frame)) return frame;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private boolean trimDiscardedSamples(Frame frame) {
        if (discardFrames <= 0) return true;
        boolean planar = frame.samples.length >= format.getChannels();
        int frameCount = planar ? frame.samples[0].remaining()
                : frame.samples[0].remaining() / Math.max(1, frame.audioChannels);
        int skipped = (int) Math.min(discardFrames, frameCount);
        for (Buffer samples : frame.samples) {
            int values = planar ? skipped : skipped * Math.max(1, frame.audioChannels);
            samples.position(Math.min(samples.limit(), samples.position() + values));
        }
        discardFrames -= skipped;
        return skipped < frameCount;
    }

    private static ByteBuffer convert(Frame frame, int channels) {
        Buffer[] samples = frame.samples;
        boolean planar = samples.length >= channels;
        int frames = planar ? samples[0].remaining() : samples[0].remaining() / Math.max(1, frame.audioChannels);
        ByteBuffer pcm = ByteBuffer.allocate(Math.max(0, frames * channels * 2)).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            for (int c = 0; c < channels; c++) {
                Buffer buffer = planar ? samples[c] : samples[0];
                int index = planar ? buffer.position() + i : buffer.position() + i * Math.max(1, frame.audioChannels) + c;
                pcm.putShort((short) Math.round(Math.max(-1, Math.min(1, sample(buffer, index))) * 32767));
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
        return 0;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        decoderThread.interrupt();
        decoded.clear();
        bufferedBytes.set(0);
    }

    private void closeResources() {
        try {
            grabber.close();
        } catch (Exception ignored) {
        }
        if (input != null) {
            try {
                input.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static double wrap(double value, double duration) {
        if (duration <= 0) return Math.max(0, value);
        double wrapped = value % duration;
        return wrapped < 0 ? wrapped + duration : wrapped;
    }

    private int bytesForMillis(int millis) {
        int frameSize = Math.max(1, format.getFrameSize());
        float frameRate = format.getFrameRate() > 0 ? format.getFrameRate() : format.getSampleRate();
        int frames = Math.max(1, Math.round(frameRate * millis / 1000.0f));
        return frames * frameSize;
    }
}
