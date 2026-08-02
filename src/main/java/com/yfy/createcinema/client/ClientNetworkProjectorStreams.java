package com.yfy.createcinema.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.blockentity.NetworkProjectorBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

import java.nio.ByteBuffer;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.bytedeco.ffmpeg.global.avutil.*;

import java.io.IOException;

public final class ClientNetworkProjectorStreams {
    private static final int MAX_STREAM_WIDTH = 854;
    private static final int MAX_STREAM_HEIGHT = 480;
    private static final double MAX_UPLOAD_FPS = 20.0;
    private static final int MAX_BUFFERED_FRAMES = 32;
    private static final double STARTUP_BUFFER_SECONDS = 0.60;
    private static final double MAX_BUFFER_SECONDS = 1.50;
    private static final double HARD_RESYNC_SECONDS = 3.0;
    private static final double DISPLAY_LEAD_SECONDS = 0.035;
    private static final String NETWORK_RW_TIMEOUT_MICROS = "5000000";
    private static final Map<String, Session> SESSIONS = new HashMap<>();
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
            session.close();
            SESSIONS.remove(key);
            session = null;
        }
        if (session == null || !session.url.equals(projector.getUrl())) {
            if (session != null) session.close();
            session = new Session(key, projector.getUrl(), projector.getPlayTime());
            SESSIONS.put(key, session);
        }
        session.targetTime = playTime;
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

    public static Status status(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return Status.IDLE;
        Session session = SESSIONS.get(minecraft.level.dimension().location() + "/" + pos.asLong());
        if (session == null) return Status.IDLE;
        if (session.failed) return Status.ERROR;
        if (session.textureLocation == null) {
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
        return Component.translatable("gui.createcinema.stream.loading_progress", Math.round(session.progress * 100.0f));
    }

    public static void requestRetry(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        Session session = SESSIONS.get(minecraft.level.dimension().location() + "/" + pos.asLong());
        if (session != null) session.retryAt = 0L;
    }

    public static void stop(NetworkProjectorBlockEntity projector) {
        if (projector.getLevel() == null) return;
        String key = projector.getLevel().dimension().location() + "/" + projector.getBlockPos().asLong();
        Session session = SESSIONS.remove(key);
        if (session != null) session.close();
    }

    public static void sweep() {
        long cutoff = System.currentTimeMillis() - 10_000L;
        SESSIONS.entrySet().removeIf(entry -> {
            if (entry.getValue().lastTouched >= cutoff) return false;
            entry.getValue().close();
            return true;
        });
    }

    public static void closeAll() {
        SESSIONS.values().forEach(Session::close);
        SESSIONS.clear();
        HlsStreamCache.clear();
    }

    private static class Session {
        private final String key;
        private final String url;
        private final ArrayDeque<DecodedFrame> bufferedFrames = new ArrayDeque<>();
        private volatile BilibiliResolver.ResolvedMedia source;
        private volatile double targetTime;
        private volatile double duration;
        private volatile long lastTouched = System.currentTimeMillis();
        private volatile long lastFrameAt;
        private volatile boolean closed;
        private volatile boolean failed;
        private volatile long retryAt;
        private volatile float progress = 0.03f;
        private volatile Component errorMessage;
        private volatile boolean bufferReady;
        private volatile double mediaOffset;
        private DynamicTexture texture;
        private ResourceLocation textureLocation;
        private int width;
        private int height;

        private Session(String key, String url, double startTime) {
            this.key = key;
            this.url = url;
            targetTime = startTime;
            try {
                STREAM_EXECUTOR.execute(this::decode);
            } catch (RejectedExecutionException error) {
                failed = true;
                errorMessage = Component.translatable("gui.createcinema.stream.error.queue_full");
                retryAt = System.currentTimeMillis() + 5_000L;
            }
        }

        private void decode() {
            try {
                progress = 0.08f;
                ClientVideoBurner.awaitFfmpeg();
                if (closed) return;
                progress = 0.18f;
                source = BilibiliResolver.resolve(url);
                if (closed) return;
                progress = 0.38f;
                boolean hls = HlsStreamCache.isHls(source.videoUrl());
                try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(source.videoUrl())) {
                    configure(grabber, source.referer());
                    grabber.setImageMode(FrameGrabber.ImageMode.RAW);
                    grabber.start();
                    if (closed) return;
                    progress = 0.60f;
                    duration = source.durationSeconds() > 0.0 ? source.durationSeconds()
                            : grabber.getLengthInTime() / 1_000_000.0;
                    double startTime = wrap(playbackTime(), duration);
                    mediaOffset = hls ? startTime : 0.0;
                    if (hls) HlsStreamCache.prefetch(source.videoUrl(), 0.0, 3);
                    if (!hls && duration > 0.0) grabber.setTimestamp((long) (startTime * 1_000_000.0));
                    long containerStartMicros = grabber.getFormatContext().start_time();
                    double timestampOrigin = containerStartMicros == AV_NOPTS_VALUE ? Double.NaN
                            : containerStartMicros / 1_000_000.0 - (hls ? startTime : 0.0);
                    progress = 0.72f;
                    double nextPublishTimestamp = -1.0;
                    double lastTimelineTimestamp = Double.NaN;
                    decodeLoop:
                    while (!closed) {
                        Frame frame = grabber.grabImage();
                        if (frame == null) {
                            if (hls || duration <= 0.0) {
                                markBufferReady();
                                break;
                            }
                            grabber.setTimestamp(0L);
                            nextPublishTimestamp = -1.0;
                            continue;
                        }
                        double rawTimestamp = frame.timestamp / 1_000_000.0;
                        if (Double.isNaN(timestampOrigin)) timestampOrigin = rawTimestamp - startTime;
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
                        DecodedFrame next = decodeRawFrame(frame);
                        if (next != null) {
                            if (closed) {
                                next.image.close();
                                break;
                            }
                            enqueue(new DecodedFrame(next.width, next.height, next.image, timestamp));
                            nextPublishTimestamp += 1.0 / MAX_UPLOAD_FPS;
                        }
                    }
                    grabber.stop();
                }
            } catch (Throwable error) {
                if (!closed) {
                    progress = 0.0f;
                    failed = true;
                    errorMessage = visibleError(url, error);
                    retryAt = System.currentTimeMillis() + 5_000L;
                    CreateCinema.LOGGER.warn("Network projector failed to stream {}", url, error);
                }
            }
        }

        private static Component visibleError(String url, Throwable error) {
            String host = host(url);
            if (host.endsWith("iqiyi.com") || host.endsWith("pps.tv")) {
                return Component.translatable("gui.createcinema.stream.error.unsupported_iqiyi");
            }
            if (host.endsWith("youku.com")) {
                return Component.translatable("gui.createcinema.stream.error.unsupported_youku");
            }
            String message = nestedMessage(error).toLowerCase(java.util.Locale.ROOT);
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
                lastFrameAt = System.currentTimeMillis();
                progress = 1.0f;
            }
            return textureLocation == null ? null : new NetworkProjectionFrame(textureLocation, width, height);
        }

        private void enqueue(DecodedFrame frame) {
            synchronized (bufferedFrames) {
                if (closed) {
                    frame.image.close();
                    return;
                }
                while (bufferedFrames.size() >= MAX_BUFFERED_FRAMES) bufferedFrames.removeFirst().image.close();
                bufferedFrames.addLast(frame);
                if (!bufferReady) {
                    double buffered = bufferedFrames.size() < 2 ? 0.0
                            : Math.max(0.0, mediaDelta(bufferedFrames.getLast().timestamp,
                            bufferedFrames.getFirst().timestamp, duration));
                    progress = (float) Math.min(0.95, 0.72 + 0.23 * buffered / STARTUP_BUFFER_SECONDS);
                    if (buffered >= STARTUP_BUFFER_SECONDS || bufferedFrames.size() >= 10) markBufferReady();
                }
            }
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
            return targetTime;
        }

        private double mediaTime() {
            return wrap(Math.max(0.0, targetTime - mediaOffset), duration);
        }

        private void close() {
            closed = true;
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

    private static DecodedFrame decodeRawFrame(Frame frame) throws IOException {
        if (!(frame.opaque instanceof AVFrame raw)) return null;
        int sourceWidth = raw.width();
        int sourceHeight = raw.height();
        if (sourceWidth <= 0 || sourceHeight <= 0) return null;
        double factor = Math.min(1.0, Math.min(MAX_STREAM_WIDTH / (double) sourceWidth,
                MAX_STREAM_HEIGHT / (double) sourceHeight));
        int width = Math.max(1, (int) Math.round(sourceWidth * factor));
        int height = Math.max(1, (int) Math.round(sourceHeight * factor));
        RawFrameData data = rawFrameData(raw);
        NativeImage image = new NativeImage(width, height, false);
        try {
            for (int y = 0; y < height; y++) {
                int sourceY = Math.min(sourceHeight - 1, y * sourceHeight / height);
                for (int x = 0; x < width; x++) {
                    int sourceX = Math.min(sourceWidth - 1, x * sourceWidth / width);
                    image.setPixelRGBA(x, y, FastColor.ABGR32.fromArgb32(rawPixel(data, sourceX, sourceY)));
                }
            }
            return new DecodedFrame(width, height, image);
        } catch (Throwable error) {
            image.close();
            throw error;
        }
    }

    private static int rawPixel(RawFrameData frame, int x, int y) throws IOException {
        int format = frame.format;
        if (format == AV_PIX_FMT_YUV420P || format == AV_PIX_FMT_YUVJ420P) return yuvPixel(frame, x, y, 1, 1);
        if (format == AV_PIX_FMT_YUV422P || format == AV_PIX_FMT_YUVJ422P) return yuvPixel(frame, x, y, 1, 0);
        if (format == AV_PIX_FMT_YUV444P || format == AV_PIX_FMT_YUVJ444P) return yuvPixel(frame, x, y, 0, 0);
        if (format == AV_PIX_FMT_NV12 || format == AV_PIX_FMT_NV21) {
            int luminance = planeByte(frame, 0, x, y);
            int chromaX = (x >> 1) * 2;
            int chromaY = y >> 1;
            int first = planeByte(frame, 1, chromaX, chromaY);
            int second = planeByte(frame, 1, chromaX + 1, chromaY);
            return yuvToArgb(luminance, format == AV_PIX_FMT_NV12 ? first : second,
                    format == AV_PIX_FMT_NV12 ? second : first, frame.fullRange, frame.bt709);
        }
        if (format == AV_PIX_FMT_GRAY8) {
            int value = planeByte(frame, 0, x, y);
            return argb(value, value, value);
        }
        if (format == AV_PIX_FMT_GBRP) {
            return argb(planeByte(frame, 2, x, y), planeByte(frame, 0, x, y), planeByte(frame, 1, x, y));
        }
        if (format == AV_PIX_FMT_RGB24) return packedRgb(frame, x, y, 3, 0, 1, 2);
        if (format == AV_PIX_FMT_BGR24) return packedRgb(frame, x, y, 3, 2, 1, 0);
        if (format == AV_PIX_FMT_RGBA) return packedRgb(frame, x, y, 4, 0, 1, 2);
        if (format == AV_PIX_FMT_BGRA) return packedRgb(frame, x, y, 4, 2, 1, 0);
        if (format == AV_PIX_FMT_ARGB) return packedRgb(frame, x, y, 4, 1, 2, 3);
        if (format == AV_PIX_FMT_ABGR) return packedRgb(frame, x, y, 4, 3, 2, 1);
        throw new IOException("Unsupported network video pixel format: " + format);
    }

    private static int yuvPixel(RawFrameData frame, int x, int y, int horizontalShift, int verticalShift) {
        int luminance = planeByte(frame, 0, x, y);
        int chromaX = x >> horizontalShift;
        int chromaY = y >> verticalShift;
        return yuvToArgb(luminance, planeByte(frame, 1, chromaX, chromaY),
                planeByte(frame, 2, chromaX, chromaY), frame.fullRange, frame.bt709);
    }

    private static int yuvToArgb(int y, int u, int v, boolean fullRange, boolean bt709) {
        int d = u - 128;
        int e = v - 128;
        int red;
        int green;
        int blue;
        if (fullRange) {
            red = y + ((bt709 ? 403 : 359) * e >> 8);
            green = y - (((bt709 ? 48 : 88) * d + (bt709 ? 120 : 183) * e) >> 8);
            blue = y + ((bt709 ? 475 : 454) * d >> 8);
        } else {
            int c = Math.max(0, y - 16);
            red = (298 * c + (bt709 ? 459 : 409) * e + 128) >> 8;
            green = (298 * c - (bt709 ? 55 : 100) * d - (bt709 ? 136 : 208) * e + 128) >> 8;
            blue = (298 * c + (bt709 ? 541 : 516) * d + 128) >> 8;
        }
        return argb(red, green, blue);
    }

    private static int packedRgb(RawFrameData frame, int x, int y, int bytesPerPixel, int redOffset, int greenOffset, int blueOffset) {
        int offset = y * frame.strides[0] + x * bytesPerPixel;
        return argb(unsigned(frame.planes[0], offset + redOffset), unsigned(frame.planes[0], offset + greenOffset),
                unsigned(frame.planes[0], offset + blueOffset));
    }

    private static int planeByte(RawFrameData frame, int plane, int x, int y) {
        return unsigned(frame.planes[plane], y * frame.strides[plane] + x);
    }

    private static RawFrameData rawFrameData(AVFrame frame) throws IOException {
        ByteBuffer[] planes = new ByteBuffer[4];
        int[] strides = new int[4];
        for (int plane = 0; plane < planes.length; plane++) {
            BytePointer pointer = frame.data(plane);
            if (pointer == null || pointer.isNull()) continue;
            int stride = frame.linesize(plane);
            if (stride < 0) throw new IOException("Negative network video stride is not supported");
            strides[plane] = stride;
            planes[plane] = pointer.position(0).capacity((long) stride * frame.height()).asByteBuffer();
        }
        return new RawFrameData(frame.format(), planes, strides, isFullRange(frame), isBt709(frame));
    }

    private static int unsigned(ByteBuffer data, int offset) {
        return data.get(offset) & 0xFF;
    }

    private static boolean isFullRange(AVFrame frame) {
        int format = frame.format();
        return frame.color_range() == AVCOL_RANGE_JPEG || format == AV_PIX_FMT_YUVJ420P
                || format == AV_PIX_FMT_YUVJ422P || format == AV_PIX_FMT_YUVJ444P;
    }

    private static boolean isBt709(AVFrame frame) {
        return frame.colorspace() == AVCOL_SPC_BT709;
    }

    private static int argb(int red, int green, int blue) {
        return 0xFF000000 | clamp(red) << 16 | clamp(green) << 8 | clamp(blue);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
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

    private record RawFrameData(int format, ByteBuffer[] planes, int[] strides, boolean fullRange, boolean bt709) {
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
        ERROR
    }
}
