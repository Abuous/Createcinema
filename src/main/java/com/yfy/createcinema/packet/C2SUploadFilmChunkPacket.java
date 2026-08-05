package com.yfy.createcinema.packet;

import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.blockentity.BurnerBlockEntity;
import com.yfy.createcinema.film.FilmMetadata;
import com.yfy.createcinema.film.FilmStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public record C2SUploadFilmChunkPacket(UUID uploadId, BlockPos burnerPos, int index, int total, byte[] data) implements CustomPacketPayload {
    private static final int MAX_CHUNK_BYTES = 900_000;
    private static final Map<UUID, UploadSession> UPLOADS = new HashMap<>();

    public static final CustomPacketPayload.Type<C2SUploadFilmChunkPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID, "upload_film_chunk"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SUploadFilmChunkPacket> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public C2SUploadFilmChunkPacket decode(RegistryFriendlyByteBuf buf) {
            return new C2SUploadFilmChunkPacket(buf.readUUID(), BlockPos.STREAM_CODEC.decode(buf), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(MAX_CHUNK_BYTES));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, C2SUploadFilmChunkPacket packet) {
            buf.writeUUID(packet.uploadId);
            BlockPos.STREAM_CODEC.encode(buf, packet.burnerPos);
            buf.writeVarInt(packet.index);
            buf.writeVarInt(packet.total);
            buf.writeByteArray(packet.data);
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void send() {
        PacketDistributor.sendToServer(this);
    }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (index < 0 || total <= 0 || index >= total || data.length > MAX_CHUNK_BYTES) return;
            if (!(player.level().getBlockEntity(burnerPos) instanceof BurnerBlockEntity burner)
                    || !burner.hasBlankFilm() || !burner.isBurning()) {
                UPLOADS.remove(uploadId);
                new S2CBurnStatusPacket(burnerPos, "Burn cancelled: blank film removed", 0.0f, false).sendTo(player);
                return;
            }

            UploadSession session = UPLOADS.computeIfAbsent(uploadId, id -> new UploadSession(total));
            if (session.total != total) {
                UPLOADS.remove(uploadId);
                new S2CBurnStatusPacket(burnerPos, "Upload failed: chunk count changed", 0.0f, false).sendTo(player);
                return;
            }
            session.chunks[index] = data;
            float uploadProgress = 0.90f + 0.08f * (session.receivedCount() / (float) total);
            new S2CBurnStatusPacket(burnerPos, "Uploading " + (session.receivedCount() * 100 / total) + "%", uploadProgress, true).sendTo(player);

            if (!session.complete()) return;
            UPLOADS.remove(uploadId);
            try {
                byte[] zip = session.join();
                FilmMetadata metadata = FilmStorage.saveUploadedFilm(player.server, zip);
                if (burner.hasBlankFilm()) {
                    burner.writeFilmId(metadata.id(), metadata.title(), metadata.durationSeconds());
                    new S2CBurnStatusPacket(burnerPos, "Burn complete: " + metadata.title(), 1.0f, false).sendTo(player);
                } else {
                    new S2CBurnStatusPacket(burnerPos, "Burner has no blank film", 0.0f, false).sendTo(player);
                }
            } catch (IOException e) {
                burner.setBurning(false);
                CreateCinema.LOGGER.warn("Failed to save uploaded film", e);
                new S2CBurnStatusPacket(burnerPos, "Burn failed: " + e.getMessage(), 0.0f, false).sendTo(player);
            }
        });
    }

    private static class UploadSession {
        private final int total;
        private final byte[][] chunks;

        private UploadSession(int total) {
            this.total = total;
            this.chunks = new byte[total][];
        }

        private int receivedCount() {
            int count = 0;
            for (byte[] chunk : chunks) if (chunk != null) count++;
            return count;
        }

        private boolean complete() {
            return receivedCount() == total;
        }

        private byte[] join() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (byte[] chunk : chunks) out.write(chunk);
            return out.toByteArray();
        }
    }
}
