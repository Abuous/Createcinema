package com.yfy.createcinema.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.NetworkVideoQuality;
import com.yfy.createcinema.blockentity.NetworkProjectorBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

import java.io.IOException;
import java.io.InputStream;

public final class ClientNetworkProjectorStreams {
    private static final int MAX_BUFFERED_FRAMES = 64;
    private static final double STARTUP_BUFFER_SECONDS = 0.60;
    private static final double MAX_BUFFER_SECONDS = 1.50;
    private static final double HARD_RESYNC_SECONDS = 3.0;
    private static final double DISPLAY_LEAD_SECONDS = 0.035;
    private static final String NETWORK_RW_TIMEOUT_MICROS = "5000000";
    private static final Map<String, Session> SESSIONS = new HashMap<>();
    private static final AtomicInteger NEXT_MEDIA_REVISION = new AtomicInteger();
    private static final ExecutorService STREAM_EXECUTOR = new ThreadPoolExecutor(4, 4, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), runnable -> {
        Thread thread = new Thread(runnable, "CreateCinema Network Stream");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());

    private ClientNetworkProjectorStreams() {
    }

    public static NetworkProjectionFrame frame(NetworkProjectorBlockEntity projector, double playTime) {
        if (projector.getLevel() == null || projector.getUrl().isBlank()) return null;
        String key = projector.getLevel().dimension().location() + "/" + projector.getBlockPos().asLong();
        Session session = SESSIONS.get(key);
        if (session != null && session.failed && System.currentTimeMillis() >= session.retryAt) {
            session.close("retry");
            SESSIONS.remove(key);
            session = null;
        }
        boolean continuousEnabled = projector.hasContinuousPlayUpgrade();
        NetworkVideoQuality quality = projector.getQuality();
        if (session == null || !session.url.equals(projector.getUrl())
                || session.continuousEnabled != continuousEnabled || session.quality != quality) {
            if (session != null) session.close("projector settings changed");
            session = new Session(key, projector.getUrl(), continuousEnabled, quality, playTime,
                    projector.getNavigationRevision(), projector.getNavigationOffset(), -1, 0);
            SESSIONS.put(key, session);
        } else if (session.navigationRevision != projector.getNavigationRevision()) {
            int navigationDelta = projector.getNavigationOffset() - session.navigationOffset;
            int requestedIndex = session.playlist == null ? -1 : session.playlistIndex + navigationDelta;
            int startOffset = session.playlist == null ? navigationDelta : 0;
            session.close("playlist navigation");
            session = new Session(key, projector.getUrl(), continuousEnabled, quality, playTime,
                    projector.getNavigationRevision(), projector.getNavigationOffset(), requestedIndex, startOffset);
            SESSIONS.put(key, session);
        }
        session.updateTarget(playTime);
        session.lastTouched = System.currentTimeMillis();
        return session.uploadReady();
    }

    public static BilibiliResolver.ResolvedMedia source(NetworkProjectorBlockEntity projector) {
        if (projector.getLevel() == null) return null;
        Session session = SESSIONS.get(projector.getLevel().dimension().location() + "/" + projector.getBlockPos().asLong());
        return session == null ? null : session.source;
    }

    public static double mediaTime(NetworkProjectorBlockEntity projector) {
        if (projector.getLevel() == null) return projector.getPlayTime();
        Session session = SESSIONS.get(projector.getLevel().dimension().location() + "/" + projector.getBlockPos().asLong());
        return session == null ? projector.getPlayTime() : session.mediaTime();
    }

    public static int mediaRevision(NetworkProjectorBlockEntity projector) {
        if (projector.getLevel() == null) return 0;
        Session session = SESSIONS.get(projector.getLevel().dimension().location() + "/" + projector.getBlockPos().asLong());
        return session == null ? 0 : session.mediaRevision;
    }

    public static Status status(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return Status.IDLE;
        Session session = SESSIONS.get(minecraft.level.dimension().location() + "/" + pos.asLong());
        if (session == null) return Status.IDLE;
        if (session.failed) return Status.ERROR;
        if (session.ended) return Status.ENDED;
        if (session.source == null || session.textureLocation == null) {
            return Status.LOADING;
        }
        return Status.PLAYING;
    }

