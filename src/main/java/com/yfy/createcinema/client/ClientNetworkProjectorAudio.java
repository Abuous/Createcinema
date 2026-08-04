package com.yfy.createcinema.client;

import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.audio.CinemaAudioNetwork;
import com.yfy.createcinema.block.SpeakerBlock;
import com.yfy.createcinema.blockentity.NetworkProjectorBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientNetworkProjectorAudio {
    private static final Map<String, ActiveSource> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<String, String> DIAGNOSTIC_STATE = new HashMap<>();
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
        if (minecraft.level == null || minecraft.player == null || minecraft.isPaused()) {
            stopAll();
            return;
        }
        if (minecraft.level.getGameTime() % 5 != 0) return;
        long now = minecraft.level.getGameTime();
        Map<String, Candidate> candidates = new HashMap<>();
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
            ClientNetworkProjectorStreams.AudioSource source = ClientNetworkProjectorStreams.audioSource(projector);
            if (source == null) {
                stop(projector);
                continue;
            }
            if (!distances.containsKey(source.key()) || distance < distances.get(source.key())) {
                candidates.put(source.key(), new Candidate(projector, source));
                distances.put(source.key(), distance);
            }
        }
        Set<String> seen = new HashSet<>();
        for (Candidate candidate : candidates.values()) {
            seen.add(candidate.source.key());
            update(candidate.source, candidate.projector);
        }
        stopMissing(seen);
    }

    public static void update(NetworkProjectorBlockEntity projector) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        ClientNetworkProjectorStreams.AudioSource source = ClientNetworkProjectorStreams.audioSource(projector);
        if (source == null) {
            stop(projector);
            return;
        }
        update(source, projector);
    }

    private static void update(ClientNetworkProjectorStreams.AudioSource source, NetworkProjectorBlockEntity projector) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        String key = source.key();
        long gameTime = minecraft.level.getGameTime();
        ActiveSource active = ACTIVE.get(key);
        if (active != null && active.shared != null && active.shared.failure() != null) {
            CreateCinema.LOGGER.debug("Recreating live network audio after shared decoder failure for {}", key,
                    active.shared.failure());
            stopKey(key);
            active = null;
        }
        if (active != null && !sameProjector(active.projector, projector)) {
            stopKey(key);
            active = null;
        }
        if (active == null) {
            active = new ActiveSource(projector, source,
                    source.media().live()
                            ? new SharedNetworkAudio(source.media(), () -> ClientNetworkProjectorStreams.mediaTime(projector))
                            : null);
            ACTIVE.put(key, active);
        } else if (gameTime < active.nextTopologyScan) {
            return;
        }
        active.nextTopologyScan = gameTime + 10L;

        List<BlockPos> speakers = CinemaAudioNetwork.findSpeakers(projector.getLevel(), projector.getBlockPos()).stream()
                .sorted(Comparator.comparingDouble(pos -> minecraft.player == null ? 0.0
                        : minecraft.player.distanceToSqr(ClientPhysicalAudioCompat.worldPosition(projector, pos))))
                .toList();
        if (speakers.isEmpty()) {
            if (!"no-speakers".equals(DIAGNOSTIC_STATE.put(key, "no-speakers"))) {
                CreateCinema.LOGGER.warn("Network audio {} found no cable-connected speakers at projector {}",
                        source.media().live() ? "live stream" : "video", projector.getBlockPos());
            }
            active.speakers.values().forEach(existing -> stopSound(minecraft, existing.sound));
            active.speakers.clear();
            return;
        }
        Set<BlockPos> connected = new HashSet<>(speakers);
        active.speakers.entrySet().removeIf(entry -> {
            if (connected.contains(entry.getKey())) return false;
            stopSound(minecraft, entry.getValue().sound);
            return true;
        });
        for (BlockPos speaker : speakers) {
            ActiveAudio current = active.speakers.get(speaker);
            if (current != null && !current.sound.isStopped()) continue;
            if (current != null) stopSound(minecraft, current.sound);
            float speakerVolume = SpeakerBlock.redstoneVolume(projector.getLevel(), speaker);
            if (speakerVolume <= 0.0f) {
                CreateCinema.LOGGER.debug("Network audio speaker {} for projector {} has redstone volume 0",
                        speaker, projector.getBlockPos());
            }
            NetworkProjectorSoundInstance sound = new NetworkProjectorSoundInstance(projector, speaker, source, active.shared);
            active.speakers.put(speaker, new ActiveAudio(speaker, sound));
            CreateCinema.LOGGER.info("Scheduling {} network audio at speaker {} (speakers={})",
                    source.media().live() ? "live" : "video", speaker, speakers.size());
            minecraft.getSoundManager().play(sound);
        }
    }

    public static void stop(NetworkProjectorBlockEntity projector) {
        ACTIVE.entrySet().removeIf(entry -> {
            if (!sameProjector(entry.getValue().projector, projector)) return false;
            stopSource(entry.getValue());
            return true;
        });
    }

    public static void stopAll() {
        ACTIVE.values().forEach(ClientNetworkProjectorAudio::stopSource);
        ACTIVE.clear();
        DIAGNOSTIC_STATE.clear();
        TOUCHED.clear();
    }

    private static void stopMissing(Set<String> sources) {
        ACTIVE.entrySet().removeIf(entry -> {
            if (sources.contains(entry.getKey())) return false;
            stopSource(entry.getValue());
            return true;
        });
    }

    private static void stopKey(String key) {
        ActiveSource removed = ACTIVE.remove(key);
        if (removed != null) stopSource(removed);
    }

    static boolean isCurrent(String sourceKey, BlockPos speaker, NetworkProjectorSoundInstance sound) {
        ActiveSource active = ACTIVE.get(sourceKey);
        ActiveAudio current = active == null ? null : active.speakers.get(speaker);
        return current != null && current.sound == sound;
    }

    private static void stopSource(ActiveSource source) {
        Minecraft minecraft = Minecraft.getInstance();
        source.speakers.values().forEach(active -> stopSound(minecraft, active.sound));
        source.speakers.clear();
        if (source.shared != null) source.shared.close();
    }

    private static void stopSound(Minecraft minecraft, NetworkProjectorSoundInstance sound) {
        sound.requestStop();
        minecraft.getSoundManager().stop(sound);
    }

    private static boolean sameProjector(NetworkProjectorBlockEntity first, NetworkProjectorBlockEntity second) {
        return first == second;
    }

    private static final class ActiveSource {
        private final NetworkProjectorBlockEntity projector;
        private final ClientNetworkProjectorStreams.AudioSource source;
        private final SharedNetworkAudio shared;
        private final Map<BlockPos, ActiveAudio> speakers = new ConcurrentHashMap<>();
        private long nextTopologyScan;

        private ActiveSource(NetworkProjectorBlockEntity projector, ClientNetworkProjectorStreams.AudioSource source,
                             SharedNetworkAudio shared) {
            this.projector = projector;
            this.source = source;
            this.shared = shared;
        }
    }

    private record ActiveAudio(BlockPos speaker, NetworkProjectorSoundInstance sound) {
    }

    private record Candidate(NetworkProjectorBlockEntity projector, ClientNetworkProjectorStreams.AudioSource source) {
    }
}
