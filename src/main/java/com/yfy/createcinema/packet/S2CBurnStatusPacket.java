package com.yfy.createcinema.packet;

import com.yfy.createcinema.CreateCinema;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record S2CBurnStatusPacket(BlockPos burnerPos, String message, float progress, boolean active) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<S2CBurnStatusPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID, "burn_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CBurnStatusPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public S2CBurnStatusPacket decode(RegistryFriendlyByteBuf buf) {
            return new S2CBurnStatusPacket(BlockPos.STREAM_CODEC.decode(buf), buf.readUtf(), buf.readFloat(), buf.readBoolean());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, S2CBurnStatusPacket packet) {
            BlockPos.STREAM_CODEC.encode(buf, packet.burnerPos);
            buf.writeUtf(packet.message);
            buf.writeFloat(packet.progress);
            buf.writeBoolean(packet.active);
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
        ctx.enqueueWork(() -> com.yfy.createcinema.client.ClientPacketHandlers.handleBurnStatus(this));
    }
}
