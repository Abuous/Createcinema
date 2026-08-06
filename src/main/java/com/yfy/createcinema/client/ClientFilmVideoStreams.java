package com.yfy.createcinema.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.yfy.createcinema.ClientConfig;
import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.blockentity.ProjectorBlockEntity;
import com.yfy.createcinema.film.FilmMetadata;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264;
import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBA;

public final class ClientFilmVideoStreams {
    private static final int MAX_BUFFERED_FRAMES = 12;
    private static final long MAX_BUFFERED_BYTES = 48L * 1024L * 1024L;
    private static final double BUFFER_AHEAD_SECONDS = 0.80;
    private static final double HARD_SEEK_SECONDS = 1.5;
    private static final double DISPLAY_LEAD_SECONDS = 0.035;
    private static final long STALE_SESSION_MILLIS = 30_000L;
    private static final Map<String, Session> SESSIONS = new HashMap<>();
    private static final Field NATIVE_IMAGE_PIXELS = findNativeImagePixels();
    private static final ExecutorService DECODER_EXECUTOR = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "CreateCinema Film Video Decode");
        thread.setDaemon(true);
        return thread;
    });

    private ClientFilmVideoStreams() {
    }

    public static ResourceLocation frame(ProjectorBlockEntity projector, FilmMetadata metadata, double playTime) {
        if (projector.getLevel() == null || metadata.formatVersion() < 3) return null;
        String key = sessionKey(projector);
        Session session = SESSIONS.get(key);
        if (session == null || !session.filmId.equals(metadata.id())) {
            if (session != null) session.close();
            Path video = ClientFilmCache.videoPath(metadata.id());
            if (!Files.isRegularFile(video)) {
                ClientFilmCache.request(metadata.id());
                return null;
            }
            session = new Session(key, metadata.id(), video, playTime);
            SESSIONS.put(key, session);
        }
        session.targetTime = Math.max(0.0, Math.min(playTime, metadata.durationSeconds()));
        session.lastTouched = System.currentTimeMillis();
        return session.uploadReady();
    }

    public static void stop(ProjectorBlockEntity projector) {
        if (projector.getLevel() == null) return;
        Session removed = SESSIONS.remove(sessionKey(projector));
        if (removed != null) removed.close();
    }

    public static void invalidateFilm(String filmId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> invalidateFilm(filmId));
            return;
        }
        SESSIONS.entrySet().removeIf(entry -> {
            if (!entry.getValue().filmId.equals(filmId)) return false;
            entry.getValue().close();
            return true;
        });
    }

    public static void tick() {
        long cutoff = System.currentTimeMillis() - STALE_SESSION_MILLIS;
        SESSIONS.entrySet().removeIf(entry -> {
            if (entry.getValue().lastTouched >= cutoff) return false;
            entry.getValue().close();
            return true;
        });
    }

    public static void closeAll() {
        SESSIONS.values().forEach(Session::close);
        SESSIONS.clear();
    }

    private static String sessionKey(ProjectorBlockEntity projector) {
        return Integer.toUnsignedString(System.identityHashCode(projector.getLevel())) + "/"
                + projector.getLevel().dimension().location() + "/" + projector.getBlockPos().asLong();
    }

    private static final class Session {
        private final String key;
        private final String filmId;
        private final Path video;
        private final ArrayDeque<DecodedFrame> frames = new ArrayDeque<>();
        private volatile double targetTime;
        private volatile long lastTouched = System.currentTimeMillis();
        private volatile boolean closed;
        private volatile boolean failed;
        private DynamicTexture texture;
        private ResourceLocation textureLocation;
        private int width;
        private int height;

        private Session(String key, String filmId, Path video, double targetTime) {
            this.key = key;
            this.filmId = filmId;
            this.video = video;
            this.targetTime = targetTime;
            DECODER_EXECUTOR.execute(this::decode);
        }

        private void decode() {
            try {
                ClientVideoBurner.awaitFfmpeg();
            } catch (Throwable error) {
                failed = true;
                CreateCinema.LOGGER.warn("Could not load FFmpeg for H.264 film {}", filmId, error);
                return;
            }
            List<String> decoders = new ArrayList<>();
            if (ClientConfig.projectorHardwareDecoding()) {
                for (String decoder : ClientVideoBurner.hardwareDecoders(AV_CODEC_ID_H264)) {
                    if (ClientVideoBurner.decoderAvailable(decoder)) decoders.add(decoder);
                }
            }
            decoders.add("");
            Throwable lastError = null;
            for (String decoder : decoders) {
                if (closed) return;
                try {
                    decodeWith(decoder.isEmpty() ? null : decoder);
                    return;
                } catch (Throwable error) {
                    lastError = error;
                    clearFrames();
                    if (!closed && !decoder.isEmpty()) {
                        CreateCinema.LOGGER.debug("Film hardware decoder {} failed for {}; trying fallback",
                                decoder, filmId, error);
                    }
                }
            }
            if (!closed) {
                failed = true;
                CreateCinema.LOGGER.warn("Could not decode H.264 film {}", filmId, lastError);
            }
        }

        private void decodeWith(String decoder) throws Exception {
            try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video.toFile())) {
                if (decoder != null) grabber.setVideoCodecName(decoder);
                grabber.setAudioStream(-1);
                grabber.setVideoOption("threads", "0");
                grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
                grabber.setPixelFormat(AV_PIX_FMT_RGBA);
                grabber.start();
                double seekTarget = targetTime;
                if (seekTarget > 0.05) grabber.setTimestamp((long) (seekTarget * 1_000_000.0));
                Frame frame = grabber.grabImage();
                if (frame == null || frame.image == null) throw new IOException("Decoder returned no first frame");
                CreateCinema.LOGGER.info("Playing H.264 film {} with {} decoder", filmId,
                        decoder == null ? "FFmpeg software" : decoder);
                while (!closed && frame != null) {
                    double timestamp = frame.timestamp / 1_000_000.0;
                    double target = targetTime;
                    if (Math.abs(target - timestamp) > HARD_SEEK_SECONDS) {
                        clearFrames();
                        grabber.setTimestamp((long) (target * 1_000_000.0));
                        frame = grabber.grabImage();
                        continue;
                    }
                    if (timestamp >= target - 0.15) {
                        waitForQueue(target, timestamp, maxBufferedFrames(frame.imageWidth, frame.imageHeight));
                        if (closed) break;
                        enqueue(new DecodedFrame(timestamp, copyFrame(frame)));
                    }
                    frame = grabber.grabImage();
                }
                grabber.stop();
            }
        }

        private void waitForQueue(double target, double timestamp, int maxFrames) throws InterruptedException {
            while (!closed) {
                synchronized (frames) {
                    if (frames.size() < maxFrames && timestamp <= target + BUFFER_AHEAD_SECONDS) return;
                }
                Thread.sleep(3L);
                target = targetTime;
            }
        }

        private static int maxBufferedFrames(int width, int height) {
            long frameBytes = Math.max(1L, (long) width * height * 4L);
            return Math.max(3, Math.min(MAX_BUFFERED_FRAMES, (int) (MAX_BUFFERED_BYTES / frameBytes)));
        }

        private void enqueue(DecodedFrame frame) {
            synchronized (frames) {
                if (closed) frame.image.close();
                else frames.addLast(frame);
            }
        }

        private ResourceLocation uploadReady() {
            if (failed) return textureLocation;
            DecodedFrame selected = null;
            synchronized (frames) {
                while (!frames.isEmpty() && frames.getFirst().timestamp <= targetTime + DISPLAY_LEAD_SECONDS) {
                    if (selected != null) selected.image.close();
                    selected = frames.removeFirst();
                }
            }
            if (selected == null) return textureLocation;
            NativeImage image = selected.image;
            if (texture == null || width != image.getWidth() || height != image.getHeight()) {
                if (textureLocation != null) Minecraft.getInstance().getTextureManager().release(textureLocation);
                textureLocation = ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID,
                        "film_video/" + Integer.toUnsignedString(key.hashCode()));
                texture = new DynamicTexture(image);
                Minecraft.getInstance().getTextureManager().register(textureLocation, texture);
            } else {
                texture.setPixels(image);
                texture.upload();
            }
            width = image.getWidth();
            height = image.getHeight();
            return textureLocation;
        }

        private void clearFrames() {
            synchronized (frames) {
                DecodedFrame frame;
                while ((frame = frames.pollFirst()) != null) frame.image.close();
            }
        }

        private void close() {
            if (closed) return;
            closed = true;
            clearFrames();
            if (textureLocation != null) Minecraft.getInstance().getTextureManager().release(textureLocation);
            texture = null;
            textureLocation = null;
        }
    }

    private static NativeImage copyFrame(Frame frame) throws IOException {
        if (frame.imageWidth <= 0 || frame.imageHeight <= 0 || frame.image == null || frame.image.length == 0
                || !(frame.image[0] instanceof ByteBuffer sourceBuffer)) {
            throw new IOException("Decoded film frame is not RGBA image data");
        }
        int width = frame.imageWidth;
        int height = frame.imageHeight;
        int stride = frame.imageStride > 0 ? frame.imageStride : width * 4;
        ByteBuffer source = sourceBuffer.duplicate();
        source.position(0);
        NativeImage image = new NativeImage(width, height, false);
        try {
            if (NATIVE_IMAGE_PIXELS != null && source.isDirect()) {
                long sourceAddress = MemoryUtil.memAddress(source);
                long targetAddress = NATIVE_IMAGE_PIXELS.getLong(image);
                if (stride == width * 4) {
                    MemoryUtil.memCopy(sourceAddress, targetAddress, (long) width * height * 4L);
                } else {
                    for (int y = 0; y < height; y++) {
                        MemoryUtil.memCopy(sourceAddress + (long) y * stride,
                                targetAddress + (long) y * width * 4L, (long) width * 4L);
                    }
                }
            } else {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int offset = y * stride + x * 4;
                        int red = source.get(offset) & 0xFF;
                        int green = source.get(offset + 1) & 0xFF;
                        int blue = source.get(offset + 2) & 0xFF;
                        int alpha = source.get(offset + 3) & 0xFF;
                        image.setPixelRGBA(x, y, FastColor.ABGR32.color(alpha, blue, green, red));
                    }
                }
            }
            return image;
        } catch (Throwable error) {
            image.close();
            throw new IOException("Could not copy decoded H.264 film frame", error);
        }
    }

    private static Field findNativeImagePixels() {
        try {
            Field field = NativeImage.class.getDeclaredField("pixels");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private record DecodedFrame(double timestamp, NativeImage image) {
    }
}
