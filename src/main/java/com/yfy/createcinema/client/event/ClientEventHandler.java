package com.yfy.createcinema.client.event;

import com.yfy.createcinema.client.render.PartialProjectorRenderer;
import com.yfy.createcinema.client.render.NetworkProjectorRenderer;
import com.yfy.createcinema.client.douyin.DouyinBrowserBridge;
import com.yfy.createcinema.client.render.ClientVideoBurner;
import com.yfy.createcinema.client.browser.BrowserProvider;
import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.ModRegistry;
import com.yfy.createcinema.gui.BurnerScreen;
import com.yfy.createcinema.gui.NetworkProjectorScreen;
import com.yfy.createcinema.gui.ProjectorScreen;
import com.yfy.createcinema.gui.RemoteControlUpgradeScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = CreateCinema.MODID, value = Dist.CLIENT)
public class ClientEventHandler {
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            com.yfy.createcinema.ClientConfig.clearLegacyDouyinCookie();
            com.yfy.createcinema.ClientConfig.setBrowserShutdown(provider ->
                    java.util.concurrent.CompletableFuture.runAsync(() ->
                            DouyinBrowserBridge.disable(BrowserProvider.byId(provider))));
            ClientVideoBurner.preloadFfmpeg();
        });
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModRegistry.BURNER_MENU.get(), BurnerScreen::new);
        event.register(ModRegistry.PROJECTOR_MENU.get(), ProjectorScreen::new);
        event.register(ModRegistry.NETWORK_PROJECTOR_MENU.get(), NetworkProjectorScreen::new);
        event.register(ModRegistry.REMOTE_CONTROL_UPGRADE_MENU.get(), RemoteControlUpgradeScreen::new);
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModRegistry.PROJECTOR_BE.get(), PartialProjectorRenderer::new);
        event.registerBlockEntityRenderer(ModRegistry.NETWORK_PROJECTOR_BE.get(), NetworkProjectorRenderer::new);
    }
}
