package com.yfy.createcinema.gui;

import com.yfy.createcinema.ModRegistry;
import com.yfy.createcinema.blockentity.BurnerBlockEntity;
import com.yfy.createcinema.item.FilmItem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.server.level.ServerPlayer;

public class BurnerMenu extends AbstractContainerMenu {
    public final BlockPos pos;
    private final BurnerBlockEntity burner;

    public BurnerMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(ModRegistry.BURNER_MENU.get(), containerId);
        this.pos = pos;
        BlockEntity be = inventory.player.level().getBlockEntity(pos);
        this.burner = be instanceof BurnerBlockEntity burner ? burner : null;

        if (burner != null) {
            addSlot(new FilmSlot(burner, 0, 222, 32));
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 49 + col * 18, 125 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 49 + col * 18, 185));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        Slot clicked = slots.get(slot);
        if (!clicked.hasItem()) return ItemStack.EMPTY;

        ItemStack original = clicked.getItem();
        ItemStack copy = original.copy();
        if (slot == 0) {
            if (!moveItemStackTo(original, 1, slots.size(), true)) return ItemStack.EMPTY;
        } else if (original.getItem() instanceof FilmItem) {
            if (!moveItemStackTo(original, 0, 1, false)) return ItemStack.EMPTY;
        } else {
            return ItemStack.EMPTY;
        }

        if (original.isEmpty()) clicked.setByPlayer(ItemStack.EMPTY);
        else clicked.setChanged();
        clicked.onTake(player, original);
        return copy;
    }

    public BurnerBlockEntity getBurner() {
        return burner;
    }

    public boolean hasBlankFilm() {
        return burner != null && burner.hasBlankFilm();
    }

    @Override
    public boolean stillValid(Player player) {
        return burner != null && burner.stillValid(player);
    }

    private static class FilmSlot extends Slot {
        private final BurnerBlockEntity burner;

        private FilmSlot(BurnerBlockEntity burner, int slot, int x, int y) {
            super(burner, slot, x, y);
            this.burner = burner;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.getItem() instanceof FilmItem;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public int getMaxStackSize(ItemStack stack) {
            return 1;
        }

        @Override
        public void onTake(Player player, ItemStack stack) {
            if (player instanceof ServerPlayer) {
                burner.cancelBurn(player);
            }
            super.onTake(player, stack);
        }
    }
}
