package com.yfy.createcinema.packet;

import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.blockentity.NetworkProjectorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record C2SNetworkProjectorPlaybackHealthPacket(BlockPos projectorPos, String url, int navigationRevision,
                                                       NetworkProjectorBlockEntity.PlaybackHealth health)
        implements CustomPacketPayload {
    public static final Type<C2SNetworkProjectorPlaybackHealthPacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID, "network_projector_playback_health"));
    public static final StreamCodec<RegistryFriendlyByteBuf, C2SNetworkProjectorPlaybackHealthPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public C2SNetworkProjectorPlaybackHealthPacket decode(RegistryFriendlyByteBuf buf) {
                    return new C2SNetworkProjectorPlaybackHealthPacket(BlockPos.STREAM_CODEC.decode(buf),
                            buf.readUtf(NetworkProjectorBlockEntity.MAX_URL_LENGTH), buf.readVarInt(),
                            buf.readEnum(NetworkProjectorBlockEntity.PlaybackHealth.class));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, C2SNetworkProjectorPlaybackHealthPacket packet) {
                    BlockPos.STREAM_CODEC.encode(buf, packet.projectorPos);
                    buf.writeUtf(packet.url, NetworkProjectorBlockEntity.MAX_URL_LENGTH);
                    buf.writeVarInt(packet.navigationRevision);
                    buf.writeEnum(packet.health);
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
                    || player.distanceToSqr(Vec3.atCenterOf(projectorPos)) > 128.0 * 128.0
                    || !player.level().hasChunkAt(projectorPos)
                    || !(player.level().getBlockEntity(projectorPos) instanceof NetworkProjectorBlockEntity projector)) {
                return;
            }
            projector.reportPlaybackHealth(player, url, navigationRevision, health);
        });
    }
}
