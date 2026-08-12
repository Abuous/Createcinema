package com.yfy.createcinema.client.event;
import com.yfy.createcinema.client.audio.NetworkProjectorSoundInstance;
import com.yfy.createcinema.client.audio.FilmSoundInstance;
import com.yfy.createcinema.client.douyin.DouyinBrowserBridge;
import com.yfy.createcinema.client.audio.ClientProjectorAudio;
import com.yfy.createcinema.client.network.ClientPacketHandlers;
import com.yfy.createcinema.client.network.ClientNetworkProjectorStreams;
import com.yfy.createcinema.client.audio.ClientNetworkProjectorAudio;
import com.yfy.createcinema.client.film.ClientFilmVideoStreams;
import com.yfy.createcinema.client.network.ClientCableIndex;
import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.blockentity.NetworkProjectorBlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.minecraft.world.level.chunk.LevelChunk;
@EventBusSubscriber(modid = CreateCinema.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class ClientRuntimeEvents {
    private static Object activeLevel;
    @SubscribeEvent public static void clientTick(ClientTickEvent.Post event) {
        Object level = net.minecraft.client.Minecraft.getInstance().level;
        if (level != activeLevel) {
            clearPlayback();
            activeLevel = level;
        }
        ClientProjectorAudio.tick();
        ClientFilmVideoStreams.tick();
        ClientNetworkProjectorAudio.tick();
        ClientNetworkProjectorStreams.tick();
        ClientCableIndex.tick(net.minecraft.client.Minecraft.getInstance().level);
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientPacketHandlers.clearDownloads();
        clearPlayback();
        activeLevel = null;
    }
    @SubscribeEvent public static void chunkLoad(ChunkEvent.Load event) {
        if (!(event.getChunk() instanceof LevelChunk chunk) || !chunk.getLevel().isClientSide) return;
        chunk.getBlockEntities().values().forEach(blockEntity -> {
            if (blockEntity instanceof NetworkProjectorBlockEntity projector) ClientNetworkProjectorAudio.mark(projector);
        });
    }

    @SubscribeEvent public static void chunkUnload(ChunkEvent.Unload event) {
        if (!(event.getChunk() instanceof LevelChunk chunk) || !chunk.getLevel().isClientSide) return;
        ClientCableIndex.onChunkUnloaded(chunk.getLevel(), chunk.getPos());
    }
    @SubscribeEvent public static void streamingSourceStarted(PlayStreamingSourceEvent event) {
        if (event.getSound() instanceof FilmSoundInstance film) film.onChannelStarted();
        if (event.getSound() instanceof NetworkProjectorSoundInstance network)
            network.onChannelStarted(event.getEngine(), event.getChannel());
    }
    @SubscribeEvent public static void shuttingDown(GameShuttingDownEvent event) {
        ClientPacketHandlers.clearDownloads();
        DouyinBrowserBridge.close();
    }
    private static void clearPlayback() {
        ClientProjectorAudio.stopAll();
        ClientFilmVideoStreams.closeAll();
        ClientNetworkProjectorAudio.stopAll();
        ClientNetworkProjectorStreams.closeAll();
        ClientCableIndex.removeAll();
        net.minecraft.client.Minecraft.getInstance().getSoundManager().stop();
    }
}
