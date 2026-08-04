package com.yfy.createcinema.item;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;

import java.util.List;

public class FilmItem extends Item {
    public static final String TAG_FILM_ID = "FilmId";
    public static final String TAG_TITLE = "Title";

    public FilmItem(Properties properties) {
        super(properties);
    }

    public static ItemStack create(Item item, String filmId, String title) {
        ItemStack stack = new ItemStack(item);
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_FILM_ID, filmId);
        tag.putString(TAG_TITLE, title);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        if (!title.isBlank()) {
            stack.set(DataComponents.ITEM_NAME, Component.literal(title));
        }
        return stack;
    }

    public static String getFilmId(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return "";
        return data.copyTag().getString(TAG_FILM_ID);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        String filmId = getFilmId(stack);
        if (filmId.isBlank()) {
            tooltip.add(Component.translatable("item.createcinema.film.empty").withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.literal(filmId).withStyle(ChatFormatting.DARK_GRAY));
        }
        if (!Screen.hasShiftDown()) {
            tooltip.add(Component.translatable("tooltip.createcinema.hold_shift").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        tooltip.add(Component.translatable("tooltip.createcinema.film.1").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.createcinema.film.2").withStyle(ChatFormatting.GRAY));
    }
}
