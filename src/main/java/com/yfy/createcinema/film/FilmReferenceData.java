package com.yfy.createcinema.film;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class FilmReferenceData extends SavedData {
    private static final String DATA_NAME = "createcinema_films";
    private final Map<String, Set<String>> references = new HashMap<>();
    private final Set<String> deletedFilms = new HashSet<>();

    public static FilmReferenceData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new Factory<>(FilmReferenceData::new, FilmReferenceData::load), DATA_NAME);
    }

    private static FilmReferenceData load(CompoundTag tag, HolderLookup.Provider registries) {
        FilmReferenceData data = new FilmReferenceData();
        CompoundTag referenceTag = tag.getCompound("References");
        for (String filmId : referenceTag.getAllKeys()) {
            ListTag copies = referenceTag.getList(filmId, 8);
            Set<String> copyIds = new HashSet<>();
            for (int i = 0; i < copies.size(); i++) copyIds.add(copies.getString(i));
            if (!copyIds.isEmpty()) data.references.put(filmId, copyIds);
        }
        ListTag deleted = tag.getList("Deleted", 8);
        for (int i = 0; i < deleted.size(); i++) data.deletedFilms.add(deleted.getString(i));
        boolean removedDeletedReferences = false;
        for (String filmId : data.deletedFilms) removedDeletedReferences |= data.references.remove(filmId) != null;
        if (removedDeletedReferences) data.setDirty();
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag referenceTag = new CompoundTag();
        references.forEach((filmId, copyIds) -> {
            ListTag copies = new ListTag();
            copyIds.forEach(copyId -> copies.add(StringTag.valueOf(copyId)));
            referenceTag.put(filmId, copies);
        });
        tag.put("References", referenceTag);

        ListTag deleted = new ListTag();
        deletedFilms.forEach(filmId -> deleted.add(StringTag.valueOf(filmId)));
        tag.put("Deleted", deleted);
        return tag;
    }

    public void registerCopy(String filmId, String copyId) {
        if (filmId.isBlank() || copyId.isBlank()) return;
        if (references.computeIfAbsent(filmId, ignored -> new HashSet<>()).add(copyId)) setDirty();
    }

    public boolean releaseCopy(String filmId, String copyId) {
        Set<String> copyIds = references.get(filmId);
        if (copyIds == null || !copyIds.remove(copyId)) return false;
        if (copyIds.isEmpty()) references.remove(filmId);
        setDirty();
        return !references.containsKey(filmId);
    }

    public boolean hasCopy(String filmId, String copyId) {
        return references.getOrDefault(filmId, Set.of()).contains(copyId);
    }

    public int referenceCount(String filmId) {
        return references.getOrDefault(filmId, Set.of()).size();
    }

    public boolean isKnown(String filmId) {
        return references.containsKey(filmId) || deletedFilms.contains(filmId);
    }

    public boolean isDeleted(String filmId) {
        return deletedFilms.contains(filmId);
    }

    public boolean markDeleted(String filmId) {
        boolean added = deletedFilms.add(filmId);
        boolean removedReferences = references.remove(filmId) != null;
        if (added || removedReferences) setDirty();
        return added;
    }

    public Set<String> deletedFilmIds() {
        return Set.copyOf(deletedFilms);
    }

    public Set<String> knownFilmIds() {
        Set<String> result = new HashSet<>(references.keySet());
        result.addAll(deletedFilms);
        return result;
    }

    public void markDeleted(Collection<String> filmIds) {
        boolean changed = deletedFilms.addAll(filmIds);
        for (String filmId : filmIds) changed |= references.remove(filmId) != null;
        if (changed) setDirty();
    }
}
