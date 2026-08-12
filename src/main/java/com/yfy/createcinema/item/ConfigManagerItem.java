package com.yfy.createcinema.item;

import com.yfy.createcinema.client.config.ClientConfigManagerScreen;
import net.createmod.catnip.platform.CatnipServices;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

public class ConfigManagerItem extends Item {
    public ConfigManagerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND) return InteractionResultHolder.pass(stack);
        if (level.isClientSide) {
            CatnipServices.PLATFORM.executeOnClientOnly(
                    () -> com.yfy.createcinema.client.config.ClientConfigManagerScreen::open);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public net.minecraft.world.InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) return net.minecraft.world.InteractionResult.PASS;
        return use(context.getLevel(), player, context.getHand()).getResult();
    }

    @Override
    public net.minecraft.world.InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) return net.minecraft.world.InteractionResult.PASS;
        return use(context.getLevel(), player, context.getHand()).getResult();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (!Screen.hasShiftDown()) {
            tooltip.add(Component.translatable("tooltip.createcinema.hold_shift").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        tooltip.add(Component.translatable("tooltip.createcinema.config_manager.1").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.createcinema.config_manager.2").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.createcinema.config_manager.3").withStyle(ChatFormatting.GRAY));
    }
}