    public static float progress(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return 0.0f;
        Session session = SESSIONS.get(minecraft.level.dimension().location() + "/" + pos.asLong());
        if (session == null || session.failed) return 0.0f;
        return Math.max(0.0f, Math.min(1.0f, session.progress));
    }

    public static Component message(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return Component.empty();
        Session session = SESSIONS.get(minecraft.level.dimension().location() + "/" + pos.asLong());
        if (session == null) return Component.empty();
        if (session.failed) return session.errorMessage == null
                ? Component.translatable("gui.createcinema.stream.error_short")
                : session.errorMessage;
        if (session.ended) return Component.translatable("gui.createcinema.stream.ended");
        return Component.translatable("gui.createcinema.stream.loading_progress", Math.round(session.progress * 100.0f));
    }

    public static Component playlistMessage(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return Component.empty();
        Session session = SESSIONS.get(minecraft.level.dimension().location() + "/" + pos.asLong());
        if (session == null || session.playlist == null) return Component.empty();
        int count = session.playlist.entries().size();
        if (count <= 1) return Component.translatable("gui.createcinema.playlist.single");
        return Component.translatable("gui.createcinema.playlist.detected", count,
                session.playlistIndex + 1, count);
    }

    public static void requestRetry(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        Session session = SESSIONS.get(minecraft.level.dimension().location() + "/" + pos.asLong());
        if (session != null) session.retryAt = 0L;
    }

    public static void requestDouyinRetry() {
        SESSIONS.entrySet().removeIf(entry -> {
            Session session = entry.getValue();
            if (!DouyinVideoResolver.canResolve(session.url) && !DouyinLiveResolver.canResolve(session.url)) return false;
            session.close("Douyin authorization changed");
            return true;
        });
    }

    public static void stop(NetworkProjectorBlockEntity projector) {
        if (projector.getLevel() == null) return;
        String key = projector.getLevel().dimension().location() + "/" + projector.getBlockPos().asLong();
        Session session = SESSIONS.remove(key);
        if (session != null) session.close("projector stopped");
    }

    public static void sweep() {
        long cutoff = System.currentTimeMillis() - 60_000L;
        SESSIONS.entrySet().removeIf(entry -> {
            if (entry.getValue().lastTouched >= cutoff) return false;
            entry.getValue().close("not rendered for 60 seconds");
            return true;
        });
    }

    public static void closeAll() {
        SESSIONS.values().forEach(session -> session.close("client level cleared"));
        SESSIONS.clear();
        HlsStreamCache.clear();
    }

    private static class Session {
        private final String key;
        private final String url;
        private final boolean continuousEnabled;
        private final NetworkVideoQuality quality;
        private final ArrayDeque<DecodedFrame> bufferedFrames = new ArrayDeque<>();
        private volatile BilibiliResolver.ResolvedPlaylist playlist;
        private volatile BilibiliResolver.ResolvedMedia source;
        private volatile double targetTime;
        private volatile double itemStartTime;
        private volatile double duration;
        private volatile long lastTouched = System.currentTimeMillis();
        private volatile long decodedFrameCount;
        private long uploadedFrameCount;
        private long lastVideoDiagnosticAt;
        private volatile boolean closed;
        private volatile boolean failed;
        private volatile boolean ended;
        private volatile long retryAt;
        private volatile float progress = 0.03f;
        private volatile Component errorMessage;
        private volatile boolean bufferReady;
        private DynamicTexture texture;
        private ResourceLocation textureLocation;
        private int width;
        private int height;
        private volatile int playlistIndex;
        private volatile int mediaRevision;
        private final int navigationRevision;
        private final int navigationOffset;
        private final int requestedPlaylistIndex;
        private final int playlistStartOffset;

        private Session(String key, String url, boolean continuousEnabled, NetworkVideoQuality quality,
                        double playTime, int navigationRevision, int navigationOffset,
                        int requestedPlaylistIndex, int playlistStartOffset) {
            this.key = key;
            this.url = url;
            this.continuousEnabled = continuousEnabled;
            this.quality = quality;
            this.navigationRevision = navigationRevision;
            this.navigationOffset = navigationOffset;
            this.requestedPlaylistIndex = requestedPlaylistIndex;
            this.playlistStartOffset = playlistStartOffset;
            targetTime = playTime;
            mediaRevision = NEXT_MEDIA_REVISION.incrementAndGet();
            CreateCinema.LOGGER.debug("Created network stream session {} (continuous={}, quality={}, revision={})",
                    url, continuousEnabled, quality, mediaRevision);
            try {
                STREAM_EXECUTOR.execute(this::decode);
            } catch (RejectedExecutionException error) {
                failed = true;
                errorMessage = Component.translatable("gui.createcinema.stream.error.queue_full");
                retryAt = System.currentTimeMillis() + 5_000L;
            }
        }

