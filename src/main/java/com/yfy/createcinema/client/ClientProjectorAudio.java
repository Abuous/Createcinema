package com.yfy.createcinema.client;

import com.yfy.createcinema.audio.CinemaAudioNetwork;
import com.yfy.createcinema.blockentity.ProjectorBlockEntity;
import com.yfy.createcinema.film.FilmMetadata;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ClientProjectorAudio {
    private static final Map<AudioKey, FilmSoundInstance> ACTIVE = new HashMap<>();

    private ClientProjectorAudio() {
    }

    public static void update(ProjectorBlockEntity projector, FilmMetadata metadata) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !metadata.hasAudio() || !ClientFilmCache.hasAudio(metadata.id())) {
            stop(projector.getBlockPos());
            return;
        }
        Set<BlockPos> connected = new HashSet<>(CinemaAudioNetwork.findSpeakers(minecraft.level, projector.getBlockPos()));
        ACTIVE.entrySet().removeIf(entry -> {
            if (!entry.getKey().projector.equals(projector.getBlockPos()) || connected.contains(entry.getKey().speaker)) return false;
            minecraft.getSoundManager().stop(entry.getValue());
            return true;
        });
        for (BlockPos speaker : connected) {
            AudioKey key = new AudioKey(projector.getBlockPos().immutable(), speaker);
            FilmSoundInstance current = ACTIVE.get(key);
            if (current != null && minecraft.getSoundManager().isActive(current)) continue;
            if (current != null) minecraft.getSoundManager().stop(current);
            FilmSoundInstance sound = new FilmSoundInstance(projector, speaker, metadata);
            ACTIVE.put(key, sound);
            minecraft.getSoundManager().play(sound);
        }
    }

    public static void stop(BlockPos projector) {
        Minecraft minecraft = Minecraft.getInstance();
        ACTIVE.entrySet().removeIf(entry -> {
            if (!entry.getKey().projector.equals(projector)) return false;
            minecraft.getSoundManager().stop(entry.getValue());
            return true;
        });
    }

    private record AudioKey(BlockPos projector, BlockPos speaker) {
    }
}
