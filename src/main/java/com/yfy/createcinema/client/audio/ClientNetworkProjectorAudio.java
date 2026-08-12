package com.yfy.createcinema.client.audio;

import com.yfy.createcinema.client.network.ClientNetworkProjectorStreams;
import com.yfy.createcinema.client.network.ClientCableIndex;
import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.ModRegistry;
import com.yfy.createcinema.PlaybackSpeeds;
import com.yfy.createcinema.block.SpeakerBlock;
import com.yfy.createcinema.blockentity.NetworkProjectorBlockEntity;
import com.yfy.createcinema.display.ProjectionScreenGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientNetworkProjectorAudio {
    private static final String SINGLE_AUDIO_ID = "nearest";
    private static final Map<String, ActiveSource> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<String, String> DIAGNOSTIC_STATE = new HashMap<>();
    private static final Map<NetworkProjectorBlockEntity, Long> TOUCHED = new HashMap<>();
    private static final Map<NetworkProjectorBlockEntity, Long> REDSTONE_RECHECK = new HashMap<>();
    private static final Map<String, Long> LAST_DRIFT_RESTART = new ConcurrentHashMap<>();
    private static final Map<String, Integer> RESTART_FAILURES = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_TOPOLOGY_REFRESH = new ConcurrentHashMap<>();
    private static volatile boolean SCREENS_DIRTY;
    private static final long STALE_AFTER_TICKS = 20L;
    private static final long DRIFT_RESTART_BASE_MILLIS = 3_000L;
    private static final long DRIFT_RESTART_MEDIUM_MILLIS = 15_000L;
    private static final long DRIFT_RESTART_MAX_MILLIS = 30_000L;
    private static final long UNSTARTED_WATCHDOG_MILLIS = 5_000L;
    private static final double VIDEO_ADVANCE_EPSILON = 0.25;
    private static final long REDSTONE_RECHECK_INTERVAL = 10L;
    private static final long TOPOLOGY_REFRESH_INTERVAL_TICKS = 40L;
    private static final double MAX_DISTANCE = 48.0;

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
        REDSTONE_RECHECK.entrySet().removeIf(entry -> entry.getKey().isRemoved() || entry.getKey().getLevel() == null);
        Set<NetworkProjectorBlockEntity> visible = new HashSet<>(TOUCHED.keySet());
        for (NetworkProjectorBlockEntity projector : visible) {
            if (projector.isRemoved() || projector.getLevel() == null) continue;
            if (!projector.getLevel().dimension().equals(minecraft.level.dimension())) continue;
            BlockPos pos = projector.getBlockPos();
            Vec3 worldPos = ClientPhysicalAudioCompat.worldPosition(projector, pos);
            double distance = minecraft.player.distanceToSqr(worldPos);
            if (distance > MAX_DISTANCE * MAX_DISTANCE) continue;
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
        if (SCREENS_DIRTY) {
            SCREENS_DIRTY = false;
            muteSourcesWithoutScreens(minecraft);
        }
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
            active = new ActiveSource(projector, source, null);
            ACTIVE.put(key, active);
        }

        float rate = source.media().live() ? 1.0f : PlaybackSpeeds.rate(projector.getSpeed());
        if (active.rate >= 0.0f && Math.abs(active.rate - rate) > 0.01f) {
            restartForRateChange(active, rate);
        }
        active.rate = rate;

        ClientCableIndex.ensure(projector.getLevel(), projector.getBlockPos(), ClientCableIndex.Kind.NETWORK);
        List<BlockPos> speakers = ClientCableIndex.speakersOf(projector.getLevel(), projector.getBlockPos()).stream()
                .sorted(Comparator.comparingDouble(pos -> minecraft.player == null ? 0.0
                        : minecraft.player.distanceToSqr(ClientPhysicalAudioCompat.worldPosition(projector, pos))))
                .toList();
        if (speakers.isEmpty()) {
            if (!"no-speakers".equals(DIAGNOSTIC_STATE.put(key, "no-speakers"))) {
                CreateCinema.LOGGER.warn("Network audio {} found no cable-connected speakers at projector {}",
                        source.media().live() ? "live stream" : "video", projector.getBlockPos());
            }
            active.clusters.values().forEach(existing -> stopSound(minecraft, existing.sound));
            active.clusters.clear();
            active.powered = Set.of();
            closeShared(active);
            return;
        }
        boolean sampleRedstone = shouldSampleRedstone(projector, speakers.size());
        recompute(minecraft, active, speakers, sampleRedstone);
    }

    private static long restartCooldown(int failures) {
        if (failures <= 1) return DRIFT_RESTART_BASE_MILLIS;
        return failures == 2 ? DRIFT_RESTART_MEDIUM_MILLIS : DRIFT_RESTART_MAX_MILLIS;
    }

    private static boolean videoClockAdvanced(ActiveSource active, double since) {
        double now = ClientNetworkProjectorStreams.mediaTime(active.projector);
        double duration = active.source.media().durationSeconds();
        double distance = Math.abs(now - since);
        return duration <= 0.0 ? distance >= VIDEO_ADVANCE_EPSILON
                : Math.min(distance, duration - distance) >= VIDEO_ADVANCE_EPSILON;
    }

    public static void screensMayHaveChanged() {
        SCREENS_DIRTY = true;
    }

    private static void muteSourcesWithoutScreens(Minecraft minecraft) {
        for (ActiveSource active : ACTIVE.values()) {
            NetworkProjectorBlockEntity projector = active.projector;
            Level projectorLevel = projector.getLevel();
            if (projector.isRemoved() || projectorLevel == null || projectorLevel != minecraft.level) continue;
            if (projectorLevel.getBlockEntity(projector.getBlockPos()) != projector) continue;
            Direction facing = projectorLevel.getBlockState(projector.getBlockPos())
                    .getValue(BlockStateProperties.HORIZONTAL_FACING);
            if (ProjectionScreenGeometry.hasAnyScreen(projectorLevel, projector.getBlockPos(), facing)) continue;
            CreateCinema.LOGGER.info("Muting {} network audio for projector {}: no projection screens remaining",
                    active.source.media().live() ? "live stream" : "video", projector.getBlockPos());
            active.clusters.values().forEach(existing -> stopSound(minecraft, existing.sound));
        }
    }

    public static void notifySpeakers(Level level, BlockPos projectorPos, Set<BlockPos> removed, Set<BlockPos> gained) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        if (!(level.getBlockEntity(projectorPos) instanceof NetworkProjectorBlockEntity projector)) return;
        for (ActiveSource active : ACTIVE.values()) {
            if (!sameProjector(active.projector, projector)) continue;
            ClientNetworkProjectorStreams.AudioSource currentSource = ClientNetworkProjectorStreams.audioSource(projector);
            if (currentSource == null || !active.source.key().equals(currentSource.key())) continue;
            ClientCableIndex.ensure(level, projectorPos, ClientCableIndex.Kind.NETWORK);
            List<BlockPos> speakers = ClientCableIndex.speakersOf(level, projectorPos).stream()
                    .sorted(Comparator.comparingDouble(pos -> minecraft.player == null ? 0.0
                            : minecraft.player.distanceToSqr(ClientPhysicalAudioCompat.worldPosition(projector, pos))))
                    .toList();
            if (speakers.isEmpty()) {
                active.clusters.values().forEach(existing -> stopSound(minecraft, existing.sound));
                active.clusters.clear();
                active.powered = Set.of();
                closeShared(active);
                continue;
            }
            recompute(minecraft, active, speakers, true);
        }
    }

    private static boolean shouldSampleRedstone(NetworkProjectorBlockEntity projector, int speakerCount) {
        if (speakerCount <= 1) return true;
        long now = projector.getLevel().getGameTime();
        Long next = REDSTONE_RECHECK.get(projector);
        if (next != null && now < next) return false;
        REDSTONE_RECHECK.put(projector, now + REDSTONE_RECHECK_INTERVAL);
        return true;
    }

    private static void closeShared(ActiveSource active) {
        if (active.shared == null) return;
        active.shared.close();
        active.shared = null;
    }

    /** Reopens the shared stream at the current video clock after a playback-rate change. */
    private static void restartForRateChange(ActiveSource active, float rate) {
        CreateCinema.LOGGER.debug("Playback rate changed for network audio {}: {}x -> {}x; resyncing",
                active.source.key(), String.format(java.util.Locale.ROOT, "%.3f", active.rate),
                String.format(java.util.Locale.ROOT, "%.3f", rate));
        Minecraft minecraft = Minecraft.getInstance();
        active.clusters.values().forEach(audio -> stopSound(minecraft, audio.sound));
        active.clusters.clear();
        LAST_DRIFT_RESTART.entrySet().removeIf(entry -> entry.getKey().startsWith(active.source.key() + "/"));
        RESTART_FAILURES.entrySet().removeIf(entry -> entry.getKey().startsWith(active.source.key() + "/"));
        closeShared(active);
        active.shared = new SharedNetworkAudio(active.source.media(),
                () -> ClientNetworkProjectorStreams.mediaTime(active.projector));
    }

    private static void recompute(Minecraft minecraft, ActiveSource active, List<BlockPos> speakers,
                                  boolean sampleRedstone) {
        if (sampleRedstone) {
            Set<BlockPos> powered = new HashSet<>();
            for (BlockPos speaker : speakers) {
                if (SpeakerBlock.redstoneVolume(active.projector.getLevel(), speaker) > 0.0f) powered.add(speaker);
            }
            active.powered = powered;
        }
        List<ActiveAudio> orphans = new ArrayList<>(active.clusters.values());
        boolean anyClusterAdvanced = orphans.isEmpty() || orphans.stream()
                .anyMatch(orphan -> videoClockAdvanced(active, orphan.sound.latestPlayTime()));
        boolean recreateShared = active.shared != null && active.shared.failure() != null && anyClusterAdvanced;
        for (ActiveAudio orphan : orphans) {
            boolean unstarted = !orphan.sound.openQueued()
                    && System.currentTimeMillis() - orphan.sound.createdMillis() > UNSTARTED_WATCHDOG_MILLIS;
            boolean restarting = orphan.sound.driftRestart() || orphan.sound.isStopped() || unstarted;
            if (!restarting) continue;
            if (!videoClockAdvanced(active, orphan.sound.latestPlayTime())) {
                RESTART_FAILURES.remove(orphan.id);
                continue;
            }
            long now = System.currentTimeMillis();
            int failures = RESTART_FAILURES.getOrDefault(orphan.id, 0);
            if (now - LAST_DRIFT_RESTART.getOrDefault(orphan.id, 0L) < restartCooldown(failures)) continue;
            LAST_DRIFT_RESTART.put(orphan.id, now);
            if (orphan.sound.openFailed() || unstarted) {
                RESTART_FAILURES.put(orphan.id, Math.min(failures + 1, 3));
            } else {
                RESTART_FAILURES.remove(orphan.id);
            }
            if (unstarted) {
                CreateCinema.LOGGER.warn("Network audio cluster {} was never started, restarting", orphan.id);
            }
            recreateShared = true;
        }
        if (recreateShared) {
            if (active.shared != null) closeShared(active);
            active.shared = new SharedNetworkAudio(active.source.media(),
                    () -> ClientNetworkProjectorStreams.mediaTime(active.projector));
            for (ActiveAudio orphan : orphans) {
                CreateCinema.LOGGER.info("Restarting {} network audio source for projector {}",
                        active.source.media().live() ? "live" : "video", active.projector.getBlockPos());
                stopSound(minecraft, orphan.sound);
            }
            orphans.clear();
            active.clusters.clear();
        }
        BlockPos anchor = anchorOf(minecraft, active, speakers);
        ActiveAudio current = active.clusters.get(SINGLE_AUDIO_ID);
        if (anchor == null) {
            for (ActiveAudio orphan : orphans) stopSound(minecraft, orphan.sound);
            active.clusters.clear();
            closeShared(active);
            refreshTopologyIfDue(minecraft, active);
            return;
        }
        if (active.shared == null) {
            active.shared = new SharedNetworkAudio(active.source.media(),
                    () -> ClientNetworkProjectorStreams.mediaTime(active.projector));
        }
        if (current != null) {
            if (!anchor.equals(current.anchor)) {
                CreateCinema.LOGGER.debug("Relocating {} network audio source to anchor {} for projector {}",
                        active.source.media().live() ? "live" : "video", anchor, active.projector.getBlockPos());
                current.sound.relocate(anchor);
            }
            active.clusters.put(SINGLE_AUDIO_ID,
                    new ActiveAudio(SINGLE_AUDIO_ID, new HashSet<>(speakers), anchor, current.sound));
        } else {
            NetworkProjectorSoundInstance sound = new NetworkProjectorSoundInstance(
                    active.projector, anchor, SINGLE_AUDIO_ID, active.source, active.shared);
            active.clusters.put(SINGLE_AUDIO_ID,
                    new ActiveAudio(SINGLE_AUDIO_ID, new HashSet<>(speakers), anchor, sound));
            CreateCinema.LOGGER.info("Scheduling {} network audio source for projector {} at anchor {} (speakers={})",
                    active.source.media().live() ? "live" : "video", active.projector.getBlockPos(), anchor,
                    speakers.size());
            minecraft.getSoundManager().play(sound);
        }
        for (ActiveAudio orphan : orphans) {
            if (current != null && orphan.sound == current.sound) continue;
            stopSound(minecraft, orphan.sound);
            active.clusters.remove(orphan.id);
        }
    }

    private static BlockPos anchorOf(Minecraft minecraft, ActiveSource active, List<BlockPos> group) {
        Vec3 player = minecraft.player == null ? null : minecraft.player.position();
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockPos pos : group) {
            if (!isSpeakerBlock(active.projector.getLevel(), pos)) continue;
            if (!active.powered.contains(pos)) continue;
            double distance = player == null ? 0.0
                    : player.distanceToSqr(ClientPhysicalAudioCompat.worldPosition(active.projector, pos));
            if (distance < nearestDistance) {
                nearest = pos;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static void refreshTopologyIfDue(Minecraft minecraft, ActiveSource active) {
        String key = active.source.key();
        long now = minecraft.level.getGameTime();
        Long last = LAST_TOPOLOGY_REFRESH.get(key);
        if (last != null && now - last < TOPOLOGY_REFRESH_INTERVAL_TICKS) return;
        LAST_TOPOLOGY_REFRESH.put(key, now);
        ClientCableIndex.refresh(active.projector.getLevel(), active.projector.getBlockPos());
    }

    private static boolean isSpeakerBlock(Level level, BlockPos pos) {
        return level.getBlockState(pos).is(ModRegistry.SPEAKER.get());
    }

    public static void stop(NetworkProjectorBlockEntity projector) {
        ClientCableIndex.remove(projector.getLevel(), projector.getBlockPos());
        ACTIVE.entrySet().removeIf(entry -> {
            if (!sameProjector(entry.getValue().projector, projector)) return false;
            stopSource(entry.getValue());
            return true;
        });
    }

    public static void stopAll() {
        ClientCableIndex.removeAll();
        ACTIVE.values().forEach(ClientNetworkProjectorAudio::stopSource);
        ACTIVE.clear();
        DIAGNOSTIC_STATE.clear();
        TOUCHED.clear();
        REDSTONE_RECHECK.clear();
        LAST_DRIFT_RESTART.clear();
        RESTART_FAILURES.clear();
        LAST_TOPOLOGY_REFRESH.clear();
        SCREENS_DIRTY = false;
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

    static boolean isCurrent(String sourceKey, String clusterId, NetworkProjectorSoundInstance sound) {
        ActiveSource active = ACTIVE.get(sourceKey);
        ActiveAudio current = active == null ? null : active.clusters.get(clusterId);
        return current != null && current.sound == sound;
    }

    private static void stopSource(ActiveSource source) {
        Minecraft minecraft = Minecraft.getInstance();
        source.clusters.values().forEach(active -> stopSound(minecraft, active.sound));
        source.clusters.clear();
        LAST_DRIFT_RESTART.entrySet().removeIf(entry -> entry.getKey().startsWith(source.source.key() + "/"));
        RESTART_FAILURES.entrySet().removeIf(entry -> entry.getKey().startsWith(source.source.key() + "/"));
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
        private SharedNetworkAudio shared;
        private final Map<String, ActiveAudio> clusters = new ConcurrentHashMap<>();
        private Set<BlockPos> powered = Set.of();
        private float rate = -1.0f;

        private ActiveSource(NetworkProjectorBlockEntity projector, ClientNetworkProjectorStreams.AudioSource source,
                             SharedNetworkAudio shared) {
            this.projector = projector;
            this.source = source;
            this.shared = shared;
        }
    }

    private record ActiveAudio(String id, Set<BlockPos> members, BlockPos anchor,
                               NetworkProjectorSoundInstance sound) {
    }

    private record Candidate(NetworkProjectorBlockEntity projector, ClientNetworkProjectorStreams.AudioSource source) {
    }
}
