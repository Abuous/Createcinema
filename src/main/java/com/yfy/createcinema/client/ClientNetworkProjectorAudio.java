package com.yfy.createcinema.client;

import com.yfy.createcinema.audio.CinemaAudioNetwork;
import com.yfy.createcinema.blockentity.NetworkProjectorBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class ClientNetworkProjectorAudio {
    private static final Map<String, ActiveAudio> ACTIVE = new HashMap<>();
    private static final Map<String, Long> NEXT_SCAN = new HashMap<>();

    private ClientNetworkProjectorAudio() {
    }

    public static void update(NetworkProjectorBlockEntity projector) {
        Minecraft minecraft = Minecraft.getInstance();
        BilibiliResolver.ResolvedMedia source = ClientNetworkProjectorStreams.source(projector);
        if (minecraft.level == null || source == null) return;
        String key = key(projector);
        ActiveAudio current = ACTIVE.get(key);
        long gameTime = minecraft.level.getGameTime();
        long nextScan = NEXT_SCAN.getOrDefault(key, 0L);
        if (gameTime < nextScan && (current == null || minecraft.getSoundManager().isActive(current.sound))) return;
        NEXT_SCAN.put(key, gameTime + 10L);

        List<BlockPos> speakers = CinemaAudioNetwork.findSpeakers(minecraft.level, projector.getBlockPos()).stream()
                .sorted(Comparator.comparingDouble(pos -> minecraft.player == null ? 0.0
                        : minecraft.player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)))
                .toList();
        if (speakers.isEmpty()) {
            stopKey(key);
            return;
        }
        BlockPos speaker = speakers.getFirst();
        if (current != null && current.speaker.equals(speaker)
                && minecraft.getSoundManager().isActive(current.sound)) return;
        stopKey(key);
        NetworkProjectorSoundInstance sound = new NetworkProjectorSoundInstance(projector, speaker, source);
        ACTIVE.put(key, new ActiveAudio(projector.getBlockPos().immutable(), speaker, sound));
        minecraft.getSoundManager().play(sound);
    }

    public static void stop(BlockPos projector) {
        Minecraft minecraft = Minecraft.getInstance();
        ACTIVE.entrySet().removeIf(entry -> {
            if (!entry.getValue().projector.equals(projector)) return false;
            minecraft.getSoundManager().stop(entry.getValue().sound);
            NEXT_SCAN.remove(entry.getKey());
            return true;
        });
    }

    public static void stopAll() {
        Minecraft minecraft = Minecraft.getInstance();
        ACTIVE.values().forEach(active -> minecraft.getSoundManager().stop(active.sound));
        ACTIVE.clear();
        NEXT_SCAN.clear();
    }

    private static void stopKey(String key) {
        ActiveAudio removed = ACTIVE.remove(key);
        if (removed != null) Minecraft.getInstance().getSoundManager().stop(removed.sound);
    }

    private static String key(NetworkProjectorBlockEntity projector) {
        return projector.getLevel().dimension().location() + "/" + projector.getBlockPos().asLong();
    }

    private record ActiveAudio(BlockPos projector, BlockPos speaker, NetworkProjectorSoundInstance sound) {
    }
}