        private void updateTarget(double playTime) {
            if (ended) return;
            targetTime = Math.max(targetTime, playTime);
        }

        private void decode() {
            boolean advance = false;
            boolean reconnect = false;
            try {
                progress = 0.08f;
                ClientVideoBurner.awaitFfmpeg();
                if (closed) return;
                progress = 0.18f;
                if (playlist == null) {
                    playlist = BilibiliResolver.discoverPlaylist(url);
                    int initialIndex = requestedPlaylistIndex >= 0
                            ? requestedPlaylistIndex : playlist.startIndex() + playlistStartOffset;
                    playlistIndex = Math.max(0, Math.min(initialIndex, playlist.entries().size() - 1));
                    itemStartTime = targetTime;
                    CreateCinema.LOGGER.debug("Detected network playlist {} with {} entries, starting at {}", url,
                            playlist.entries().size(), playlistIndex + 1);
                }
                String mediaUrl = continuousPlaylist()
                        ? playlist.entries().get(playlistIndex).url() : url;
                source = BilibiliResolver.resolve(mediaUrl, quality);
                if (closed) return;
                progress = 0.38f;
                boolean hlsUrl = HlsStreamCache.isHls(source.videoUrl());
                if (hlsUrl && !HlsStreamCache.isPrepared(source.videoUrl())) {
                    HlsStreamCache.prepare(source.videoUrl(), source.referer());
                }
                boolean hls = hlsUrl && HlsStreamCache.isPrepared(source.videoUrl());
                double knownDuration = source.durationSeconds() > 0.0 ? source.durationSeconds()
                        : hls ? HlsStreamCache.duration(source.videoUrl()) : 0.0;
                double requestedStart = wrap(playbackTime(), knownDuration);
                double streamStart = hls ? HlsStreamCache.segmentStart(source.videoUrl(), requestedStart) : 0.0;
                InputStream hlsInput = hls ? HlsStreamCache.open(source.videoUrl(), source.referer(), requestedStart) : null;
                if (hls) HlsStreamCache.prefetch(source.videoUrl(), requestedStart, 3);
                try (InputStream input = hlsInput;
                     NativeFrameScaler scaler = new NativeFrameScaler();
                     FFmpegFrameGrabber grabber = hls ? new FFmpegFrameGrabber(input, 0)
                             : new FFmpegFrameGrabber(source.videoUrl())) {
                    configure(grabber, source.referer());
                    if (hls) grabber.setFormat("mpegts");
                    if (source.live() && source.videoUrl().contains(".flv")) {
                        grabber.setFormat("flv");
                        grabber.setOption("analyzeduration", "5000000");
                        grabber.setOption("probesize", "5000000");
                    }
                    grabber.setImageMode(FrameGrabber.ImageMode.RAW);
                    grabber.start();
                    if (closed) return;
                    progress = 0.60f;
                    duration = source.durationSeconds() > 0.0 ? source.durationSeconds()
                            : hls ? HlsStreamCache.duration(source.videoUrl())
                            : grabber.getLengthInTime() / 1_000_000.0;
                    double startTime = wrap(playbackTime(), duration);
                    if (!hls && duration > 0.0) grabber.setTimestamp((long) (startTime * 1_000_000.0));
                    long containerStartMicros = grabber.getFormatContext().start_time();
                    double timestampOrigin = hls || containerStartMicros == AV_NOPTS_VALUE ? Double.NaN
                            : containerStartMicros / 1_000_000.0;
                    progress = 0.72f;
                    double nextPublishTimestamp = -1.0;
                    double lastTimelineTimestamp = Double.NaN;
                    decodeLoop:
                    while (!closed) {
                        Frame frame = grabber.grabImage();
                        if (frame == null) {
                            if (source.live()) {
                                markBufferReady();
                                reconnect = true;
                                break;
                            }
                            if (continuousPlaylist()) {
                                markBufferReady();
                                awaitPlaybackEnd();
                                if (playlistIndex + 1 < playlist.entries().size()) advance = true;
                                else finishPlaylist();
                                break;
                            }
                            if (hls || duration <= 0.0) {
                                markBufferReady();
                                break;
                            }
                            grabber.setTimestamp(0L);
                            nextPublishTimestamp = -1.0;
                            continue;
                        }
                        double rawTimestamp = frame.timestamp / 1_000_000.0;
                        if (Double.isNaN(timestampOrigin)) {
                            timestampOrigin = rawTimestamp - (hls ? streamStart : startTime);
                        }
                        double timestamp = rawTimestamp - timestampOrigin;
                        if (duration > 0.0) timestamp = wrap(timestamp, duration);
                        if (duration <= 0.0 && !Double.isNaN(lastTimelineTimestamp)
                                && (timestamp < lastTimelineTimestamp - 1.0 || timestamp > lastTimelineTimestamp + 10.0)) {
                            timestampOrigin = rawTimestamp - lastTimelineTimestamp - 1.0 / Math.max(1.0, grabber.getFrameRate());
                            timestamp = rawTimestamp - timestampOrigin;
                            nextPublishTimestamp = -1.0;
                        }
                        lastTimelineTimestamp = timestamp;
                        while (!closed) {
                            double masterTime = wrap(playbackTime(), duration);
                            double drift = mediaDelta(timestamp, masterTime, duration);
                            if (!hls && duration > 0.0 && drift < -HARD_RESYNC_SECONDS) {
                                clearBufferedFrames(true);
                                grabber.setTimestamp((long) (masterTime * 1_000_000.0));
                                lastTimelineTimestamp = Double.NaN;
                                nextPublishTimestamp = -1.0;
                                continue decodeLoop;
                            }
                            if (drift <= MAX_BUFFER_SECONDS) break;
                            Thread.sleep(4L);
                        }
                        if (mediaDelta(timestamp, wrap(playbackTime(), duration), duration) < -0.12) continue;
                        if (nextPublishTimestamp < 0.0 || timestamp - nextPublishTimestamp > 0.5) {
                            nextPublishTimestamp = timestamp;
                        }
                        if (timestamp + 0.001 < nextPublishTimestamp) continue;
                        DecodedFrame next = scaler.decode(frame, quality.maxWidth(), quality.maxHeight());
                        if (next != null) {
                            if (closed) {
                                next.image.close();
                                break;
                            }
                            enqueue(new DecodedFrame(next.width, next.height, next.image, timestamp));
                            nextPublishTimestamp += 1.0 / quality.maxFps();
                        }
                    }
                    grabber.stop();
                }
            } catch (Throwable error) {
                if (!closed) {
                    progress = 0.0f;
                    failed = true;
                    errorMessage = visibleError(url, error);
                    String details = nestedMessage(error).toLowerCase(java.util.Locale.ROOT);
                    boolean configurationFailure = details.contains("douyin cookie is not configured")
                            || details.contains("douyin cookie is incomplete")
                            || details.contains("douyin browser authorization")
                            || details.contains("douyin recommendation authorization")
                            || details.contains("douyin authenticated")
                            || details.contains("requires verification");
                    retryAt = configurationFailure ? Long.MAX_VALUE : System.currentTimeMillis() + 5_000L;
                    if (configurationFailure) {
                        CreateCinema.LOGGER.warn("Network projector requires updated Douyin client authorization for {}", url);
                    } else {
                        CreateCinema.LOGGER.warn("Network projector failed to stream {}", url, error);
                    }
                }
            }
            if (advance && !closed) advancePlaylist();
            else if (reconnect && !closed) reconnectLiveStream();
        }

