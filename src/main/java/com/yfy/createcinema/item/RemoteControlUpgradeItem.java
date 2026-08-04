package com.yfy.createcinema.item;

import com.simibubi.create.foundation.item.ItemHelper;
import com.yfy.createcinema.ModRegistry;
import com.yfy.createcinema.gui.RemoteControlUpgradeMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.List;

public class RemoteControlUpgradeItem extends Item implements MenuProvider {
    public RemoteControlUpgradeItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!player.isShiftKeyDown() || hand != InteractionHand.MAIN_HAND)
            return InteractionResultHolder.pass(stack);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.openMenu(this, buffer -> ItemStack.STREAM_CODEC.encode(buffer, stack));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    public static ItemStackHandler getFrequencyItems(ItemStack stack) {
        ItemStackHandler inventory = new ItemStackHandler(4) {
            @Override
            public void setStackInSlot(int slot, ItemStack value) {
                super.setStackInSlot(slot, value.isEmpty() || recursive(value) ? ItemStack.EMPTY : value.copyWithCount(1));
            }

            @Override
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                return recursive(stack) ? stack : super.insertItem(slot, stack, simulate);
            }

            @Override
            public int getSlotLimit(int slot) {
                return 1;
            }

            private boolean recursive(ItemStack stack) {
                return stack.is(ModRegistry.REMOTE_CONTROL_UPGRADE.get())
                        || stack.has(ModRegistry.REMOTE_FREQUENCIES.get());
            }
        };
        ItemContainerContents contents = stack.getOrDefault(ModRegistry.REMOTE_FREQUENCIES.get(), ItemContainerContents.EMPTY);
        ItemHelper.fillItemStackHandler(contents, inventory);
        return inventory;
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new RemoteControlUpgradeMenu(id, inventory, player.getMainHandItem());
    }

    @Override
    public Component getDisplayName() {
        return getDescription();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (!Screen.hasShiftDown()) {
            tooltip.add(Component.translatable("tooltip.createcinema.hold_shift").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        tooltip.add(Component.translatable("tooltip.createcinema.remote_control_upgrade.1").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.createcinema.remote_control_upgrade.2").withStyle(ChatFormatting.GRAY));
    }
}
