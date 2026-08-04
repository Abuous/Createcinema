package com.yfy.createcinema.item;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

public class TooltipBlockItem extends BlockItem {
    private final String tooltipKey;
    private final int detailLines;

    public TooltipBlockItem(Block block, Properties properties, String tooltipKey, int detailLines) {
        super(block, properties);
        this.tooltipKey = tooltipKey;
        this.detailLines = detailLines;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (!Screen.hasShiftDown()) {
            tooltip.add(Component.translatable("tooltip.createcinema.hold_shift").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        for (int line = 1; line <= detailLines; line++) {
            tooltip.add(Component.translatable(tooltipKey + "." + line).withStyle(ChatFormatting.GRAY));
        }
    }
}
