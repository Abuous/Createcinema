package com.yfy.createcinema.client.connected;

import com.simibubi.create.AllSpriteShifts;
import com.simibubi.create.foundation.block.connected.CTSpriteShiftEntry;
import com.simibubi.create.foundation.block.connected.ConnectedTextureBehaviour;
import com.yfy.createcinema.block.CableBlock;
import com.yfy.createcinema.block.CableBlock.CasingType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

public class CableCasingCTBehaviour extends ConnectedTextureBehaviour.Base {

    @Override
    public boolean connectsTo(BlockState state, BlockState other, BlockAndTintGetter level,
                              BlockPos pos, BlockPos otherPos, Direction face) {
        if (isBeingBlocked(state, level, pos, otherPos, face))
            return false;
        if (!(other.getBlock() instanceof CableBlock))
            return false;
        CasingType from = state.getValue(CableBlock.CASING);
        CasingType to = other.getValue(CableBlock.CASING);
        return from != CasingType.NONE && from == to;
    }

    @Override
    public CTSpriteShiftEntry getShift(BlockState state, Direction face, TextureAtlasSprite sprite) {
        return switch (state.getValue(CableBlock.CASING)) {
            case ANDESITE -> AllSpriteShifts.ANDESITE_CASING;
            case BRASS -> AllSpriteShifts.BRASS_CASING;
            default -> null;
        };
    }
}