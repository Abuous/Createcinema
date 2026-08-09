package com.yfy.createcinema.display;

import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.tterrag.registrate.util.entry.RegistryEntry;
import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.ModRegistry;
import net.minecraft.core.registries.Registries;

public final class CinemaDisplaySources {
    private static final RegistryEntry<DisplaySource, ProjectorDisplaySource> PROJECTOR_STATUS = CreateCinema.REGISTRATE
            .displaySource("projector_status", ProjectorDisplaySource::new)
            .onRegisterAfter(Registries.BLOCK_ENTITY_TYPE, source -> {
                DisplaySource.BY_BLOCK_ENTITY.add(ModRegistry.PROJECTOR_BE.get(), source);
                DisplaySource.BY_BLOCK_ENTITY.add(ModRegistry.NETWORK_PROJECTOR_BE.get(), source);
            })
            .register();
    private static final RegistryEntry<DisplayTarget, ProjectorDisplayTarget> PROJECTOR_OUTPUT = CreateCinema.REGISTRATE
            .displayTarget("projector", ProjectorDisplayTarget::new)
            .onRegisterAfter(Registries.BLOCK_ENTITY_TYPE, target ->
                    DisplayTarget.BY_BLOCK_ENTITY.register(ModRegistry.PROJECTOR_BE.get(), target))
            .register();

    private CinemaDisplaySources() {
    }

    public static void register() {
        // Forces static registration during mod construction.
    }
}