        private boolean continuousPlaylist() {
            return continuousEnabled && playlist != null && playlist.entries().size() > 1;
        }

        private void awaitPlaybackEnd() throws InterruptedException {
            while (!closed && duration > 0.0 && playbackTime() < duration - DISPLAY_LEAD_SECONDS) {
                Thread.sleep(4L);
            }
        }

        private void advancePlaylist() {
            playlistIndex++;
            mediaRevision = NEXT_MEDIA_REVISION.incrementAndGet();
            itemStartTime = targetTime;
            duration = 0.0;
            source = null;
            clearBufferedFrames(true);
            progress = 0.18f;
            CreateCinema.LOGGER.debug("Advancing network playlist {} to {}/{}", url,
                    playlistIndex + 1, playlist.entries().size());
            submitDecode();
        }

        private void finishPlaylist() {
            ended = true;
            mediaRevision = NEXT_MEDIA_REVISION.incrementAndGet();
            source = null;
            clearBufferedFrames(false);
            progress = 1.0f;
        }

        private void reconnectLiveStream() {
            mediaRevision = NEXT_MEDIA_REVISION.incrementAndGet();
            source = null;
            duration = 0.0;
            clearBufferedFrames(true);
            progress = 0.18f;
            try {
                Thread.sleep(1_000L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!closed) submitDecode();
        }

        private void submitDecode() {
            try {
                STREAM_EXECUTOR.execute(this::decode);
            } catch (RejectedExecutionException error) {
                failed = true;
                errorMessage = Component.translatable("gui.createcinema.stream.error.queue_full");
                retryAt = System.currentTimeMillis() + 5_000L;
            }
        }

        private static Component visibleError(String url, Throwable error) {
            String message = nestedMessage(error).toLowerCase(java.util.Locale.ROOT);
            if (message.contains("douyin cookie is not configured")) {
                return Component.translatable("gui.createcinema.stream.error.douyin_cookie_required");
            }
            if (message.contains("douyin browser authorization")) {
                return Component.translatable("gui.createcinema.stream.error.douyin_browser_required");
            }
            if (message.contains("douyin cookie is incomplete")) {
                return Component.translatable("gui.createcinema.stream.error.douyin_cookie_incomplete");
            }
            if (message.contains("douyin recommendation authorization")
                    || message.contains("douyin authenticated") || message.contains("requires verification")) {
                return Component.translatable("gui.createcinema.stream.error.douyin_cookie_invalid");
            }
            if (DouyinLiveResolver.canResolve(url)) {
                return Component.translatable("gui.createcinema.stream.error.douyin_live_auth");
            }
            if (DouyinVideoResolver.isHomepageFeed(url)) {
                return Component.translatable("gui.createcinema.stream.error.douyin_feed_login");
            }
            if (DouyinVideoResolver.isLongVideo(url)) {
                return Component.translatable("gui.createcinema.stream.error.douyin_long_video_restricted");
            }
            if (DouyinVideoResolver.canResolve(url)) {
                return Component.translatable("gui.createcinema.stream.error.douyin_public_only");
            }
            String host = host(url);
            if (host.endsWith("iqiyi.com") || host.endsWith("pps.tv")) {
                return Component.translatable("gui.createcinema.stream.error.unsupported_iqiyi");
            }
            if (host.endsWith("youku.com")) {
                return Component.translatable("gui.createcinema.stream.error.unsupported_youku");
            }
            if (message.contains("drm") || message.contains("login") || message.contains("vip")
                    || message.contains("directly playable") || message.contains("browser")) {
                return Component.translatable("gui.createcinema.stream.error.unsupported_web");
            }
            return Component.translatable("gui.createcinema.stream.error_short");
        }

        private static String host(String url) {
            try {
                String host = URI.create(url).getHost();
                return host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
            } catch (IllegalArgumentException e) {
                return "";
            }
        }

        private static String nestedMessage(Throwable error) {
            StringBuilder builder = new StringBuilder();
            for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
                if (cursor.getMessage() != null) builder.append(cursor.getMessage()).append('\n');
            }
            return builder.toString();
        }

