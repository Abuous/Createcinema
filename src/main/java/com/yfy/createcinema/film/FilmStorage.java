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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class FilmStorage {
    public static Path serverFilmRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("createcinema").resolve("films");
    }

    public static Path serverFilmDirectory(MinecraftServer server, String filmId) {
        return serverFilmRoot(server).resolve(filmId);
    }

    public static FilmMetadata saveUploadedFilm(MinecraftServer server, byte[] zipBytes) throws IOException {
        FilmMetadata metadata = readMetadata(zipBytes);
        if (metadata == null || metadata.id().isBlank()) {
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
