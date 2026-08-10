package com.yfy.createcinema.client;

import com.yfy.createcinema.CreateCinema;
import net.minecraft.client.sounds.AudioStream;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;

/** Decodes one network source once and gives each speaker its own PCM cursor. */
final class SharedNetworkAudio implements AutoCloseable {
    private static final int MAX_QUEUED_MILLIS = 1_500;
    private static final int STARTUP_CHUNKS = 4;
    private static final long STARTUP_WAIT_MILLIS = 2_000L;
    private static final int PRODUCER_WAIT_MILLIS = 100;
    private static final long PRODUCER_BACKOFF_MILLIS = 250L;
    private static final long PRODUCER_QUICK_MILLIS = 20L;
    private static final int PRODUCER_PACING_CHUNKS = 4;
    private static final long STALL_TIMEOUT_MILLIS = 10_000L;

    private final BilibiliResolver.ResolvedMedia source;
    private final DoubleSupplier startSeconds;
    private final boolean live;
    private final Set<Tap> taps = ConcurrentHashMap.newKeySet();
    private volatile NetworkFfmpegAudioStream upstream;
    private volatile AudioFormat format;
    private volatile int chunkBytes;
    private volatile int bytesPerSecond;
    private volatile double pumpedSeconds;
    private volatile double audioLag;
    private volatile float targetRate = 1.0f;
    private volatile Throwable failure;
    private volatile boolean closed;
    private volatile boolean pumpStarted;

    SharedNetworkAudio(BilibiliResolver.ResolvedMedia source, DoubleSupplier startSeconds) {
        this.source = source;
        this.startSeconds = startSeconds;
        live = source.live();
    }

    synchronized Tap openTap() throws IOException {
        if (closed) throw new IOException("Shared network audio is closed");
        if (upstream == null) openUpstream();
        Tap tap = new Tap(format, chunkBytes, this, Math.max(0.0, startSeconds.getAsDouble()));
        taps.add(tap);
        if (!pumpStarted) {
            pumpStarted = true;
            Thread pump = new Thread(this::pump, "CreateCinema Shared Network Audio");
            pump.setDaemon(true);
            pump.start();
        }
        notifyAll();
        try {
            tap.awaitStartup();
            return tap;
        } catch (IOException error) {
            tap.close();
            throw error;
        }
    }

    Throwable failure() {
        return failure;
    }

    /** Audio playback lag vs the video clock, in media seconds. Positive means audio is ahead. */
    double audioLag() {
        return audioLag;
    }

    void setTargetRate(float rate) {
        if (!live) targetRate = Math.max(1.0f, rate);
    }

    private void openUpstream() throws IOException {
        NetworkFfmpegAudioStream opened = new NetworkFfmpegAudioStream(source, startSeconds);
        if (closed) {
            opened.close();
            throw new IOException("Shared network audio was closed while opening");
        }
        upstream = opened;
        format = opened.getFormat();
        chunkBytes = bytesForMillis(format, 250);
        bytesPerSecond = bytesForMillis(format, 1_000);
        pumpedSeconds = opened.startTime();
    }

    private void pump() {
        long lastProduced = System.currentTimeMillis();
        try {
            while (!closed) {
                if (taps.isEmpty()) {
                    lastProduced = System.currentTimeMillis();
                    awaitTap();
                    continue;
                }
                ByteBuffer pcm = upstream.readDecoded(chunkBytes, PRODUCER_WAIT_MILLIS);
                double videoNow = startSeconds.getAsDouble();
                if (!pcm.hasRemaining()) {
                    if (upstream.decoderEnded()) throw new IOException("Shared network audio decoder ended");
                    if (System.currentTimeMillis() - lastProduced > STALL_TIMEOUT_MILLIS) {
                        throw new IOException("Shared network audio upstream stalled");
                    }
                    audioLag = pumpedSeconds - videoNow;
                    continue;
                }
                lastProduced = System.currentTimeMillis();
                byte[] copy = keepBytes(pcm);
                double chunkStartSeconds = pumpedSeconds;
                for (Tap tap : taps) tap.offer(copy, chunkStartSeconds);
                pumpedSeconds = chunkStartSeconds + copy.length / (double) Math.max(1, bytesPerSecond);
                audioLag = pumpedSeconds - videoNow;
                pace();
            }
        } catch (Throwable error) {
            if (!closed) failure = error;
        } finally {
            close();
        }
    }

    private synchronized void awaitTap() throws IOException {
        long deadline = System.currentTimeMillis() + STARTUP_WAIT_MILLIS;
        while (!closed && taps.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                wait(100L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for shared network audio listeners", error);
            }
        }
        if (closed) throw new IOException("Shared network audio is closed");
    }