        private NetworkProjectionFrame uploadReady() {
            DecodedFrame frame = null;
            if (bufferReady) {
                double masterTime = wrap(playbackTime(), duration);
                synchronized (bufferedFrames) {
                    while (!bufferedFrames.isEmpty()
                            && mediaDelta(bufferedFrames.getFirst().timestamp, masterTime, duration) <= DISPLAY_LEAD_SECONDS) {
                        if (frame != null) frame.image.close();
                        frame = bufferedFrames.removeFirst();
                    }
                }
            }
            if (frame != null) {
                if (texture == null || width != frame.width || height != frame.height) {
                    if (textureLocation != null) Minecraft.getInstance().getTextureManager().release(textureLocation);
                    textureLocation = ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID,
                            "network_stream/" + Integer.toUnsignedString(key.hashCode()));
                    texture = new DynamicTexture(frame.image);
                    Minecraft.getInstance().getTextureManager().register(textureLocation, texture);
                } else {
                    texture.setPixels(frame.image);
                    texture.upload();
                }
                width = frame.width;
                height = frame.height;
                uploadedFrameCount++;
                progress = 1.0f;
            }
            logVideoState();
            return textureLocation == null ? null : new NetworkProjectionFrame(textureLocation, width, height);
        }

        private void enqueue(DecodedFrame frame) throws InterruptedException {
            while (!closed) {
                synchronized (bufferedFrames) {
                    if (closed) break;
                    if (bufferedFrames.size() < MAX_BUFFERED_FRAMES) {
                        bufferedFrames.addLast(frame);
                        decodedFrameCount++;
                        if (!bufferReady) {
                            double buffered = bufferedFrames.size() < 2 ? 0.0
                                    : Math.max(0.0, mediaDelta(bufferedFrames.getLast().timestamp,
                                    bufferedFrames.getFirst().timestamp, duration));
                            progress = (float) Math.min(0.95, 0.72 + 0.23 * buffered / STARTUP_BUFFER_SECONDS);
                            if (buffered >= STARTUP_BUFFER_SECONDS || bufferedFrames.size() >= 10) markBufferReady();
                        }
                        return;
                    }
                }
                Thread.sleep(4L);
            }
            frame.image.close();
        }

