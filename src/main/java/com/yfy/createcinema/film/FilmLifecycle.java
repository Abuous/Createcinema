package com.yfy.createcinema.film;

import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.ModRegistry;
import com.yfy.createcinema.item.FilmItem;
import com.yfy.createcinema.packet.S2CFilmDeletedPacket;
import com.yfy.createcinema.packet.S2CFilmAvailablePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

public final class FilmLifecycle {
    private FilmLifecycle() {
    }

    public static void registerCopy(MinecraftServer server, ItemStack stack) {
        String filmId = FilmItem.getFilmId(stack);
        String copyId = FilmItem.getCopyId(stack);
        FilmReferenceData data = FilmReferenceData.get(server);
        data.registerCopy(filmId, copyId);
        if (!filmId.isBlank() && !copyId.isBlank()) {
            CreateCinema.LOGGER.info("Registered film copy {} for {} ({} references)", copyId, filmId,
                    data.referenceCount(filmId));
        }
    }

    public static ItemStack createFilmCopy(MinecraftServer server, String filmId) throws IOException {
        FilmReferenceData data = FilmReferenceData.get(server);
        if (!FilmStorage.isValidFilmId(filmId) || data.isDeleted(filmId) || !FilmStorage.exists(server, filmId)) {
            return ItemStack.EMPTY;
        }

        FilmMetadata metadata = FilmStorage.readServerMetadata(server, filmId);
        if (metadata == null || !filmId.equals(metadata.id())) return ItemStack.EMPTY;

        ItemStack stack = FilmItem.create(switch (metadata.mediaTypeValue()) {
            case IMAGE -> ModRegistry.BLANK_IMAGE.get();
            case ALBUM -> ModRegistry.BLANK_ALBUM.get();
            case SLIDES -> ModRegistry.BLANK_SLIDES.get();
            case VIDEO -> ModRegistry.FILM.get();
        }, metadata.id(), metadata.title());
        FilmItem.setDurationSeconds(stack, metadata.durationSeconds());
        FilmItem.setRecorded(stack, metadata.id(), metadata.title(), metadata.durationSeconds(),
                metadata.mediaTypeValue(), metadata.frameCount());
        registerCopy(server, stack);
        return stack;
    }

    public static void releaseDestroyedCopy(MinecraftServer server, ItemStack stack) {
        releaseDestroyedCopy(server, stack, "expired");
    }

    public static void releaseDestroyedCopy(MinecraftServer server, ItemStack stack, String reason) {
        releaseDestroyedCopy(server, stack, reason, null);
    }

    public static void releaseDestroyedCopy(MinecraftServer server, ItemStack stack, String reason,
                                            ItemEntity destroyedEntity) {
        String filmId = FilmItem.getFilmId(stack);
        String copyId = FilmItem.getCopyId(stack);
        if (filmId.isBlank() || copyId.isBlank()) return;

        FilmReferenceData data = FilmReferenceData.get(server);
        if (!data.hasCopy(filmId, copyId)) {
            CreateCinema.LOGGER.warn("Destroyed film copy {} for {} was not registered ({})", copyId, filmId, reason);
            return;
        }
        if (hasSurvivingCopy(server, filmId, copyId, destroyedEntity)) {
            CreateCinema.LOGGER.info("Kept film reference {} for {} after {} because another matching copy exists",
                    copyId, filmId, reason);
            return;
        }
        boolean lastReference = data.releaseCopy(filmId, copyId);
        CreateCinema.LOGGER.info("Released film copy {} for {} after {} ({} references remain)", copyId, filmId,
                reason, data.referenceCount(filmId));
        if (lastReference) {
            CreateCinema.LOGGER.info("Deleting unreferenced film {}", filmId);
            deleteFilm(server, filmId);
        }
    }

    public static boolean normalizeCreativeInventory(ServerPlayer player) {
        FilmReferenceData data = FilmReferenceData.get(player.server);
        Set<String> seenCopies = new HashSet<>();
        boolean changed = false;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            String filmId = FilmItem.getFilmId(stack);
            if (!(stack.getItem() instanceof FilmItem) || filmId.isBlank() || data.isDeleted(filmId)) continue;

            String copyId = FilmItem.getCopyId(stack);
            String key = filmId + "\u0000" + copyId;
            if (copyId.isBlank() || !seenCopies.add(key)) {
                copyId = FilmItem.newCopyId();
                FilmItem.setCopyId(stack, copyId);
                seenCopies.add(filmId + "\u0000" + copyId);
                registerCopy(player.server, stack);
                changed = true;
            } else if (!data.hasCopy(filmId, copyId)) {
                registerCopy(player.server, stack);
            }
        }
        return changed;
    }

    private static boolean hasSurvivingCopy(MinecraftServer server, String filmId, String copyId,
                                            ItemEntity destroyedEntity) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                if (matches(player.getInventory().getItem(slot), filmId, copyId)) return true;
            }
        }
        for (var level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof ItemEntity itemEntity && itemEntity != destroyedEntity
                        && !itemEntity.isRemoved() && matches(itemEntity.getItem(), filmId, copyId)) return true;
            }
        }
        return false;
    }

    private static boolean matches(ItemStack stack, String filmId, String copyId) {
        return stack.getItem() instanceof FilmItem && FilmItem.getFilmId(stack).equals(filmId)
                && FilmItem.getCopyId(stack).equals(copyId);
    }

    public static boolean deleteFilm(MinecraftServer server, String filmId) {
        if (!FilmStorage.isValidFilmId(filmId)) return false;
        FilmReferenceData data = FilmReferenceData.get(server);
        boolean existed = data.isKnown(filmId) || FilmStorage.exists(server, filmId);
        if (!existed) return false;

        data.markDeleted(filmId);
        try {
            FilmStorage.deleteAsync(server, filmId);
        } catch (RuntimeException e) {
            CreateCinema.LOGGER.warn("Failed to schedule deletion for film {}", filmId, e);
        }
        broadcastDeleted(server, filmId);
        return true;
    }

    public static int deleteAllFilms(MinecraftServer server) {
        FilmReferenceData data = FilmReferenceData.get(server);
        Set<String> filmIds = new LinkedHashSet<>(data.knownFilmIds());
        try {
            filmIds.addAll(FilmStorage.listFilmIds(server));
        } catch (IOException e) {
            CreateCinema.LOGGER.warn("Failed to list stored films", e);
        }
        data.markDeleted(filmIds);
        for (String filmId : filmIds) {
            try {
                FilmStorage.deleteAsync(server, filmId);
            } catch (RuntimeException e) {
                CreateCinema.LOGGER.warn("Failed to schedule deletion for film {}", filmId, e);
            }
            broadcastDeleted(server, filmId);
        }
        return filmIds.size();
    }

    public static void restoreFilm(MinecraftServer server, String filmId) {
        FilmReferenceData.get(server).restoreDeleted(filmId);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            new S2CFilmAvailablePacket(filmId).sendTo(player);
        }
    }

    public static void syncDeletedFilms(ServerPlayer player) {
        for (String filmId : FilmReferenceData.get(player.server).deletedFilmIds()) {
            new S2CFilmDeletedPacket(filmId).sendTo(player);
        }
    }

    private static void broadcastDeleted(MinecraftServer server, String filmId) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            new S2CFilmDeletedPacket(filmId).sendTo(player);
        }
    }
}
