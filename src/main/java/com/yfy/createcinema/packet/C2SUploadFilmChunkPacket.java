package com.yfy.createcinema.packet;

import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.blockentity.BurnerBlockEntity;
import com.yfy.createcinema.film.FilmMetadata;
import com.yfy.createcinema.film.FilmStorage;
import com.yfy.createcinema.item.FilmItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@EventBusSubscriber(modid = CreateCinema.MODID)
public record C2SUploadFilmChunkPacket(UUID uploadId, BlockPos burnerPos, int index, int total, byte[] data) implements CustomPacketPayload {
    private static final int MAX_CHUNK_BYTES = 900_000;
    private static final long SESSION_TIMEOUT_MILLIS = 10 * 60 * 1000L;
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

    public static void cancelUploads(ServerPlayer player, BlockPos burnerPos) {
        UPLOADS.entrySet().removeIf(entry -> {
            UploadSession session = entry.getValue();
            if (!session.owner.equals(player.getUUID()) || !session.burnerPos.equals(burnerPos)) return false;
            session.delete();
            return true;
        });
    }

    @SubscribeEvent
    public static void playerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            UPLOADS.entrySet().removeIf(entry -> {
                if (!entry.getValue().owner.equals(player.getUUID())) return false;
                entry.getValue().delete();
                return true;
            });
        }
    }

    public void handle(IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            discardExpiredUploads();
            if (!validChunk()) return;
            if (!(player.level().getBlockEntity(burnerPos) instanceof BurnerBlockEntity burner)
                    || !burner.hasBlankFilm() || !burner.isBurning()) {
                discard(uploadId);
                new S2CBurnStatusPacket(burnerPos, "Burn cancelled: blank film removed", 0.0f, false).sendTo(player);
                return;
            }

            try {
                UploadSession session = UPLOADS.get(uploadId);
                if (session == null) {
                    session = new UploadSession(player, burnerPos, total);
                    UPLOADS.put(uploadId, session);
                }
                if (!session.matches(player, burnerPos, total)) {
                    discard(uploadId);
                    new S2CBurnStatusPacket(burnerPos, "Upload failed: transfer changed", 0.0f, false).sendTo(player);
                    return;
                }
                session.write(index, data);
                float uploadProgress = 0.90f + 0.08f * (session.receivedCount() / (float) total);
                new S2CBurnStatusPacket(burnerPos, "Uploading " + (session.receivedCount() * 100 / total) + "%", uploadProgress, true).sendTo(player);
                if (!session.complete()) return;

                try {
                    UPLOADS.remove(uploadId);
                    Path packageFile = session.finish();
                    FilmMetadata metadata = FilmStorage.saveUploadedFilm(player.server, packageFile);
                    if (burner.hasBlankFilm() && FilmItem.getMediaType(burner.getFilm()) == metadata.mediaTypeValue()) {
                        burner.writeFilmId(metadata.id(), metadata.title(), metadata.durationSeconds(), metadata.mediaTypeValue(), metadata.frameCount());
                        new S2CBurnStatusPacket(burnerPos, "Burn complete: " + metadata.title(), 1.0f, false).sendTo(player);
                    } else {
                        new S2CBurnStatusPacket(burnerPos, "Burner has no blank film", 0.0f, false).sendTo(player);
                    }
                } finally {
                    session.delete();
                }
            } catch (IOException e) {
                discard(uploadId);
                burner.setBurning(false);
                CreateCinema.LOGGER.warn("Failed to save uploaded film", e);
                new S2CBurnStatusPacket(burnerPos, "Burn failed: " + e.getMessage(), 0.0f, false).sendTo(player);
            }
        });
    }

    private boolean validChunk() {
        return index >= 0 && total > 0 && index < total && data.length > 0 && data.length <= MAX_CHUNK_BYTES
                && (index == total - 1 || data.length == MAX_CHUNK_BYTES);
    }

    private static void discard(UUID uploadId) {
        UploadSession session = UPLOADS.remove(uploadId);
        if (session != null) session.delete();
    }

    private static void discardExpiredUploads() {
        long cutoff = System.currentTimeMillis() - SESSION_TIMEOUT_MILLIS;
        UPLOADS.entrySet().removeIf(entry -> {
            if (entry.getValue().lastUpdated >= cutoff) return false;
            entry.getValue().delete();
            return true;
        });
    }

    private static class UploadSession {
        private final UUID owner;
        private final BlockPos burnerPos;
        private final int total;
        private final Path packageFile;
        private final FileChannel channel;
        private final Set<Integer> received = new HashSet<>();
        private long lastUpdated = System.currentTimeMillis();

        private UploadSession(ServerPlayer player, BlockPos burnerPos, int total) throws IOException {
            this.owner = player.getUUID();
            this.burnerPos = burnerPos.immutable();
            this.total = total;
            Path root = FilmStorage.serverFilmRoot(player.server).resolve(".uploads");
            Files.createDirectories(root);
            deleteExpiredFiles(root);
            this.packageFile = Files.createTempFile(root, "upload-", ".zip");
            this.channel = FileChannel.open(packageFile, StandardOpenOption.WRITE);
        }

        private boolean matches(ServerPlayer player, BlockPos burnerPos, int total) {
            return owner.equals(player.getUUID()) && this.burnerPos.equals(burnerPos) && this.total == total;
        }

        private void write(int index, byte[] data) throws IOException {
            lastUpdated = System.currentTimeMillis();
            if (!received.add(index)) return;
            channel.position(Math.multiplyExact((long) index, MAX_CHUNK_BYTES));
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) channel.write(buffer);
        }

        private int receivedCount() {
            return received.size();
        }

        private boolean complete() {
            return received.size() == total;
        }

        private Path finish() throws IOException {
            channel.close();
            return packageFile;
        }

        private void delete() {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            try {
                Files.deleteIfExists(packageFile);
            } catch (IOException e) {
                CreateCinema.LOGGER.debug("Failed to delete temporary film upload {}", packageFile, e);
            }
        }

        private static void deleteExpiredFiles(Path root) throws IOException {
            long cutoff = System.currentTimeMillis() - SESSION_TIMEOUT_MILLIS;
            try (var files = Files.list(root)) {
                for (Path file : files.toList()) {
                    boolean active = UPLOADS.values().stream().anyMatch(session -> session.packageFile.equals(file));
                    if (!active && Files.getLastModifiedTime(file).toMillis() < cutoff) Files.deleteIfExists(file);
                }
            }
        }
    }
}
