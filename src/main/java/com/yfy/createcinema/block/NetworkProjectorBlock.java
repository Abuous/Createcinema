package com.yfy.createcinema.block;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.equipment.wrench.WrenchItem;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.block.IBE;
import com.yfy.createcinema.ModRegistry;
import com.yfy.createcinema.blockentity.NetworkProjectorBlockEntity;
import com.yfy.createcinema.client.ClientCableIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

public class NetworkProjectorBlock extends KineticBlock implements IBE<NetworkProjectorBlockEntity>, IWrenchable {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public NetworkProjectorBlock(Properties properties) { super(properties); registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH)); }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()); }
    @Override public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) { return face == state.getValue(FACING).getOpposite(); }
    @Override public Direction.Axis getRotationAxis(BlockState state) { return state.getValue(FACING).getAxis(); }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                                        Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.getItem() instanceof WrenchItem) return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof NetworkProjectorBlockEntity projector)
            serverPlayer.openMenu(projector.getMenuProvider(), pos);
        return InteractionResult.SUCCESS;
    }
    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                               LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        // A newly placed cable updates this projector, not the cable itself.
        ClientCableIndex.onBlockChanged(level, neighborPos);
        return state;
    }
    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (state.is(newState.getBlock())) return;
        if (level.getBlockEntity(pos) instanceof NetworkProjectorBlockEntity projector) {
            Containers.dropContents(level, pos, projector);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
    @Override public Class<NetworkProjectorBlockEntity> getBlockEntityClass() { return NetworkProjectorBlockEntity.class; }
    @Override public BlockEntityType<? extends NetworkProjectorBlockEntity> getBlockEntityType() { return ModRegistry.NETWORK_PROJECTOR_BE.get(); }
    @Override public BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override public InteractionResult onWrenched(BlockState state, UseOnContext context) { BlockState rotated = state.setValue(FACING, state.getValue(FACING).getClockWise()); if (!context.getLevel().isClientSide) { KineticBlockEntity.switchToBlockState(context.getLevel(), context.getClickedPos(), rotated); IWrenchable.playRotateSound(context.getLevel(), context.getClickedPos()); } return InteractionResult.SUCCESS; }
    @Override public BlockState mirror(BlockState state, Mirror mirror) { return state.setValue(FACING, mirror.mirror(state.getValue(FACING))); }
}