        private void markBufferReady() {
            synchronized (bufferedFrames) {
                if (!bufferedFrames.isEmpty()) {
                    bufferReady = true;
                    progress = 0.95f;
                }
            }
        }

        private void clearBufferedFrames(boolean resetReady) {
            synchronized (bufferedFrames) {
                DecodedFrame frame;
                while ((frame = bufferedFrames.pollFirst()) != null) frame.image.close();
                if (resetReady) {
                    bufferReady = false;
                    progress = 0.72f;
                }
            }
        }

        private double playbackTime() {
            return Math.max(0.0, targetTime - itemStartTime);
        }

        private double mediaTime() {
            return wrap(playbackTime(), duration);
        }

        private void logVideoState() {
            long now = System.currentTimeMillis();
            if (now - lastVideoDiagnosticAt < 2_000L) return;
            lastVideoDiagnosticAt = now;
            synchronized (bufferedFrames) {
                String first = bufferedFrames.isEmpty() ? "-"
                        : String.format(java.util.Locale.ROOT, "%.3f", bufferedFrames.getFirst().timestamp);
                String last = bufferedFrames.isEmpty() ? "-"
                        : String.format(java.util.Locale.ROOT, "%.3f", bufferedFrames.getLast().timestamp);
                CreateCinema.LOGGER.debug(
                        "Network video revision {}: media={}s duration={}s ready={} queued={} [{}..{}] decoded={} uploaded={}",
                        mediaRevision,
                        String.format(java.util.Locale.ROOT, "%.3f", mediaTime()),
                        String.format(java.util.Locale.ROOT, "%.3f", duration),
                        bufferReady, bufferedFrames.size(), first, last, decodedFrameCount, uploadedFrameCount);
            }
        }

