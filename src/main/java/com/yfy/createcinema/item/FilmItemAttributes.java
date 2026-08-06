package com.yfy.createcinema.item;

import com.simibubi.create.api.registry.CreateBuiltInRegistries;
import com.simibubi.create.content.logistics.item.filter.attribute.ItemAttributeType;
import com.simibubi.create.content.logistics.item.filter.attribute.SingletonItemAttribute;
import com.yfy.createcinema.CreateCinema;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;

public final class FilmItemAttributes {
    private static final ItemAttributeType COMPLETED = singleton("item_attributes.createcinema.film_completed",
            stack -> stack.getItem() instanceof FilmItem && FilmItem.isCompleted(stack));
    private static final ItemAttributeType UNCOMPLETED = singleton("item_attributes.createcinema.film_uncompleted",
            stack -> stack.getItem() instanceof FilmItem && !FilmItem.isCompleted(stack));
    private static boolean registered;

    private FilmItemAttributes() {
    }

    public static void register() {
        if (registered) return;
        Registry.register(CreateBuiltInRegistries.ITEM_ATTRIBUTE_TYPE,
                ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID, "film_completed"), COMPLETED);
        Registry.register(CreateBuiltInRegistries.ITEM_ATTRIBUTE_TYPE,
                ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID, "film_uncompleted"), UNCOMPLETED);
        registered = true;
    }

    private static ItemAttributeType singleton(String translationKey,
                                               java.util.function.Predicate<net.minecraft.world.item.ItemStack> predicate) {
        return new SingletonItemAttribute.Type(type -> new SingletonItemAttribute(type,
                (stack, level) -> predicate.test(stack), translationKey));
    }
}
