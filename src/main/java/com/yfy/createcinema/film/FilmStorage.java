package com.yfy.createcinema.film;

import com.yfy.createcinema.CreateCinema;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class FilmStorage {
    private static final int MAX_METADATA_BYTES = 1_048_576;
    private static final ExecutorService DELETE_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "CreateCinema Film Delete");
        thread.setDaemon(true);
        return thread;
    });

    public static Path serverFilmRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("createcinema").resolve("films");
    }

    public static Path serverFilmDirectory(MinecraftServer server, String filmId) {
        if (!isValidFilmId(filmId)) throw new IllegalArgumentException("Invalid film id");
        return serverFilmRoot(server).resolve(filmId);
    }

    public static boolean isValidFilmId(String filmId) {
        return filmId != null && filmId.matches("[A-Za-z0-9_-]{1,128}");
    }

    public static boolean exists(MinecraftServer server, String filmId) {
        return isValidFilmId(filmId) && Files.isDirectory(serverFilmDirectory(server, filmId));
    }

    public static Set<String> listFilmIds(MinecraftServer server) throws IOException {
        Path root = serverFilmRoot(server);
        if (!Files.isDirectory(root)) return Set.of();
        try (Stream<Path> paths = Files.list(root)) {
            return paths.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(FilmStorage::isValidFilmId)
                    .collect(Collectors.toSet());
        }
    }

    public static void delete(MinecraftServer server, String filmId) throws IOException {
        if (!isValidFilmId(filmId)) return;
        deleteDirectory(serverFilmDirectory(server, filmId));
    }

    public static void deleteAsync(MinecraftServer server, String filmId) {
        if (!isValidFilmId(filmId)) return;
        Path directory = serverFilmDirectory(server, filmId);
        DELETE_EXECUTOR.execute(() -> {
            try {
                deleteDirectory(directory);
            } catch (IOException e) {
                CreateCinema.LOGGER.warn("Failed to delete film {}", filmId, e);
            }
        });
    }

    public static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    public static FilmMetadata saveUploadedFilm(MinecraftServer server, Path zipFile) throws IOException {
        FilmMetadata metadata = readMetadata(zipFile);
        if (metadata == null || !isValidFilmId(metadata.id())) {
            throw new IOException("Film package is missing meta.json or id");
        }

        Path filmDir = serverFilmDirectory(server, metadata.id());
        Files.createDirectories(filmDir);
        Path zipTarget = filmDir.resolve("film.zip");
        Path metaTarget = filmDir.resolve("meta.json");
        Path temporaryZip = filmDir.resolve("film.zip." + UUID.randomUUID() + ".tmp");
        Path temporaryMeta = filmDir.resolve("meta.json." + UUID.randomUUID() + ".tmp");
        try {
            Files.copy(zipFile, temporaryZip, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(temporaryMeta, metadata.toJson(), StandardCharsets.UTF_8);
            moveReplace(temporaryZip, zipTarget);
            moveReplace(temporaryMeta, metaTarget);
            return metadata;
        } finally {
            Files.deleteIfExists(temporaryZip);
            Files.deleteIfExists(temporaryMeta);
        }
    }

    public static Path serverZipPath(MinecraftServer server, String filmId) {
        return serverFilmDirectory(server, filmId).resolve("film.zip");
    }

    public static FilmMetadata readServerMetadata(MinecraftServer server, String filmId) throws IOException {
        return FilmMetadata.fromJson(Files.readString(serverFilmDirectory(server, filmId).resolve("meta.json"), StandardCharsets.UTF_8));
    }

    public static FilmMetadata readMetadata(Path zipFile) throws IOException {
        try (InputStream input = Files.newInputStream(zipFile)) {
            return readMetadata(input);
        }
    }

    public static FilmMetadata readMetadata(InputStream input) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().equals("meta.json")) {
                    return FilmMetadata.fromJson(new String(readLimited(zip, MAX_METADATA_BYTES), StandardCharsets.UTF_8));
                }
            }
        }
        return null;
    }

    public static void extractZip(Path zipFile, Path targetDir) throws IOException {
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        Path parent = normalizedTarget.getParent();
        if (parent == null) throw new IOException("Film cache has no parent directory");
        Files.createDirectories(parent);
        Path temporaryTarget = parent.resolve(normalizedTarget.getFileName() + ".extract-" + UUID.randomUUID());
        try {
            Files.createDirectories(temporaryTarget);
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    Path out = temporaryTarget.resolve(entry.getName()).normalize();
                    if (!out.startsWith(temporaryTarget)) {
                        throw new IOException("Invalid film zip entry: " + entry.getName());
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(out);
                    } else {
                        Files.createDirectories(out.getParent());
                        Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            deleteDirectory(normalizedTarget);
            moveReplace(temporaryTarget, normalizedTarget);
        } finally {
            deleteDirectory(temporaryTarget);
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            CreateCinema.LOGGER.warn("SHA-256 unavailable", e);
            return "";
        }
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > limit) throw new IOException("Film metadata is too large");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
