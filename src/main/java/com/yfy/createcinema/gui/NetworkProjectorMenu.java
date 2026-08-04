package com.yfy.createcinema.gui;
import com.yfy.createcinema.ModRegistry;
import com.yfy.createcinema.NetworkVideoQuality;
import com.yfy.createcinema.blockentity.NetworkProjectorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
public class NetworkProjectorMenu extends AbstractContainerMenu {
    public final BlockPos pos;
    private final NetworkProjectorBlockEntity projector;
    public NetworkProjectorMenu(int id, Inventory inventory, BlockPos pos) {
        super(ModRegistry.NETWORK_PROJECTOR_MENU.get(), id); this.pos = pos;
        projector = inventory.player.level().getBlockEntity(pos) instanceof NetworkProjectorBlockEntity found ? found : null;
        net.minecraft.world.Container upgrades = projector != null ? projector : new SimpleContainer(2);
        addSlot(new UpgradeSlot(upgrades, 0, 259, 40, ModRegistry.CONTINUOUS_PLAY_UPGRADE.get()));
        addSlot(new UpgradeSlot(upgrades, 1, 279, 40, ModRegistry.REMOTE_CONTROL_UPGRADE.get()));
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inventory, col + row * 9 + 9, 45 + col * 18, 154 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inventory, col, 45 + col * 18, 212));
        }
    }
    public String getUrl() { return projector == null ? "" : projector.getUrl(); }
    public NetworkVideoQuality getQuality() { return projector == null ? NetworkVideoQuality.HIGH : projector.getQuality(); }
    @Override public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot slot = slots.get(slotIndex);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        if (slotIndex < 2) {
            if (!moveItemStackTo(stack, 2, slots.size(), true)) return ItemStack.EMPTY;
        } else if (stack.is(ModRegistry.CONTINUOUS_PLAY_UPGRADE.get())) {
            if (!moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
        } else if (stack.is(ModRegistry.REMOTE_CONTROL_UPGRADE.get())) {
            if (!moveItemStackTo(stack, 1, 2, false)) return ItemStack.EMPTY;
        } else {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        return copy;
    }
    @Override public boolean stillValid(Player player) { return projector != null && projector.stillValid(player); }

    private static class UpgradeSlot extends Slot {
        private final net.minecraft.world.item.Item acceptedItem;

        private UpgradeSlot(net.minecraft.world.Container container, int slot, int x, int y,
                            net.minecraft.world.item.Item acceptedItem) {
            super(container, slot, x, y);
            this.acceptedItem = acceptedItem;
        }

        @Override public boolean mayPlace(ItemStack stack) { return stack.is(acceptedItem); }
        @Override public int getMaxStackSize() { return 1; }
    }
}
