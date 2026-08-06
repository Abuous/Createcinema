package com.yfy.createcinema.mixin;

import com.yfy.createcinema.film.FilmLifecycle;
import com.yfy.createcinema.item.FilmItem;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {
    @Inject(method = "hurt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;onDestroyed(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/damagesource/DamageSource;)V"))
    private void createcinema$releaseDestroyedFilm(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        ItemEntity entity = (ItemEntity) (Object) this;
        ItemStack stack = entity.getItem();
        if (!entity.level().isClientSide && stack.getItem() instanceof FilmItem && entity.getServer() != null) {
            FilmLifecycle.releaseDestroyedCopy(entity.getServer(), stack, source.typeHolder().unwrapKey()
                    .map(key -> key.location().toString()).orElse("unknown"), entity);
        }
    }
}