        private void close(String reason) {
            if (closed) return;
            closed = true;
            CreateCinema.LOGGER.debug("Closed network stream session {} revision {}: {}", url, mediaRevision, reason);
            clearBufferedFrames(false);
            if (textureLocation != null) Minecraft.getInstance().getTextureManager().release(textureLocation);
        }
    }

    static void configure(FFmpegFrameGrabber grabber, String referer) {
        grabber.setOption("user_agent", BilibiliResolver.USER_AGENT);
        if (referer != null && !referer.isBlank()) grabber.setOption("referer", referer);
        grabber.setOption("rw_timeout", NETWORK_RW_TIMEOUT_MICROS);
        grabber.setOption("timeout", NETWORK_RW_TIMEOUT_MICROS);
        grabber.setOption("stimeout", NETWORK_RW_TIMEOUT_MICROS);
        grabber.setOption("reconnect", "1");
        grabber.setOption("reconnect_streamed", "1");
        grabber.setOption("reconnect_on_network_error", "1");
        grabber.setOption("reconnect_delay_max", "2");
        grabber.setOption("reconnect_max_retries", "2");
    }

    private static class NativeFrameScaler implements AutoCloseable {
        private SwsContext context;
        private BytePointer output;
        private PointerPointer<BytePointer> outputPlanes;
        private IntPointer outputStrides;
        private int sourceWidth;
        private int sourceHeight;
        private int sourceFormat;
        private int width;
        private int height;

        private DecodedFrame decode(Frame frame, int maxWidth, int maxHeight) throws IOException {
            if (!(frame.opaque instanceof AVFrame raw)) return null;
            int rawWidth = raw.width();
            int rawHeight = raw.height();
            if (rawWidth <= 0 || rawHeight <= 0) return null;
            double factor = Math.min(1.0, Math.min(maxWidth / (double) rawWidth, maxHeight / (double) rawHeight));
            int targetWidth = Math.max(1, (int) Math.round(rawWidth * factor));
            int targetHeight = Math.max(1, (int) Math.round(rawHeight * factor));
            ensure(rawWidth, rawHeight, raw.format(), targetWidth, targetHeight);
            int rows = sws_scale(context, raw.data(), raw.linesize(), 0, rawHeight, outputPlanes, outputStrides);
            if (rows != targetHeight) throw new IOException("FFmpeg scaled " + rows + " of " + targetHeight + " rows");

            NativeImage image = new NativeImage(targetWidth, targetHeight, false);
            try {
                IntBuffer pixels = output.position(0).capacity((long) targetWidth * targetHeight * 4L)
                        .asByteBuffer().order(ByteOrder.nativeOrder()).asIntBuffer();
                for (int y = 0; y < targetHeight; y++) {
                    for (int x = 0; x < targetWidth; x++) image.setPixelRGBA(x, y, pixels.get());
                }
                return new DecodedFrame(targetWidth, targetHeight, image);
            } catch (Throwable error) {
                image.close();
                throw error;
            }
        }

        private void ensure(int rawWidth, int rawHeight, int rawFormat, int targetWidth, int targetHeight)
                throws IOException {
            if (context != null && sourceWidth == rawWidth && sourceHeight == rawHeight && sourceFormat == rawFormat
                    && width == targetWidth && height == targetHeight) return;
            close();
            context = sws_getContext(rawWidth, rawHeight, rawFormat, targetWidth, targetHeight,
                    AV_PIX_FMT_RGBA, SWS_BILINEAR, null, null, (double[]) null);
            if (context == null || context.isNull()) throw new IOException("FFmpeg could not create a video scaler");
            output = new BytePointer((long) targetWidth * targetHeight * 4L);
            outputPlanes = new PointerPointer<>(4);
            outputPlanes.put(0, output);
            outputStrides = new IntPointer(4);
            outputStrides.put(0, targetWidth * 4);
            sourceWidth = rawWidth;
            sourceHeight = rawHeight;
            sourceFormat = rawFormat;
            width = targetWidth;
            height = targetHeight;
        }

        @Override
        public void close() {
            if (context != null) sws_freeContext(context);
            if (output != null) output.close();
            if (outputPlanes != null) outputPlanes.close();
            if (outputStrides != null) outputStrides.close();
            context = null;
            output = null;
            outputPlanes = null;
            outputStrides = null;
        }
    }

    private static double wrap(double value, double duration) {
        if (duration <= 0.0) return value;
        double wrapped = value % duration;
        return wrapped < 0.0 ? wrapped + duration : wrapped;
    }

    private static double mediaDelta(double value, double reference, double duration) {
        double delta = value - reference;
        if (duration > 0.0) {
            if (delta > duration / 2.0) delta -= duration;
            else if (delta < -duration / 2.0) delta += duration;
        }
        return delta;
    }

    private record DecodedFrame(int width, int height, NativeImage image, double timestamp) {
        private DecodedFrame(int width, int height, NativeImage image) {
            this(width, height, image, 0.0);
        }
    }

    public enum Status {
        IDLE,
        LOADING,
        PLAYING,
        ENDED,
        ERROR
    }
}
