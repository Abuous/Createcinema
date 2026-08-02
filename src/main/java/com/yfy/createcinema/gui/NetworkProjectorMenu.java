package com.yfy.createcinema.gui;
import com.yfy.createcinema.ModRegistry;
import com.yfy.createcinema.blockentity.NetworkProjectorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
public class NetworkProjectorMenu extends AbstractContainerMenu {
    public final BlockPos pos;
    private final NetworkProjectorBlockEntity projector;
    public NetworkProjectorMenu(int id, Inventory inventory, BlockPos pos) {
        super(ModRegistry.NETWORK_PROJECTOR_MENU.get(), id); this.pos = pos;
        projector = inventory.player.level().getBlockEntity(pos) instanceof NetworkProjectorBlockEntity found ? found : null;
    }
    public String getUrl() { return projector == null ? "" : projector.getUrl(); }
    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player player) { return projector != null && projector.stillValid(player); }
}
