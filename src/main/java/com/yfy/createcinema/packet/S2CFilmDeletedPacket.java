package com.yfy.createcinema.packet;

import com.yfy.createcinema.client.network.ClientPacketHandlers;
import com.yfy.createcinema.CreateCinema;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record S2CFilmDeletedPacket(String filmId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<S2CFilmDeletedPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID, "film_deleted"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CFilmDeletedPacket> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, S2CFilmDeletedPacket::filmId, S2CFilmDeletedPacket::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void sendTo(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, this);
    }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.yfy.createcinema.client.network.ClientPacketHandlers.handleFilmDeleted(this));
    }
}
