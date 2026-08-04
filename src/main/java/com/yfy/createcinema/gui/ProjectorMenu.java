package com.yfy.createcinema.gui;

import com.yfy.createcinema.ModRegistry;
import com.yfy.createcinema.blockentity.ProjectorBlockEntity;
import com.yfy.createcinema.item.FilmItem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

public class ProjectorMenu extends AbstractContainerMenu {
    public final BlockPos pos;
    private final ProjectorBlockEntity projector;

    public ProjectorMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(ModRegistry.PROJECTOR_MENU.get(), containerId);
        this.pos = pos;
        BlockEntity be = inventory.player.level().getBlockEntity(pos);
        projector = be instanceof ProjectorBlockEntity found ? found : null;

        net.minecraft.world.Container container = projector != null ? projector : new SimpleContainer(2);
        addSlot(new FilmSlot(container, 0, 80, 35));
        addSlot(new ReservedUpgradeSlot(container, 1, 188, 36));
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, 92 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 8 + col * 18, 150));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot slot = slots.get(slotIndex);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();

        if (slotIndex < 2) {
            if (!moveItemStackTo(stack, 2, slots.size(), true)) return ItemStack.EMPTY;
        } else if (stack.getItem() instanceof FilmItem) {
            if (!moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
        } else {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return projector != null && projector.stillValid(player);
    }

    private static class FilmSlot extends Slot {
        private FilmSlot(net.minecraft.world.Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.getItem() instanceof FilmItem && !FilmItem.getFilmId(stack).isBlank();
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }

    private static class ReservedUpgradeSlot extends Slot {
        private ReservedUpgradeSlot(net.minecraft.world.Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }
}
