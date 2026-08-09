package com.yfy.createcinema.client;

import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.blockentity.ProjectorBlockEntity;
import com.yfy.createcinema.film.FilmMetadata;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ClientProjectorAudio {
    private static final Map<String, ActiveAudio> ACTIVE = new HashMap<>();
    private static final Map<ProjectorBlockEntity, Long> TOUCHED = new HashMap<>();
    private static final long STALE_AFTER_TICKS = 20L;

    private ClientProjectorAudio() {
    }

    public static void mark(ProjectorBlockEntity projector) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || projector.getLevel() == null) return;
        if (projector.getLevel() == minecraft.level && minecraft.level.getBlockEntity(projector.getBlockPos()) != projector) return;
        if (projector.getLevel().getClass().getName().startsWith("net.createmod.ponder.")) return;
        TOUCHED.put(projector, minecraft.level.getGameTime());
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            stopAll();
            return;
        }
        if (minecraft.level.getGameTime() % 5 != 0) return;
        long now = minecraft.level.getGameTime();
        Map<String, ProjectorCandidate> candidates = new HashMap<>();
        TOUCHED.entrySet().removeIf(entry -> now - entry.getValue() > STALE_AFTER_TICKS);
        for (ProjectorBlockEntity projector : TOUCHED.keySet()) {
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
            FilmMetadata metadata = ClientFilmCache.metadata(projector.getFilmId());
            if (metadata == null || metadata.frameCount() <= 0 || metadata.fps() <= 0) {
                stop(projector);
                continue;
            }
            String source = sourceKey(projector);
            ProjectorCandidate current = candidates.get(source);
            if (current == null || distance < current.distance) {
                candidates.put(source, new ProjectorCandidate(projector, metadata, distance));
            }
        }
        Set<String> seen = new HashSet<>();
        for (ProjectorCandidate candidate : candidates.values()) {
            String source = sourceKey(candidate.projector);
            seen.add(source);
            update(source, candidate.projector, candidate.metadata);
        }
        stopMissing(seen);
    }

    public static void update(ProjectorBlockEntity projector, FilmMetadata metadata) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        update(sourceKey(projector), projector, metadata);
    }

    private static void update(String key, ProjectorBlockEntity projector, FilmMetadata metadata) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !metadata.hasAudio() || !ClientFilmCache.hasAudio(metadata.id())) {
            stopKey(key);
            return;
        }
        ActiveAudio current = ACTIVE.get(key);
        if (current != null && !current.sound.isStopped()) return;
        stopKey(key);

        ClientCableIndex.ensure(projector.getLevel(), projector.getBlockPos(), ClientCableIndex.Kind.FILM);
        BlockPos speaker = ClientCableIndex.speakersOf(projector.getLevel(), projector.getBlockPos()).stream()
                .min((first, second) -> Double.compare(distanceToPlayer(minecraft, projector, first),
                        distanceToPlayer(minecraft, projector, second)))
                .orElse(null);
        if (speaker == null) {
            stopKey(key);
            return;
        }
        FilmSoundInstance sound = new FilmSoundInstance(projector, speaker, metadata);
        ACTIVE.put(key, new ActiveAudio(projector, speaker, sound));
        minecraft.getSoundManager().play(sound);
    }

    public static void notifySpeakers(Level level, BlockPos projectorPos, Set<BlockPos> removed, Set<BlockPos> gained) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return;
        if (!(level.getBlockEntity(projectorPos) instanceof ProjectorBlockEntity projector)) return;
        String key = sourceKey(projector);
        ActiveAudio current = ACTIVE.get(key);
        if (current == null) {
            CreateCinema.LOGGER.info("Film audio topology changed at {} with no active sound: removed={}, gained={}",
                    projectorPos, removed, gained);
            return;
        }
        if (removed.contains(current.speaker)) {
            CreateCinema.LOGGER.info("Stopping film audio at disconnected speaker {} for projector {}",
                    current.speaker, projectorPos);
            stopKey(key);
            return;
        }
        if (gained.isEmpty()) return;
        double currentDistance = distanceToPlayer(minecraft, projector, current.speaker);
        for (BlockPos speaker : gained) {
            if (distanceToPlayer(minecraft, projector, speaker) < currentDistance) {
                CreateCinema.LOGGER.info("Restarting film audio for closer speaker {} at projector {}", speaker, projectorPos);
                stopKey(key);
                return;
            }
        }
    }

    public static void stop(ProjectorBlockEntity projector) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientCableIndex.remove(projector.getLevel(), projector.getBlockPos());
        ACTIVE.entrySet().removeIf(entry -> {
            if (entry.getValue().projector != projector) return false;
            minecraft.getSoundManager().stop(entry.getValue().sound);
            return true;
        });
    }

    public static void stopFilm(String filmId) {
        Minecraft minecraft = Minecraft.getInstance();
        ACTIVE.entrySet().removeIf(entry -> {
            if (!entry.getValue().projector.getFilmId().equals(filmId)) return false;
            minecraft.getSoundManager().stop(entry.getValue().sound);
            return true;
        });
    }

    public static void stopAll() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientCableIndex.removeAll();
        ACTIVE.values().forEach(active -> minecraft.getSoundManager().stop(active.sound));
        ACTIVE.clear();
        TOUCHED.clear();
    }

    private static void stopMissing(Set<String> sources) {
        Minecraft minecraft = Minecraft.getInstance();
        ACTIVE.entrySet().removeIf(entry -> {
            if (sources.contains(entry.getKey())) return false;
            minecraft.getSoundManager().stop(entry.getValue().sound);
            return true;
        });
    }

    private static void stopKey(String key) {
        ActiveAudio removed = ACTIVE.remove(key);
        if (removed != null) Minecraft.getInstance().getSoundManager().stop(removed.sound);
    }

    private static double distanceToPlayer(Minecraft minecraft, ProjectorBlockEntity projector, BlockPos pos) {
        return minecraft.player == null ? 0.0
                : minecraft.player.distanceToSqr(ClientPhysicalAudioCompat.worldPosition(projector, pos));
    }

    private static String sourceKey(ProjectorBlockEntity projector) {
        return projector.getLevel().dimension().location() + "/film/" + projector.getFilmId();
    }

    private record ProjectorCandidate(ProjectorBlockEntity projector, FilmMetadata metadata, double distance) {
    }

    private record ActiveAudio(ProjectorBlockEntity projector, BlockPos speaker, FilmSoundInstance sound) {
    }
}
