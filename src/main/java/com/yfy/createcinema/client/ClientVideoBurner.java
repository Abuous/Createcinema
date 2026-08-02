package com.yfy.createcinema.client;

import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.film.FilmMetadata;
import com.yfy.createcinema.film.FilmQuality;
import com.yfy.createcinema.film.FilmStorage;
import com.yfy.createcinema.packet.C2SBurnStatePacket;
import com.yfy.createcinema.packet.C2SUploadFilmChunkPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ClientVideoBurner {
    private static final int MAX_SECONDS = 600;
    private static final int CHUNK_SIZE = 900_000;
    private static final Map<BlockPos, BurnJob> ACTIVE_JOBS = new ConcurrentHashMap<>();
    private static final ExecutorService BURN_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "CreateCinema Video Worker");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean FFMPEG_LOAD_STARTED = new AtomicBoolean();
    private static final CompletableFuture<Void> FFMPEG_READY = new CompletableFuture<>();

    public static void preloadFfmpeg() {
        if (!FFMPEG_LOAD_STARTED.compareAndSet(false, true)) return;
        BURN_EXECUTOR.execute(() -> {
            try {
                Path cacheDir = Minecraft.getInstance().gameDirectory.toPath()
                        .resolve("createcinema").resolve("native-cache");
                Files.createDirectories(cacheDir);
                System.setProperty("org.bytedeco.javacpp.cachedir", cacheDir.toAbsolutePath().toString());
                FFmpegFrameGrabber.tryLoad();
                FFMPEG_READY.complete(null);
                CreateCinema.LOGGER.info("Embedded FFmpeg is ready");
            } catch (Throwable error) {
                FFMPEG_READY.completeExceptionally(error);
                CreateCinema.LOGGER.error("Failed to load embedded FFmpeg", error);
            }
        });
    }

    public static void awaitFfmpeg() {
        preloadFfmpeg();
        FFMPEG_READY.join();
    }

    public static void startBurn(BlockPos burnerPos, String pathText, FilmQuality quality) {
        cancel(burnerPos, "Starting new burn");
        BurnJob job = new BurnJob();
        ACTIVE_JOBS.put(burnerPos, job);
        new C2SBurnStatePacket(burnerPos, true).send();
        ClientPacketHandlers.setBurnProgress(burnerPos, "Preparing burn", 0.02f, true);
        BURN_EXECUTOR.execute(() -> {
            Path path;
            try {
                checkCancelled(job);
                path = Path.of(normalizePathInput(pathText));
            } catch (InvalidPathException e) {
                CreateCinema.LOGGER.warn("Invalid video path: {}", pathText, e);
                ClientPacketHandlers.setBurnProgress(burnerPos, "Invalid path: " + e.getInput(), 0.0f, false);
                ACTIVE_JOBS.remove(burnerPos, job);
                new C2SBurnStatePacket(burnerPos, false).send();
                return;
            }

            try {
                checkCancelled(job);
                if (!Files.isRegularFile(path)) {
                    ClientPacketHandlers.setBurnProgress(burnerPos, "File not found", 0.0f, false);
                    ACTIVE_JOBS.remove(burnerPos, job);
                    new C2SBurnStatePacket(burnerPos, false).send();
                    return;
                }
                ClientPacketHandlers.setBurnProgress(burnerPos, "Loading FFmpeg native libraries", 0.03f, true);
                preloadFfmpeg();
                FFMPEG_READY.join();
                checkCancelled(job);
                byte[] zip = createFilmZip(path, burnerPos, quality, job);
                checkCancelled(job);
                cacheFilmLocally(zip);
                job.waitingForServer.set(true);
                upload(burnerPos, zip, job);
            } catch (CancellationException e) {
                if (ACTIVE_JOBS.remove(burnerPos, job)) {
                    ClientPacketHandlers.setBurnProgress(burnerPos, "Burn cancelled", 0.0f, false);
                }
            } catch (Throwable error) {
                CreateCinema.LOGGER.warn("Failed to burn video {}", path, error);
                String detail = error.getMessage();
                if (detail == null || detail.isBlank()) detail = error.getClass().getSimpleName();
                ClientPacketHandlers.setBurnProgress(burnerPos, "Burn failed: " + detail, 0.0f, false);
                ACTIVE_JOBS.remove(burnerPos, job);
                new C2SBurnStatePacket(burnerPos, false).send();
            }
        });
    }

    public static void cancel(BlockPos burnerPos, String message) {
        BurnJob job = ACTIVE_JOBS.get(burnerPos);
        if (job == null) return;
        job.cancelled.set(true);
        new C2SBurnStatePacket(burnerPos, false).send();
        if (!job.waitingForServer.get()) {
            ClientPacketHandlers.setBurnProgress(burnerPos, message, 0.0f, false);
        }
    }

    public static void finish(BlockPos burnerPos) {
        BurnJob job = ACTIVE_JOBS.remove(burnerPos);
        if (job != null) job.cancelled.set(true);
    }

    private static String normalizePathInput(String pathText) {
        String path = pathText.trim();
        if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
            path = path.substring(1, path.length() - 1).trim();
        }
        return path;
    }

    private static byte[] createFilmZip(Path videoPath, BlockPos burnerPos, FilmQuality quality, BurnJob job) throws Exception {
        ClientPacketHandlers.setBurnProgress(burnerPos, "Creating temporary workspace", 0.04f, true);
        Path tempDir = Files.createTempDirectory("createcinema-film-");
        try {
            return createFilmZip(videoPath, burnerPos, tempDir, quality, job);
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static byte[] createFilmZip(Path videoPath, BlockPos burnerPos, Path tempDir, FilmQuality quality, BurnJob job) throws Exception {
        String filmId = UUID.randomUUID().toString();
        String title = videoPath.getFileName().toString();
        Path framesDir = tempDir.resolve("frames");
        Files.createDirectories(framesDir);
        int frameCount = 0;
        int width = quality.maxWidth();
        int height = quality.maxHeight();
        double totalDurationSeconds = MAX_SECONDS;
        ImageIO.setUseCache(false);

        ClientPacketHandlers.setBurnProgress(burnerPos, "Opening video", 0.05f, true);
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoPath.toFile());
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            checkCancelled(job);
            grabber.start();
            long lengthInTime = grabber.getLengthInTime();
            if (lengthInTime > 0) {
                totalDurationSeconds = Math.min(MAX_SECONDS, lengthInTime / 1_000_000.0);
            }
            int maxFrames = quality.fps() * MAX_SECONDS;
            long nextFrameTimestamp = 0;
            double lastProgressSecond = -1.0;
            Frame frame;
            while ((frame = grabber.grabImage()) != null && frameCount < maxFrames) {
                checkCancelled(job);
                if (frame.timestamp > MAX_SECONDS * 1_000_000L) break;
                if (frame.timestamp < nextFrameTimestamp) continue;
                BufferedImage image = converter.convert(frame);
                if (image == null) continue;
                BufferedImage scaled = scale(image, quality);
                width = scaled.getWidth();
                height = scaled.getHeight();
                writeJpeg(scaled, framesDir.resolve("%06d.jpg".formatted(frameCount)), quality.jpegQuality());
                frameCount++;
                nextFrameTimestamp = Math.round(frameCount * 1_000_000.0 / quality.fps());
                double currentSecond = frameCount / (double) quality.fps();
                if (totalDurationSeconds > 0 && (currentSecond - lastProgressSecond >= 0.3 || frameCount == 1)) {
                    lastProgressSecond = currentSecond;
                    float progress = 0.05f + 0.75f * (float) Math.min(1.0, currentSecond / totalDurationSeconds);
                    ClientPacketHandlers.setBurnProgress(burnerPos,
                            "Encoding " + Math.round(currentSecond) + "s / " + Math.round(totalDurationSeconds) + "s",
                            progress, true);
                }
            }
            grabber.stop();
        }

        if (frameCount == 0) throw new IllegalStateException("No video frames were decoded");

        Path audioPath = tempDir.resolve("audio.ogg");
        boolean hasAudio = encodeAudio(videoPath, audioPath, quality, burnerPos, job);

        ClientPacketHandlers.setBurnProgress(burnerPos, "Packaging film", 0.84f, true);
        String hashSource = filmId + title + quality.id() + quality.fps() + width + height + frameCount + hasAudio;
        FilmMetadata metadata = new FilmMetadata(2, filmId, title, quality.fps(), width, height, frameCount,
                FilmStorage.sha256(hashSource.getBytes(StandardCharsets.UTF_8)), quality.id(), hasAudio);
        ByteArrayOutputStream finalBytes = new ByteArrayOutputStream();
        try (ZipOutputStream finalZip = new ZipOutputStream(finalBytes)) {
            finalZip.putNextEntry(new ZipEntry("meta.json"));
            finalZip.write(metadata.toJson().getBytes(StandardCharsets.UTF_8));
            finalZip.closeEntry();

            if (hasAudio) {
                finalZip.putNextEntry(new ZipEntry("audio.ogg"));
                finalZip.write(Files.readAllBytes(audioPath));
                finalZip.closeEntry();
            }

            for (int i = 0; i < frameCount; i++) {
                checkCancelled(job);
                Path framePath = framesDir.resolve("%06d.jpg".formatted(i));
                finalZip.putNextEntry(new ZipEntry("frames/%06d.jpg".formatted(i)));
                finalZip.write(Files.readAllBytes(framePath));
                finalZip.closeEntry();
                if (i % quality.fps() == 0 || i == frameCount - 1) {
                    float progress = 0.84f + 0.06f * ((i + 1) / (float) frameCount);
                    ClientPacketHandlers.setBurnProgress(burnerPos, "Packaging " + ((i + 1) * 100 / frameCount) + "%", progress, true);
                }
            }
        }
        return finalBytes.toByteArray();
    }

    private static void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    CreateCinema.LOGGER.debug("Failed to delete temporary film file {}", path, e);
                }
            });
        } catch (Exception e) {
            CreateCinema.LOGGER.debug("Failed to clean temporary film directory {}", root, e);
        }
    }

    private static BufferedImage scale(BufferedImage image, FilmQuality quality) {
        double scale = Math.min(quality.maxWidth() / (double) image.getWidth(), quality.maxHeight() / (double) image.getHeight());
        scale = Math.min(1.0, scale);
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(image, 0, 0, width, height, null);
        graphics.dispose();
        return scaled;
    }

    private static void writeJpeg(BufferedImage image, Path target, float quality) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IllegalStateException("No JPEG writer is available");
        ImageWriter writer = writers.next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(target.toFile())) {
            writer.setOutput(output);
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
    }

    private static boolean encodeAudio(Path videoPath, Path output, FilmQuality quality, BlockPos burnerPos, BurnJob job) throws Exception {
        ClientPacketHandlers.setBurnProgress(burnerPos, "Encoding audio", 0.80f, true);
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoPath.toFile())) {
            grabber.start();
            int channels = grabber.getAudioChannels();
            if (channels <= 0) return false;
            int sampleRate = grabber.getSampleRate() > 0 ? grabber.getSampleRate() : 48_000;
            try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(output.toFile(), channels)) {
                recorder.setFormat("ogg");
                recorder.setAudioCodec(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_VORBIS);
                recorder.setAudioOption("strict", "experimental");
                recorder.setAudioChannels(channels);
                recorder.setSampleRate(sampleRate);
                recorder.setAudioBitrate(quality.audioBitrate());
                recorder.start();
                Frame frame;
                while ((frame = grabber.grabSamples()) != null) {
                    checkCancelled(job);
                    if (frame.timestamp > MAX_SECONDS * 1_000_000L) break;
                    if (frame.samples != null) {
                        int frameRate = frame.sampleRate > 0 ? frame.sampleRate : sampleRate;
                        int frameChannels = frame.audioChannels > 0 ? frame.audioChannels : channels;
                        recorder.recordSamples(frameRate, frameChannels, frame.samples);
                    }
                }
                recorder.stop();
            }
            grabber.stop();
        }
        return Files.isRegularFile(output) && Files.size(output) > 0;
    }

    private static void upload(BlockPos burnerPos, byte[] zip, BurnJob job) {
        UUID uploadId = UUID.randomUUID();
        int total = Math.max(1, (zip.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
        for (int i = 0; i < total; i++) {
            checkCancelled(job);
            int from = i * CHUNK_SIZE;
            int to = Math.min(zip.length, from + CHUNK_SIZE);
            byte[] chunk = java.util.Arrays.copyOfRange(zip, from, to);
            new C2SUploadFilmChunkPacket(uploadId, burnerPos, i, total, chunk).send();
        }
        ClientPacketHandlers.setBurnProgress(burnerPos, "Waiting for server", 0.90f, true);
    }

    private static void cacheFilmLocally(byte[] zip) {
        try {
            FilmMetadata metadata = FilmStorage.readMetadata(zip);
            if (metadata == null) return;
            FilmStorage.extractZip(zip, ClientFilmCache.filmDirectory(metadata.id()));
            ClientFilmCache.invalidate(metadata.id());
            CreateCinema.LOGGER.info("Cached newly burned film {} locally", metadata.id());
        } catch (Exception error) {
            CreateCinema.LOGGER.warn("Could not cache newly burned film locally; it will be downloaded from the server", error);
        }
    }

    private static void checkCancelled(BurnJob job) {
        if (job.cancelled.get()) throw new CancellationException();
    }

    private static class BurnJob {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean waitingForServer = new AtomicBoolean();
    }
}
