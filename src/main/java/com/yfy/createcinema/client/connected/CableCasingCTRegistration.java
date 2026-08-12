package com.yfy.createcinema.client.connected;

import com.simibubi.create.CreateClient;
import com.simibubi.create.foundation.block.connected.CTModel;
import com.simibubi.create.foundation.block.render.CustomBlockModels;
import com.yfy.createcinema.CreateCinema;

import net.minecraft.resources.ResourceLocation;

public final class CableCasingCTRegistration {

    private CableCasingCTRegistration() {}

    public static void register() {
        CustomBlockModels models = CreateClient.MODEL_SWAPPER.getCustomBlockModels();
        ResourceLocation cable = ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID, "cable");
        models.register(cable, base -> new CTModel(base, new CableCasingCTBehaviour()));
    }
}
