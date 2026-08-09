package com.yfy.createcinema.block;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.equipment.wrench.WrenchItem;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class SpeakerBlock extends KineticBlock implements IWrenchable {

    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    private static final VoxelShape NORTH_SHAPE = Block.box(2.0, 0.0, 0.5, 14.0, 16.0, 14.0);
    private static final VoxelShape EAST_SHAPE = Block.box(2.0, 0.0, 2.0, 15.5, 16.0, 14.0);
    private static final VoxelShape SOUTH_SHAPE = Block.box(2.0, 0.0, 2.0, 14.0, 16.0, 15.5);
    private static final VoxelShape WEST_SHAPE = Block.box(0.5, 0.0, 2.0, 14.0, 16.0, 14.0);
    private static final VoxelShape SUPPORT_SHAPE = Block.box(0.0, 0.0, 0.0, 16.0, 16.0, 16.0);

    public SpeakerBlock(Properties properties) {
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

    // ---- KineticBlock 抽象方法（必须实现） ----
    @Override
    public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
        return false; // 扬声器没有传动轴接口
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        // 返回 FACING 的轴，或固定为 Y 轴均可
        return state.getValue(FACING).getAxis();
    }

    // ---- 扳手旋转（IWrenchable 接口方法） ----
    // 注意：不加 @Override，因为这是接口方法
    public BlockState rotate(BlockState state, Level level, BlockPos pos, Direction direction) {
        return state.setValue(FACING, state.getValue(FACING).getClockWise());
    }

    // 可选：覆盖 onWrenched 以触发更完整的旋转更新
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        BlockState rotated = state.setValue(FACING, state.getValue(FACING).getClockWise());
        if (!context.getLevel().isClientSide) {
            context.getLevel().setBlockAndUpdate(context.getClickedPos(), rotated);
            // 可以播放旋转音效（参考 ProjectorBlock）
        }
        return InteractionResult.SUCCESS;
    }

    // ---- 碰撞箱 ----
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapeFor(state);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapeFor(state);
    }

    @Override
    protected VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
        return SUPPORT_SHAPE;
    }

    // ---- 自定义红石音量方法 ----
    public static float redstoneVolume(Level level, BlockPos pos) {
        return level.getBestNeighborSignal(pos) / 15.0f;
    }

    private static VoxelShape shapeFor(BlockState state) {
        return switch (state.getValue(FACING)) {
            case NORTH -> NORTH_SHAPE;
            case EAST -> EAST_SHAPE;
            case SOUTH -> SOUTH_SHAPE;
            case WEST -> WEST_SHAPE;
            default -> SOUTH_SHAPE;
        };
    }
}
