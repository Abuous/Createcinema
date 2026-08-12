package com.yfy.createcinema.client.audio;

import com.yfy.createcinema.client.film.ClientFilmCache;
import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.ClientConfig;
import com.yfy.createcinema.PlaybackSpeeds;
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
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class FilmSoundInstance extends AbstractSoundInstance implements TickableSoundInstance {
    private static final double RESYNC_THRESHOLD_SECONDS = 0.20;
    private static final int RESYNC_CONFIRM_TICKS = 10;
    private final BlockPos projectorPos;
    private final BlockPos speakerPos;
    private final ProjectorBlockEntity projector;
    private final String filmId;
    private final Path audioPath;
    private final double duration;
    private volatile double seekTarget;
    private volatile double streamStartTime = Double.NaN;
    private volatile boolean channelStarted;
    private volatile boolean driftRestart;
    private double expectedTime;
    private boolean stopped;
    private boolean audioClockStarted;
    private int driftTicks;

    public FilmSoundInstance(ProjectorBlockEntity projector, BlockPos speaker, FilmMetadata metadata) {
        super(soundLocation(projector.getFilmId(), speaker), SoundSource.RECORDS, RandomSource.create());
        projectorPos = projector.getBlockPos().immutable();
        speakerPos = speaker.immutable();
        this.projector = projector;
        filmId = projector.getFilmId();
        audioPath = ClientFilmCache.audioPath(filmId);
        duration = metadata.durationSeconds();
        seekTarget = wrap(projector.getPlayTime(), duration);
        expectedTime = seekTarget;
        updatePosition();
        volume = projector.getLevel() == null ? 0.0f : SpeakerBlock.redstoneVolume(projector.getLevel(), speakerPos);
        pitch = playbackRate(projector);
        looping = false;
        attenuation = SoundInstance.Attenuation.LINEAR;
        relative = false;
        sound = new Sound(location, ConstantFloat.of(1.0f), ConstantFloat.of(1.0f), 1,
                Sound.Type.FILE, true, false, ClientConfig.speakerAttenuationDistance());
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
                double seekTo = seekTarget;
                AudioStream stream = new LocalFfmpegAudioStream(audioPath, seekTo);
                streamStartTime = seekTo;
                CreateCinema.LOGGER.debug("Prepared film audio {} at {}s", filmId, String.format(java.util.Locale.ROOT, "%.3f", seekTo));
                return stream;
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, Util.nonCriticalIoPool());
    }

    @Override
    public void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        updatePosition();
        if (minecraft.level == null || minecraft.player == null || projector.isRemoved() || projector.getLevel() == null
                || !projector.getLevel().dimension().equals(minecraft.level.dimension())
                || !projector.canProject() || !filmId.equals(projector.getFilmId())
                || minecraft.player.distanceToSqr(x, y, z) > 48.0 * 48.0) {
            stopped = true;
            return;
        }

        float rate = playbackRate(projector);
        pitch = rate;
        volume = SpeakerBlock.redstoneVolume(projector.getLevel(), speakerPos);
        double currentTime = wrap(projector.getPlayTime(), duration);
        seekTarget = currentTime;
        if (Double.isNaN(streamStartTime) || !channelStarted) return;
        if (!audioClockStarted) {
            audioClockStarted = true;
            expectedTime = streamStartTime;
        } else {
            expectedTime = wrap(expectedTime + rate / 20.0, duration);
        }
        double drift = circularDistance(expectedTime, currentTime, duration);
        driftTicks = drift > RESYNC_THRESHOLD_SECONDS ? driftTicks + 1 : 0;
        if (driftTicks >= RESYNC_CONFIRM_TICKS) {
            CreateCinema.LOGGER.debug("Restarting film audio {} after {}s confirmed drift", filmId,
                    String.format(java.util.Locale.ROOT, "%.3f", drift));
            driftRestart = true;
            stopped = true;
        }
    }

    @Override
    public boolean isStopped() {
        return stopped;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    public boolean driftRestart() {
        return driftRestart;
    }

    public BlockPos projectorPos() {
        return projectorPos;
    }

    public BlockPos speakerPos() {
        return speakerPos;
    }

    public void onChannelStarted() {
        channelStarted = true;
        CreateCinema.LOGGER.debug("Started film audio {} at {}s", filmId,
                String.format(java.util.Locale.ROOT, "%.3f", streamStartTime));
    }

    private void updatePosition() {
        Vec3 position = ClientPhysicalAudioCompat.worldPosition(projector, speakerPos);
        x = position.x;
        y = position.y;
        z = position.z;
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
