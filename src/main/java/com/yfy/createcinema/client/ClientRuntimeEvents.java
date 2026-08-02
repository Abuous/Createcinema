package com.yfy.createcinema.client;
import com.yfy.createcinema.CreateCinema;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
@EventBusSubscriber(modid = CreateCinema.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class ClientRuntimeEvents {
    @SubscribeEvent public static void clientTick(ClientTickEvent.Post event) { ClientNetworkProjectorStreams.sweep(); }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { ClientNetworkProjectorAudio.stopAll(); ClientNetworkProjectorStreams.closeAll(); }
}
