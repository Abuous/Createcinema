package com.yfy.createcinema.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.film.FilmMetadata;
import com.yfy.createcinema.packet.C2SRequestFilmPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ClientFilmCache {
    private static final Map<String, FilmMetadata> METADATA = new HashMap<>();
    private static final Map<String, FrameTexture> FRAME_TEXTURES = new HashMap<>();
    private static final Set<String> REQUESTED = new HashSet<>();
    private static final Set<String> DELETED = new HashSet<>();
    private static final Set<String> FAILED_FRAME_WARNINGS = new HashSet<>();
    private static final ExecutorService DELETE_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "CreateCinema Client Film Delete");
        thread.setDaemon(true);
        return thread;
    });
    private static final Field NATIVE_IMAGE_PIXELS = findNativeImagePixels();

    public static Path root() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("createcinema").resolve("films");
    }

    public static Path filmDirectory(String filmId) {
        return root().resolve(filmId);
    }

    public static Path audioPath(String filmId) {
        return filmDirectory(filmId).resolve("audio.ogg");
    }

    public static Path videoPath(String filmId) {
        return filmDirectory(filmId).resolve("video.mp4");
    }

    public static boolean hasAudio(String filmId) {
        return Files.isRegularFile(audioPath(filmId));
    }

    public static void invalidate(String filmId) {
        ClientFilmVideoStreams.invalidateFilm(filmId);
        METADATA.remove(filmId);
        REQUESTED.remove(filmId);
    }

    public static boolean isDeleted(String filmId) {
        return DELETED.contains(filmId);
    }

    public static void delete(String filmId) {
        ClientFilmVideoStreams.invalidateFilm(filmId);
        DELETED.add(filmId);
        METADATA.remove(filmId);
        REQUESTED.remove(filmId);
        FAILED_FRAME_WARNINGS.remove(filmId);
        FrameTexture texture = FRAME_TEXTURES.remove(filmId);
        if (texture != null) Minecraft.getInstance().getTextureManager().release(texture.location);
        Path directory = filmDirectory(filmId);
        DELETE_EXECUTOR.execute(() -> {
            if (!Files.exists(directory)) return;
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (IOException e) {
                CreateCinema.LOGGER.warn("Failed to delete cached film {}", filmId, e);
            }
        });
    }

    public static FilmMetadata metadata(String filmId) {
        if (filmId.isBlank() || isDeleted(filmId)) return null;
        FilmMetadata cached = METADATA.get(filmId);
        if (cached != null) return cached;
        Path meta = filmDirectory(filmId).resolve("meta.json");
        if (!Files.isRegularFile(meta)) {
            request(filmId);
            return null;
        }
        try {
            FilmMetadata metadata = FilmMetadata.fromJson(Files.readString(meta, StandardCharsets.UTF_8));
            METADATA.put(filmId, metadata);
            return metadata;
        } catch (IOException e) {
            CreateCinema.LOGGER.warn("Failed to read cached film metadata {}", filmId, e);
            return null;
        }
    }

    public static ResourceLocation frameTexture(String filmId, int frameIndex) {
        if (isDeleted(filmId)) return null;
        FrameTexture cached = FRAME_TEXTURES.get(filmId);
        if (cached != null && cached.frameIndex == frameIndex) return cached.location;

        Path frame = filmDirectory(filmId).resolve("frames").resolve("%06d.jpg".formatted(frameIndex));
        if (!Files.isRegularFile(frame)) {
            request(filmId);
            return null;
        }
        try (InputStream in = Files.newInputStream(frame)) {
            NativeImage image = readJpeg(in);
            if (cached == null) {
                ResourceLocation location = ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID, "film/" + filmId.replace('-', '_'));
                DynamicTexture texture = new DynamicTexture(image);
                Minecraft.getInstance().getTextureManager().register(location, texture);
                FRAME_TEXTURES.put(filmId, new FrameTexture(location, texture, frameIndex));
                return location;
            }
            cached.texture.setPixels(image);
            cached.texture.upload();
            cached.frameIndex = frameIndex;
            FAILED_FRAME_WARNINGS.remove(filmId);
            return cached.location;
        } catch (IOException e) {
            if (FAILED_FRAME_WARNINGS.add(filmId)) {
                CreateCinema.LOGGER.warn("Failed to load film frame {} {}", filmId, frameIndex, e);
            }
            return null;
        }
    }

    private static NativeImage readJpeg(InputStream input) throws IOException {
        if (PlatformInfo.isAndroid()) {
            return readWithStb(input);
        }
        BufferedImage buffered = ImageIO.read(input);
        if (buffered == null) throw new IOException("Unsupported or corrupt film frame");

        NativeImage image = new NativeImage(buffered.getWidth(), buffered.getHeight(), false);
        for (int y = 0; y < buffered.getHeight(); y++) {
            for (int x = 0; x < buffered.getWidth(); x++) {
                int argb = buffered.getRGB(x, y);
                int alpha = argb >>> 24;
                int red = argb >> 16 & 0xFF;
                int green = argb >> 8 & 0xFF;
                int blue = argb & 0xFF;
                image.setPixelRGBA(x, y, FastColor.ABGR32.color(alpha, blue, green, red));
            }
        }
        return image;
    }

    private static NativeImage readWithStb(InputStream input) throws IOException {
        byte[] bytes = input.readAllBytes();
        ByteBuffer encoded = MemoryUtil.memAlloc(bytes.length);
        encoded.put(bytes).flip();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);
            ByteBuffer decoded = STBImage.stbi_load_from_memory(encoded, width, height, channels, 4);
            if (decoded == null) {
                String reason = STBImage.stbi_failure_reason();
                throw new IOException("Could not decode film frame" + (reason == null ? "" : ": " + reason));
            }
            NativeImage image = new NativeImage(width.get(0), height.get(0), false);
            try {
                long byteCount = (long) width.get(0) * height.get(0) * 4L;
                if (NATIVE_IMAGE_PIXELS != null) {
                    MemoryUtil.memCopy(MemoryUtil.memAddress(decoded), NATIVE_IMAGE_PIXELS.getLong(image), byteCount);
                } else {
                    for (int y = 0; y < height.get(0); y++) {
                        for (int x = 0; x < width.get(0); x++) {
                            int offset = (y * width.get(0) + x) * 4;
                            int red = decoded.get(offset) & 0xFF;
                            int green = decoded.get(offset + 1) & 0xFF;
                            int blue = decoded.get(offset + 2) & 0xFF;
                            int alpha = decoded.get(offset + 3) & 0xFF;
                            image.setPixelRGBA(x, y, FastColor.ABGR32.color(alpha, blue, green, red));
                        }
                    }
                }
                return image;
            } catch (Throwable error) {
                image.close();
                if (error instanceof IOException io) throw io;
                throw new IOException("Could not copy decoded film frame", error);
            } finally {
                STBImage.stbi_image_free(decoded);
            }
        } finally {
            MemoryUtil.memFree(encoded);
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

    static void request(String filmId) {
        if (REQUESTED.add(filmId)) {
            new C2SRequestFilmPacket(filmId).send();
        }
    }

    private static class FrameTexture {
        private final ResourceLocation location;
        private final DynamicTexture texture;
        private int frameIndex;

        private FrameTexture(ResourceLocation location, DynamicTexture texture, int frameIndex) {
            this.location = location;
            this.texture = texture;
            this.frameIndex = frameIndex;
        }
    }
}
