package com.yfy.createcinema.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.film.FilmLifecycle;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = CreateCinema.MODID)
public final class CreateCinemaCommands {
    private CreateCinemaCommands() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("createcinema")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("delete")
                        .then(Commands.argument("filmId", StringArgumentType.word())
                                .executes(context -> delete(context.getSource(), StringArgumentType.getString(context, "filmId")))))
                .then(Commands.literal("delete_all").executes(context -> deleteAll(context.getSource()))));
    }

    private static int delete(net.minecraft.commands.CommandSourceStack source, String filmId) {
        if (!FilmLifecycle.deleteFilm(source.getServer(), filmId)) {
            source.sendFailure(Component.translatable("command.createcinema.delete.missing", filmId));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.createcinema.delete.success", filmId), true);
        return 1;
    }

    private static int deleteAll(net.minecraft.commands.CommandSourceStack source) {
        int count = FilmLifecycle.deleteAllFilms(source.getServer());
        source.sendSuccess(() -> Component.translatable("command.createcinema.delete_all.success", count), true);
        return count;
    }
}
