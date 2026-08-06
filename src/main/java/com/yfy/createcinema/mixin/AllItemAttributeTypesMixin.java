package com.yfy.createcinema.mixin;

import com.simibubi.create.content.logistics.item.filter.attribute.AllItemAttributeTypes;
import com.yfy.createcinema.item.FilmItemAttributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AllItemAttributeTypes.class)
public class AllItemAttributeTypesMixin {
    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void createcinema$registerFilmAttributes(CallbackInfo ci) {
        FilmItemAttributes.register();
    }
}
