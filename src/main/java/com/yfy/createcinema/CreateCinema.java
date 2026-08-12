package com.yfy.createcinema;

import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.foundation.data.CreateRegistrate;
import com.yfy.createcinema.client.connected.CableCasingCTRegistration;
import com.yfy.createcinema.client.ponder.CreateCinemaPonderPlugin;
import com.yfy.createcinema.datagen.ModItemModelProvider;
import com.yfy.createcinema.client.browser.PlatformInfo;
import com.yfy.createcinema.display.CinemaDisplaySources;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(CreateCinema.MODID)
public class CreateCinema {
    public static final String MODID = "createcinema";
    public static final Logger LOGGER = LoggerFactory.getLogger(CreateCinema.class);
    public static final CreateRegistrate REGISTRATE = CreateRegistrate.create(MODID);

    public CreateCinema(IEventBus modEventBus, ModContainer modContainer) {
        PlatformInfo.ensureJavacppPlatform();
        if (PlatformInfo.isAndroid()) {
            LOGGER.info("CreateCinema Android compatibility patch active; JavaCPP platform {}, pathsFirst={}",
                    System.getProperty("org.bytedeco.javacpp.platform"),
                    System.getProperty("org.bytedeco.javacpp.pathsFirst"));
        }
        ModRegistry.BLOCKS.register(modEventBus);
        ModRegistry.ITEMS.register(modEventBus);
        ModRegistry.BLOCK_ENTITIES.register(modEventBus);
        ModRegistry.MENU_TYPES.register(modEventBus);
        ModRegistry.DATA_COMPONENTS.register(modEventBus);
        ModRegistry.CREATIVE_TABS.register(modEventBus);
        REGISTRATE.registerEventListeners(modEventBus);
        CinemaDisplaySources.register();
        modContainer.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerCapabilities);
        modEventBus.addListener(ModItemModelProvider::gatherData);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            CreateCinemaPonderPlugin.register();
            CableCasingCTRegistration.register();
        }
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            BlockStressValues.IMPACTS.register(ModRegistry.PROJECTOR.get(), () -> 4.0);
            BlockStressValues.IMPACTS.register(ModRegistry.NETWORK_PROJECTOR.get(), () -> 4.0);
        });
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModRegistry.PROJECTOR_BE.get(),
                (projector, side) -> new SidedInvWrapper(projector, side));
    }
}
