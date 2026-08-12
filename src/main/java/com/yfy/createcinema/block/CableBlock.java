package com.yfy.createcinema.block;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.decoration.encasing.CasingBlock;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.yfy.createcinema.ModRegistry;
import com.yfy.createcinema.client.network.ClientCableIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class CableBlock extends PipeBlock implements IWrenchable {
    public static final MapCodec<CableBlock> CODEC = simpleCodec(CableBlock::new);
    public static final EnumProperty<CasingType> CASING = EnumProperty.create("casing", CasingType.class);

    public CableBlock(Properties properties) {
        super(0.1875f, properties);
        BlockState state = stateDefinition.any();
        for (Direction direction : Direction.values()) {
            state = state.setValue(PROPERTY_BY_DIRECTION.get(direction), false);
        }
        registerDefaultState(state.setValue(CASING, CasingType.NONE));
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
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (player.isShiftKeyDown() || !player.mayBuild()) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        CasingType casing = casingTypeOf(stack);
        if (casing == null) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (level.isClientSide) return ItemInteractionResult.SUCCESS;
        BlockState encased = state.setValue(CASING, casing);
        level.setBlock(pos, encased, 3);
        SoundType soundType = encased.getSoundType();
        level.playSound(null, pos, soundType.getPlaceSound(), SoundSource.BLOCKS,
                (soundType.getVolume() + 1.0f) / 2.0f, soundType.getPitch() * 0.8f);
        return ItemInteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        if (state.getValue(CASING) == CasingType.NONE) return InteractionResult.PASS;
        if (context.getLevel().isClientSide) return InteractionResult.SUCCESS;
        IWrenchable.playRemoveSound(context.getLevel(), context.getClickedPos());
        context.getLevel().setBlock(context.getClickedPos(), state.setValue(CASING, CasingType.NONE), 3);
        return InteractionResult.SUCCESS;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (state.getValue(CASING) != CasingType.NONE) return Shapes.block();
        return super.getShape(state, level, pos, context);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, UP, DOWN, CASING);
    }

    private static CasingType casingTypeOf(ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) return null;
        Block block = blockItem.getBlock();
        if (!(block instanceof CasingBlock)) return null;
        if (block == AllBlocks.ANDESITE_CASING.get()) return CasingType.ANDESITE;
        if (block == AllBlocks.BRASS_CASING.get()) return CasingType.BRASS;
        return null;
    }

    private static boolean connectsTo(BlockState state) {
        return state.is(ModRegistry.CABLE.get()) || state.is(ModRegistry.SPEAKER.get())
                || state.is(ModRegistry.PROJECTOR.get()) || state.is(ModRegistry.NETWORK_PROJECTOR.get());
    }

    public enum CasingType implements StringRepresentable {
        NONE("none"), ANDESITE("andesite"), BRASS("brass");

        private final String name;

        CasingType(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }
}