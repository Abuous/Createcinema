package com.yfy.createcinema.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

public class SpeakerBlock extends Block {
    public SpeakerBlock(Properties properties) {
        super(properties);
    }

    public static float redstoneVolume(Level level, BlockPos pos) {
        return level.getBestNeighborSignal(pos) / 15.0f;
    }
}
