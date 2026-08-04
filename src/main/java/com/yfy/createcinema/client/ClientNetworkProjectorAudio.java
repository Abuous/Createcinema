package com.yfy.createcinema.client;

import com.yfy.createcinema.audio.CinemaAudioNetwork;
import com.yfy.createcinema.blockentity.NetworkProjectorBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ClientNetworkProjectorAudio {
    private static final Map<String, ActiveAudio> ACTIVE = new HashMap<>();
    private static final Map<String, Long> NEXT_SCAN = new HashMap<>();
    private static final Map<NetworkProjectorBlockEntity, Long> TOUCHED = new HashMap<>();
    private static final long STALE_AFTER_TICKS = 20L;

    private ClientNetworkProjectorAudio() {
    }

    public static void mark(NetworkProjectorBlockEntity projector) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || projector.getLevel() == null) return;
        if (projector.getLevel() == minecraft.level && minecraft.level.getBlockEntity(projector.getBlockPos()) != projector) return;
        if (projector.getLevel().getClass().getName().startsWith("net.createmod.ponder.")) return;
        TOUCHED.put(projector, minecraft.level.getGameTime());
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.screen instanceof PauseScreen) {
            stopAll();
            return;
        }
        if (minecraft.level.getGameTime() % 5 != 0) return;
        long now = minecraft.level.getGameTime();
        Map<String, NetworkProjectorBlockEntity> candidates = new HashMap<>();
        Map<String, Double> distances = new HashMap<>();
        TOUCHED.entrySet().removeIf(entry -> now - entry.getValue() > STALE_AFTER_TICKS);
        for (NetworkProjectorBlockEntity projector : TOUCHED.keySet()) {
            if (projector.isRemoved() || projector.getLevel() == null) continue;
            if (!projector.getLevel().dimension().equals(minecraft.level.dimension())) continue;
            BlockPos pos = projector.getBlockPos();
            Vec3 worldPos = ClientPhysicalAudioCompat.worldPosition(projector, pos);
            double distance = minecraft.player.distanceToSqr(worldPos);
            if (distance > 96.0 * 96.0) continue;
            if (!projector.canProject()) {
                stop(projector);
                continue;
            }
            String source = sourceKey(projector);
            if (!distances.containsKey(source) || distance < distances.get(source)) {
                candidates.put(source, projector);
                distances.put(source, distance);
            }
        }
        Set<String> seen = new HashSet<>();
        for (NetworkProjectorBlockEntity projector : candidates.values()) {
            String source = sourceKey(projector);
            seen.add(source);
            update(source, projector);
        }
        stopMissing(seen);
    }

    public static void update(NetworkProjectorBlockEntity projector) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        update(sourceKey(projector), projector);
    }

    private static void update(String key, NetworkProjectorBlockEntity projector) {
        Minecraft minecraft = Minecraft.getInstance();
        BilibiliResolver.ResolvedMedia source = ClientNetworkProjectorStreams.source(projector);
        if (minecraft.level == null || source == null) {
            return;
        }
        ActiveAudio current = ACTIVE.get(key);
        long gameTime = minecraft.level.getGameTime();
        long nextScan = NEXT_SCAN.getOrDefault(key, 0L);
        if (current != null && !current.sound.isStopped()) return;
        if (gameTime < nextScan) return;
        NEXT_SCAN.put(key, gameTime + 10L);

        List<BlockPos> speakers = CinemaAudioNetwork.findSpeakers(projector.getLevel(), projector.getBlockPos()).stream()
                .sorted(Comparator.comparingDouble(pos -> minecraft.player == null ? 0.0
                        : minecraft.player.distanceToSqr(ClientPhysicalAudioCompat.worldPosition(projector, pos))))
                .toList();
        if (speakers.isEmpty()) {
            stopKey(key);
            return;
        }
        BlockPos speaker = speakers.getFirst();
        stopKey(key);
        NetworkProjectorSoundInstance sound = new NetworkProjectorSoundInstance(projector, speaker, source);
        ACTIVE.put(key, new ActiveAudio(projector, speaker, sound));
        minecraft.getSoundManager().play(sound);
    }

    public static void stop(NetworkProjectorBlockEntity projector) {
        Minecraft minecraft = Minecraft.getInstance();
        ACTIVE.entrySet().removeIf(entry -> {
            if (entry.getValue().projector != projector) return false;
            entry.getValue().sound.requestStop();
            minecraft.getSoundManager().stop(entry.getValue().sound);
            NEXT_SCAN.remove(entry.getKey());
            return true;
        });
    }

    public static void stopAll() {
        Minecraft minecraft = Minecraft.getInstance();
        ACTIVE.values().forEach(active -> {
            active.sound.requestStop();
            minecraft.getSoundManager().stop(active.sound);
        });
        ACTIVE.clear();
        NEXT_SCAN.clear();
        TOUCHED.clear();
    }

    private static void stopMissing(Set<String> sources) {
        Minecraft minecraft = Minecraft.getInstance();
        ACTIVE.entrySet().removeIf(entry -> {
            if (sources.contains(entry.getKey())) return false;
            entry.getValue().sound.requestStop();
            minecraft.getSoundManager().stop(entry.getValue().sound);
            NEXT_SCAN.remove(entry.getKey());
            return true;
        });
    }

    private static void stopKey(String key) {
        ActiveAudio removed = ACTIVE.remove(key);
        NEXT_SCAN.remove(key);
        if (removed != null) {
            removed.sound.requestStop();
            Minecraft.getInstance().getSoundManager().stop(removed.sound);
        }
    }

    private static String sourceKey(NetworkProjectorBlockEntity projector) {
        return projector.getLevel().dimension().location() + "/url/" + projector.getUrl()
                + "/" + ClientNetworkProjectorStreams.mediaRevision(projector);
    }

    private record ActiveAudio(NetworkProjectorBlockEntity projector, BlockPos speaker, NetworkProjectorSoundInstance sound) {
    }
}
