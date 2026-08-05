package com.yfy.createcinema.client;

import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.film.FilmStorage;
import com.yfy.createcinema.packet.S2CBurnStatusPacket;
import com.yfy.createcinema.packet.S2CDownloadFilmChunkPacket;
import com.yfy.createcinema.packet.S2CFilmDeletedPacket;
import net.minecraft.core.BlockPos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClientPacketHandlers {
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
        if (!packet.active()) {
            ClientVideoBurner.finish(packet.burnerPos());
        }
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
        DownloadSession session = DOWNLOADS.computeIfAbsent(packet.filmId(), id -> new DownloadSession(packet.total()));
        if (session.total != packet.total()) {
            DOWNLOADS.remove(packet.filmId());
            return;
        }
        session.chunks[packet.index()] = packet.data();
        if (!session.complete()) return;

        DOWNLOADS.remove(packet.filmId());
        try {
            byte[] zip = session.join();
            FilmStorage.extractZip(zip, ClientFilmCache.filmDirectory(packet.filmId()));
            ClientFilmCache.invalidate(packet.filmId());
            CreateCinema.LOGGER.info("Cached downloaded film {}", packet.filmId());
        } catch (IOException e) {
            ClientFilmCache.invalidate(packet.filmId());
            CreateCinema.LOGGER.warn("Failed to cache downloaded film {}", packet.filmId(), e);
        }
    }

    public static void handleFilmDeleted(S2CFilmDeletedPacket packet) {
        DOWNLOADS.remove(packet.filmId());
        ClientFilmCache.delete(packet.filmId());
        ClientProjectorAudio.stopFilm(packet.filmId());
    }

    private static class DownloadSession {
        private final int total;
        private final byte[][] chunks;

        private DownloadSession(int total) {
            this.total = total;
            this.chunks = new byte[total][];
        }

        private boolean complete() {
            for (byte[] chunk : chunks) if (chunk == null) return false;
            return true;
        }

        private byte[] join() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (byte[] chunk : chunks) out.write(chunk);
            return out.toByteArray();
        }
    }
}
