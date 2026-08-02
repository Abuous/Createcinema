package com.yfy.createcinema;

import com.simibubi.create.api.stress.BlockStressValues;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(CreateCinema.MODID)
public class CreateCinema {
    public static final String MODID = "createcinema";
    public static final Logger LOGGER = LoggerFactory.getLogger(CreateCinema.class);

    public CreateCinema(IEventBus modEventBus) {
        ModRegistry.BLOCKS.register(modEventBus);
        ModRegistry.ITEMS.register(modEventBus);
        ModRegistry.BLOCK_ENTITIES.register(modEventBus);
        ModRegistry.MENU_TYPES.register(modEventBus);
        ModRegistry.CREATIVE_TABS.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            BlockStressValues.IMPACTS.register(ModRegistry.PROJECTOR.get(), () -> 4.0);
            BlockStressValues.IMPACTS.register(ModRegistry.NETWORK_PROJECTOR.get(), () -> 4.0);
        });
    }
}
