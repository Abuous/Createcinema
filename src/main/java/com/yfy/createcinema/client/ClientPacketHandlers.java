package com.yfy.createcinema.client;

import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.film.FilmStorage;
import com.yfy.createcinema.packet.S2CBurnStatusPacket;
import com.yfy.createcinema.packet.S2CDownloadFilmChunkPacket;
import com.yfy.createcinema.packet.S2CFilmAvailablePacket;
import com.yfy.createcinema.packet.S2CFilmDeletedPacket;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ClientPacketHandlers {
    private static final int CHUNK_SIZE = 900_000;
    private static final Map<BlockPos, BurnProgress> BURN_STATUS = new ConcurrentHashMap<>();
    private static final Map<String, DownloadSession> DOWNLOADS = new ConcurrentHashMap<>();

    public static BurnProgress getBurnProgress(BlockPos pos) {
        return BURN_STATUS.getOrDefault(pos, BurnProgress.idle());
    }

    public static void setBurnStatus(BlockPos pos, String status) {
        setBurnProgress(pos, status, 0.0f, false);
    }

    public static void setBurnProgress(BlockPos pos, String status, float progress, boolean active) {
        BURN_STATUS.put(pos, new BurnProgress(status, clamp(progress), active));
    }

    public static void handleBurnStatus(S2CBurnStatusPacket packet) {
        setBurnProgress(packet.burnerPos(), packet.message(), packet.progress(), packet.active());
        if (!packet.active()) ClientVideoBurner.finish(packet.burnerPos());
    }

    private static float clamp(float progress) {
        return Math.max(0.0f, Math.min(1.0f, progress));
    }

    public record BurnProgress(String message, float progress, boolean active) {
        public static BurnProgress idle() {
            return new BurnProgress("Idle", 0.0f, false);
        }
    }

    public static void handleFilmChunk(S2CDownloadFilmChunkPacket packet) {
        if (!validChunk(packet)) {
            discardDownload(packet.filmId());
            return;
        }
        try {
            DownloadSession session = DOWNLOADS.get(packet.filmId());
            if (session == null) {
                session = new DownloadSession(packet.filmId(), packet.total());
                DOWNLOADS.put(packet.filmId(), session);
            }
            if (session.total != packet.total()) {
                discardDownload(packet.filmId());
                return;
            }
            session.write(packet.index(), packet.data());
            if (!session.complete()) return;

            try {
                DOWNLOADS.remove(packet.filmId());
                Path packageFile = session.finish();
                FilmStorage.extractZip(packageFile, ClientFilmCache.filmDirectory(packet.filmId()));
                ClientFilmCache.invalidate(packet.filmId());
                CreateCinema.LOGGER.info("Cached downloaded film {}", packet.filmId());
            } finally {
                session.delete();
            }
        } catch (IOException e) {
            discardDownload(packet.filmId());
            ClientFilmCache.invalidate(packet.filmId());
            CreateCinema.LOGGER.warn("Failed to cache downloaded film {}", packet.filmId(), e);
        }
    }

    public static void handleFilmDeleted(S2CFilmDeletedPacket packet) {
        discardDownload(packet.filmId());
        ClientFilmCache.delete(packet.filmId());
        ClientProjectorAudio.stopFilm(packet.filmId());
    }

    public static void clearDownloads() {
        DOWNLOADS.forEach((filmId, session) -> session.delete());
        DOWNLOADS.clear();
    }

    public static void handleFilmAvailable(S2CFilmAvailablePacket packet) {
        discardDownload(packet.filmId());
        ClientFilmCache.restore(packet.filmId());
    }

    private static boolean validChunk(S2CDownloadFilmChunkPacket packet) {
        return FilmStorage.isValidFilmId(packet.filmId()) && packet.index() >= 0 && packet.total() > 0 && packet.index() < packet.total()
                && packet.data().length > 0 && packet.data().length <= CHUNK_SIZE
                && (packet.index() == packet.total() - 1 || packet.data().length == CHUNK_SIZE);
    }

    private static void discardDownload(String filmId) {
        DownloadSession session = DOWNLOADS.remove(filmId);
        if (session != null) session.delete();
    }

    private static class DownloadSession {
        private final int total;
        private final Path packageFile;
        private final FileChannel channel;
        private final Set<Integer> received = new HashSet<>();

        private DownloadSession(String filmId, int total) throws IOException {
            this.total = total;
            Path root = ClientFilmCache.root().resolve(".downloads");
            Files.createDirectories(root);
            this.packageFile = Files.createTempFile(root, filmId + "-", ".zip");
            this.channel = FileChannel.open(packageFile, StandardOpenOption.WRITE);
        }

        private void write(int index, byte[] data) throws IOException {
            if (!received.add(index)) return;
            channel.position(Math.multiplyExact((long) index, CHUNK_SIZE));
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) channel.write(buffer);
        }

        private boolean complete() {
            return received.size() == total;
        }

        private Path finish() throws IOException {
            channel.close();
            return packageFile;
        }

        private void delete() {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            try {
                Files.deleteIfExists(packageFile);
            } catch (IOException e) {
                CreateCinema.LOGGER.debug("Failed to delete temporary film download {}", packageFile, e);
            }
        }
    }
}
