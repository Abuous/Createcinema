package com.yfy.createcinema.blockentity;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.yfy.createcinema.ModRegistry;
import com.yfy.createcinema.PlaybackSpeeds;
import com.yfy.createcinema.gui.NetworkProjectorMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.List;

public class NetworkProjectorBlockEntity extends KineticBlockEntity {
    public static final int MAX_URL_LENGTH = 2048;

    private String url = "";
    private double playTime;

    public NetworkProjectorBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.NETWORK_PROJECTOR_BE.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null) return;
        if (canProject()) playTime += PlaybackSpeeds.secondsPerTick(getSpeed());
        if (!level.isClientSide && level.getGameTime() % 20L == 0L) setChanged();
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

    public void setUrl(String value) {
        String trimmed = value.trim();
        if (trimmed.length() > MAX_URL_LENGTH) trimmed = trimmed.substring(0, MAX_URL_LENGTH);
        if (url.equals(trimmed)) return;
        url = trimmed;
        playTime = 0.0;
        setChanged();
        if (level != null && !level.isClientSide) sendData();
    }

    public boolean stillValid(Player player) {
        return level != null && level.getBlockEntity(worldPosition) == this
                && player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5,
                worldPosition.getZ() + 0.5) <= 64.0;
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
        tag.putDouble("PlayTime", playTime);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        url = tag.getString("Url");
        playTime = tag.getDouble("PlayTime");
    }
}
