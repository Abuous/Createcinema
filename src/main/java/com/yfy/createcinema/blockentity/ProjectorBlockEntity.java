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
    private ItemStack upgrade = ItemStack.EMPTY;
    private double playTime;
    private boolean wasProjecting;

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
        boolean projecting = canProject();
        if (projecting) playTime += PlaybackSpeeds.secondsPerTick(getSpeed());
        if (!level.isClientSide && (projecting != wasProjecting || level.getGameTime() % 20L == 0L)) {
            setChanged();
            sendData();
        }
        wasProjecting = projecting;
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
        return 2;
    }

    @Override
    public boolean isEmpty() {
        return film.isEmpty() && upgrade.isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return switch (slot) {
            case 0 -> film;
            case 1 -> upgrade;
            default -> ItemStack.EMPTY;
        };
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (amount <= 0 || slot < 0 || slot >= getContainerSize()) return ItemStack.EMPTY;
        ItemStack stack = getItem(slot);
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack removed = stack.split(amount);
        if (slot == 0) {
            if (film.isEmpty()) film = ItemStack.EMPTY;
            resetAndSync();
        } else {
            if (upgrade.isEmpty()) upgrade = ItemStack.EMPTY;
            sync();
        }
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return switch (slot) {
            case 0 -> {
                ItemStack removed = film;
                film = ItemStack.EMPTY;
                playTime = 0.0;
                yield removed;
            }
            case 1 -> {
                ItemStack removed = upgrade;
                upgrade = ItemStack.EMPTY;
                yield removed;
            }
            default -> ItemStack.EMPTY;
        };
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (slot == 0) {
            film = stack.getItem() instanceof FilmItem && !FilmItem.getFilmId(stack).isBlank()
                    ? stack.copyWithCount(1) : ItemStack.EMPTY;
            resetAndSync();
        } else if (slot == 1) {
            upgrade = ItemStack.EMPTY;
            sync();
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return level != null && level.getBlockEntity(worldPosition) == this
                && player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5) <= 64.0;
    }

    @Override
    public void clearContent() {
        film = ItemStack.EMPTY;
        upgrade = ItemStack.EMPTY;
        resetAndSync();
    }

    private void resetAndSync() {
        playTime = 0.0;
        sync();
    }

    private void sync() {
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
        if (!upgrade.isEmpty()) tag.put("Upgrade", upgrade.saveOptional(registries));
        if (clientPacket) tag.putDouble("PlayTime", playTime);
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
        upgrade = ItemStack.EMPTY;
        playTime = clientPacket && tag.contains("PlayTime") ? tag.getDouble("PlayTime") : 0.0;
    }
}
