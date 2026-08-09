package com.yfy.createcinema.packet;

import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.blockentity.BurnerBlockEntity;
import com.yfy.createcinema.film.MediaType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record C2SBurnStatePacket(BlockPos burnerPos, boolean active, MediaType mediaType) implements CustomPacketPayload {
    public C2SBurnStatePacket(BlockPos burnerPos, boolean active) {
        this(burnerPos, active, MediaType.VIDEO);
    }
    public static final Type<C2SBurnStatePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID, "burn_state"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SBurnStatePacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public C2SBurnStatePacket decode(RegistryFriendlyByteBuf buf) {
            return new C2SBurnStatePacket(BlockPos.STREAM_CODEC.decode(buf), buf.readBoolean(),
                    MediaType.fromId(buf.readUtf(16)));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, C2SBurnStatePacket packet) {
            BlockPos.STREAM_CODEC.encode(buf, packet.burnerPos);
            buf.writeBoolean(packet.active);
            buf.writeUtf(packet.mediaType.id(), 16);
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
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (!(player.level().getBlockEntity(burnerPos) instanceof BurnerBlockEntity burner)) return;
            if (player.distanceToSqr(burnerPos.getX() + 0.5, burnerPos.getY() + 0.5, burnerPos.getZ() + 0.5) > 64.0) return;

            if (active && !burner.hasBlankFilm()) {
                new S2CBurnStatusPacket(burnerPos, "Insert a blank film", 0.0f, false).sendTo(player);
                return;
            }
            if (active && burner.getBlankMediaType() != mediaType) {
                new S2CBurnStatusPacket(burnerPos, "Media type changed", 0.0f, false).sendTo(player);
                return;
            }
            burner.setBurning(active);
            if (!active) C2SUploadFilmChunkPacket.cancelUploads(player, burnerPos);
        });
    }
}
