package com.yfy.createcinema.client;

import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.PlaybackSpeeds;
import com.yfy.createcinema.audio.CinemaAudioNetwork;
import com.yfy.createcinema.block.SpeakerBlock;
import com.yfy.createcinema.blockentity.ProjectorBlockEntity;
import com.yfy.createcinema.film.FilmMetadata;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class FilmSoundInstance extends AbstractSoundInstance implements TickableSoundInstance {
    private final BlockPos projectorPos;
    private final BlockPos speakerPos;
    private final String filmId;
    private final Path audioPath;
    private final double duration;
    private final double startOffset;
    private double expectedTime;
    private int connectionCheck;
    private boolean stopped;

    public FilmSoundInstance(ProjectorBlockEntity projector, BlockPos speaker, FilmMetadata metadata) {
        super(soundLocation(projector.getFilmId(), speaker), SoundSource.RECORDS, RandomSource.create());
        projectorPos = projector.getBlockPos().immutable();
        speakerPos = speaker.immutable();
        filmId = projector.getFilmId();
        audioPath = ClientFilmCache.audioPath(filmId);
        duration = metadata.durationSeconds();
        startOffset = wrap(projector.getPlayTime(), duration);
        expectedTime = startOffset;
        x = speaker.getX() + 0.5;
        y = speaker.getY() + 0.5;
        z = speaker.getZ() + 0.5;
        volume = projector.getLevel() == null ? 0.0f : SpeakerBlock.redstoneVolume(projector.getLevel(), speakerPos);
        pitch = playbackRate(projector);
        looping = false;
        attenuation = SoundInstance.Attenuation.LINEAR;
        relative = false;
        sound = new Sound(location, ConstantFloat.of(1.0f), ConstantFloat.of(1.0f), 1,
                Sound.Type.FILE, true, false, 32);
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        WeighedSoundEvents event = new WeighedSoundEvents(location, null);
        event.addSound(sound);
        return event;
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                AudioStream stream = new JOrbisAudioStream(new BufferedInputStream(Files.newInputStream(audioPath)));
                skipTo(stream, startOffset);
                return stream;
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, Util.nonCriticalIoPool());
    }

    @Override
    public void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null
                || !(minecraft.level.getBlockEntity(projectorPos) instanceof ProjectorBlockEntity projector)
                || !projector.canProject() || !filmId.equals(projector.getFilmId())
                || minecraft.player.distanceToSqr(x, y, z) > 96.0 * 96.0) {
            stopped = true;
            return;
        }

        if (--connectionCheck <= 0) {
            connectionCheck = 10;
            if (!CinemaAudioNetwork.isConnected(minecraft.level, projectorPos, speakerPos)) {
                stopped = true;
                return;
            }
        }

        float rate = playbackRate(projector);
        pitch = rate;
        volume = SpeakerBlock.redstoneVolume(minecraft.level, speakerPos);
        expectedTime = wrap(expectedTime + rate / 20.0, duration);
        double currentTime = wrap(projector.getPlayTime(), duration);
        if (circularDistance(expectedTime, currentTime, duration) > 0.40) stopped = true;
    }

    @Override
    public boolean isStopped() {
        return stopped;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    public BlockPos projectorPos() {
        return projectorPos;
    }

    public BlockPos speakerPos() {
        return speakerPos;
    }

    private static void skipTo(AudioStream stream, double seconds) throws IOException {
        long bytes = Math.round(seconds * stream.getFormat().getFrameRate() * stream.getFormat().getFrameSize());
        while (bytes > 0) {
            ByteBuffer discarded = stream.read((int) Math.min(bytes, 256 * 1024L));
            int read = discarded.remaining();
            if (read <= 0) break;
            bytes -= read;
        }
    }

    private static float playbackRate(ProjectorBlockEntity projector) {
        return PlaybackSpeeds.rate(projector.getSpeed());
    }

    private static double wrap(double value, double duration) {
        if (duration <= 0.0) return 0.0;
        double wrapped = value % duration;
        return wrapped < 0.0 ? wrapped + duration : wrapped;
    }

    private static double circularDistance(double first, double second, double duration) {
        double distance = Math.abs(first - second);
        return duration <= 0.0 ? distance : Math.min(distance, duration - distance);
    }

    private static ResourceLocation soundLocation(String filmId, BlockPos speaker) {
        String path = "film_audio/" + filmId.replace('-', '_') + "/" + Long.toUnsignedString(speaker.asLong());
        return ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID, path);
    }
}
