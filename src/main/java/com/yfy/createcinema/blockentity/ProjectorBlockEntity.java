package com.yfy.createcinema.blockentity;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.yfy.createcinema.ModRegistry;
import com.yfy.createcinema.PlaybackSpeeds;
import com.yfy.createcinema.gui.ProjectorMenu;
import com.yfy.createcinema.item.FilmItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

public class ProjectorBlockEntity extends KineticBlockEntity implements Container {
    private ItemStack film = ItemStack.EMPTY;
    private double playTime;

    public ProjectorBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.PROJECTOR_BE.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null) return;
        if (canProject()) {
            playTime += PlaybackSpeeds.secondsPerTick(getSpeed());
        }
        if (!level.isClientSide && level.getGameTime() % 20L == 0L) setChanged();
    }

    public boolean canProject() {
        return !getFilmId().isBlank() && Math.abs(getSpeed()) > 0.0f && !isOverStressed();
    }

    public String getFilmId() {
        return FilmItem.getFilmId(film);
    }

    public double getPlayTime() {
        return playTime;
    }

    public ItemStack getFilm() {
        return film;
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
        if (slot != 0 || amount <= 0 || film.isEmpty()) return ItemStack.EMPTY;
        ItemStack removed = film.split(amount);
        if (film.isEmpty()) film = ItemStack.EMPTY;
        resetAndSync();
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (slot != 0) return ItemStack.EMPTY;
        ItemStack removed = film;
        film = ItemStack.EMPTY;
        playTime = 0.0;
        return removed;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot != 0) return;
        film = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        resetAndSync();
    }

    @Override
    public boolean stillValid(Player player) {
        return level != null && level.getBlockEntity(worldPosition) == this
                && player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) <= 64.0;
    }

    @Override
    public void clearContent() {
        film = ItemStack.EMPTY;
        resetAndSync();
    }

    private void resetAndSync() {
        playTime = 0.0;
        setChanged();
        if (level != null && !level.isClientSide) sendData();
    }

    public MenuProvider getMenuProvider() {
        return new SimpleMenuProvider(
                (id, inventory, player) -> new ProjectorMenu(id, inventory, worldPosition),
                Component.translatable("container.createcinema.projector")
        );
    }

    @Override
    protected AABB createRenderBoundingBox() {
        return new AABB(worldPosition).inflate(17.0);
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (!film.isEmpty()) tag.put("Film", film.saveOptional(registries));
        tag.putDouble("PlayTime", playTime);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        if (tag.contains("Film")) {
            film = ItemStack.parseOptional(registries, tag.getCompound("Film"));
        } else if (tag.contains("FilmId") && !tag.getString("FilmId").isBlank()) {
            film = FilmItem.create(ModRegistry.FILM.get(), tag.getString("FilmId"), "Film");
        } else {
            film = ItemStack.EMPTY;
        }
        playTime = tag.getDouble("PlayTime");
    }
}
