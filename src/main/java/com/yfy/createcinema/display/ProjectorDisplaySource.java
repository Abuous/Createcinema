package com.yfy.createcinema.display;

import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import com.simibubi.create.foundation.gui.ModularGuiLineBuilder;
import com.yfy.createcinema.blockentity.NetworkProjectorBlockEntity;
import com.yfy.createcinema.blockentity.ProjectorBlockEntity;
import com.yfy.createcinema.item.FilmItem;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

public final class ProjectorDisplaySource extends DisplaySource {
    private static final String MODE_KEY = "Mode";

    private enum Mode {
        ALL,
        FILM,
        STATE,
        PROGRESS,
        COMPLETION;

        private static Mode from(int value) {
            return value < 0 || value >= values().length ? ALL : values()[value];
        }
    }

    @Override
    public List<MutableComponent> provideText(DisplayLinkContext context, DisplayTargetStats stats) {
        BlockEntity source = context.getSourceBlockEntity();
        Mode mode = Mode.from(context.sourceConfig().getInt(MODE_KEY));
        if (source instanceof ProjectorBlockEntity projector) return filmLines(projector, mode, stats.maxRows());
        if (source instanceof NetworkProjectorBlockEntity projector) return networkLines(projector, mode, stats.maxRows());
        return EMPTY;
    }

    @Override
    public Component getName() {
        return Component.translatable("display_source.createcinema.projector_status");
    }

    @Override
    public int getPassiveRefreshTicks() {
        return 5;
    }

    @Override
    public void initConfigurationWidgets(DisplayLinkContext context, ModularGuiLineBuilder builder, boolean isFirstLine) {
        if (isFirstLine) return;
        builder.addSelectionScrollInput(0, 95, (input, label) -> input.forOptions(List.of(
                Component.translatable("display_source.createcinema.projector_mode.all"),
                Component.translatable("display_source.createcinema.projector_mode.film"),
                Component.translatable("display_source.createcinema.projector_mode.state"),
                Component.translatable("display_source.createcinema.projector_mode.progress"),
                Component.translatable("display_source.createcinema.projector_mode.completion"))), MODE_KEY);
    }

    private static List<MutableComponent> filmLines(ProjectorBlockEntity projector, Mode mode, int maxRows) {
        double duration = FilmItem.getDurationSeconds(projector.getFilm());
        double elapsed = duration > 0.0 ? Math.min(Math.max(0.0, projector.getPlayTime()), duration)
                : Math.max(0.0, projector.getPlayTime());
        int percent = duration <= 0.0 ? 0 : (int) Math.round(elapsed * 100.0 / duration);
        MutableComponent title = projector.getFilm().isEmpty()
                ? Component.translatable("display_source.createcinema.projector.no_film")
                : projector.getFilm().getHoverName().copy();
        MutableComponent progress = duration <= 0.0
                ? Component.translatable("display_source.createcinema.projector.unavailable")
                : Component.translatable("display_source.createcinema.projector.progress",
                formatTime(elapsed), formatTime(duration));
        MutableComponent completion = duration <= 0.0
                ? Component.translatable("display_source.createcinema.projector.unavailable")
                : Component.translatable("display_source.createcinema.projector.completion", percent);
        return selectLines(mode, title,
                Component.translatable("display_source.createcinema.projector.state", filmState(projector)),
                progress, completion, maxRows);
    }

    private static List<MutableComponent> networkLines(NetworkProjectorBlockEntity projector, Mode mode, int maxRows) {
        boolean live = projector.isMediaLive();
        double duration = projector.getMediaDurationSeconds();
        double elapsed = projector.getMediaTimeSeconds();
        MutableComponent title = Component.translatable(projector.getUrl().isBlank()
                ? "display_source.createcinema.projector.network_empty"
                : live ? "display_source.createcinema.projector.network_live"
                : "display_source.createcinema.projector.network_video");
        elapsed = Math.max(0.0, duration > 0.0 ? Math.min(elapsed, duration) : elapsed);
        MutableComponent progress = live || duration <= 0.0
                ? Component.translatable("display_source.createcinema.projector.elapsed", formatTime(elapsed))
                : Component.translatable("display_source.createcinema.projector.progress",
                formatTime(elapsed), formatTime(duration));
        MutableComponent completion = duration <= 0.0
                ? Component.translatable("display_source.createcinema.projector.unavailable")
                : Component.translatable("display_source.createcinema.projector.completion",
                (int) Math.round(elapsed * 100.0 / duration));
        return selectLines(mode, title,
                Component.translatable("display_source.createcinema.projector.state", networkState(projector)),
                progress, completion, maxRows);
    }

    private static List<MutableComponent> selectLines(Mode mode, MutableComponent title, MutableComponent state,
                                                       MutableComponent progress, MutableComponent completion,
                                                       int maxRows) {
        List<MutableComponent> lines = switch (mode) {
            case FILM -> List.of(title);
            case STATE -> List.of(state);
            case PROGRESS -> List.of(progress);
            case COMPLETION -> List.of(completion);
            case ALL -> List.of(title, state, progress, completion);
        };
        return limit(lines, maxRows);
    }

    private static MutableComponent filmState(ProjectorBlockEntity projector) {
        if (projector.hasCompletedFilm()) return Component.translatable("display_source.createcinema.projector.completed");
        if (projector.canProject()) return Component.translatable("display_source.createcinema.projector.playing");
        return Component.translatable("display_source.createcinema.projector.paused");
    }

    private static MutableComponent networkState(NetworkProjectorBlockEntity projector) {
        return switch (projector.getMediaStatus()) {
            case ERROR -> Component.translatable("display_source.createcinema.projector.error");
            case ENDED -> Component.translatable("display_source.createcinema.projector.ended");
            case PLAYING -> projector.canProject()
                    ? Component.translatable("display_source.createcinema.projector.playing")
                    : Component.translatable("display_source.createcinema.projector.paused");
            case LOADING -> Component.translatable("display_source.createcinema.projector.loading");
            case IDLE -> projector.canProject()
                    ? Component.translatable("display_source.createcinema.projector.loading")
                    : Component.translatable("display_source.createcinema.projector.paused");
        };
    }

    private static List<MutableComponent> limit(List<MutableComponent> lines, int maxRows) {
        return lines.subList(0, Math.min(lines.size(), Math.max(1, maxRows)));
    }

    private static String formatTime(double seconds) {
        long total = (long) Math.floor(Math.max(0.0, seconds));
        long hours = total / 3_600L;
        long minutes = total / 60L % 60L;
        long remainder = total % 60L;
        if (hours > 0L) return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainder);
        return String.format(java.util.Locale.ROOT, "%d:%02d", minutes, remainder);
    }
}
