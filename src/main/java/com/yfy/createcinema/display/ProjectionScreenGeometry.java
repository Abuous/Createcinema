package com.yfy.createcinema.display;

import com.yfy.createcinema.ClientConfig;
import com.yfy.createcinema.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class ProjectionScreenGeometry {
    private ProjectionScreenGeometry() {
    }

    public static ScreenMatrix find(Level level, BlockPos projector, Direction facing) {
        BlockPos anchor = findAnchor(level, projector, facing);
        if (anchor == null) return new ScreenMatrix(1, 1);
        Direction horizontal = facing.getClockWise();
        return expand(level, anchor, horizontal);
    }

    public static boolean hasAnyScreen(Level level, BlockPos projector, Direction facing) {
        return findAnchor(level, projector, facing) != null;
    }

    private static BlockPos findAnchor(Level level, BlockPos projector, Direction facing) {
        Direction horizontal = facing.getClockWise();
        int anchorRadius = ClientConfig.screenAnchorRadius();
        for (int distance = 1; distance <= ClientConfig.screenMaxDistance(); distance++) {
            BlockPos center = projector.relative(facing, distance);
            BlockPos anchor = null;
            int bestScore = Integer.MAX_VALUE;
            for (int vertical = -anchorRadius; vertical <= anchorRadius; vertical++) {
                for (int side = -anchorRadius; side <= anchorRadius; side++) {
                    BlockPos candidate = center.relative(horizontal, side).above(vertical);
                    int score = Math.abs(side) + Math.abs(vertical);
                    if (score < bestScore && isScreen(level, candidate)) {
                        anchor = candidate;
                        bestScore = score;
                    }
                }
            }
            if (anchor != null) return anchor;
        }
        return null;
    }

    private static ScreenMatrix expand(Level level, BlockPos anchor, Direction horizontal) {
        int maxWidth = ClientConfig.screenMaxWidth();
        int maxHeight = ClientConfig.screenMaxHeight();
        int minHorizontalLimit = -((maxWidth - 1) / 2);
        int maxHorizontalLimit = maxWidth - 1 + minHorizontalLimit;
        int minVerticalLimit = -((maxHeight - 1) / 2);
        int maxVerticalLimit = maxHeight - 1 + minVerticalLimit;
        int minHorizontal = 0;
        int maxHorizontal = 0;
        while (minHorizontal > minHorizontalLimit
                && isScreen(level, anchor.relative(horizontal, minHorizontal - 1))) minHorizontal--;
        while (maxHorizontal < maxHorizontalLimit
                && isScreen(level, anchor.relative(horizontal, maxHorizontal + 1))) maxHorizontal++;

        int minVertical = 0;
        int maxVertical = 0;
        while (minVertical > minVerticalLimit
                && isCompleteRow(level, anchor, horizontal, minHorizontal, maxHorizontal, minVertical - 1)) minVertical--;
        while (maxVertical < maxVerticalLimit
                && isCompleteRow(level, anchor, horizontal, minHorizontal, maxHorizontal, maxVertical + 1)) maxVertical++;
        return new ScreenMatrix(maxHorizontal - minHorizontal + 1, maxVertical - minVertical + 1);
    }

    private static boolean isCompleteRow(Level level, BlockPos anchor, Direction horizontal,
                                         int min, int max, int vertical) {
        for (int side = min; side <= max; side++) {
            if (!isScreen(level, anchor.relative(horizontal, side).above(vertical))) return false;
        }
        return true;
    }

    private static boolean isScreen(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(ModRegistry.SCREEN.get()) || state.is(ModRegistry.BLACK_SCREEN.get());
    }

    public record ScreenMatrix(int width, int height) {
    }
}
