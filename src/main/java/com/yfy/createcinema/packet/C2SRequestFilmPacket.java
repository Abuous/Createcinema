package com.yfy.createcinema.packet;

import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.film.FilmStorage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.IOException;

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
            if (!(ctx.player() instanceof ServerPlayer player) || filmId.isBlank()) return;
            try {
                byte[] zip = FilmStorage.readServerZip(player.server, filmId);
                int total = Math.max(1, (zip.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
                for (int i = 0; i < total; i++) {
                    int from = i * CHUNK_SIZE;
                    int to = Math.min(zip.length, from + CHUNK_SIZE);
                    byte[] chunk = java.util.Arrays.copyOfRange(zip, from, to);
                    new S2CDownloadFilmChunkPacket(filmId, i, total, chunk).sendTo(player);
                }
            } catch (IOException e) {
                CreateCinema.LOGGER.warn("Failed to send film {}", filmId, e);
            }
        });
    }
}
