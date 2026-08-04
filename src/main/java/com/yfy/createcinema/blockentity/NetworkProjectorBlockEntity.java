package com.yfy.createcinema.blockentity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.redstone.link.LinkBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.yfy.createcinema.ModRegistry;
import com.yfy.createcinema.NetworkVideoQuality;
import com.yfy.createcinema.PlaybackSpeeds;
import com.yfy.createcinema.gui.NetworkProjectorMenu;
import com.yfy.createcinema.item.RemoteControlUpgradeItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.List;

public class NetworkProjectorBlockEntity extends KineticBlockEntity implements Container {
    public static final int MAX_URL_LENGTH = 2048;

    private String url = "";
    private double playTime;
    private final NonNullList<ItemStack> upgrades = NonNullList.withSize(2, ItemStack.EMPTY);
    private NetworkVideoQuality quality = NetworkVideoQuality.HIGH;
    private boolean wasProjecting;
    private LinkBehaviour previousLink;
    private LinkBehaviour nextLink;
    private ItemStack appliedRemoteUpgrade = ItemStack.EMPTY;
    private boolean appliedRemoteEnabled;
    private int previousSignal;
    private int nextSignal;
    private int navigationRevision;
    private int navigationDirection;
    private int navigationOffset;
    private boolean applyingRemoteFrequencies;

