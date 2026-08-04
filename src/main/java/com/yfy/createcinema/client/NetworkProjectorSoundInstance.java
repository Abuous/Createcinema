package com.yfy.createcinema.client;

import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.PlaybackSpeeds;
import com.yfy.createcinema.audio.CinemaAudioNetwork;
import com.yfy.createcinema.block.SpeakerBlock;
import com.yfy.createcinema.blockentity.NetworkProjectorBlockEntity;
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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class NetworkProjectorSoundInstance extends AbstractSoundInstance implements TickableSoundInstance {
    private static final double RESYNC_THRESHOLD_SECONDS = 0.75;
    private static final int RESYNC_CONFIRM_TICKS = 10;
    private final BlockPos projectorPos;
    private final BlockPos speakerPos;
    private final NetworkProjectorBlockEntity projector;
    private final String url;
    private final BilibiliResolver.ResolvedMedia sourceMedia;
    private volatile double latestPlayTime;
    private volatile double streamStartTime = Double.NaN;
    private volatile NetworkFfmpegAudioStream audioStream;
    private volatile boolean channelStarted;
    private volatile long channelStartedNanos;
    private int connectionCheck;
    private boolean audioClockStarted;
    private double expectedTime;
    private long audioClockNanos;
    private int driftTicks;
    private boolean stopped;

    public NetworkProjectorSoundInstance(NetworkProjectorBlockEntity projector, BlockPos speaker,
                                         BilibiliResolver.ResolvedMedia sourceMedia) {
        super(soundLocation(projector.getBlockPos(), speaker), SoundSource.RECORDS, RandomSource.create());
        projectorPos = projector.getBlockPos().immutable();
        speakerPos = speaker.immutable();
        this.projector = projector;
        url = projector.getUrl();
        this.sourceMedia = sourceMedia;
        latestPlayTime = ClientNetworkProjectorStreams.mediaTime(projector);
        updatePosition();
        volume = projector.getLevel() == null ? 0.0f : SpeakerBlock.redstoneVolume(projector.getLevel(), speakerPos);
        pitch = playbackRate(projector);
        attenuation = SoundInstance.Attenuation.LINEAR;
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
                NetworkFfmpegAudioStream stream = new NetworkFfmpegAudioStream(sourceMedia,
                        () -> wrap(latestPlayTime, sourceMedia.durationSeconds()));
                audioStream = stream;
                streamStartTime = stream.startTime();
                if (stopped) stream.close();
                return stream;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, Util.nonCriticalIoPool());
    }

    @Override
    public void tick() {
        if (stopped) return;
        Minecraft minecraft = Minecraft.getInstance();
        updatePosition();
        if (minecraft.level == null || minecraft.player == null || projector.isRemoved() || projector.getLevel() == null
                || !projector.getLevel().dimension().equals(minecraft.level.dimension())
                || !projector.canProject() || !url.equals(projector.getUrl())
                || minecraft.player.distanceToSqr(x, y, z) > 96.0 * 96.0) {
            stopped = true;
            return;
        }
        latestPlayTime = ClientNetworkProjectorStreams.mediaTime(projector);
        pitch = playbackRate(projector);
        volume = SpeakerBlock.redstoneVolume(projector.getLevel(), speakerPos);
        NetworkFfmpegAudioStream activeStream = audioStream;
        if (activeStream != null && activeStream.insertedSilenceSeconds() > 0.5) {
            CreateCinema.LOGGER.debug("Restarting network audio {} after {}s decoder starvation", url,
                    String.format(java.util.Locale.ROOT, "%.3f", activeStream.insertedSilenceSeconds()));
            stopped = true;
            return;
        }
        if (--connectionCheck <= 0) {
            connectionCheck = 10;
            if (!CinemaAudioNetwork.isConnected(projector.getLevel(), projectorPos, speakerPos)) {
                stopped = true;
                return;
            }
        }
        if (Double.isNaN(streamStartTime) || !channelStarted) return;
        if (!audioClockStarted) {
            audioClockStarted = true;
            expectedTime = streamStartTime;
            audioClockNanos = channelStartedNanos > 0L ? channelStartedNanos : System.nanoTime();
            double startupDrift = circularDistance(expectedTime, latestPlayTime, sourceMedia.durationSeconds());
            CreateCinema.LOGGER.debug("Aligned network audio {} at {}s with {}s startup drift", url,
                    String.format(java.util.Locale.ROOT, "%.3f", streamStartTime),
                    String.format(java.util.Locale.ROOT, "%.3f", startupDrift));
            return;
        }
        long now = System.nanoTime();
        double elapsed = Math.max(0.0, (now - audioClockNanos) / 1_000_000_000.0);
        expectedTime = wrap(expectedTime + elapsed * pitch, sourceMedia.durationSeconds());
        audioClockNanos = now;
        double drift = circularDistance(expectedTime, latestPlayTime, sourceMedia.durationSeconds());
        driftTicks = drift > RESYNC_THRESHOLD_SECONDS ? driftTicks + 1 : 0;
        if (driftTicks >= RESYNC_CONFIRM_TICKS) {
            CreateCinema.LOGGER.debug("Restarting network audio {} after {}s playback drift (audio {}s, video {}s)", url,
                    String.format(java.util.Locale.ROOT, "%.3f", drift),
                    String.format(java.util.Locale.ROOT, "%.3f", expectedTime),
                    String.format(java.util.Locale.ROOT, "%.3f", latestPlayTime));
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

    public void requestStop() {
        stopped = true;
    }

    public void onChannelStarted() {
        channelStartedNanos = System.nanoTime();
        channelStarted = true;
        CreateCinema.LOGGER.debug("Started network audio {} at {}s", url,
                String.format(java.util.Locale.ROOT, "%.3f", streamStartTime));
    }

    private void updatePosition() {
        Vec3 position = ClientPhysicalAudioCompat.worldPosition(projector, speakerPos);
        x = position.x;
        y = position.y;
        z = position.z;
    }

    private static float playbackRate(NetworkProjectorBlockEntity projector) {
        return PlaybackSpeeds.rate(projector.getSpeed());
    }

    private static double wrap(double value, double duration) {
        if (duration <= 0.0) return value;
        double wrapped = value % duration;
        return wrapped < 0.0 ? wrapped + duration : wrapped;
    }

    private static double circularDistance(double first, double second, double duration) {
        double distance = Math.abs(first - second);
        return duration <= 0.0 ? distance : Math.min(distance, duration - distance);
    }

    private static ResourceLocation soundLocation(BlockPos projector, BlockPos speaker) {
        return ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID,
                "network_audio/" + Long.toUnsignedString(projector.asLong()) + "/" + Long.toUnsignedString(speaker.asLong()));
    }
}
