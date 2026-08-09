package com.yfy.createcinema.blockentity;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.redstone.link.LinkBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.yfy.createcinema.ModRegistry;
import com.yfy.createcinema.PlaybackSpeeds;
import com.yfy.createcinema.film.FilmMetadata;
import com.yfy.createcinema.film.FilmStorage;
import com.yfy.createcinema.gui.ProjectorMenu;
import com.yfy.createcinema.item.FilmItem;
import com.yfy.createcinema.item.RemoteControlUpgradeItem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.List;
import java.util.ArrayList;

public class ProjectorBlockEntity extends KineticBlockEntity implements WorldlyContainer {
    private static final int[] AUTOMATION_SLOTS = {0, 1};
    private ItemStack film = ItemStack.EMPTY;
    private ItemStack upgrade = ItemStack.EMPTY;
    private final List<Component> displayLines = new ArrayList<>();
    private double playTime;
    private int currentPage;
    private boolean wasProjecting;
    private LinkBehaviour previousLink;
    private LinkBehaviour nextLink;
    private ItemStack appliedRemoteUpgrade = ItemStack.EMPTY;
    private boolean appliedRemoteEnabled;
    private int previousSignal;
    private int nextSignal;
    private boolean applyingRemoteFrequencies;

    public ProjectorBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.PROJECTOR_BE.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        previousLink = LinkBehaviour.receiver(this, ValueBoxTransform.Dual.makeSlots(HiddenFrequencySlot::new),
                this::receivePreviousSignal);
        SecondaryLinkBehaviour secondary = new SecondaryLinkBehaviour(this,
                ValueBoxTransform.Dual.makeSlots(HiddenFrequencySlot::new), this::receiveNextSignal);
        nextLink = secondary.link;
        behaviours.add(previousLink);
        behaviours.add(secondary);
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null) return;
        if (!level.isClientSide) updateRemoteLinks();
        boolean projecting = canProject();
        boolean completedNow = false;
        if (projecting && !FilmItem.isStatic(film)) {
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
                && !hasDisplayUpgrade()
                && Math.abs(getSpeed()) > 0.0f && !isOverStressed();
    }

    public boolean canDisplay() {
        return hasDisplayUpgrade() && Math.abs(getSpeed()) > 0.0f && !isOverStressed();
    }

    public boolean hasDisplayUpgrade() {
        return upgrade.is(ModRegistry.DISPLAY_UPGRADE.get());
    }

    public boolean hasRemoteControlUpgrade() {
        return upgrade.is(ModRegistry.REMOTE_CONTROL_UPGRADE.get());
    }

    public int getCurrentPage() {
        return currentPage;
    }

    private void receivePreviousSignal(int signal) {
        boolean risingEdge = previousSignal == 0 && signal > 0;
        previousSignal = signal;
        if (risingEdge && !applyingRemoteFrequencies) navigatePage(-1);
    }

    private void receiveNextSignal(int signal) {
        boolean risingEdge = nextSignal == 0 && signal > 0;
        nextSignal = signal;
        if (risingEdge && !applyingRemoteFrequencies) navigatePage(1);
    }

    private void navigatePage(int direction) {
        if (level == null || level.isClientSide || !hasRemoteControlUpgrade()
                || !FilmItem.getMediaType(film).supportsPageNavigation()) return;
        int pageCount = FilmItem.getPageCount(film);
        int next = Math.max(0, Math.min(pageCount - 1, currentPage + (direction < 0 ? -1 : 1)));
        if (next == currentPage) return;
        currentPage = next;
        sync();
    }

    private void updateRemoteLinks() {
        boolean enabled = hasRemoteControlUpgrade();
        if (enabled == appliedRemoteEnabled && ItemStack.isSameItemSameComponents(upgrade, appliedRemoteUpgrade)) return;
        ItemStackHandler frequencies = enabled ? RemoteControlUpgradeItem.getFrequencyItems(upgrade) : new ItemStackHandler(4);
        applyingRemoteFrequencies = true;
        try {
            setLinkFrequencies(previousLink, frequencies.getStackInSlot(0), frequencies.getStackInSlot(1));
            setLinkFrequencies(nextLink, frequencies.getStackInSlot(2), frequencies.getStackInSlot(3));
        } finally {
            applyingRemoteFrequencies = false;
        }
        appliedRemoteEnabled = enabled;
        appliedRemoteUpgrade = upgrade.copy();
        if (!enabled) {
            previousSignal = 0;
            nextSignal = 0;
        }
    }

    private static void setLinkFrequencies(LinkBehaviour link, ItemStack first, ItemStack second) {
        if (link == null) return;
        link.setFrequency(true, first);
        link.setFrequency(false, second);
    }

    public boolean hasDisplayConflict() {
        return hasDisplayUpgrade() && !film.isEmpty();
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

    public List<Component> getDisplayLines() {
        return List.copyOf(displayLines);
    }

    public void clearDisplayLines() {
        if (displayLines.isEmpty()) return;
        displayLines.clear();
        sync();
    }

    public void setDisplayLine(int line, Component text) {
        if (line < 0 || line >= 256) return;
        while (displayLines.size() <= line) displayLines.add(Component.empty());
        displayLines.set(line, text == null ? Component.empty() : text.copy());
        while (!displayLines.isEmpty() && displayLines.getLast().getString().isBlank()) displayLines.removeLast();
        sync();
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
            if (upgrade.isEmpty()) displayLines.clear();
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
                currentPage = 0;
                yield removed;
            }
            case 1 -> {
                ItemStack removed = upgrade;
                upgrade = ItemStack.EMPTY;
                displayLines.clear();
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
            upgrade = stack.is(ModRegistry.DISPLAY_UPGRADE.get()) || stack.is(ModRegistry.REMOTE_CONTROL_UPGRADE.get())
                    ? stack.copyWithCount(1) : ItemStack.EMPTY;
            if (!hasDisplayUpgrade()) displayLines.clear();
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
        displayLines.clear();
        resetAndSync();
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot == 0) {
            return stack.getItem() instanceof FilmItem && !FilmItem.getFilmId(stack).isBlank();
        }
        return slot == 1 && (stack.is(ModRegistry.DISPLAY_UPGRADE.get()) || stack.is(ModRegistry.REMOTE_CONTROL_UPGRADE.get()));
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
        return (slot == 0 && (hasCompletedFilm() || FilmItem.isStatic(film)))
                || (slot == 1 && (hasDisplayUpgrade() || hasRemoteControlUpgrade()));
    }

    private void resetAndSync() {
        playTime = 0.0;
        currentPage = 0;
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
        if (!displayLines.isEmpty()) {
            ListTag lines = new ListTag();
            for (Component line : displayLines) lines.add(net.minecraft.nbt.StringTag.valueOf(
                    Component.Serializer.toJson(line, registries)));
            tag.put("DisplayLines", lines);
        }
        tag.putInt("CurrentPage", currentPage);
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
        upgrade = tag.contains("Upgrade") ? ItemStack.parseOptional(registries, tag.getCompound("Upgrade")) : ItemStack.EMPTY;
        if (!hasDisplayUpgrade() && !hasRemoteControlUpgrade()) upgrade = ItemStack.EMPTY;
        displayLines.clear();
        if (hasDisplayUpgrade()) {
            ListTag lines = tag.getList("DisplayLines", Tag.TAG_STRING);
            for (int i = 0; i < lines.size() && i < 256; i++) {
                Component line = Component.Serializer.fromJson(lines.getString(i), registries);
                if (line != null) displayLines.add(line);
            }
        }
        currentPage = Math.max(0, tag.getInt("CurrentPage"));
        playTime = clientPacket && tag.contains("PlayTime") ? tag.getDouble("PlayTime") : 0.0;
    }

    private static class HiddenFrequencySlot extends ValueBoxTransform.Dual {
        private HiddenFrequencySlot(boolean first) {
            super(first);
        }

        @Override
        public Vec3 getLocalOffset(net.minecraft.world.level.LevelAccessor level, BlockPos pos, BlockState state) {
            return Vec3.ZERO;
        }

        @Override
        public void rotate(net.minecraft.world.level.LevelAccessor level, BlockPos pos, BlockState state, PoseStack poseStack) {
        }

        @Override
        public boolean shouldRender(net.minecraft.world.level.LevelAccessor level, BlockPos pos, BlockState state) {
            return false;
        }

        @Override
        public boolean testHit(net.minecraft.world.level.LevelAccessor level, BlockPos pos, BlockState state, Vec3 hit) {
            return false;
        }
    }

    private static class SecondaryLinkBehaviour extends BlockEntityBehaviour {
        private static final BehaviourType<SecondaryLinkBehaviour> TYPE = new BehaviourType<>("projector_next_link");
        private final LinkBehaviour link;

        private SecondaryLinkBehaviour(ProjectorBlockEntity blockEntity,
                                       org.apache.commons.lang3.tuple.Pair<ValueBoxTransform, ValueBoxTransform> slots,
                                       java.util.function.IntConsumer signalCallback) {
            super(blockEntity);
            link = LinkBehaviour.receiver(blockEntity, slots, signalCallback);
        }

        @Override
        public BehaviourType<?> getType() {
            return TYPE;
        }

        @Override
        public void initialize() {
            link.initialize();
        }

        @Override
        public void tick() {
            link.tick();
        }

        @Override
        public void unload() {
            link.unload();
        }

        @Override
        public void destroy() {
            link.destroy();
        }

        @Override
        public boolean isSafeNBT() {
            return true;
        }

        @Override
        public void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            CompoundTag nested = new CompoundTag();
            link.write(nested, registries, clientPacket);
            tag.put("NextLink", nested);
        }

        @Override
        public void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
            if (tag.contains("NextLink")) link.read(tag.getCompound("NextLink"), registries, clientPacket);
        }
    }
}
