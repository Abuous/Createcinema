package com.yfy.createcinema.film;

import com.yfy.createcinema.CreateCinema;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class FilmStorage {
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
        Path directory = serverFilmDirectory(server, filmId);
        if (!Files.exists(directory)) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
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

    private static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    public static FilmMetadata saveUploadedFilm(MinecraftServer server, byte[] zipBytes) throws IOException {
        FilmMetadata metadata = readMetadata(zipBytes);
        if (metadata == null || !isValidFilmId(metadata.id())) {
            throw new IOException("Film package is missing meta.json or id");
        }

        Path filmDir = serverFilmDirectory(server, metadata.id());
        Files.createDirectories(filmDir);
        Files.write(filmDir.resolve("film.zip"), zipBytes);
        Files.writeString(filmDir.resolve("meta.json"), metadata.toJson(), StandardCharsets.UTF_8);
        return metadata;
    }

    public static byte[] readServerZip(MinecraftServer server, String filmId) throws IOException {
        return Files.readAllBytes(serverFilmDirectory(server, filmId).resolve("film.zip"));
    }

    public static FilmMetadata readServerMetadata(MinecraftServer server, String filmId) throws IOException {
        return FilmMetadata.fromJson(Files.readString(serverFilmDirectory(server, filmId).resolve("meta.json"), StandardCharsets.UTF_8));
    }

    public static FilmMetadata readMetadata(byte[] zipBytes) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().equals("meta.json")) {
                    return FilmMetadata.fromJson(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return null;
    }

    public static void extractZip(byte[] zipBytes, Path targetDir) throws IOException {
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        Files.createDirectories(normalizedTarget);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path out = normalizedTarget.resolve(entry.getName()).normalize();
                if (!out.startsWith(normalizedTarget)) {
                    throw new IOException("Invalid film zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.write(out, zip.readAllBytes());
                }
            }
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
}
