package com.yfy.createcinema.client;

import com.mojang.blaze3d.audio.Channel;
import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.ClientConfig;
import com.yfy.createcinema.PlaybackSpeeds;
import com.yfy.createcinema.block.SpeakerBlock;
import com.yfy.createcinema.blockentity.NetworkProjectorBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.phys.Vec3;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NetworkProjectorSoundInstance extends AbstractSoundInstance implements TickableSoundInstance {
    private static final double RESYNC_THRESHOLD_SECONDS = 2.0;
    private static final int RESYNC_CONFIRM_TICKS = 10;
    private static final int FREEZE_STOP_TICKS = 40;
    private static final ExecutorService AUDIO_OPEN_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "CreateCinema Network Audio Open");
        thread.setDaemon(true);
        return thread;
    });
    private volatile BlockPos anchor;
    private final String clusterId;
    private final NetworkProjectorBlockEntity projector;
    private final String url;
    private final String sourceKey;
    private final BilibiliResolver.ResolvedMedia sourceMedia;
    private final SharedNetworkAudio sharedAudio;
    private volatile double latestPlayTime;
    private volatile double streamStartTime = Double.NaN;
    private volatile AudioStream audioStream;
    private volatile ChannelAccess.ChannelHandle channelHandle;
    private volatile boolean channelStarted;
    private volatile long channelStartedNanos;
    private boolean audioClockStarted;
    private double expectedTime;
    private long audioClockNanos;
    private int driftTicks;
    private int freezeTicks;
    private double prevLatestPlayTime = Double.NaN;
    private volatile boolean openFailed;
    private volatile boolean openQueuedAtMillisKnown;
    private final long createdMillis = System.currentTimeMillis();
    private volatile boolean driftRestart;
    private volatile boolean stopped;

    public NetworkProjectorSoundInstance(NetworkProjectorBlockEntity projector, BlockPos anchor, String clusterId,
                                         ClientNetworkProjectorStreams.AudioSource source,
                                         SharedNetworkAudio sharedAudio) {
        super(soundLocation(projector.getBlockPos(), clusterId), SoundSource.RECORDS, RandomSource.create());
        this.anchor = anchor.immutable();
        this.clusterId = clusterId;
        this.projector = projector;
        url = projector.getUrl();
        sourceKey = source.key();
        sourceMedia = source.media();
        this.sharedAudio = sharedAudio;
        latestPlayTime = ClientNetworkProjectorStreams.mediaTime(projector);
        updatePosition();
        volume = projector.getLevel() == null ? 0.0f : SpeakerBlock.redstoneVolume(projector.getLevel(), this.anchor);
        pitch = playbackRate(projector, sourceMedia);
        attenuation = SoundInstance.Attenuation.LINEAR;
        sound = new Sound(location, ConstantFloat.of(1.0f), ConstantFloat.of(1.0f), 1,
                Sound.Type.FILE, true, false, ClientConfig.speakerAttenuationDistance());
    }

    void relocate(BlockPos newAnchor) {
        anchor = newAnchor.immutable();
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        WeighedSoundEvents event = new WeighedSoundEvents(location, null);
        event.addSound(sound);
        return event;
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
        openQueuedAtMillisKnown = true;
        return CompletableFuture.supplyAsync(this::openStream, AUDIO_OPEN_EXECUTOR);
    }

    private AudioStream openStream() {
        if (stopped || !ClientNetworkProjectorAudio.isCurrent(sourceKey, clusterId, this)) {
            return failedStream();
        }
        try {
            AudioStream stream;
            if (sharedAudio != null) {
                SharedNetworkAudio.Tap tap = sharedAudio.openTap();
                stream = tap;
                double firstChunk = tap.firstChunkSeconds();
                streamStartTime = Double.isNaN(firstChunk) ? tap.startTime() : firstChunk;
            } else {
                NetworkFfmpegAudioStream direct = new NetworkFfmpegAudioStream(sourceMedia,
                        () -> wrap(latestPlayTime, sourceMedia.durationSeconds()));
                stream = direct;
                streamStartTime = direct.startTime();
            }
            audioStream = stream;
            if (stopped || !ClientNetworkProjectorAudio.isCurrent(sourceKey, clusterId, this)) {
                closeStream(stream);
                return failedStream();
            }
            return stream;
        } catch (Exception e) {
            CreateCinema.LOGGER.warn("Failed to open {} network audio for {}",
                    sourceMedia.live() ? "live" : "video", url, e);
            return failedStream();
        }
    }

    private AudioStream failedStream() {
        openFailed = true;
        AudioStream failed = new SilentAudioStream();
        audioStream = failed;
        return failed;
    }

    @Override
    public void tick() {
        if (stopped) return;
        Minecraft minecraft = Minecraft.getInstance();
        updatePosition();
        if (minecraft.level == null || minecraft.player == null || projector.isRemoved() || projector.getLevel() == null
                || !projector.getLevel().dimension().equals(minecraft.level.dimension())
                || !projector.canProject() || !url.equals(projector.getUrl())
                || minecraft.player.distanceToSqr(x, y, z) > 48.0 * 48.0) {
            stopInstance();
            return;
        }
        ClientNetworkProjectorStreams.AudioSource activeSource = ClientNetworkProjectorStreams.audioSource(projector);
        if (activeSource == null || !sourceKey.equals(activeSource.key())) {
            stopInstance();
            return;
        }
        latestPlayTime = ClientNetworkProjectorStreams.mediaTime(projector);
        if (!Double.isNaN(prevLatestPlayTime) && latestPlayTime == prevLatestPlayTime) {
            freezeTicks++;
        } else {
            freezeTicks = 0;
        }
        prevLatestPlayTime = latestPlayTime;
        pitch = playbackRate(projector, sourceMedia);
        volume = SpeakerBlock.redstoneVolume(projector.getLevel(), anchor);
        if (sharedAudio != null && sharedAudio.failure() != null) {
            openFailed = true;
            CreateCinema.LOGGER.debug("Stopping network audio {} after shared decoder failure", url, sharedAudio.failure());
            stopInstance();
            return;
        }
        if (openFailed) {
            CreateCinema.LOGGER.warn("Restarting {} network audio {} after failed open",
                    sourceMedia.live() ? "live" : "video", url);
            driftRestart = true;
            stopInstance();
            return;
        }
        if (!sourceMedia.live() && freezeTicks >= FREEZE_STOP_TICKS) {
            CreateCinema.LOGGER.debug("Stopping network audio {} after video clock froze for {} ticks", url, freezeTicks);
            driftRestart = true;
            stopInstance();
            return;
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
        if (sourceMedia.live()) return;
        double drift = circularDistance(expectedTime, latestPlayTime, sourceMedia.durationSeconds());
        if (freezeTicks >= RESYNC_CONFIRM_TICKS) {
            driftTicks = 0;
        } else {
            driftTicks = drift > RESYNC_THRESHOLD_SECONDS ? driftTicks + 1 : 0;
        }
        if (driftTicks >= RESYNC_CONFIRM_TICKS) {
            CreateCinema.LOGGER.debug("Restarting network audio {} after {}s playback drift (audio {}s, video {}s)", url,
                    String.format(java.util.Locale.ROOT, "%.3f", drift),
                    String.format(java.util.Locale.ROOT, "%.3f", expectedTime),
                    String.format(java.util.Locale.ROOT, "%.3f", latestPlayTime));
            driftRestart = true;
            stopInstance();
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
        stopInstance();
    }

    public boolean driftRestart() {
        return driftRestart;
    }

    boolean openFailed() {
        return openFailed;
    }

    /** Last video clock position this sound sampled before it stopped. */
    double latestPlayTime() {
        return latestPlayTime;
    }

    boolean channelStarted() {
        return channelStarted;
    }

    long createdMillis() {
        return createdMillis;
    }

    /** True when getStream was invoked (i.e. SoundEngine accepted the play request). */
    boolean openQueued() {
        return openQueuedAtMillisKnown;
    }

    private void stopInstance() {
        if (stopped) return;
        stopped = true;
        CreateCinema.LOGGER.debug("Stopping network audio {}", url);
        ChannelAccess.ChannelHandle handle = channelHandle;
        if (handle != null) handle.execute(Channel::stop);
        AudioStream stream = audioStream;
        closeStream(stream);
    }

    public void onChannelStarted(SoundEngine engine, Channel channel) {
        ChannelAccess.ChannelHandle handle = engine.instanceToChannel.get(this);
        channelHandle = handle;
        if (stopped) {
            if (handle != null) handle.execute(Channel::stop);
            else channel.stop();
            AudioStream stream = audioStream;
            closeStream(stream);
            CreateCinema.LOGGER.debug("Stopped late network audio channel for {}", url);
            return;
        }
        channelStartedNanos = System.nanoTime();
        channelStarted = true;
        CreateCinema.LOGGER.debug("Started network audio {} at {}s", url,
                String.format(java.util.Locale.ROOT, "%.3f", streamStartTime));
    }

    private void updatePosition() {
        Vec3 position = ClientPhysicalAudioCompat.worldPosition(projector, anchor);
        x = position.x;
        y = position.y;
        z = position.z;
    }

    private static void closeStream(AudioStream stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (IOException ignored) {
        }
    }

    private static float playbackRate(NetworkProjectorBlockEntity projector,
                                      BilibiliResolver.ResolvedMedia source) {
        return source.live() ? 1.0f : PlaybackSpeeds.rate(projector.getSpeed());
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

    private static ResourceLocation soundLocation(BlockPos projector, String clusterId) {
        return ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID,
                "network_audio/" + Long.toUnsignedString(projector.asLong()) + "/" + clusterId);
    }

    /** Fallback stream so SoundEngine always attaches a channel, even when the open failed. */
    private static final class SilentAudioStream implements AudioStream {
        private static final AudioFormat FORMAT = new AudioFormat(
                44_100.0f, 16, 1, true, false);

        @Override
        public AudioFormat getFormat() {
            return FORMAT;
        }

        @Override
        public ByteBuffer read(int size) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(Math.max(1, size)).order(ByteOrder.LITTLE_ENDIAN);
            while (buffer.hasRemaining()) buffer.put((byte) 0);
            buffer.flip();
            return buffer;
        }

        @Override
        public void close() {
        }
    }
}
