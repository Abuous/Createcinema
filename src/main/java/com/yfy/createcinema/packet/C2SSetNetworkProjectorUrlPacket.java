package com.yfy.createcinema.packet;
import com.yfy.createcinema.CreateCinema;
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
import java.net.URI;
public record C2SSetNetworkProjectorUrlPacket(BlockPos projectorPos, String url) implements CustomPacketPayload {
    public static final Type<C2SSetNetworkProjectorUrlPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID, "set_network_projector_url"));
    public static final StreamCodec<RegistryFriendlyByteBuf, C2SSetNetworkProjectorUrlPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override public C2SSetNetworkProjectorUrlPacket decode(RegistryFriendlyByteBuf buf) { return new C2SSetNetworkProjectorUrlPacket(BlockPos.STREAM_CODEC.decode(buf), buf.readUtf(NetworkProjectorBlockEntity.MAX_URL_LENGTH)); }
        @Override public void encode(RegistryFriendlyByteBuf buf, C2SSetNetworkProjectorUrlPacket packet) { BlockPos.STREAM_CODEC.encode(buf, packet.projectorPos); buf.writeUtf(packet.url, NetworkProjectorBlockEntity.MAX_URL_LENGTH); }
    };
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    public void send() { PacketDistributor.sendToServer(this); }
    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !(player.containerMenu instanceof NetworkProjectorMenu menu)
                    || !menu.pos.equals(projectorPos) || !menu.stillValid(player)
                    || !(player.level().getBlockEntity(projectorPos) instanceof NetworkProjectorBlockEntity projector)) return;
            String value = url.trim(); if (!value.isEmpty() && !isValidWebUrl(value)) return; projector.setUrl(value, player.getUUID());
        });
    }
    private static boolean isValidWebUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return (scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException e) { return false; }
    }
}
