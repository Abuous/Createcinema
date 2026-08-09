package com.yfy.createcinema.client;

import com.yfy.createcinema.CreateCinema;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.resources.ResourceLocation;

final class CinemaPartialModels {
    static final PartialModel PROJECTOR_WHEEL_TOP = partial("block/projector_wheel_top");
    static final PartialModel PROJECTOR_WHEEL_BOTTOM = partial("block/projector_wheel_bottom");

    private CinemaPartialModels() {
    }

    static void init() {
        // Forces registration before Create's partial-model bake listener runs.
    }

    private static PartialModel partial(String path) {
        return PartialModel.of(ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID, path));
    }
}
