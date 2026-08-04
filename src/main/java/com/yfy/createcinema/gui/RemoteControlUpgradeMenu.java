package com.yfy.createcinema.gui;

import com.simibubi.create.foundation.gui.menu.HeldItemGhostItemMenu;
import com.simibubi.create.foundation.item.ItemHelper;
import com.yfy.createcinema.ModRegistry;
import com.yfy.createcinema.item.RemoteControlUpgradeItem;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

public class RemoteControlUpgradeMenu extends HeldItemGhostItemMenu {
    public RemoteControlUpgradeMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        super(ModRegistry.REMOTE_CONTROL_UPGRADE_MENU.get(), id, inventory, buffer);
    }

    public RemoteControlUpgradeMenu(int id, Inventory inventory, ItemStack stack) {
        super(ModRegistry.REMOTE_CONTROL_UPGRADE_MENU.get(), id, inventory, stack);
    }

    @Override
    protected ItemStackHandler createGhostInventory() {
        return RemoteControlUpgradeItem.getFrequencyItems(contentHolder);
    }

    @Override
    protected void addSlots() {
        addPlayerSlots(8, 100);
        addSlot(new FrequencySlot(ghostInventory, 0, 48, 40));
        addSlot(new FrequencySlot(ghostInventory, 1, 66, 40));
        addSlot(new FrequencySlot(ghostInventory, 2, 110, 40));
        addSlot(new FrequencySlot(ghostInventory, 3, 128, 40));
    }

    @Override
    protected void saveData(ItemStack stack) {
        stack.set(ModRegistry.REMOTE_FREQUENCIES.get(), ItemHelper.containerContentsFromHandler(ghostInventory));
    }

    @Override
    protected boolean allowRepeats() {
        return true;
    }

    private static class FrequencySlot extends SlotItemHandler {
        private FrequencySlot(ItemStackHandler inventory, int slot, int x, int y) {
            super(inventory, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !stack.is(ModRegistry.REMOTE_CONTROL_UPGRADE.get())
                    && !stack.has(ModRegistry.REMOTE_FREQUENCIES.get());
        }
    }
}
