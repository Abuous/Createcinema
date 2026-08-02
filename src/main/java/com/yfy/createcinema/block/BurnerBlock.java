package com.yfy.createcinema.block;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.yfy.createcinema.blockentity.BurnerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class BurnerBlock extends Block implements EntityBlock, IWrenchable {
    public BurnerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BurnerBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.getBlockEntity(pos) instanceof BurnerBlockEntity burner) {
            if (player.isShiftKeyDown() && !burner.hasNoFilm()) {
                if (!level.isClientSide) burner.ejectFilm(player);
                return InteractionResult.sidedSuccess(level.isClientSide);
            }
            if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
                serverPlayer.openMenu(burner.getMenuProvider(), pos);
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        if (!context.getLevel().isClientSide && context.getPlayer() instanceof ServerPlayer serverPlayer
                && context.getLevel().getBlockEntity(context.getClickedPos()) instanceof BurnerBlockEntity burner) {
            serverPlayer.openMenu(burner.getMenuProvider(), context.getClickedPos());
        }
        return InteractionResult.SUCCESS;
    }
}
