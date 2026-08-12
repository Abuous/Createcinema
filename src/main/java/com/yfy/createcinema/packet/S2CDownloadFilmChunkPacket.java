package com.yfy.createcinema.packet;

import com.yfy.createcinema.client.network.ClientPacketHandlers;
import com.yfy.createcinema.CreateCinema;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record S2CDownloadFilmChunkPacket(String filmId, int index, int total, byte[] data) implements CustomPacketPayload {
    private static final int MAX_CHUNK_BYTES = 900_000;
    public static final CustomPacketPayload.Type<S2CDownloadFilmChunkPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID, "download_film_chunk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CDownloadFilmChunkPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public S2CDownloadFilmChunkPacket decode(RegistryFriendlyByteBuf buf) {
            return new S2CDownloadFilmChunkPacket(buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(MAX_CHUNK_BYTES));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, S2CDownloadFilmChunkPacket packet) {
            buf.writeUtf(packet.filmId);
            buf.writeVarInt(packet.index);
            buf.writeVarInt(packet.total);
            buf.writeByteArray(packet.data);
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void sendTo(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, this);
    }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.yfy.createcinema.client.network.ClientPacketHandlers.handleFilmChunk(this));
    }
}
