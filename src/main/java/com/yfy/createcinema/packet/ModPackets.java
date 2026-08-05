package com.yfy.createcinema.packet;

import com.yfy.createcinema.CreateCinema;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = CreateCinema.MODID)
public class ModPackets {
    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(CreateCinema.MODID);

        registrar.playToServer(C2SBurnStatePacket.TYPE, C2SBurnStatePacket.STREAM_CODEC, C2SBurnStatePacket::handle);
        registrar.playToServer(C2SUploadFilmChunkPacket.TYPE, C2SUploadFilmChunkPacket.STREAM_CODEC, C2SUploadFilmChunkPacket::handle);
        registrar.playToServer(C2SRequestFilmPacket.TYPE, C2SRequestFilmPacket.STREAM_CODEC, C2SRequestFilmPacket::handle);
        registrar.playToServer(C2SSetNetworkProjectorUrlPacket.TYPE, C2SSetNetworkProjectorUrlPacket.STREAM_CODEC,
                C2SSetNetworkProjectorUrlPacket::handle);
        registrar.playToServer(C2SSetNetworkProjectorQualityPacket.TYPE,
                C2SSetNetworkProjectorQualityPacket.STREAM_CODEC, C2SSetNetworkProjectorQualityPacket::handle);

        registrar.playToClient(S2CBurnStatusPacket.TYPE, S2CBurnStatusPacket.STREAM_CODEC, S2CBurnStatusPacket::handle);
        registrar.playToClient(S2CDownloadFilmChunkPacket.TYPE, S2CDownloadFilmChunkPacket.STREAM_CODEC, S2CDownloadFilmChunkPacket::handle);
        registrar.playToClient(S2CFilmDeletedPacket.TYPE, S2CFilmDeletedPacket.STREAM_CODEC, S2CFilmDeletedPacket::handle);
    }
}
