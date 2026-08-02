package com.yfy.createcinema.client;
import net.minecraft.client.sounds.AudioStream;
import org.bytedeco.javacv.*;
import org.lwjgl.system.MemoryUtil;
import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;
import java.nio.*;
import java.util.function.DoubleSupplier;
public class NetworkFfmpegAudioStream implements AudioStream {
    private final FFmpegFrameGrabber grabber;
    private final AudioFormat format;
    private final double duration;
    private final InputStream input;
    private ByteBuffer pending = ByteBuffer.allocate(0);
    private boolean closed; private long discardFrames;
    public NetworkFfmpegAudioStream(BilibiliResolver.ResolvedMedia source, DoubleSupplier startSeconds) throws IOException {
        FFmpegFrameGrabber opened = null;
        InputStream openedInput = null;
        try {
            ClientVideoBurner.awaitFfmpeg(); boolean hlsUrl = HlsStreamCache.isHls(source.audioUrl()); if (hlsUrl && !HlsStreamCache.isPrepared(source.audioUrl())) HlsStreamCache.prepare(source.audioUrl(), source.referer()); boolean hls = hlsUrl && HlsStreamCache.isPrepared(source.audioUrl()); double requestedStart = wrap(startSeconds.getAsDouble(), source.durationSeconds());
            openedInput = hls ? HlsStreamCache.open(source.audioUrl(), source.referer(), requestedStart) : null; opened = hls ? new FFmpegFrameGrabber(openedInput, 0) : new FFmpegFrameGrabber(source.audioUrl()); ClientNetworkProjectorStreams.configure(opened, source.referer());
            if (hls) opened.setFormat("mpegts"); opened.setVideoStream(-1); opened.start(); double cachedDuration = HlsStreamCache.duration(source.audioUrl()), resolvedDuration = source.durationSeconds() > 0 ? source.durationSeconds() : cachedDuration > 0 ? cachedDuration : opened.getLengthInTime() / 1_000_000.0;
            if (hls) { double corrected = wrap(startSeconds.getAsDouble(), resolvedDuration); if (corrected - requestedStart > 1) { opened.close(); openedInput.close(); requestedStart = corrected; openedInput = HlsStreamCache.open(source.audioUrl(), source.referer(), requestedStart); opened = new FFmpegFrameGrabber(openedInput, 0); ClientNetworkProjectorStreams.configure(opened, source.referer()); opened.setFormat("mpegts"); opened.setVideoStream(-1); opened.start(); } }
            grabber = opened; input = openedInput; duration = resolvedDuration; double currentStart = wrap(hlsUrl ? requestedStart : startSeconds.getAsDouble(), duration); if (!hlsUrl && currentStart > 0) grabber.setTimestamp((long) (currentStart * 1_000_000));
            int sampleRate = grabber.getSampleRate() > 0 ? grabber.getSampleRate() : 48_000; format = new AudioFormat(sampleRate, 16, Math.max(1, Math.min(2, grabber.getAudioChannels())), true, false); if (hls) discardFrames = Math.max(0, Math.round((requestedStart - HlsStreamCache.segmentStart(source.audioUrl(), requestedStart)) * sampleRate));
        } catch (Exception e) { if (opened != null) try { opened.close(); } catch (Exception ignored) { } if (openedInput != null) try { openedInput.close(); } catch (IOException ignored) { } throw new IOException("Failed to open network audio", e); }
    }
    @Override public AudioFormat getFormat() { return format; }
    @Override public ByteBuffer read(int requestedBytes) throws IOException {
        ByteBuffer output = MemoryUtil.memAlloc(Math.max(1, requestedBytes)).order(ByteOrder.LITTLE_ENDIAN);
        try {
            while (output.hasRemaining() && !closed) {
                if (pending.hasRemaining()) {
                    int count = Math.min(output.remaining(), pending.remaining()), limit = pending.limit(); pending.limit(pending.position() + count); output.put(pending); pending.limit(limit); continue;
                }
                Frame frame = grabSamples(); if (frame == null) break; pending = convert(frame, format.getChannels());
            }
            return output.flip();
        } catch (Throwable error) { MemoryUtil.memFree(output); if (error instanceof IOException io) throw io; throw new IOException("Failed to decode network audio", error); }
    }
    private Frame grabSamples() throws Exception {
        Frame frame; while ((frame = grabber.grabSamples()) != null) if (frame.samples != null && frame.samples.length > 0 && trimDiscardedSamples(frame)) return frame;
        if (!closed) try { grabber.setTimestamp(0); while ((frame = grabber.grabSamples()) != null) if (frame.samples != null && frame.samples.length > 0 && trimDiscardedSamples(frame)) return frame; } catch (Exception ignored) { }
        return null;
    }
    private boolean trimDiscardedSamples(Frame frame) { if (discardFrames <= 0) return true; boolean planar = frame.samples.length >= format.getChannels(); int frameCount = planar ? frame.samples[0].remaining() : frame.samples[0].remaining() / Math.max(1, frame.audioChannels), skipped = (int) Math.min(discardFrames, frameCount); for (Buffer samples : frame.samples) { int values = planar ? skipped : skipped * Math.max(1, frame.audioChannels); samples.position(Math.min(samples.limit(), samples.position() + values)); } discardFrames -= skipped; return skipped < frameCount; }
    private static ByteBuffer convert(Frame frame, int channels) {
        Buffer[] samples = frame.samples; boolean planar = samples.length >= channels;
        int frames = planar ? samples[0].remaining() : samples[0].remaining() / Math.max(1, frame.audioChannels);
        ByteBuffer pcm = ByteBuffer.allocate(Math.max(0, frames * channels * 2)).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) for (int c = 0; c < channels; c++) {
            Buffer buffer = planar ? samples[c] : samples[0]; int index = planar ? buffer.position() + i : buffer.position() + i * Math.max(1, frame.audioChannels) + c;
            pcm.putShort((short) Math.round(Math.max(-1, Math.min(1, sample(buffer, index))) * 32767));
        }
        return pcm.flip();
    }
    private static double sample(Buffer b, int i) {
        if (b instanceof FloatBuffer v) return v.get(i); if (b instanceof DoubleBuffer v) return v.get(i); if (b instanceof ShortBuffer v) return v.get(i) / 32768.0;
        if (b instanceof IntBuffer v) return v.get(i) / 2147483648.0; if (b instanceof ByteBuffer v) return v.get(i) / 128.0; return 0;
    }
    @Override public void close() throws IOException { if (closed) return; closed = true; try { grabber.close(); if (input != null) input.close(); } catch (Exception e) { throw new IOException(e); } }
    private static double wrap(double value, double duration) { if (duration <= 0) return Math.max(0, value); double wrapped = value % duration; return wrapped < 0 ? wrapped + duration : wrapped; }
}
