package com.yfy.createcinema.mixin;

import com.yfy.createcinema.film.FilmLifecycle;
import com.yfy.createcinema.film.FilmReferenceData;
import com.yfy.createcinema.item.FilmItem;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerMixin {
    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleSetCreativeModeSlot", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V", shift = At.Shift.AFTER))
    private void createcinema$assignCreativeCopyId(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        if (!player.gameMode.isCreative()) return;
        ItemStack stack = packet.itemStack();
        if (!(stack.getItem() instanceof FilmItem) || FilmItem.getFilmId(stack).isBlank()) return;

        int slot = packet.slotNum();
        String copyId = FilmItem.getCopyId(stack);
        FilmReferenceData references = FilmReferenceData.get(player.server);
        if (slot < 0) {
            if (copyId.isBlank()) {
                FilmItem.setCopyId(stack, FilmItem.newCopyId());
                FilmLifecycle.registerCopy(player.server, stack);
            } else if (!references.hasCopy(FilmItem.getFilmId(stack), copyId)) {
                FilmLifecycle.registerCopy(player.server, stack);
            }
            return;
        }

        if (slot >= 1 && slot <= 45) {
            ItemStack existing = player.inventoryMenu.getSlot(slot).getItem();
            if (copyId.equals(FilmItem.getCopyId(existing)) && !copyId.isBlank()) return;
        }

        if (copyId.isBlank() || references.hasCopy(FilmItem.getFilmId(stack), copyId)) {
            FilmItem.setCopyId(stack, FilmItem.newCopyId());
        }
        FilmLifecycle.registerCopy(player.server, stack);
    }

    @Inject(method = "handleSetCreativeModeSlot", at = @At("RETURN"))
    private void createcinema$normalizeCreativeFilmCopies(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        if (player.gameMode.isCreative() && FilmLifecycle.normalizeCreativeInventory(player)) {
            player.inventoryMenu.broadcastChanges();
        }
    }
}
