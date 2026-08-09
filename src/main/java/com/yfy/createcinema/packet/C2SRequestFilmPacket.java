package com.yfy.createcinema.packet;

import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.film.FilmStorage;
import com.yfy.createcinema.film.FilmReferenceData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public record C2SRequestFilmPacket(String filmId) implements CustomPacketPayload {
    private static final int CHUNK_SIZE = 900_000;
    public static final CustomPacketPayload.Type<C2SRequestFilmPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID, "request_film"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SRequestFilmPacket> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, C2SRequestFilmPacket::filmId, C2SRequestFilmPacket::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void send() {
        PacketDistributor.sendToServer(this);
    }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player) || !FilmStorage.isValidFilmId(filmId)) return;
            if (FilmReferenceData.get(player.server).isDeleted(filmId)) {
                new S2CFilmDeletedPacket(filmId).sendTo(player);
                return;
            }
            try {
                Path zip = FilmStorage.serverZipPath(player.server, filmId);
                long size = Files.size(zip);
                long chunks = Math.max(1L, (size + CHUNK_SIZE - 1L) / CHUNK_SIZE);
                if (chunks > Integer.MAX_VALUE) throw new IOException("Film package is too large for the transfer protocol");
                try (InputStream input = Files.newInputStream(zip)) {
                    for (int i = 0; i < (int) chunks; i++) {
                        byte[] chunk = input.readNBytes(CHUNK_SIZE);
                        if (chunk.length == 0) throw new IOException("Film package ended before all chunks were sent");
                        new S2CDownloadFilmChunkPacket(filmId, i, (int) chunks, chunk).sendTo(player);
                    }
                }
            } catch (IOException e) {
                CreateCinema.LOGGER.warn("Failed to send film {}", filmId, e);
            }
        });
    }
}
