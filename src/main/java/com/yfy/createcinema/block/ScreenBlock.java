package com.yfy.createcinema.block;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.yfy.createcinema.ModRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.createmod.catnip.placement.IPlacementHelper;
import net.createmod.catnip.placement.PlacementHelpers;
import net.createmod.catnip.placement.PlacementOffset;

import java.util.function.Predicate;

public class ScreenBlock extends Block implements IWrenchable {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final int PLACEMENT_HELPER_ID = PlacementHelpers.register(new ScreenPlacementHelper());
    private static final VoxelShape SOUTH_SHAPE = Block.box(0.0, 0.0, 14.0, 16.0, 16.0, 16.0);
    private static final VoxelShape NORTH_SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 2.0);
    private static final VoxelShape EAST_SHAPE = Block.box(14.0, 0.0, 0.0, 16.0, 16.0, 16.0);
    private static final VoxelShape WEST_SHAPE = Block.box(0.0, 0.0, 0.0, 2.0, 16.0, 16.0);

    public ScreenBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.SOUTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shape(state);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shape(state);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, net.minecraft.world.level.Level level,
                                              BlockPos pos, Player player, InteractionHand hand,
                                              net.minecraft.world.phys.BlockHitResult hitResult) {
        if (player.isShiftKeyDown()) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        IPlacementHelper helper = PlacementHelpers.get(PLACEMENT_HELPER_ID);
        if (!helper.matchesItem(stack)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        return helper.getOffset(player, level, state, pos, hitResult)
                .placeInWorld(level, (net.minecraft.world.item.BlockItem) stack.getItem(), player, hand, hitResult);
    }

    private static VoxelShape shape(BlockState state) {
        return switch (state.getValue(FACING)) {
            case NORTH -> NORTH_SHAPE;
            case EAST -> EAST_SHAPE;
            case WEST -> WEST_SHAPE;
            default -> SOUTH_SHAPE;
        };
    }

    private static final class ScreenPlacementHelper implements IPlacementHelper {
        private final Predicate<ItemStack> itemPredicate = stack -> stack.is(ModRegistry.SCREEN_ITEM.get())
                || stack.is(ModRegistry.BLACK_SCREEN_ITEM.get());
        private final Predicate<BlockState> statePredicate = state -> state.is(ModRegistry.SCREEN.get())
                || state.is(ModRegistry.BLACK_SCREEN.get());

        @Override
        public Predicate<ItemStack> getItemPredicate() {
            return itemPredicate;
        }

        @Override
        public Predicate<BlockState> getStatePredicate() {
            return statePredicate;
        }

        @Override
        public PlacementOffset getOffset(Player player, net.minecraft.world.level.Level level, BlockState state,
                                         BlockPos pos, net.minecraft.world.phys.BlockHitResult hitResult) {
            Direction facing = state.getValue(FACING);
            for (Direction direction : IPlacementHelper.orderedByDistanceExceptAxis(pos, hitResult.getLocation(), facing.getAxis())) {
                BlockPos target = pos;
                for (int distance = 1; distance <= 16; distance++) {
                    target = target.relative(direction);
                    BlockState targetState = level.getBlockState(target);
                    if (matchesState(targetState) && targetState.getValue(FACING) == facing) continue;
                    if (!targetState.canBeReplaced()) break;
                    return PlacementOffset.success(target, placed -> placed.setValue(FACING, facing));
                }
            }
            return PlacementOffset.fail();
        }
    }
}
