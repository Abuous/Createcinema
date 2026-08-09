package com.yfy.createcinema.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.film.FilmLifecycle;
import com.yfy.createcinema.film.FilmMetadata;
import com.yfy.createcinema.film.FilmReferenceData;
import com.yfy.createcinema.film.FilmStorage;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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
                                .suggests(CreateCinemaCommands::suggestFilmIds)
                                .executes(context -> delete(context.getSource(), StringArgumentType.getString(context, "filmId")))))
                .then(Commands.literal("delete_all").executes(context -> deleteAll(context.getSource())))
                .then(Commands.literal("list").executes(context -> list(context.getSource())))
                .then(Commands.literal("info")
                        .then(Commands.argument("filmId", StringArgumentType.word())
                                .suggests(CreateCinemaCommands::suggestFilmIds)
                                .executes(context -> info(context.getSource(), StringArgumentType.getString(context, "filmId")))))
                .then(Commands.literal("import")
                        .then(Commands.argument("packagePath", StringArgumentType.greedyString())
                                .executes(context -> importPackage(context.getSource(),
                                        StringArgumentType.getString(context, "packagePath")))))
                .then(Commands.literal("add")
                        .then(Commands.argument("packagePath", StringArgumentType.greedyString())
                                .executes(context -> importPackage(context.getSource(),
                                        StringArgumentType.getString(context, "packagePath")))))
                .then(Commands.literal("give")
                        .then(Commands.argument("filmId", StringArgumentType.word())
                                .suggests(CreateCinemaCommands::suggestFilmIds)
                                .executes(context -> give(context, StringArgumentType.getString(context, "filmId"),
                                        context.getSource().getPlayerOrException()))
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> give(context, StringArgumentType.getString(context, "filmId"),
                                                EntityArgument.getPlayer(context, "player")))))));
    }

    private static int give(CommandContext<CommandSourceStack> context, String filmId, ServerPlayer target) {
        ItemStackResult result;
        try {
            result = new ItemStackResult(FilmLifecycle.createFilmCopy(context.getSource().getServer(), filmId), null);
        } catch (IOException e) {
            CreateCinema.LOGGER.warn("Failed to create a film copy for {}", filmId, e);
            result = new ItemStackResult(null, "error");
        }
        if (result.stack == null || result.stack.isEmpty()) {
            sourceFailure(context.getSource(), result.error == null ? "missing" : result.error, filmId);
            return 0;
        }

        if (!target.getInventory().add(result.stack)) target.drop(result.stack, false);
        target.inventoryMenu.broadcastChanges();
        context.getSource().sendSuccess(() -> Component.translatable("command.createcinema.give.success",
                target.getDisplayName(), filmId), true);
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestFilmIds(CommandContext<CommandSourceStack> context,
                                                                  SuggestionsBuilder builder) {
        try {
            FilmReferenceData references = FilmReferenceData.get(context.getSource().getServer());
            return SharedSuggestionProvider.suggest(FilmStorage.listFilmIds(context.getSource().getServer()).stream()
                    .filter(filmId -> !references.isDeleted(filmId))
                    .toList(), builder);
        } catch (IOException e) {
            return builder.buildFuture();
        }
    }

    private static void sourceFailure(CommandSourceStack source, String reason, String filmId) {
        source.sendFailure(Component.translatable("command.createcinema.give." + reason, filmId));
    }

    private static int delete(net.minecraft.commands.CommandSourceStack source, String filmId) {
        FilmMetadata metadata = readMetadata(source, filmId);
        if (!FilmLifecycle.deleteFilm(source.getServer(), filmId)) {
            source.sendFailure(Component.translatable("command.createcinema.delete.missing", filmId));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.createcinema.delete.success", filmId,
                metadata == null ? "unknown" : metadata.mediaTypeValue().id()), true);
        return 1;
    }

    private static int deleteAll(net.minecraft.commands.CommandSourceStack source) {
        List<MediaSummary> media = availableFilmIds(source).stream()
                .map(filmId -> new MediaSummary(filmId, readMetadata(source, filmId)))
                .toList();
        int count = FilmLifecycle.deleteAllFilms(source.getServer());
        for (MediaSummary entry : media) {
            source.sendSuccess(() -> Component.translatable("command.createcinema.delete_all.item", entry.filmId(),
                    entry.metadata() == null ? "unknown" : entry.metadata().mediaTypeValue().id()), true);
        }
        source.sendSuccess(() -> Component.translatable("command.createcinema.delete_all.success", count), true);
        return count;
    }

    private static int list(CommandSourceStack source) {
        List<String> filmIds = availableFilmIds(source);
        if (filmIds.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("command.createcinema.list.empty"), false);
            return 0;
        }
        for (String filmId : filmIds) {
            FilmMetadata metadata = readMetadata(source, filmId);
            if (metadata == null) continue;
            source.sendSuccess(() -> Component.translatable("command.createcinema.list.item", metadata.id(),
                    metadata.mediaTypeValue().id(), metadata.title()), false);
        }
        source.sendSuccess(() -> Component.translatable("command.createcinema.list.total", filmIds.size()), false);
        return filmIds.size();
    }

    private static int info(CommandSourceStack source, String filmId) {
        FilmMetadata metadata = readMetadata(source, filmId);
        if (metadata == null || FilmReferenceData.get(source.getServer()).isDeleted(filmId)) {
            source.sendFailure(Component.translatable("command.createcinema.info.missing", filmId));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.createcinema.info", metadata.id(),
                metadata.mediaTypeValue().id(), metadata.title(), metadata.width(), metadata.height(), metadata.frameCount()), false);
        return 1;
    }

    private static int importPackage(CommandSourceStack source, String pathText) {
        Path path;
        try {
            path = Path.of(unquote(pathText));
        } catch (InvalidPathException error) {
            source.sendFailure(Component.translatable("command.createcinema.import.invalid_path", pathText));
            return 0;
        }
        if (!Files.isRegularFile(path)) {
            source.sendFailure(Component.translatable("command.createcinema.import.missing", path));
            return 0;
        }
        try {
            FilmMetadata metadata = FilmStorage.saveUploadedFilm(source.getServer(), path);
            FilmLifecycle.restoreFilm(source.getServer(), metadata.id());
            source.sendSuccess(() -> Component.translatable("command.createcinema.import.success", metadata.id(),
                    metadata.mediaTypeValue().id(), metadata.title()), true);
            return 1;
        } catch (IOException | RuntimeException error) {
            CreateCinema.LOGGER.warn("Failed to import Create Cinema media package {}", path, error);
            source.sendFailure(Component.translatable("command.createcinema.import.invalid_package", path));
            return 0;
        }
    }

    private static List<String> availableFilmIds(CommandSourceStack source) {
        try {
            FilmReferenceData references = FilmReferenceData.get(source.getServer());
            return FilmStorage.listFilmIds(source.getServer()).stream()
                    .filter(filmId -> !references.isDeleted(filmId))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException error) {
            source.sendFailure(Component.translatable("command.createcinema.list.error"));
            return List.of();
        }
    }

    private static FilmMetadata readMetadata(CommandSourceStack source, String filmId) {
        try {
            return FilmStorage.exists(source.getServer(), filmId)
                    ? FilmStorage.readServerMetadata(source.getServer(), filmId) : null;
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        return trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")
                ? trimmed.substring(1, trimmed.length() - 1) : trimmed;
    }

    private record ItemStackResult(net.minecraft.world.item.ItemStack stack, String error) {
    }

    private record MediaSummary(String filmId, FilmMetadata metadata) {
    }
}
