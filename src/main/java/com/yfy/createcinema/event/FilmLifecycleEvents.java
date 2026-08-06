package com.yfy.createcinema.event;

import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.film.FilmLifecycle;
import com.yfy.createcinema.item.FilmItem;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.item.ItemExpireEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

@EventBusSubscriber(modid = CreateCinema.MODID)
public final class FilmLifecycleEvents {
    private FilmLifecycleEvents() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onItemExpire(ItemExpireEvent event) {
        if (event.getExtraLife() > 0 || event.getEntity().level().isClientSide) return;
        if (event.getEntity().getItem().getItem() instanceof FilmItem) {
            FilmLifecycle.releaseDestroyedCopy(event.getEntity().getServer(), event.getEntity().getItem(), "expired",
                    event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            FilmLifecycle.syncDeletedFilms(player);
        }
    }
}
