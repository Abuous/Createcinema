package com.yfy.createcinema.datagen;

import com.yfy.createcinema.CreateCinema;
import net.minecraft.data.PackOutput;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.data.event.GatherDataEvent;

public class ModItemModelProvider extends ItemModelProvider {
    public ModItemModelProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, CreateCinema.MODID, existingFileHelper);
    }

    @Override
    protected void registerModels() {
        blockItemWithDisplay("burner", 225);
        blockItemWithDisplay("screen", 225);
        blockItemWithDisplay("black_screen", 225);
        blockItemWithDisplay("projector", 225);
        blockItemWithDisplay("speaker", 225);
        blockItemWithDisplay("network_projector", 225);
        blockItemWithDisplay("darkroom_block", 225);
        cableItem();

        flatItem("film");
        flatItem("blank_image");
        flatItem("blank_album");
        flatItem("blank_slides");
        flatItem("continuous_play_upgrade");
        flatItem("remote_control_upgrade");
        flatItem("config_manager");
        flatItem("display_upgrade");
    }

    public static void gatherData(GatherDataEvent event) {
        if (event.includeClient()) {
            event.addProvider(new ModItemModelProvider(event.getGenerator().getPackOutput(), event.getExistingFileHelper()));
        }
    }

    private void blockItemWithDisplay(String name, int guiYRotation) {
        withExistingParent(name, modLoc("block/" + name))
                .transforms()
                .transform(ItemDisplayContext.GUI)
                .rotation(35, guiYRotation, 0)
                .scale(0.7f)
                .end()
                .transform(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND)
                .rotation(0, 0, 0)
                .translation(0, 0, 0)
                .scale(0.5f)
                .end()
                .transform(ItemDisplayContext.FIRST_PERSON_LEFT_HAND)
                .rotation(0, 0, 0)
                .translation(0, 0, 0)
                .scale(0.5f)
                .end()
                .transform(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND)
                .rotation(0, 0, 0)
                .translation(0, 0, 0)
                .scale(0.5f)
                .end()
                .transform(ItemDisplayContext.THIRD_PERSON_LEFT_HAND)
                .rotation(0, 0, 0)
                .translation(0, 0, 0)
                .scale(0.5f)
                .end()
                .end();
    }

    private void cableItem() {
        withExistingParent("cable", modLoc("block/cable_item_connected"))
                .transforms()
                .transform(ItemDisplayContext.GUI)
                .rotation(30, 225, 0)
                .scale(1f)
                .end()
                .transform(ItemDisplayContext.GROUND)
                .translation(0, 3, 0)
                .scale(0.35f)
                .end()
                .transform(ItemDisplayContext.FIXED)
                .rotation(0, 0, 0)
                .scale(0.75f)
                .end()
                .transform(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND)
                .rotation(75, 45, 0)
                .translation(0, 2.5f, 0)
                .scale(0.55f)
                .end()
                .transform(ItemDisplayContext.THIRD_PERSON_LEFT_HAND)
                .rotation(75, 315, 0)
                .translation(0, 2.5f, 0)
                .scale(0.55f)
                .end()
                .transform(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND)
                .rotation(0, 45, 0)
                .translation(0, 0, 0)
                .scale(0.65f)
                .end()
                .transform(ItemDisplayContext.FIRST_PERSON_LEFT_HAND)
                .rotation(0, 225, 0)
                .translation(0, 0, 0)
                .scale(0.75f)
                .end()
                .end();
    }

    private void flatItem(String name) {
        singleTexture(name, mcLoc("item/generated"), "layer0", modLoc("item/" + name));
    }
}
