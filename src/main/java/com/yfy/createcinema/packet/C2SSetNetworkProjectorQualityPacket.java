package com.yfy.createcinema.packet;

import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.NetworkVideoQuality;
import com.yfy.createcinema.blockentity.NetworkProjectorBlockEntity;
import com.yfy.createcinema.gui.NetworkProjectorMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record C2SSetNetworkProjectorQualityPacket(BlockPos projectorPos, int qualityId)
        implements CustomPacketPayload {
    public static final Type<C2SSetNetworkProjectorQualityPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID, "set_network_projector_quality"));
    public static final StreamCodec<RegistryFriendlyByteBuf, C2SSetNetworkProjectorQualityPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public C2SSetNetworkProjectorQualityPacket decode(RegistryFriendlyByteBuf buf) {
                    return new C2SSetNetworkProjectorQualityPacket(BlockPos.STREAM_CODEC.decode(buf), buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, C2SSetNetworkProjectorQualityPacket packet) {
                    BlockPos.STREAM_CODEC.encode(buf, packet.projectorPos);
                    buf.writeVarInt(packet.qualityId);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void send() { PacketDistributor.sendToServer(this); }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !(player.containerMenu instanceof NetworkProjectorMenu menu)
                    || !menu.pos.equals(projectorPos) || !menu.stillValid(player)
                    || !(player.level().getBlockEntity(projectorPos) instanceof NetworkProjectorBlockEntity projector)) return;
            projector.setQuality(NetworkVideoQuality.byId(qualityId));
        });
    }
}
