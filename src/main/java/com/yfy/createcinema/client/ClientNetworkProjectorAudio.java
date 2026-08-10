package com.yfy.createcinema.client;

import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.ClientConfig;
import com.yfy.createcinema.ModRegistry;
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
            active = new ActiveSource(projector, source,
                    new SharedNetworkAudio(source.media(), () -> ClientNetworkProjectorStreams.mediaTime(projector)));
            ACTIVE.put(key, active);
        } else if (active.shared == null) {
            active.shared = new SharedNetworkAudio(source.media(),
                    () -> ClientNetworkProjectorStreams.mediaTime(projector));
        }

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
            if (active.shared == null) {
                active.shared = new SharedNetworkAudio(active.source.media(),
                        () -> ClientNetworkProjectorStreams.mediaTime(projector));
            }
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
                CreateCinema.LOGGER.info("Restarting {} network audio cluster {} for projector {}",
                        active.source.media().live() ? "live" : "video", orphan.id, active.projector.getBlockPos());
                stopSound(minecraft, orphan.sound);
            }
            orphans.clear();
        }
        for (List<BlockPos> group : connectedClusters(speakers, ClientConfig.speakerClusterDistance())) {
            ActiveAudio reuse = null;
            int bestOverlap = 0;
            for (ActiveAudio candidate : orphans) {
                int overlap = overlap(candidate.members, group);
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    reuse = candidate;
                }
            }
            if (reuse != null) {
                orphans.remove(reuse);
            }
            String clusterId = reuse == null ? clusterId(group) : reuse.id;
            BlockPos anchor = anchorOf(minecraft, active, group);
            if (anchor == null) {
                if (reuse != null) {
                    CreateCinema.LOGGER.info("Stopping {} network audio cluster {} for projector {}: no valid speaker blocks",
                            active.source.media().live() ? "live" : "video", clusterId, active.projector.getBlockPos());
                    stopSound(minecraft, reuse.sound);
                    active.clusters.remove(clusterId);
                }
                refreshTopologyIfDue(minecraft, active);
                continue;
            }
            if (reuse != null) {
                ActiveAudio updated = anchor.equals(reuse.anchor)
                        ? reuse
                        : new ActiveAudio(reuse.id, reuse.members, anchor, reuse.sound);
                if (updated != reuse) {
                    CreateCinema.LOGGER.debug("Relocating {} network audio cluster {} to anchor {} for projector {}",
                            active.source.media().live() ? "live" : "video", clusterId, anchor,
                            active.projector.getBlockPos());
                    updated.sound.relocate(anchor);
                }
                active.clusters.put(clusterId, updated);
                continue;
            }
            NetworkProjectorSoundInstance sound = new NetworkProjectorSoundInstance(active.projector, anchor, clusterId,
                    active.source, active.shared);
            active.clusters.put(clusterId, new ActiveAudio(clusterId, new HashSet<>(group), anchor, sound));
            CreateCinema.LOGGER.info("Scheduling {} network audio cluster {} for projector {} at anchor {} (speakers={})",
                    active.source.media().live() ? "live" : "video", clusterId, active.projector.getBlockPos(), anchor,
                    group.size());
            minecraft.getSoundManager().play(sound);
        }
        for (ActiveAudio orphan : orphans) {
            CreateCinema.LOGGER.info("Stopping {} network audio cluster {} for projector {}",
                    active.source.media().live() ? "live" : "video", orphan.id, active.projector.getBlockPos());
            stopSound(minecraft, orphan.sound);
            active.clusters.remove(orphan.id);
        }
    }

    private static BlockPos anchorOf(Minecraft minecraft, ActiveSource active, List<BlockPos> group) {
        Vec3 player = minecraft.player == null ? null : minecraft.player.position();
        BlockPos fallback = null;
        BlockPos powered = null;
        double fallbackDistance = Double.MAX_VALUE;
        double poweredDistance = Double.MAX_VALUE;
        for (BlockPos pos : group) {
            if (!isSpeakerBlock(active.projector.getLevel(), pos)) continue;
            double distance = player == null ? 0.0
                    : player.distanceToSqr(ClientPhysicalAudioCompat.worldPosition(active.projector, pos));
            if (distance < fallbackDistance) {
                fallback = pos;
                fallbackDistance = distance;
            }
            if (active.powered.contains(pos) && distance < poweredDistance) {
                powered = pos;
                poweredDistance = distance;
            }
        }
        return powered != null ? powered : fallback;
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

    private static List<List<BlockPos>> connectedClusters(List<BlockPos> speakers, double maxDistance) {
        int count = speakers.size();
        int[] parent = new int[count];
        for (int i = 0; i < count; i++) parent[i] = i;
        double maxSquared = maxDistance * maxDistance;
        for (int first = 0; first < count; first++) {
            for (int second = first + 1; second < count; second++) {
                if (distanceSquared(speakers.get(first), speakers.get(second)) <= maxSquared) {
                    union(parent, first, second);
                }
            }
        }
        Map<Integer, List<BlockPos>> groups = new HashMap<>();
        for (int i = 0; i < count; i++) {
            groups.computeIfAbsent(find(parent, i), ignored -> new ArrayList<>()).add(speakers.get(i));
        }
        return new ArrayList<>(groups.values());
    }

    private static int find(int[] parent, int index) {
        while (parent[index] != index) {
            parent[index] = parent[parent[index]];
            index = parent[index];
        }
        return index;
    }

    private static void union(int[] parent, int first, int second) {
        int firstRoot = find(parent, first);
        int secondRoot = find(parent, second);
        if (firstRoot != secondRoot) parent[secondRoot] = firstRoot;
    }

    private static double distanceSquared(BlockPos first, BlockPos second) {
        double dx = first.getX() - second.getX();
        double dy = first.getY() - second.getY();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static int overlap(Set<BlockPos> members, List<BlockPos> group) {
        int count = 0;
        for (BlockPos pos : group) {
            if (members.contains(pos)) count++;
        }
        return count;
    }

    private static String clusterId(List<BlockPos> group) {
        return Long.toUnsignedString(group.stream().mapToLong(BlockPos::asLong).min().orElse(0L));
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