package com.yfy.createcinema.client.ponder;

import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.ModRegistry;
import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.createmod.ponder.foundation.PonderIndex;
import net.minecraft.resources.ResourceLocation;

public class CreateCinemaPonderPlugin implements PonderPlugin {
    private static boolean registered;
    private static final ResourceLocation TAG = ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID, "cinema");

    public static void register() {
        if (registered) return;
        registered = true;
        PonderIndex.addPlugin(new CreateCinemaPonderPlugin());
    }

    @Override
    public String getModId() {
        return CreateCinema.MODID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        helper.forComponents(location("burner"))
                .addStoryBoard("film_burning", CreateCinemaPonderScenes::filmBurning, TAG);
        helper.forComponents(location("projector"))
                .addStoryBoard("projector_setup", CreateCinemaPonderScenes::projectorSetup, TAG);
        helper.forComponents(location("network_projector"))
                .addStoryBoard("network_audio", CreateCinemaPonderScenes::networkAudio, TAG);
    }

    @Override
    public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        helper.registerTag(TAG)
                .title("Create Cinema")
                .description("Projectors, screens, films, network video, and speaker audio")
                .item(ModRegistry.PROJECTOR_ITEM.get(), true, false)
                .addToIndex()
                .register();
        helper.addToTag(TAG).add(location("burner")).add(location("projector")).add(location("network_projector"));
    }

    private static ResourceLocation location(String path) {
        return ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID, path);
    }
}
