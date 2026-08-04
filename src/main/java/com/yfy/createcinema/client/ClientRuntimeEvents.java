package com.yfy.createcinema.client;
import com.yfy.createcinema.CreateCinema;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
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
        ClientNetworkProjectorAudio.tick();
        ClientNetworkProjectorStreams.tick();
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        clearPlayback();
        activeLevel = null;
    }
    @SubscribeEvent public static void streamingSourceStarted(PlayStreamingSourceEvent event) {
        if (event.getSound() instanceof FilmSoundInstance film) film.onChannelStarted();
        if (event.getSound() instanceof NetworkProjectorSoundInstance network)
            network.onChannelStarted(event.getEngine(), event.getChannel());
    }
    @SubscribeEvent public static void shuttingDown(GameShuttingDownEvent event) {
        DouyinBrowserBridge.close();
    }
    private static void clearPlayback() {
        ClientProjectorAudio.stopAll();
        ClientNetworkProjectorAudio.stopAll();
        ClientNetworkProjectorStreams.closeAll();
        net.minecraft.client.Minecraft.getInstance().getSoundManager().stop();
    }
}
