package com.yfy.createcinema.client;

import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.ClientConfig;
import com.yfy.createcinema.film.FilmMetadata;
import com.yfy.createcinema.film.FilmQuality;
import com.yfy.createcinema.film.FilmStorage;
import com.yfy.createcinema.film.MediaType;
import com.yfy.createcinema.packet.C2SBurnStatePacket;
import com.yfy.createcinema.packet.C2SUploadFilmChunkPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.javacpp.Loader;
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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.zip.Deflater;

public class ClientVideoBurner {
    private static final String POI_RUNTIME_RESOURCE = "/META-INF/createcinema/poi-runtime.jar";
    private static volatile Class<?> POI_RENDERER_CLASS;
    private static final long MAX_SOURCE_VIDEO_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final int BURN_CACHE_VERSION = 5;
    private static final int CHUNK_SIZE = 900_000;
    private static final Map<BlockPos, BurnJob> ACTIVE_JOBS = new ConcurrentHashMap<>();
    private static final ExecutorService BURN_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "CreateCinema Video Worker");
        thread.setDaemon(true);
        return thread;
    });
    private static final int JPEG_QUEUE_SIZE = Math.max(4, Math.min(16, Runtime.getRuntime().availableProcessors()));
    private static final ExecutorService JPEG_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)), runnable -> {
                Thread thread = new Thread(runnable, "CreateCinema JPEG Encoder");
                thread.setDaemon(true);
                return thread;
            });
    private static final ExecutorService MEDIA_ENCODE_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "CreateCinema Media Encoder");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean FFMPEG_LOAD_STARTED = new AtomicBoolean();
    private static final CompletableFuture<Void> FFMPEG_READY = new CompletableFuture<>();
    private static final String[][] ANDROID_FFMPEG_LIBRARIES = {
            {"jnijavacpp", "jnijavacpp"},
            {"avutil", "avutil@.58"}, {"jniavutil", "jniavutil"},
            {"swresample", "swresample@.4"}, {"jniswresample", "jniswresample"},
            {"swscale", "swscale@.7"}, {"jniswscale", "jniswscale"},
            {"avcodec", "avcodec@.60"}, {"jniavcodec", "jniavcodec"},
            {"avformat", "avformat@.60"}, {"jniavformat", "jniavformat"},
            {"avfilter", "avfilter@.9"}, {"jniavfilter", "jniavfilter"},
            {"avdevice", "avdevice@.60"}, {"jniavdevice", "jniavdevice"}
    };

    public static void preloadFfmpeg() {
        if (!FFMPEG_LOAD_STARTED.compareAndSet(false, true)) return;
        BURN_EXECUTOR.execute(() -> {
            try {
                if (!PlatformInfo.hasBundledFfmpeg()) {
                    throw new UnsatisfiedLinkError(PlatformInfo.ffmpegSupportMessage());
                }
                Path cacheDir = PlatformInfo.isAndroid()
                        ? Path.of(System.getProperty("java.io.tmpdir")).resolve("createcinema-native-cache")
                        : Minecraft.getInstance().gameDirectory.toPath().resolve("createcinema").resolve("native-cache");
                Files.createDirectories(cacheDir);
                System.setProperty("org.bytedeco.javacpp.cachedir", cacheDir.toAbsolutePath().toString());
                if (PlatformInfo.isAndroid()) {
                    CreateCinema.LOGGER.info("CreateCinema Android FFmpeg native cache: {}", cacheDir.toAbsolutePath());
                    loadAndroidFfmpegLibraries();
                    CreateCinema.LOGGER.info("CreateCinema loaded Android FFmpeg NDK libraries");
                }
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

    static boolean ffmpegLoadFailed() {
        return FFMPEG_READY.isCompletedExceptionally();
    }

    private static void loadAndroidFfmpegLibraries() throws Exception {
        for (String[] library : ANDROID_FFMPEG_LIBRARIES) {
            String resourcePath = "/lib/arm64-v8a/lib" + library[0] + ".so";
            URL resource = ClientVideoBurner.class.getResource(resourcePath);
            if (resource == null) throw new FileNotFoundException("Missing Android native resource " + resourcePath);
            try {
                String loaded = Loader.loadLibrary(ClientVideoBurner.class, new URL[]{resource}, library[1]);
                CreateCinema.LOGGER.debug("Loaded Android native {} from {} as {}", library[1], resource, loaded);
            } catch (Throwable error) {
                throw new IOException("Could not load Android native " + library[1] + " from " + resource
                        + ": " + error.getMessage(), error);
            }
        }
    }

    public static void startBurn(BlockPos burnerPos, String pathText, FilmQuality quality) {
        startBurn(burnerPos, pathText, quality, MediaType.VIDEO);
    }

    public static void startBurn(BlockPos burnerPos, String pathText, FilmQuality quality, MediaType mediaType) {
        cancel(burnerPos, "Starting new burn");
        BurnJob job = new BurnJob();
        ACTIVE_JOBS.put(burnerPos, job);
        new C2SBurnStatePacket(burnerPos, true, mediaType).send();
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

            Path packageFile = null;
            try {
                checkCancelled(job);
                if (!Files.isRegularFile(path) && !(mediaType == MediaType.ALBUM && Files.isDirectory(path))) {
                    ClientPacketHandlers.setBurnProgress(burnerPos, "File not found", 0.0f, false);
                    ACTIVE_JOBS.remove(burnerPos, job);
                    new C2SBurnStatePacket(burnerPos, false).send();
                    return;
                }
                if (mediaType == MediaType.VIDEO && Files.size(path) > MAX_SOURCE_VIDEO_BYTES) {
                    throw new IOException("Source video exceeds 2 GiB; choose a smaller video file");
                }
                Path zip;
                boolean newlyEncoded = true;
                if (mediaType.isStatic()) {
                    preloadStaticMedia(path, mediaType, burnerPos, job);
                    zip = createStaticFilmZip(path, burnerPos, quality, mediaType, job);
                } else {
                    ClientPacketHandlers.setBurnProgress(burnerPos, "Loading FFmpeg native libraries", 0.03f, true);
                    preloadFfmpeg();
                    FFMPEG_READY.join();
                    checkCancelled(job);
                    zip = loadCachedFilm(path, quality, burnerPos);
                    newlyEncoded = zip == null;
                    if (zip == null) {
                        zip = createFilmZip(path, burnerPos, quality, job);
                    }
                }
                packageFile = zip;
                checkCancelled(job);
                cacheFilmLocally(zip);
                if (newlyEncoded && !mediaType.isStatic()) storeCachedFilm(path, quality, zip);
                job.waitingForServer.set(true);
                upload(burnerPos, zip, job);
            } catch (CancellationException e) {
                if (ACTIVE_JOBS.remove(burnerPos, job)) {
                    ClientPacketHandlers.setBurnProgress(burnerPos, "Burn cancelled", 0.0f, false);
                }
            } catch (AudioTranscodeException error) {
                CreateCinema.LOGGER.warn("Failed to transcode audio for {}", path, error);
                ClientPacketHandlers.setBurnProgress(burnerPos, "音频转码失败，请切换格式", 0.0f, false);
                ACTIVE_JOBS.remove(burnerPos, job);
                new C2SBurnStatePacket(burnerPos, false, mediaType).send();
            } catch (Throwable error) {
                CreateCinema.LOGGER.warn("Failed to burn video {}", path, error);
                String detail = error.getMessage();
                if (detail == null || detail.isBlank()) detail = error.getClass().getSimpleName();
                ClientPacketHandlers.setBurnProgress(burnerPos, "Burn failed: " + detail, 0.0f, false);
                ACTIVE_JOBS.remove(burnerPos, job);
                new C2SBurnStatePacket(burnerPos, false, mediaType).send();
            } finally {
                if (packageFile != null) {
                    try {
                        Files.deleteIfExists(packageFile);
                    } catch (IOException error) {
                        CreateCinema.LOGGER.debug("Failed to delete temporary film package {}", packageFile, error);
                    }
                }
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

    private static void preloadStaticMedia(Path source, MediaType mediaType,
                                            BlockPos burnerPos, BurnJob job) throws Exception {
        checkCancelled(job);
        ClientPacketHandlers.setBurnProgress(burnerPos, "Loading media codecs", 0.03f, true);
        ImageIO.setUseCache(false);
        if (!ImageIO.getImageReadersByFormatName("jpeg").hasNext()
                || !ImageIO.getImageReadersByFormatName("png").hasNext()) {
            throw new IOException("Image codecs are unavailable");
        }
        checkCancelled(job);
        if (mediaType != MediaType.SLIDES) return;

        String lower = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".ppt") && !lower.endsWith(".pptx")) {
            throw new IOException("Only PPT and PPTX slide files are supported");
        }
        ClientPacketHandlers.setBurnProgress(burnerPos, "Loading PPT renderer", 0.04f, true);
        checkCancelled(job);
    }

    private static Path createStaticFilmZip(Path source, BlockPos burnerPos, FilmQuality quality,
                                               MediaType mediaType, BurnJob job) throws Exception {
        Path tempDir = Files.createTempDirectory("createcinema-static-");
        Path packageFile = Files.createTempFile("createcinema-static-", ".zip");
        try {
            Path framesDir = tempDir.resolve("frames");
            Files.createDirectories(framesDir);
            List<Path> sources = staticSources(source, mediaType, tempDir, burnerPos, job);
            if (sources.isEmpty()) throw new IOException("No readable images found");
            int width = 0;
            int height = 0;
            try (FrameEncoder encoder = new FrameEncoder()) {
                for (int index = 0; index < sources.size(); index++) {
                    checkCancelled(job);
                    BufferedImage image = ImageIO.read(sources.get(index).toFile());
                    if (image == null) throw new IOException("Unsupported image: " + sources.get(index).getFileName());
                    BufferedImage scaled = scaleToPage(image, quality);
                    width = scaled.getWidth();
                    height = scaled.getHeight();
                    encoder.submit(scaled, framesDir.resolve("%06d.jpg".formatted(index)), quality.jpegQuality());
                    ClientPacketHandlers.setBurnProgress(burnerPos,
                            "Preparing page " + (index + 1) + "/" + sources.size(),
                            0.08f + 0.72f * ((index + 1) / (float) sources.size()), true);
                }
            }
            String title = source.getFileName().toString();
            String filmId = UUID.randomUUID().toString();
            String hashSource = filmId + title + mediaType.id() + quality.id() + width + height + sources.size();
            FilmMetadata metadata = new FilmMetadata(2, filmId, title, 1, width, height, sources.size(),
                    FilmStorage.sha256(hashSource.getBytes(StandardCharsets.UTF_8)), quality.id(), false, mediaType.id());
            ClientPacketHandlers.setBurnProgress(burnerPos, "Packaging static media", 0.84f, true);
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(packageFile,
                    StandardOpenOption.TRUNCATE_EXISTING))) {
                zip.setLevel(Deflater.NO_COMPRESSION);
                zip.putNextEntry(new ZipEntry("meta.json"));
                zip.write(metadata.toJson().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                for (int index = 0; index < sources.size(); index++) {
                    zip.putNextEntry(new ZipEntry("frames/%06d.jpg".formatted(index)));
                    Files.copy(framesDir.resolve("%06d.jpg".formatted(index)), zip);
                    zip.closeEntry();
                }
            }
            return packageFile;
        } catch (Exception error) {
            Files.deleteIfExists(packageFile);
            throw error;
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static List<Path> staticSources(Path source, MediaType mediaType, Path tempDir,
                                            BlockPos burnerPos, BurnJob job) throws Exception {
        if (mediaType == MediaType.IMAGE) return List.of(source);
        if (mediaType == MediaType.ALBUM) {
            Path root = source;
            if (!Files.isDirectory(root) && isZip(source)) {
                root = tempDir.resolve("album");
                extractImageArchive(source, root);
            }
            if (!Files.isDirectory(root)) return List.of(source);
            try (var paths = Files.walk(root)) {
                return paths.filter(Files::isRegularFile).filter(ClientVideoBurner::isImage)
                        .sorted(Comparator.comparing(path -> path.toString().toLowerCase(Locale.ROOT)))
                        .toList();
            }
        }
        String lower = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".ppt") && !lower.endsWith(".pptx")) {
            throw new IOException("Only PPT and PPTX slide files are supported");
        }
        return renderPresentation(source, tempDir.resolve("ppt-pages"), burnerPos, job);
    }

    private static List<Path> renderPresentation(Path source, Path outputDir,
                                                  BlockPos burnerPos, BurnJob job) throws Exception {
        BooleanSupplier cancelled = () -> job.cancelled.get() || Thread.currentThread().isInterrupted();
        BiConsumer<Integer, Integer> progress = (index, total) -> updateSlideProgress(burnerPos, index, total);
        try {
            Object result = poiRendererClass().getMethod("render", Path.class, Path.class,
                            BooleanSupplier.class, BiConsumer.class)
                    .invoke(null, source, outputDir, cancelled, progress);
            if (!(result instanceof List<?> values)) throw new IOException("PPT renderer returned invalid pages");
            List<Path> pages = new ArrayList<>(values.size());
            for (Object value : values) {
                if (!(value instanceof Path page)) throw new IOException("PPT renderer returned an invalid page");
                pages.add(page);
            }
            return pages;
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error fatal) throw fatal;
            throw new IOException("PPT renderer failed", cause);
        }
    }

    private static void updateSlideProgress(BlockPos burnerPos, int index, int total) {
        ClientPacketHandlers.setBurnProgress(burnerPos,
                "Rendering slide " + (index + 1) + "/" + total,
                0.06f + 0.72f * ((index + 1) / (float) total), true);
    }

    private static Class<?> poiRendererClass() throws Exception {
        Class<?> cached = POI_RENDERER_CLASS;
        if (cached != null) return cached;
        synchronized (ClientVideoBurner.class) {
            if (POI_RENDERER_CLASS != null) return POI_RENDERER_CLASS;
            Path runtimeDir = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("createcinema").resolve("runtime");
            Files.createDirectories(runtimeDir);
            Path runtimeJar = runtimeDir.resolve("poi-runtime.jar");
            try (InputStream input = ClientVideoBurner.class.getResourceAsStream(POI_RUNTIME_RESOURCE)) {
                if (input == null) throw new IOException("Embedded PPT renderer is missing");
                Files.copy(input, runtimeJar, StandardCopyOption.REPLACE_EXISTING);
            }
            URLClassLoader loader = new PrivateLibraryClassLoader(
                    new URL[]{runtimeJar.toUri().toURL()}, ClientVideoBurner.class.getClassLoader());
            POI_RENDERER_CLASS = Class.forName(
                    "com.yfy.createcinema.poibridge.PoiPresentationRenderer", true, loader);
            return POI_RENDERER_CLASS;
        }
    }

    private static boolean isZip(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip");
    }

    private static boolean isImage(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    private static void extractImageArchive(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(source))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !isImage(Path.of(entry.getName()))) continue;
                Path output = target.resolve(entry.getName()).normalize();
                if (!output.startsWith(target)) throw new IOException("Invalid album archive entry");
                Files.createDirectories(output.getParent());
                Files.copy(zip, output, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static Path createFilmZip(Path videoPath, BlockPos burnerPos, FilmQuality quality, BurnJob job) throws Exception {
        ClientPacketHandlers.setBurnProgress(burnerPos, "Creating temporary workspace", 0.04f, true);
        Path tempDir = Files.createTempDirectory("createcinema-film-");
        Path packageFile = Files.createTempFile("createcinema-film-", ".zip");
        try {
            return createFilmZip(videoPath, burnerPos, tempDir, packageFile, quality, job);
        } catch (Exception error) {
            Files.deleteIfExists(packageFile);
            throw error;
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static Path createFilmZip(Path videoPath, BlockPos burnerPos, Path tempDir, Path packageFile,
                                      FilmQuality quality, BurnJob job) throws Exception {
        try {
            return createH264FilmZip(videoPath, burnerPos, tempDir, packageFile, quality, job);
        } catch (VideoEncodeException error) {
            CreateCinema.LOGGER.warn("H.264 film encoding failed for {}; falling back to JPEG film format",
                    videoPath.getFileName(), error);
            ClientPacketHandlers.setBurnProgress(burnerPos, "H.264 unavailable; using compatible film format", 0.05f, true);
            deleteRecursively(tempDir);
            Files.createDirectories(tempDir);
            Files.deleteIfExists(packageFile);
            return createLegacyFilmZip(videoPath, burnerPos, tempDir, packageFile, quality, job);
        }
    }

    private static Path createH264FilmZip(Path source, BlockPos burnerPos, Path tempDir, Path packageFile,
                                             FilmQuality quality, BurnJob job) throws Exception {
        String filmId = UUID.randomUUID().toString();
        String title = source.getFileName().toString();
        Path videoPath = tempDir.resolve("video.mp4");
        Path audioPath = tempDir.resolve("audio.ogg");
        Future<int[]> videoTask = MEDIA_ENCODE_EXECUTOR.submit(
                () -> encodeVideo(source, videoPath, quality, burnerPos, job));
        Future<Boolean> audioTask = MEDIA_ENCODE_EXECUTOR.submit(
                () -> encodeAudio(source, audioPath, quality, burnerPos, job));
        int[] videoInfo;
        boolean hasAudio;
        try {
            videoInfo = videoTask.get();
            hasAudio = audioTask.get();
        } catch (InterruptedException error) {
            videoTask.cancel(true);
            audioTask.cancel(true);
            Thread.currentThread().interrupt();
            throw new CancellationException();
        } catch (ExecutionException error) {
            videoTask.cancel(true);
            audioTask.cancel(true);
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error fatal) throw fatal;
            throw new IOException("Media encoding failed", cause);
        }
        int width = videoInfo[0];
        int height = videoInfo[1];
        int frameCount = videoInfo[2];
        ClientPacketHandlers.setBurnProgress(burnerPos, "Packaging H.264 film", 0.86f, true);
        String hashSource = filmId + title + "h264" + quality.id() + quality.fps()
                + width + height + frameCount + hasAudio;
        FilmMetadata metadata = new FilmMetadata(3, filmId, title, quality.fps(), width, height, frameCount,
                FilmStorage.sha256(hashSource.getBytes(StandardCharsets.UTF_8)), quality.id(), hasAudio);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(packageFile,
                StandardOpenOption.TRUNCATE_EXISTING))) {
            output.setLevel(Deflater.NO_COMPRESSION);
            output.putNextEntry(new ZipEntry("meta.json"));
            output.write(metadata.toJson().getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("video.mp4"));
            Files.copy(videoPath, output);
            output.closeEntry();
            if (hasAudio) {
                output.putNextEntry(new ZipEntry("audio.ogg"));
                Files.copy(audioPath, output);
                output.closeEntry();
            }
        }
        ClientPacketHandlers.setBurnProgress(burnerPos, "H.264 film ready", 0.90f, true);
        return packageFile;
    }

    private static int[] encodeVideo(Path source, Path output, FilmQuality quality,
                                     BlockPos burnerPos, BurnJob job) throws Exception {
        if (isCopyCompatible(source, quality)) {
            try {
                return copyH264Video(source, output, quality, burnerPos, job);
            } catch (Exception error) {
                Files.deleteIfExists(output);
                CreateCinema.LOGGER.warn("H.264 stream copy failed for {}; falling back to encoding",
                        source.getFileName(), error);
            }
        }
        return encodeH264Video(source, output, quality, burnerPos, job);
    }

    private static boolean isCopyCompatible(Path source, FilmQuality quality) {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(source.toFile())) {
            grabber.start();
            int width = grabber.getImageWidth();
            int height = grabber.getImageHeight();
            double frameRate = grabber.getFrameRate();
            int bitrate = grabber.getVideoBitrate();
            int[] dimensions = scaledDimensions(width, height, quality);
            boolean compatible = grabber.getVideoCodec() == org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
                    && width == dimensions[0] && height == dimensions[1]
                    && frameRate > 0.0 && Math.abs(frameRate - quality.fps()) < 0.01
                    && (bitrate <= 0 || bitrate <= quality.videoBitrate());
            grabber.stop();
            return compatible;
        } catch (Exception error) {
            return false;
        }
    }

    private static int[] copyH264Video(Path source, Path output, FilmQuality quality,
                                       BlockPos burnerPos, BurnJob job) throws Exception {
        long startedAt = System.nanoTime();
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(source.toFile())) {
            grabber.start();
            int width = grabber.getImageWidth();
            int height = grabber.getImageHeight();
            try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(output.toFile(), width, height)) {
                recorder.setFormat("mp4");
                recorder.setVideoCodec(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264);
                recorder.setFrameRate(quality.fps());
                recorder.setOption("movflags", "+faststart");
                recorder.start(grabber.getFormatContext());
                AVPacket packet;
                while ((packet = grabber.grabPacket()) != null) {
                    checkCancelled(job);
                    recorder.recordPacket(packet);
                }
                recorder.stop();
            }
        }
        if (!Files.isRegularFile(output) || Files.size(output) == 0) {
            throw new IOException("H.264 stream copy produced no video");
        }
        double elapsedSeconds = Math.max(0.001, (System.nanoTime() - startedAt) / 1_000_000_000.0);
        CreateCinema.LOGGER.info("Copied H.264 video stream in {}s", decimal(elapsedSeconds));
        return probeVideoOutput(output, quality);
    }

    private static int[] probeVideoOutput(Path video, FilmQuality quality) throws Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video.toFile())) {
            grabber.start();
            int count = grabber.getLengthInVideoFrames();
            if (count <= 0) count = Math.max(1, (int) Math.round(grabber.getLengthInTime()
                    / 1_000_000.0 * quality.fps()));
            return new int[]{grabber.getImageWidth(), grabber.getImageHeight(), count};
        }
    }

    private static int[] encodeH264Video(Path source, Path output, FilmQuality quality,
                                          BlockPos burnerPos, BurnJob job) throws VideoEncodeException {
        Throwable lastError = null;
        for (H264Encoder encoder : h264Encoders()) {
            checkCancelled(job);
            try {
                Files.deleteIfExists(output);
                return encodeH264Video(source, output, quality, burnerPos, job, encoder);
            } catch (CancellationException error) {
                throw error;
            } catch (Throwable error) {
                lastError = error;
                if (encoder.hardware()) {
                    CreateCinema.LOGGER.info("Hardware video encoder {} failed; trying the next encoder",
                            encoder.displayName());
                    CreateCinema.LOGGER.debug("Hardware encoder {} failure", encoder.displayName(), error);
                } else {
                    CreateCinema.LOGGER.warn("Software video encoder {} failed; trying fallback",
                            encoder.displayName(), error);
                }
            }
        }
        throw new VideoEncodeException("No H.264 encoder could complete the film", lastError);
    }

    private static int[] encodeH264Video(Path source, Path output, FilmQuality quality,
                                          BlockPos burnerPos, BurnJob job, H264Encoder encoder) throws Exception {
        long startedAt = System.nanoTime();
        try (OpenedVideo opened = openVideo(source, burnerPos)) {
            FFmpegFrameGrabber grabber = opened.grabber();
            Frame first = opened.firstFrame();
            int[] dimensions = scaledDimensions(first.imageWidth, first.imageHeight, quality);
            int width = dimensions[0];
            int height = dimensions[1];
            long lengthInTime = grabber.getLengthInTime();
            double duration = lengthInTime > 0 ? lengthInTime / 1_000_000.0 : 0.0;
            int frameCount = 0;
            long nextSourceTimestamp = 0L;
            double lastProgressSecond = -1.0;

            try (FFmpegFrameRecorder recorder = startH264Recorder(output, width, height, quality, encoder)) {
                Frame frame = first;
                while (frame != null) {
                    checkCancelled(job);
                    long sourceTimestamp = frame.timestamp;
                    if (sourceTimestamp >= nextSourceTimestamp && frame.image != null && frame.image.length > 0) {
                        frame.timestamp = Math.round(frameCount * 1_000_000.0 / quality.fps());
                        recorder.record(frame);
                        frameCount++;
                        nextSourceTimestamp = Math.round(frameCount * 1_000_000.0 / quality.fps());
                        double currentSecond = frameCount / (double) quality.fps();
                        if (currentSecond - lastProgressSecond >= 0.3 || frameCount == 1) {
                            lastProgressSecond = currentSecond;
                            float progress = duration > 0.0
                                    ? 0.05f + 0.73f * (float) Math.min(1.0, currentSecond / duration)
                                    : 0.05f;
                            ClientPacketHandlers.setBurnProgress(burnerPos,
                                    "Encoding " + encoder.displayName() + " " + Math.round(currentSecond)
                                            + "s / " + Math.round(duration) + "s",
                                    progress, true);
                        }
                    }
                    frame = grabber.grabImage();
                }
                recorder.stop();
            }
            if (frameCount == 0 || !Files.isRegularFile(output) || Files.size(output) == 0) {
                throw new IOException("H.264 encoder produced no video frames");
            }
            double elapsedSeconds = Math.max(0.001, (System.nanoTime() - startedAt) / 1_000_000_000.0);
            double mediaSeconds = frameCount / (double) quality.fps();
            CreateCinema.LOGGER.info("Encoded H.264 film with {}: {} frames in {}s ({} fps, {}x realtime)",
                    encoder.displayName(), frameCount, decimal(elapsedSeconds), decimal(frameCount / elapsedSeconds),
                    decimal(mediaSeconds / elapsedSeconds));
            return new int[]{width, height, frameCount};
        }
    }

    private static FFmpegFrameRecorder startH264Recorder(Path output, int width, int height,
                                                          FilmQuality quality, H264Encoder encoder) throws Exception {
        FFmpegFrameRecorder recorder = configuredH264Recorder(output, width, height, quality, encoder);
        try {
            recorder.start();
            CreateCinema.LOGGER.info("Using {} video encoder {} at {}x{} {} fps",
                    encoder.hardware() ? "hardware" : "software", encoder.displayName(),
                    width, height, quality.fps());
            return recorder;
        } catch (Exception error) {
            try {
                recorder.close();
            } catch (Exception ignored) {
            }
            throw error;
        }
    }

    private static FFmpegFrameRecorder configuredH264Recorder(Path output, int width, int height,
                                                               FilmQuality quality, H264Encoder encoder) {
        FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(output.toFile(), width, height);
        recorder.setFormat("mp4");
        if (encoder.name() == null) recorder.setVideoCodec(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264);
        else recorder.setVideoCodecName(encoder.name());
        recorder.setPixelFormat(encoderPixelFormat(encoder.name()));
        recorder.setFrameRate(quality.fps());
        recorder.setVideoBitrate(quality.videoBitrate());
        recorder.setGopSize(quality.fps() * 2);
        recorder.setMaxBFrames(0);
        if (!encoder.hardware()) recorder.setVideoOption("threads", "0");
        if ("libx264".equals(encoder.name())) {
            recorder.setVideoOption("preset", "veryfast");
            recorder.setVideoOption("tune", "fastdecode");
        } else if ("h264_nvenc".equals(encoder.name())) {
            recorder.setVideoOption("preset", "p1");
            recorder.setVideoOption("tune", "ll");
            recorder.setVideoOption("rc", "vbr");
            recorder.setVideoOption("profile", "main");
        } else if ("h264_qsv".equals(encoder.name())) {
            recorder.setVideoOption("preset", "veryfast");
        } else if ("h264_amf".equals(encoder.name())) {
            recorder.setVideoOption("quality", "speed");
            recorder.setVideoOption("usage", "transcoding");
        } else if ("h264_videotoolbox".equals(encoder.name())) {
            recorder.setVideoOption("realtime", "1");
            recorder.setVideoOption("allow_sw", "0");
        }
        recorder.setOption("movflags", "+faststart");
        return recorder;
    }

    private static List<H264Encoder> h264Encoders() {
        List<H264Encoder> encoders = new ArrayList<>();
        if (ClientConfig.burnHardwareEncoding()) {
            if (PlatformInfo.isWindows()) {
                addEncoder(encoders, "h264_nvenc", true);
                addEncoder(encoders, "h264_qsv", true);
                addEncoder(encoders, "h264_amf", true);
            } else if (PlatformInfo.isLinux()) {
                addEncoder(encoders, "h264_nvenc", true);
                addEncoder(encoders, "h264_qsv", true);
            } else if (PlatformInfo.isMacos()) {
                addEncoder(encoders, "h264_videotoolbox", true);
            } else if (PlatformInfo.isAndroid()) {
                addEncoder(encoders, "h264_mediacodec", true);
            }
        }
        addEncoder(encoders, "libx264", false);
        addEncoder(encoders, "libopenh264", false);
        if (encoders.stream().noneMatch(encoder -> !encoder.hardware())) {
            encoders.add(new H264Encoder(null, false));
        }
        CreateCinema.LOGGER.info("H.264 encoder candidates: {}",
                encoders.stream().map(H264Encoder::displayName).toList());
        return encoders;
    }

    private static void addEncoder(List<H264Encoder> encoders, String name, boolean hardware) {
        var codec = org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name(name);
        if (codec != null && !codec.isNull()) encoders.add(new H264Encoder(name, hardware));
    }

    private static int encoderPixelFormat(String encoder) {
        if ("h264_qsv".equals(encoder) || "h264_amf".equals(encoder)) {
            return org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_NV12;
        }
        return org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P;
    }

    private static String decimal(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static boolean awaitAudioTask(Future<Boolean> audioTask) throws Exception {
        try {
            return audioTask.get();
        } catch (InterruptedException error) {
            audioTask.cancel(true);
            Thread.currentThread().interrupt();
            throw new CancellationException();
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error fatal) throw fatal;
            throw new IOException("Audio encoding failed", cause);
        }
    }

    private static Path createLegacyFilmZip(Path videoPath, BlockPos burnerPos, Path tempDir, Path packageFile,
                                            FilmQuality quality, BurnJob job) throws Exception {
        if (PlatformInfo.isAndroid()) {
            return createAndroidFilmZip(videoPath, burnerPos, tempDir, packageFile, quality, job);
        }
        String filmId = UUID.randomUUID().toString();
        String title = videoPath.getFileName().toString();
        Path framesDir = tempDir.resolve("frames");
        Files.createDirectories(framesDir);
        int frameCount = 0;
        int width = quality.maxWidth();
        int height = quality.maxHeight();
        double totalDurationSeconds = 0.0;
        ImageIO.setUseCache(false);

        ClientPacketHandlers.setBurnProgress(burnerPos, "Opening video", 0.05f, true);
        Path audioPath = tempDir.resolve("audio.ogg");
        Future<Boolean> audioTask = MEDIA_ENCODE_EXECUTOR.submit(
                () -> encodeAudio(videoPath, audioPath, quality, burnerPos, job));
        try {
            try (OpenedVideo opened = openVideo(videoPath, burnerPos);
                 Java2DFrameConverter converter = new Java2DFrameConverter();
                 FrameEncoder encoder = new FrameEncoder()) {
                FFmpegFrameGrabber grabber = opened.grabber();
                checkCancelled(job);
                long lengthInTime = grabber.getLengthInTime();
                if (lengthInTime > 0) {
                    totalDurationSeconds = lengthInTime / 1_000_000.0;
                }
                long nextFrameTimestamp = 0;
                double lastProgressSecond = -1.0;
                Frame frame = opened.firstFrame();
                while (frame != null) {
                    checkCancelled(job);
                    if (frame.timestamp >= nextFrameTimestamp) {
                        BufferedImage image = converter.convert(frame);
                        if (image != null) {
                            BufferedImage scaled = scale(image, quality);
                            width = scaled.getWidth();
                            height = scaled.getHeight();
                            encoder.submit(scaled, framesDir.resolve("%06d.jpg".formatted(frameCount)), quality.jpegQuality());
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
                    }
                    frame = grabber.grabImage();
                }
                encoder.finish();
            }
        } catch (Exception error) {
            audioTask.cancel(true);
            throw error;
        }

        if (frameCount == 0) throw new IllegalStateException("No video frames were decoded");

        boolean hasAudio = awaitAudioTask(audioTask);

        ClientPacketHandlers.setBurnProgress(burnerPos, "Packaging film", 0.84f, true);
        String hashSource = filmId + title + quality.id() + quality.fps() + width + height + frameCount + hasAudio;
        FilmMetadata metadata = new FilmMetadata(2, filmId, title, quality.fps(), width, height, frameCount,
                FilmStorage.sha256(hashSource.getBytes(StandardCharsets.UTF_8)), quality.id(), hasAudio);
        try (ZipOutputStream finalZip = new ZipOutputStream(Files.newOutputStream(packageFile,
                StandardOpenOption.TRUNCATE_EXISTING))) {
            finalZip.setLevel(Deflater.NO_COMPRESSION);
            finalZip.putNextEntry(new ZipEntry("meta.json"));
            finalZip.write(metadata.toJson().getBytes(StandardCharsets.UTF_8));
            finalZip.closeEntry();

            if (hasAudio) {
                finalZip.putNextEntry(new ZipEntry("audio.ogg"));
                Files.copy(audioPath, finalZip);
                finalZip.closeEntry();
            }

            for (int i = 0; i < frameCount; i++) {
                checkCancelled(job);
                Path framePath = framesDir.resolve("%06d.jpg".formatted(i));
                finalZip.putNextEntry(new ZipEntry("frames/%06d.jpg".formatted(i)));
                Files.copy(framePath, finalZip);
                finalZip.closeEntry();
                if (i % quality.fps() == 0 || i == frameCount - 1) {
                    float progress = 0.84f + 0.06f * ((i + 1) / (float) frameCount);
                    ClientPacketHandlers.setBurnProgress(burnerPos, "Packaging " + ((i + 1) * 100 / frameCount) + "%", progress, true);
                }
            }
        }
        return packageFile;
    }

    private static Path createAndroidFilmZip(Path videoPath, BlockPos burnerPos, Path tempDir, Path packageFile,
                                                FilmQuality quality, BurnJob job) throws Exception {
        String filmId = UUID.randomUUID().toString();
        String title = videoPath.getFileName().toString();
        Path framesDir = tempDir.resolve("frames");
        Files.createDirectories(framesDir);
        int frameCount = 0;
        int width = 0;
        int height = 0;
        double totalDurationSeconds = 0.0;

        ClientPacketHandlers.setBurnProgress(burnerPos, "Opening video", 0.05f, true);
        Path audioPath = tempDir.resolve("audio.ogg");
        Future<Boolean> audioTask = MEDIA_ENCODE_EXECUTOR.submit(
                () -> encodeAudio(videoPath, audioPath, quality, burnerPos, job));
        try {
            try (OpenedVideo opened = openVideo(videoPath, burnerPos)) {
                FFmpegFrameGrabber grabber = opened.grabber();
                checkCancelled(job);
                long lengthInTime = grabber.getLengthInTime();
                if (lengthInTime > 0) totalDurationSeconds = lengthInTime / 1_000_000.0;

                long nextFrameTimestamp = 0;
                Frame first = opened.firstFrame();
                if (first == null || first.image == null || first.image.length == 0) {
                    throw new IllegalStateException("No video frames were decoded");
                }

                int[] dimensions = scaledDimensions(first.imageWidth, first.imageHeight, quality);
                width = dimensions[0];
                height = dimensions[1];
                Path framePattern = framesDir.resolve("%06d.jpg");
                try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(framePattern.toFile(), width, height)) {
                    recorder.setFormat("image2");
                    recorder.setVideoCodec(org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MJPEG);
                    recorder.setPixelFormat(org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUVJ420P);
                    recorder.setVideoOption("q:v", Integer.toString(mjpegQuality(quality.jpegQuality())));
                    recorder.setOption("start_number", "0");
                    recorder.setFrameRate(quality.fps());
                    recorder.start();

                    double lastProgressSecond = -1.0;
                    Frame frame = first;
                    while (frame != null) {
                        checkCancelled(job);
                        if (frame.timestamp >= nextFrameTimestamp && frame.image != null && frame.image.length > 0) {
                            recorder.record(frame);
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
                        frame = grabber.grabImage();
                    }
                    recorder.stop();
                }
            }
        } catch (Exception error) {
            audioTask.cancel(true);
            throw error;
        }

        if (frameCount == 0 || !Files.isRegularFile(framesDir.resolve("000000.jpg"))) {
            throw new IllegalStateException("No video frames were encoded");
        }

        boolean hasAudio = awaitAudioTask(audioTask);

        ClientPacketHandlers.setBurnProgress(burnerPos, "Packaging film", 0.84f, true);
        String hashSource = filmId + title + quality.id() + quality.fps() + width + height + frameCount + hasAudio;
        FilmMetadata metadata = new FilmMetadata(2, filmId, title, quality.fps(), width, height, frameCount,
                FilmStorage.sha256(hashSource.getBytes(StandardCharsets.UTF_8)), quality.id(), hasAudio);
        try (ZipOutputStream finalZip = new ZipOutputStream(Files.newOutputStream(packageFile,
                StandardOpenOption.TRUNCATE_EXISTING))) {
            finalZip.setLevel(Deflater.NO_COMPRESSION);
            finalZip.putNextEntry(new ZipEntry("meta.json"));
            finalZip.write(metadata.toJson().getBytes(StandardCharsets.UTF_8));
            finalZip.closeEntry();
            if (hasAudio) {
                finalZip.putNextEntry(new ZipEntry("audio.ogg"));
                Files.copy(audioPath, finalZip);
                finalZip.closeEntry();
            }
            for (int i = 0; i < frameCount; i++) {
                checkCancelled(job);
                Path framePath = framesDir.resolve("%06d.jpg".formatted(i));
                finalZip.putNextEntry(new ZipEntry("frames/%06d.jpg".formatted(i)));
                Files.copy(framePath, finalZip);
                finalZip.closeEntry();
                if (i % quality.fps() == 0 || i == frameCount - 1) {
                    float progress = 0.84f + 0.06f * ((i + 1) / (float) frameCount);
                    ClientPacketHandlers.setBurnProgress(burnerPos, "Packaging " + ((i + 1) * 100 / frameCount) + "%", progress, true);
                }
            }
        }
        return packageFile;
    }

    private static int[] scaledDimensions(int sourceWidth, int sourceHeight, FilmQuality quality) {
        if (sourceWidth <= 0 || sourceHeight <= 0) throw new IllegalStateException("Decoded video frame has no dimensions");
        double scale = Math.min(1.0, Math.min(quality.maxWidth() / (double) sourceWidth,
                quality.maxHeight() / (double) sourceHeight));
        int width = Math.max(2, (int) Math.round(sourceWidth * scale));
        int height = Math.max(2, (int) Math.round(sourceHeight * scale));
        if ((width & 1) != 0) width--;
        if ((height & 1) != 0) height--;
        return new int[]{width, height};
    }

    private static int mjpegQuality(float jpegQuality) {
        return Math.max(2, Math.min(31, Math.round(31 - jpegQuality * 29.0f)));
    }

    private static OpenedVideo openVideo(Path videoPath, BlockPos burnerPos) throws Exception {
        if (ClientConfig.burnHardwareDecoding() && !PlatformInfo.isAndroid()) {
            int codecId = probeVideoCodec(videoPath);
            for (String decoder : hardwareDecoders(codecId)) {
                if (!decoderAvailable(decoder)) continue;
                ClientPacketHandlers.setBurnProgress(burnerPos, "Trying GPU decoder " + decoder, 0.05f, true);
                try {
                    OpenedVideo opened = openVideoWithDecoder(videoPath, decoder);
                    CreateCinema.LOGGER.info("Using hardware video decoder {} for {}", decoder, videoPath.getFileName());
                    return opened;
                } catch (Exception error) {
                    CreateCinema.LOGGER.debug("Hardware decoder {} could not open {}; falling back", decoder,
                            videoPath.getFileName(), error);
                }
            }
        }
        OpenedVideo opened = openVideoWithDecoder(videoPath, null);
        CreateCinema.LOGGER.info("Using FFmpeg software video decoder for {}", videoPath.getFileName());
        return opened;
    }

    private static OpenedVideo openVideoWithDecoder(Path videoPath, String decoder) throws Exception {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoPath.toFile());
        try {
            if (decoder != null) grabber.setVideoCodecName(decoder);
            grabber.setVideoOption("threads", "0");
            grabber.start();
            Frame first = grabber.grabImage();
            if (first == null || first.image == null || first.image.length == 0) {
                throw new IllegalStateException("No video frames were decoded");
            }
            return new OpenedVideo(grabber, first);
        } catch (Throwable error) {
            try {
                grabber.close();
            } catch (Exception ignored) {
            }
            if (error instanceof Exception exception) throw exception;
            throw new IOException("Could not open video decoder", error);
        }
    }

    private static int probeVideoCodec(Path videoPath) {
        try (FFmpegFrameGrabber probe = new FFmpegFrameGrabber(videoPath.toFile())) {
            probe.start();
            return probe.getVideoCodec();
        } catch (Exception error) {
            CreateCinema.LOGGER.debug("Could not probe video codec for hardware decoding", error);
            return -1;
        }
    }

    static List<String> hardwareDecoders(int codecId) {
        String codec = switch (codecId) {
            case org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264 -> "h264";
            case org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_HEVC -> "hevc";
            case org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_VP9 -> "vp9";
            case org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AV1 -> "av1";
            case org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MPEG2VIDEO -> "mpeg2";
            default -> "";
        };
        if (codec.isEmpty()) return List.of();
        if (PlatformInfo.isWindows()) return List.of(codec + "_cuvid", codec + "_qsv");
        if (PlatformInfo.isLinux()) return List.of(codec + "_cuvid", codec + "_v4l2m2m");
        if (PlatformInfo.isMacos()) return List.of(codec + "_videotoolbox");
        if (PlatformInfo.isAndroid()) return List.of(codec + "_mediacodec");
        return List.of();
    }

    static boolean decoderAvailable(String decoder) {
        var codec = org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder_by_name(decoder);
        return codec != null && !codec.isNull();
    }

    private static Path loadCachedFilm(Path videoPath, FilmQuality quality, BlockPos burnerPos) {
        if (!ClientConfig.burnCacheEnabled()) return null;
        try {
            Path cached = burnCacheFile(videoPath, quality);
            if (!Files.isRegularFile(cached)) return null;
            ClientPacketHandlers.setBurnProgress(burnerPos, "Reusing burn cache", 0.80f, true);
            Path cloned = Files.createTempFile("createcinema-cached-", ".zip");
            try {
                cloneCachedFilm(cached, videoPath.getFileName().toString(), cloned);
            } catch (Exception error) {
                Files.deleteIfExists(cloned);
                throw error;
            }
            Files.setLastModifiedTime(cached, FileTime.fromMillis(System.currentTimeMillis()));
            CreateCinema.LOGGER.info("Reused burn cache {} for {}", cached.getFileName(), videoPath.getFileName());
            return cloned;
        } catch (Exception error) {
            CreateCinema.LOGGER.warn("Ignoring invalid burn cache for {}", videoPath.getFileName(), error);
            return null;
        }
    }

    private static void storeCachedFilm(Path videoPath, FilmQuality quality, Path zip) {
        if (!ClientConfig.burnCacheEnabled()) return;
        try {
            Path target = burnCacheFile(videoPath, quality);
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
            try {
                Files.copy(zip, temporary, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            trimBurnCache();
            CreateCinema.LOGGER.info("Stored reusable burn cache {}", target.getFileName());
        } catch (Exception error) {
            CreateCinema.LOGGER.warn("Could not store burn cache for {}", videoPath.getFileName(), error);
        }
    }

    private static Path burnCacheFile(Path videoPath, FilmQuality quality) throws IOException {
        String source = videoPath.toAbsolutePath().normalize() + "\n"
                + Files.size(videoPath) + "\n" + Files.getLastModifiedTime(videoPath).toMillis() + "\n"
                + quality.id() + "\n" + quality.maxWidth() + "x" + quality.maxHeight() + "\n"
                + quality.fps() + "\n" + quality.videoBitrate() + "\n" + quality.jpegQuality() + "\n"
                + "h264-mp4-v5\n" + BURN_CACHE_VERSION;
        return burnCacheRoot().resolve(FilmStorage.sha256(source.getBytes(StandardCharsets.UTF_8)) + ".zip");
    }

    private static Path burnCacheRoot() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("createcinema").resolve("burn-cache");
    }

    private static void cloneCachedFilm(Path cached, String title, Path outputFile) throws IOException {
        FilmMetadata oldMetadata = FilmStorage.readMetadata(cached);
        if (oldMetadata == null) throw new IOException("Cached film has no metadata");

        String filmId = UUID.randomUUID().toString();
        String hash = FilmStorage.sha256((oldMetadata.hash() + filmId).getBytes(StandardCharsets.UTF_8));
        FilmMetadata metadata = new FilmMetadata(oldMetadata.formatVersion(), filmId, title, oldMetadata.fps(),
                oldMetadata.width(), oldMetadata.height(), oldMetadata.frameCount(), hash, oldMetadata.quality(),
                oldMetadata.hasAudio());

        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(cached));
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(outputFile,
                     StandardOpenOption.TRUNCATE_EXISTING))) {
            output.setLevel(Deflater.NO_COMPRESSION);
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                output.putNextEntry(new ZipEntry(entry.getName()));
                if (entry.getName().equals("meta.json")) {
                    output.write(metadata.toJson().getBytes(StandardCharsets.UTF_8));
                } else {
                    input.transferTo(output);
                }
                output.closeEntry();
            }
        }
    }

    private static void trimBurnCache() throws IOException {
        Path root = burnCacheRoot();
        if (!Files.isDirectory(root)) return;
        List<CacheFile> files = new ArrayList<>();
        try (var paths = Files.list(root)) {
            for (Path path : paths.filter(candidate -> candidate.getFileName().toString().endsWith(".zip")).toList()) {
                files.add(new CacheFile(path, Files.size(path), Files.getLastModifiedTime(path).toMillis()));
            }
        }
        long total = files.stream().mapToLong(CacheFile::size).sum();
        files.sort(Comparator.comparingLong(CacheFile::lastUsed));
        for (CacheFile file : files) {
            if (total <= ClientConfig.burnCacheMaxBytes()) break;
            Files.deleteIfExists(file.path());
            total -= file.size();
        }
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

    private static BufferedImage scaleToPage(BufferedImage image, FilmQuality quality) {
        int targetWidth = quality.maxWidth();
        int targetHeight = quality.maxHeight();
        double scale = Math.min(targetWidth / (double) image.getWidth(), targetHeight / (double) image.getHeight());
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage page = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = page.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setColor(java.awt.Color.BLACK);
        graphics.fillRect(0, 0, targetWidth, targetHeight);
        graphics.drawImage(image, (targetWidth - width) / 2, (targetHeight - height) / 2, width, height, null);
        graphics.dispose();
        return page;
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
            boolean vorbis = supportsVorbis(channels, sampleRate);
            try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(output.toFile(), channels)) {
                recorder.setFormat("ogg");
                recorder.setAudioCodec(vorbis ? org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_VORBIS
                        : org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_FLAC);
                if (vorbis) {
                    recorder.setAudioOption("strict", "experimental");
                    recorder.setAudioBitrate(quality.audioBitrate());
                }
                recorder.setAudioChannels(channels);
                recorder.setSampleRate(sampleRate);
                try {
                    recorder.start();
                } catch (Exception error) {
                    throw new AudioTranscodeException("Could not open " + (vorbis ? "Vorbis" : "FLAC")
                            + " encoder for " + channels + " channels at " + sampleRate + " Hz", error);
                }
                CreateCinema.LOGGER.info("Encoding film audio as {} ({} channels, {} Hz)",
                        vorbis ? "Vorbis" : "FLAC", channels, sampleRate);
                Frame frame;
                try {
                    while ((frame = grabber.grabSamples()) != null) {
                        checkCancelled(job);
                        if (frame.samples != null) {
                            int frameRate = frame.sampleRate > 0 ? frame.sampleRate : sampleRate;
                            int frameChannels = frame.audioChannels > 0 ? frame.audioChannels : channels;
                            recorder.recordSamples(frameRate, frameChannels, frame.samples);
                        }
                    }
                    recorder.stop();
                } catch (CancellationException error) {
                    throw error;
                } catch (Exception error) {
                    throw new AudioTranscodeException("Could not encode film audio", error);
                }
            }
            grabber.stop();
        }
        return Files.isRegularFile(output) && Files.size(output) > 0;
    }

    private static boolean supportsVorbis(int channels, int sampleRate) {
        return channels >= 1 && channels <= 2 && switch (sampleRate) {
            case 8_000, 11_025, 16_000, 22_050, 32_000, 44_100, 48_000 -> true;
            default -> false;
        };
    }

    private static class AudioTranscodeException extends Exception {
        private AudioTranscodeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static class VideoEncodeException extends Exception {
        private VideoEncodeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class FrameEncoder implements AutoCloseable {
        private final Deque<Future<?>> pending = new ArrayDeque<>();

        private void submit(BufferedImage image, Path target, float quality) throws Exception {
            pending.addLast(JPEG_EXECUTOR.submit(() -> {
                try {
                    writeJpeg(image, target, quality);
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            }));
            if (pending.size() >= JPEG_QUEUE_SIZE) await(pending.removeFirst());
        }

        private void finish() throws Exception {
            while (!pending.isEmpty()) await(pending.removeFirst());
        }

        private static void await(Future<?> future) throws Exception {
            try {
                future.get();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw error;
            } catch (ExecutionException error) {
                Throwable cause = error.getCause();
                if (cause instanceof RuntimeException runtime && runtime.getCause() instanceof Exception exception) {
                    throw exception;
                }
                throw new IOException("Could not encode JPEG frame", cause);
            }
        }

        @Override
        public void close() throws Exception {
            finish();
        }
    }

    private record OpenedVideo(FFmpegFrameGrabber grabber, Frame firstFrame) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            grabber.close();
        }
    }

    private record H264Encoder(String name, boolean hardware) {
        private String displayName() {
            return name == null ? "FFmpeg default H.264" : name;
        }
    }

    private record CacheFile(Path path, long size, long lastUsed) {
    }

    private static final class PrivateLibraryClassLoader extends URLClassLoader {
        private PrivateLibraryClassLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("java.")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try {
                        loaded = findClass(name);
                    } catch (ClassNotFoundException ignored) {
                        loaded = super.loadClass(name, false);
                    }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }

    private static void upload(BlockPos burnerPos, Path zip, BurnJob job) throws IOException {
        UUID uploadId = UUID.randomUUID();
        long size = Files.size(zip);
        long chunks = Math.max(1L, (size + CHUNK_SIZE - 1L) / CHUNK_SIZE);
        if (chunks > Integer.MAX_VALUE) throw new IOException("Film package is too large for the transfer protocol");
        try (InputStream input = Files.newInputStream(zip)) {
            for (int i = 0; i < (int) chunks; i++) {
                checkCancelled(job);
                byte[] chunk = input.readNBytes(CHUNK_SIZE);
                if (chunk.length == 0) throw new IOException("Film package ended before all chunks were uploaded");
                new C2SUploadFilmChunkPacket(uploadId, burnerPos, i, (int) chunks, chunk).send();
            }
        }
        ClientPacketHandlers.setBurnProgress(burnerPos, "Waiting for server", 0.90f, true);
    }

    private static void cacheFilmLocally(Path zip) {
        try {
            FilmMetadata metadata = FilmStorage.readMetadata(zip);
            if (metadata == null) return;
            FilmStorage.extractZip(zip, ClientFilmCache.filmDirectory(metadata.id()));
            Minecraft.getInstance().execute(() -> ClientFilmCache.invalidate(metadata.id()));
            CreateCinema.LOGGER.info("Cached newly burned film {} locally", metadata.id());
        } catch (Exception error) {
            CreateCinema.LOGGER.warn("Could not cache newly burned film locally; it will be downloaded from the server", error);
        }
    }

    private static void checkCancelled(BurnJob job) {
        if (job.cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private static class BurnJob {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean waitingForServer = new AtomicBoolean();
    }
}
