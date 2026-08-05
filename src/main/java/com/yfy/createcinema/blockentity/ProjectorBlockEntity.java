package com.yfy.createcinema.blockentity;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.yfy.createcinema.ModRegistry;
import com.yfy.createcinema.PlaybackSpeeds;
import com.yfy.createcinema.film.FilmMetadata;
import com.yfy.createcinema.film.FilmStorage;
import com.yfy.createcinema.gui.ProjectorMenu;
import com.yfy.createcinema.item.FilmItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.phys.AABB;

import java.util.List;

public class ProjectorBlockEntity extends KineticBlockEntity implements WorldlyContainer {
    private static final int[] AUTOMATION_SLOTS = {0};
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
        boolean completedNow = false;
        if (projecting) {
            playTime += PlaybackSpeeds.secondsPerTick(getSpeed());
            if (!level.isClientSide) {
                double duration = resolveDurationSeconds();
                if (duration > 0.0 && playTime >= duration) {
                    playTime = duration;
                    FilmItem.setCompleted(film, true);
                    projecting = false;
                    completedNow = true;
                }
            }
        }
        if (!level.isClientSide && (projecting != wasProjecting || completedNow || level.getGameTime() % 20L == 0L)) {
            setChanged();
            sendData();
        }
        wasProjecting = projecting;
    }

    public boolean canProject() {
        return !getFilmId().isBlank() && !FilmItem.isCompleted(film)
                && Math.abs(getSpeed()) > 0.0f && !isOverStressed();
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

    public boolean hasCompletedFilm() {
        return film.getItem() instanceof FilmItem && FilmItem.isCompleted(film);
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
            if (!film.isEmpty()) {
                FilmItem.prepareForPlayback(film);
                resolveDurationSeconds();
            }
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

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return slot == 0 && film.isEmpty() && stack.getItem() instanceof FilmItem
                && !FilmItem.getFilmId(stack).isBlank();
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return AUTOMATION_SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction side) {
        return canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return slot == 0 && hasCompletedFilm();
    }

    private void resetAndSync() {
        playTime = 0.0;
        sync();
    }

    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide) sendData();
    }

    private double resolveDurationSeconds() {
        if (film.isEmpty()) return 0.0;
        double duration = FilmItem.getDurationSeconds(film);
        if (duration > 0.0 || level == null || level.isClientSide || level.getServer() == null) return duration;
        try {
            FilmMetadata metadata = FilmStorage.readServerMetadata(level.getServer(), getFilmId());
            duration = metadata.durationSeconds();
            FilmItem.setDurationSeconds(film, duration);
            setChanged();
            return duration;
        } catch (Exception error) {
            return 0.0;
        }
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
