package com.yfy.createcinema.packet;

import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.blockentity.NetworkProjectorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record C2SNetworkProjectorMediaInfoPacket(BlockPos projectorPos, String url, int mediaRevision,
                                                 double durationSeconds, double timeSeconds, boolean live,
                                                 NetworkProjectorBlockEntity.MediaStatus status)
        implements CustomPacketPayload {
    public static final Type<C2SNetworkProjectorMediaInfoPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID, "network_projector_media_info"));
    public static final StreamCodec<RegistryFriendlyByteBuf, C2SNetworkProjectorMediaInfoPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public C2SNetworkProjectorMediaInfoPacket decode(RegistryFriendlyByteBuf buf) {
            return new C2SNetworkProjectorMediaInfoPacket(BlockPos.STREAM_CODEC.decode(buf),
                    buf.readUtf(NetworkProjectorBlockEntity.MAX_URL_LENGTH), buf.readVarInt(), buf.readDouble(),
                    buf.readDouble(), buf.readBoolean(), buf.readEnum(NetworkProjectorBlockEntity.MediaStatus.class));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, C2SNetworkProjectorMediaInfoPacket packet) {
            BlockPos.STREAM_CODEC.encode(buf, packet.projectorPos);
            buf.writeUtf(packet.url, NetworkProjectorBlockEntity.MAX_URL_LENGTH);
            buf.writeVarInt(packet.mediaRevision);
            buf.writeDouble(packet.durationSeconds);
            buf.writeDouble(packet.timeSeconds);
            buf.writeBoolean(packet.live);
            buf.writeEnum(packet.status);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void send() {
        PacketDistributor.sendToServer(this);
    }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !(player.level().getBlockEntity(projectorPos) instanceof NetworkProjectorBlockEntity projector)) {
                return;
            }
            projector.updateMediaInfo(player.getUUID(), url, mediaRevision, durationSeconds, timeSeconds, live, status);
        });
    }
}