    /** Trims a decoded chunk to the head fraction matching the playback rate, so consumers at
     *  pitch > 1 are fed enough media to stay continuous. */
    private byte[] keepBytes(ByteBuffer pcm) {
        int bytes = pcm.remaining();
        float rate = Math.max(1.0f, targetRate);
        int keep = bytes;
        if (rate > 1.01f) {
            int frameSize = Math.max(1, format == null ? 2 : format.getFrameSize());
            int head = (int) (bytes / rate);
            keep = Math.max(frameSize, head - head % frameSize);
        }
        byte[] copy = new byte[keep];
        int oldLimit = pcm.limit();
        pcm.limit(pcm.position() + keep);
        pcm.get(copy);
        pcm.limit(oldLimit);
        if (keep < bytes) {
            CreateCinema.LOGGER.debug("Trimmed shared network audio chunk {} -> {} bytes for {}x playback",
                    bytes, keep, rate);
        }
        return copy;
    }

    private void pace() {
        int pacingBytes = chunkBytes * PRODUCER_PACING_CHUNKS;
        int slowestQueued = Integer.MAX_VALUE;
        for (Tap tap : taps) {
            int queued = tap.queuedBytes.get();
            if (queued < slowestQueued) slowestQueued = queued;
        }
        long sleepMillis = slowestQueued >= pacingBytes ? PRODUCER_BACKOFF_MILLIS : PRODUCER_QUICK_MILLIS;
        if (sleepMillis <= 0L) return;
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        NetworkFfmpegAudioStream stream = upstream;
        if (stream != null) stream.close();
        taps.forEach(Tap::close);
        taps.clear();
    }

    static final class Tap implements AudioStream {
        private final AudioFormat format;
        private final int chunkBytes;
        private final SharedNetworkAudio owner;
        private final double startTime;
        private final LinkedBlockingDeque<byte[]> chunks = new LinkedBlockingDeque<>();
        private final AtomicInteger queuedBytes = new AtomicInteger();
        private volatile double firstChunkSeconds = Double.NaN;
        private ByteBuffer pending = ByteBuffer.allocate(0);
        private ByteBuffer readBuffer;
        private volatile boolean closed;

        private Tap(AudioFormat format, int chunkBytes, SharedNetworkAudio owner, double startTime) {
            this.format = format;
            this.chunkBytes = chunkBytes;
            this.owner = owner;
            this.startTime = startTime;
        }

        double startTime() {
            return startTime;
        }

        /** Content position, in media seconds, of the first PCM chunk this tap received. */
        double firstChunkSeconds() {
            return firstChunkSeconds;
        }

        @Override
        public AudioFormat getFormat() {
            return format;
        }

        @Override
        public ByteBuffer read(int requestedBytes) throws IOException {
            int size = Math.max(1, Math.min(requestedBytes, chunkBytes));
            ByteBuffer output = readBuffer;
            if (output == null || output.capacity() < size) {
                output = ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
                readBuffer = output;
            } else {
                output.clear();
            }
            try {
                if (closed) return output.flip();
                while (output.hasRemaining() && !closed) {
                    if (pending.hasRemaining()) {
                        int count = Math.min(output.remaining(), pending.remaining());
                        int limit = pending.limit();
                        pending.limit(pending.position() + count);
                        output.put(pending);
                        pending.limit(limit);
                        continue;
                    }
                    byte[] next = chunks.poll();
                    if (next == null) break;
                    queuedBytes.addAndGet(-next.length);
                    pending = ByteBuffer.wrap(next).order(ByteOrder.LITTLE_ENDIAN);
                }
                if (closed) return output.flip();
                while (output.hasRemaining()) output.put((byte) 0);
                return output.flip();
            } catch (Throwable error) {
                throw error instanceof IOException io ? io : new IOException("Failed to read shared network audio", error);
            }
        }

        private void awaitStartup() throws IOException {
            long deadline = System.currentTimeMillis() + STARTUP_WAIT_MILLIS;
            int startupBytes = chunkBytes * STARTUP_CHUNKS;
            while (!closed && queuedBytes.get() < startupBytes && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while buffering shared network audio", error);
                }
            }
            if (queuedBytes.get() >= startupBytes) return;
            Throwable error = owner.failure();
            if (error != null) throw new IOException("Shared network audio decoder failed", error);
            throw new IOException("Timed out while buffering shared network audio");
        }

        private void offer(byte[] pcm, double chunkStartSeconds) {
            if (closed) return;
            if (Double.isNaN(firstChunkSeconds)) firstChunkSeconds = chunkStartSeconds;
            int maxQueuedBytes = bytesForMillis(format, MAX_QUEUED_MILLIS);
            while (queuedBytes.get() + pcm.length > maxQueuedBytes) {
                byte[] discarded = chunks.pollFirst();
                if (discarded == null) break;
                queuedBytes.addAndGet(-discarded.length);
            }
            chunks.offerLast(pcm);
            queuedBytes.addAndGet(pcm.length);
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            chunks.clear();
            queuedBytes.set(0);
            owner.taps.remove(this);
            synchronized (owner) {
                owner.notifyAll();
            }
        }
    }

    private static int bytesForMillis(AudioFormat format, int millis) {
        int frameSize = Math.max(1, format.getFrameSize());
        float frameRate = format.getFrameRate() > 0 ? format.getFrameRate() : format.getSampleRate();
        return Math.max(frameSize, Math.round(frameRate * millis / 1_000.0f) * frameSize);
    }
}
