package com.yfy.createcinema.client;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.yfy.createcinema.CreateCinema;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.IModelBuilder;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IGeometryLoader;
import net.neoforged.neoforge.client.model.geometry.SimpleUnbakedGeometry;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

@EventBusSubscriber(modid = CreateCinema.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class BedrockModelLoader {
    private BedrockModelLoader() {
    }

    @SubscribeEvent
    public static void registerGeometryLoaders(ModelEvent.RegisterGeometryLoaders event) {
        event.register(ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID, "bedrock"), Loader.INSTANCE);
    }

    private static final class Loader implements IGeometryLoader<Geometry> {
        private static final Loader INSTANCE = new Loader();

        @Override
        public Geometry read(JsonObject model, JsonDeserializationContext context) throws JsonParseException {
            if (!model.has("source")) throw new JsonParseException("Bedrock model requires a source resource");
            try {
                Set<String> bones = new HashSet<>();
                if (model.has("bones")) {
                    for (JsonElement bone : model.getAsJsonArray("bones")) bones.add(bone.getAsString());
                }
                return new Geometry(ResourceLocation.parse(model.get("source").getAsString()), Set.copyOf(bones));
            } catch (RuntimeException error) {
                throw new JsonParseException("Bedrock model source is invalid", error);
            }
        }
    }

    private static final class Geometry extends SimpleUnbakedGeometry<Geometry> {
        private final ResourceLocation source;
        private final Set<String> bones;

        private Geometry(ResourceLocation source, Set<String> bones) {
            this.source = source;
            this.bones = bones;
        }

        @Override
        protected void addQuads(IGeometryBakingContext context, IModelBuilder<?> builder, ModelBaker baker,
                                Function<Material, TextureAtlasSprite> spriteGetter, ModelState modelState) {
            BedrockGeometryRenderer.bakeStatic(source, bones, context, builder, spriteGetter, modelState);
        }
    }
}
