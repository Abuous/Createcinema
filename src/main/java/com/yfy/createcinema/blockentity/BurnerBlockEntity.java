package com.yfy.createcinema.blockentity;

import com.yfy.createcinema.ModRegistry;
import com.yfy.createcinema.gui.BurnerMenu;
import com.yfy.createcinema.item.FilmItem;
import com.yfy.createcinema.film.FilmLifecycle;
import com.yfy.createcinema.packet.S2CBurnStatusPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class BurnerBlockEntity extends BlockEntity implements Container {
    private ItemStack film = ItemStack.EMPTY;
    private boolean burning;

    public BurnerBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.BURNER_BE.get(), pos, state);
    }

    public boolean hasNoFilm() {
        return film.isEmpty();
    }

    public boolean hasBlankFilm() {
        return film.getItem() instanceof FilmItem && FilmItem.getFilmId(film).isBlank();
    }

    public ItemStack getFilm() {
        return film;
    }

    public void setFilm(ItemStack stack) {
        this.film = stack;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void writeFilmId(String filmId, String title, double durationSeconds) {
        if (film.getItem() instanceof FilmItem) {
            film = FilmItem.create(ModRegistry.FILM.get(), filmId, title);
            FilmItem.setDurationSeconds(film, durationSeconds);
            if (level != null && !level.isClientSide && level.getServer() != null) {
                FilmLifecycle.registerCopy(level.getServer(), film);
            }
            burning = false;
            setChanged();
            if (level != null && !level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
        }
    }

    public void ejectFilm(Player player) {
        if (film.isEmpty()) return;
        cancelBurn(player);
        ItemStack stack = film.copy();
        film = ItemStack.EMPTY;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        if (!player.addItem(stack)) {
            player.drop(stack, false);
        }
    }

    public boolean isBurning() {
        return burning;
    }

    public void setBurning(boolean burning) {
        this.burning = burning;
    }

    public void cancelBurn(Player player) {
        if (!burning) return;
        burning = false;
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            new S2CBurnStatusPacket(worldPosition, "Film removed - burn cancelled", 0.0f, false).sendTo(serverPlayer);
        }
    }

    @Override
    public int getContainerSize() {
        return 1;
    }

    @Override
    public boolean isEmpty() {
        return film.isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return slot == 0 ? film : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (slot != 0 || film.isEmpty() || amount <= 0) return ItemStack.EMPTY;
        ItemStack removed = film.split(amount);
        if (film.isEmpty()) film = ItemStack.EMPTY;
        setChangedAndSync();
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot != 0) return ItemStack.EMPTY;
        ItemStack removed = film;
        film = ItemStack.EMPTY;
        return removed;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot != 0) return;
        setFilm(stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
    }

    @Override
    public boolean stillValid(Player player) {
        return level != null && level.getBlockEntity(worldPosition) == this
                && player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) <= 64.0;
    }

    @Override
    public void clearContent() {
        film = ItemStack.EMPTY;
        setChangedAndSync();
    }

    private void setChangedAndSync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!film.isEmpty()) {
            tag.put("Film", film.saveOptional(registries));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Film")) {
            film = ItemStack.parseOptional(registries, tag.getCompound("Film"));
        } else {
            film = ItemStack.EMPTY;
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    public MenuProvider getMenuProvider() {
        return new SimpleMenuProvider(
                (id, inv, player) -> new BurnerMenu(id, inv, worldPosition),
                Component.translatable("container.createcinema.burner")
        );
    }
}
