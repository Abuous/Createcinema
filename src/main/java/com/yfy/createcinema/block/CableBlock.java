package com.yfy.createcinema.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.yfy.createcinema.ModRegistry;
import com.yfy.createcinema.client.ClientCableIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

public class CableBlock extends PipeBlock implements IWrenchable {
    public static final MapCodec<CableBlock> CODEC = simpleCodec(CableBlock::new);

    public CableBlock(Properties properties) {
        super(0.1875f, properties);
        BlockState state = stateDefinition.any();
        for (Direction direction : Direction.values()) {
            state = state.setValue(PROPERTY_BY_DIRECTION.get(direction), false);
        }
        registerDefaultState(state);
    }

    @Override
    protected MapCodec<? extends PipeBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState();
        for (Direction direction : Direction.values()) {
            BlockState neighbor = context.getLevel().getBlockState(context.getClickedPos().relative(direction));
            state = state.setValue(PROPERTY_BY_DIRECTION.get(direction), connectsTo(neighbor));
        }
        return state;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        ClientCableIndex.onBlockChanged(level, pos);
        return state.setValue(PROPERTY_BY_DIRECTION.get(direction), connectsTo(neighborState));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, UP, DOWN);
    }

    private static boolean connectsTo(BlockState state) {
        return state.is(ModRegistry.CABLE.get()) || state.is(ModRegistry.SPEAKER.get())
                || state.is(ModRegistry.PROJECTOR.get()) || state.is(ModRegistry.NETWORK_PROJECTOR.get());
    }
}