    public NetworkProjectorBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.NETWORK_PROJECTOR_BE.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        previousLink = LinkBehaviour.receiver(this, ValueBoxTransform.Dual.makeSlots(HiddenFrequencySlot::new),
                this::receivePreviousSignal);
        behaviours.add(previousLink);
        SecondaryLinkBehaviour secondary = new SecondaryLinkBehaviour(this,
                ValueBoxTransform.Dual.makeSlots(HiddenFrequencySlot::new), this::receiveNextSignal);
        nextLink = secondary.link;
        behaviours.add(secondary);
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null) return;
        if (!level.isClientSide) updateRemoteLinks();
        boolean projecting = canProject();
        if (projecting) playTime += PlaybackSpeeds.secondsPerTick(getSpeed());
        if (!level.isClientSide && (projecting != wasProjecting || level.getGameTime() % 20L == 0L)) {
            setChanged();
            sendData();
        }
        wasProjecting = projecting;
    }

    public boolean canProject() {
        return !url.isBlank() && Math.abs(getSpeed()) > 0.0f && !isOverStressed();
    }

    public String getUrl() {
        return url;
    }

    public double getPlayTime() {
        return playTime;
    }

    public boolean hasContinuousPlayUpgrade() {
        return upgrades.get(0).is(ModRegistry.CONTINUOUS_PLAY_UPGRADE.get());
    }

    public boolean hasRemoteControlUpgrade() {
        return upgrades.get(1).is(ModRegistry.REMOTE_CONTROL_UPGRADE.get());
    }

    public int getNavigationRevision() {
        return navigationRevision;
    }

    public int getNavigationDirection() {
        return navigationDirection;
    }

    public int getNavigationOffset() {
        return navigationOffset;
    }

    private void receivePreviousSignal(int signal) {
        boolean risingEdge = previousSignal == 0 && signal > 0;
        previousSignal = signal;
        if (risingEdge && !applyingRemoteFrequencies) navigatePlaylist(-1);
    }

    private void receiveNextSignal(int signal) {
        boolean risingEdge = nextSignal == 0 && signal > 0;
        nextSignal = signal;
        if (risingEdge && !applyingRemoteFrequencies) navigatePlaylist(1);
    }

    private void navigatePlaylist(int direction) {
        if (level == null || level.isClientSide || !hasContinuousPlayUpgrade() || !hasRemoteControlUpgrade()) return;
        navigationDirection = direction < 0 ? -1 : 1;
        navigationOffset += navigationDirection;
        navigationRevision++;
        sync();
    }

    private void updateRemoteLinks() {
        boolean enabled = hasContinuousPlayUpgrade() && hasRemoteControlUpgrade();
        ItemStack remote = upgrades.get(1);
        if (enabled == appliedRemoteEnabled && ItemStack.isSameItemSameComponents(remote, appliedRemoteUpgrade)) return;

        ItemStackHandler frequencies = enabled ? RemoteControlUpgradeItem.getFrequencyItems(remote) : new ItemStackHandler(4);
        applyingRemoteFrequencies = true;
        try {
            setLinkFrequencies(previousLink, frequencies.getStackInSlot(0), frequencies.getStackInSlot(1));
            setLinkFrequencies(nextLink, frequencies.getStackInSlot(2), frequencies.getStackInSlot(3));
        } finally {
            applyingRemoteFrequencies = false;
        }
        appliedRemoteEnabled = enabled;
        appliedRemoteUpgrade = remote.copy();
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

    public NetworkVideoQuality getQuality() {
        return quality;
    }

    public void setQuality(NetworkVideoQuality quality) {
        if (this.quality == quality) return;
        this.quality = quality;
        sync();
    }

    public void setUrl(String value) {
        String trimmed = value.trim();
        if (trimmed.length() > MAX_URL_LENGTH) trimmed = trimmed.substring(0, MAX_URL_LENGTH);
        if (url.equals(trimmed)) return;
        url = trimmed;
        playTime = 0.0;
        navigationRevision = 0;
        navigationDirection = 0;
        navigationOffset = 0;
        setChanged();
        if (level != null && !level.isClientSide) sendData();
    }

    @Override
    public int getContainerSize() {
        return upgrades.size();
    }

    @Override
    public boolean isEmpty() {
        return upgrades.stream().allMatch(ItemStack::isEmpty);
    }

    @Override
    public ItemStack getItem(int slot) {
        return validSlot(slot) ? upgrades.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        if (!validSlot(slot) || amount <= 0 || upgrades.get(slot).isEmpty()) return ItemStack.EMPTY;
        ItemStack removed = upgrades.get(slot).split(amount);
        if (upgrades.get(slot).isEmpty()) upgrades.set(slot, ItemStack.EMPTY);
        sync();
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        if (!validSlot(slot)) return ItemStack.EMPTY;
        ItemStack removed = upgrades.get(slot);
        upgrades.set(slot, ItemStack.EMPTY);
        return removed;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (!validSlot(slot)) return;
        if (!stack.isEmpty() && !canPlaceItem(slot, stack)) return;
        upgrades.set(slot, stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
        sync();
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (!validSlot(slot)) return false;
        return slot == 0 ? stack.is(ModRegistry.CONTINUOUS_PLAY_UPGRADE.get())
                : stack.is(ModRegistry.REMOTE_CONTROL_UPGRADE.get());
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public void clearContent() {
        upgrades.set(0, ItemStack.EMPTY);
        upgrades.set(1, ItemStack.EMPTY);
        sync();
    }

    private boolean validSlot(int slot) {
        return slot >= 0 && slot < upgrades.size();
    }

    public boolean stillValid(Player player) {
        return level != null && level.getBlockEntity(worldPosition) == this
                && player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5,
                worldPosition.getZ() + 0.5) <= 64.0;
    }

    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide) sendData();
    }

    public MenuProvider getMenuProvider() {
        return new SimpleMenuProvider(
                (id, inventory, player) -> new NetworkProjectorMenu(id, inventory, worldPosition),
                Component.translatable("container.createcinema.network_projector")
        );
    }

    @Override
    protected AABB createRenderBoundingBox() {
        return new AABB(worldPosition).inflate(17.0);
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putString("Url", url);
        tag.putInt("Quality", quality.id());
        if (!upgrades.get(0).isEmpty()) tag.put("Upgrade0", upgrades.get(0).saveOptional(registries));
        if (!upgrades.get(1).isEmpty()) tag.put("Upgrade1", upgrades.get(1).saveOptional(registries));
        tag.putInt("NavigationRevision", navigationRevision);
        tag.putInt("NavigationDirection", navigationDirection);
        tag.putInt("NavigationOffset", navigationOffset);
        if (clientPacket) tag.putDouble("PlayTime", playTime);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        url = tag.getString("Url");
        quality = NetworkVideoQuality.byId(tag.contains("Quality") ? tag.getInt("Quality") : NetworkVideoQuality.HIGH.id());
        CompoundTag continuousTag = tag.contains("Upgrade0") ? tag.getCompound("Upgrade0") : tag.getCompound("Upgrade");
        upgrades.set(0, ItemStack.parseOptional(registries, continuousTag));
        upgrades.set(1, tag.contains("Upgrade1")
                ? ItemStack.parseOptional(registries, tag.getCompound("Upgrade1")) : ItemStack.EMPTY);
        if (!hasContinuousPlayUpgrade()) upgrades.set(0, ItemStack.EMPTY);
        if (!hasRemoteControlUpgrade()) upgrades.set(1, ItemStack.EMPTY);
        navigationRevision = tag.getInt("NavigationRevision");
        navigationDirection = tag.getInt("NavigationDirection");
        navigationOffset = tag.getInt("NavigationOffset");
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
        private static final BehaviourType<SecondaryLinkBehaviour> TYPE = new BehaviourType<>("network_projector_next_link");
        private final LinkBehaviour link;

        private SecondaryLinkBehaviour(NetworkProjectorBlockEntity blockEntity,
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
