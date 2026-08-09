package com.yfy.createcinema.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yfy.createcinema.CreateCinema;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JeiPlugin
public final class CreateCinemaJeiPlugin implements IModPlugin {
    private static final ResourceLocation PLUGIN_ID =
            ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID, "jei_plugin");
    private static final List<String> RECIPE_FILES = List.of(
            "black_screen", "blank_album", "blank_image", "blank_slides", "burner", "cable",
            "config_manager", "continuous_play_upgrade", "darkroom_block", "display_upgrade", "film",
            "network_projector", "projector", "remote_control_upgrade", "screen", "speaker");

    @Override
    public ResourceLocation getPluginUid() {
        return PLUGIN_ID;
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        List<RecipeHolder<CraftingRecipe>> recipes = new ArrayList<>();
        for (String name : RECIPE_FILES) {
            try {
                recipes.add(new RecipeHolder<>(
                        ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID, name), loadRecipe(name)));
            } catch (Exception error) {
                CreateCinema.LOGGER.warn("Could not register {} recipe with JEI", name, error);
            }
        }
        registration.addRecipes(RecipeTypes.CRAFTING, recipes);
    }

    private static CraftingRecipe loadRecipe(String name) throws IOException {
        String path = "/data/" + CreateCinema.MODID + "/recipe/" + name + ".json";
        try (InputStream input = CreateCinemaJeiPlugin.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("Missing recipe resource " + path);
            JsonObject json = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            Map<Character, Ingredient> key = new HashMap<>();
            for (var entry : json.getAsJsonObject("key").entrySet()) {
                JsonObject ingredient = entry.getValue().getAsJsonObject();
                ResourceLocation itemId = ResourceLocation.parse(ingredient.get("item").getAsString());
                Item item = BuiltInRegistries.ITEM.get(itemId);
                if (item == Items.AIR) throw new IOException("Unknown recipe item " + itemId);
                key.put(entry.getKey().charAt(0), Ingredient.of(item));
            }

            List<String> pattern = json.getAsJsonArray("pattern").asList().stream()
                    .map(value -> value.getAsString())
                    .toList();
            ShapedRecipePattern shapedPattern = ShapedRecipePattern.of(key, pattern);
            JsonObject resultJson = json.getAsJsonObject("result");
            ResourceLocation resultId = ResourceLocation.parse(resultJson.get("id").getAsString());
            Item resultItem = BuiltInRegistries.ITEM.get(resultId);
            if (resultItem == Items.AIR) throw new IOException("Unknown recipe result " + resultId);
            int count = resultJson.has("count") ? resultJson.get("count").getAsInt() : 1;
            CraftingBookCategory category = json.has("category")
                    ? CraftingBookCategory.valueOf(json.get("category").getAsString().toUpperCase())
                    : CraftingBookCategory.MISC;
            return new ShapedRecipe("", category, shapedPattern, new ItemStack(resultItem, count));
        }
    }
}
