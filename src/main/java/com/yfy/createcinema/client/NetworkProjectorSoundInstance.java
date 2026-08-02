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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class NetworkProjectorSoundInstance extends AbstractSoundInstance implements TickableSoundInstance {
    private final BlockPos projectorPos;
    private final BlockPos speakerPos;
    private final String url;
    private final BilibiliResolver.ResolvedMedia sourceMedia;
    private volatile double latestPlayTime;
    private int connectionCheck;
    private boolean stopped;

    public NetworkProjectorSoundInstance(NetworkProjectorBlockEntity projector, BlockPos speaker,
                                         BilibiliResolver.ResolvedMedia sourceMedia) {
        super(soundLocation(projector.getBlockPos(), speaker), SoundSource.RECORDS, RandomSource.create());
        projectorPos = projector.getBlockPos().immutable();
        speakerPos = speaker.immutable();
        url = projector.getUrl();
        this.sourceMedia = sourceMedia;
        latestPlayTime = ClientNetworkProjectorStreams.mediaTime(projector);
        x = speaker.getX() + 0.5;
        y = speaker.getY() + 0.5;
        z = speaker.getZ() + 0.5;
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
                return new NetworkFfmpegAudioStream(sourceMedia,
                        () -> wrap(latestPlayTime, sourceMedia.durationSeconds()));
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, Util.nonCriticalIoPool());
    }

    @Override
    public void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null
                || !(minecraft.level.getBlockEntity(projectorPos) instanceof NetworkProjectorBlockEntity projector)
                || !projector.canProject() || !url.equals(projector.getUrl())
                || minecraft.player.distanceToSqr(x, y, z) > 96.0 * 96.0) {
            stopped = true;
            return;
        }
        latestPlayTime = ClientNetworkProjectorStreams.mediaTime(projector);
        pitch = playbackRate(projector);
        volume = SpeakerBlock.redstoneVolume(minecraft.level, speakerPos);
        if (--connectionCheck <= 0) {
            connectionCheck = 10;
            if (!CinemaAudioNetwork.isConnected(minecraft.level, projectorPos, speakerPos)) stopped = true;
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

    private static float playbackRate(NetworkProjectorBlockEntity projector) {
        return PlaybackSpeeds.rate(projector.getSpeed());
    }

    private static double wrap(double value, double duration) {
        if (duration <= 0.0) return value;
        double wrapped = value % duration;
        return wrapped < 0.0 ? wrapped + duration : wrapped;
    }

    private static ResourceLocation soundLocation(BlockPos projector, BlockPos speaker) {
        return ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID,
                "network_audio/" + Long.toUnsignedString(projector.asLong()) + "/" + Long.toUnsignedString(speaker.asLong()));
    }
}
