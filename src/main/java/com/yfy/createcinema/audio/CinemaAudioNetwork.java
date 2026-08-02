package com.yfy.createcinema.audio;

import com.yfy.createcinema.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CinemaAudioNetwork {
    private static final int MAX_CABLES = 512;
    private static final int MAX_SPEAKERS = 16;

    private CinemaAudioNetwork() {
    }

    public static List<BlockPos> findSpeakers(Level level, BlockPos projector) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        List<BlockPos> speakers = new ArrayList<>();
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = projector.relative(direction);
            if (level.getBlockState(adjacent).is(ModRegistry.CABLE.get())) queue.add(adjacent);
        }

        while (!queue.isEmpty() && visited.size() < MAX_CABLES && speakers.size() < MAX_SPEAKERS) {
            BlockPos cable = queue.removeFirst();
            if (!visited.add(cable) || !level.getBlockState(cable).is(ModRegistry.CABLE.get())) continue;
            for (Direction direction : Direction.values()) {
                BlockPos adjacent = cable.relative(direction);
                if (level.getBlockState(adjacent).is(ModRegistry.CABLE.get())) {
                    if (!visited.contains(adjacent)) queue.addLast(adjacent);
                } else if (level.getBlockState(adjacent).is(ModRegistry.SPEAKER.get()) && !speakers.contains(adjacent)) {
                    speakers.add(adjacent.immutable());
                    if (speakers.size() >= MAX_SPEAKERS) break;
                }
            }
        }
        return speakers;
    }

    public static boolean isConnected(Level level, BlockPos projector, BlockPos speaker) {
        return findSpeakers(level, projector).contains(speaker);
    }
}
