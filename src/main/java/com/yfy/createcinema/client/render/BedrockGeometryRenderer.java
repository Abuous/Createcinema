package com.yfy.createcinema.client.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.yfy.createcinema.CreateCinema;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.BlockElementRotation;
import net.minecraft.client.renderer.block.model.BlockFaceUV;
import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.client.model.IModelBuilder;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.pipeline.QuadBakingVertexConsumer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Renders the supplied Bedrock geometry assets without exposing them to Java's block-model loader. */
public final class BedrockGeometryRenderer {
    static final ResourceLocation PROJECTOR = id("bedrock/block/projector.json");
    static final ResourceLocation NETWORK_PROJECTOR = id("bedrock/block/network_projector.json");
    static final ResourceLocation BURNER = id("bedrock/block/burner.json");
    static final ResourceLocation PROJECTOR_WHEEL_TOP = id("bedrock/block/projector_wheel_top.json");
    static final ResourceLocation PROJECTOR_WHEEL_BOTTOM = id("bedrock/block/projector_wheel_bottom.json");
    static final ResourceLocation PROJECTOR_TEXTURE = id("textures/block/projector.png");
    static final ResourceLocation NETWORK_PROJECTOR_TEXTURE = id("textures/block/network_projector.png");
    static final ResourceLocation BURNER_TEXTURE = id("textures/block/burner.png");
    private static final ResourceLocation PROJECTOR_ANIMATION = id("animations/projector.animation.json");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Map<ResourceLocation, Geometry> GEOMETRIES = new HashMap<>();

    private BedrockGeometryRenderer() {
    }

    static void render(ResourceLocation model, ResourceLocation texture, Direction facing, float seconds, boolean powered,
                       PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        Geometry geometry = geometry(model);
        if (geometry == null) return;
        VertexConsumer vertices = buffers.getBuffer(RenderType.entityCutoutNoCull(texture));
        poseStack.pushPose();
        rotateToFacing(poseStack, facing);
        for (Bone bone : geometry.roots) renderBone(geometry, bone, seconds, powered, poseStack, vertices, packedLight);
        poseStack.popPose();
    }

    static void renderWheel(ResourceLocation model, String boneName, float angleRadians, Direction facing,
                            PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        Geometry geometry = geometry(model);
        if (geometry == null) return;
        Bone bone = findBone(geometry.roots, boneName);
        if (bone == null) {
            CreateCinema.LOGGER.warn("Bedrock wheel bone {} not found in {}", boneName, model);
            return;
        }
        VertexConsumer vertices = buffers.getBuffer(RenderType.entityCutoutNoCull(PROJECTOR_TEXTURE));
        poseStack.pushPose();
        rotateToFacing(poseStack, facing);
        renderWheelBone(geometry, bone, angleRadians, poseStack, vertices, packedLight);
        poseStack.popPose();
    }

    private static void renderWheelBone(Geometry geometry, Bone bone, float angleRadians, PoseStack poseStack,
                                        VertexConsumer vertices, int packedLight) {
        poseStack.pushPose();
        if (angleRadians != 0.0f) {
            poseStack.translate(bone.pivot[0] / 16.0f + 0.5f, bone.pivot[1] / 16.0f, bone.pivot[2] / 16.0f + 0.5f);
            poseStack.mulPose(Axis.XP.rotation(angleRadians));
            poseStack.translate(-bone.pivot[0] / 16.0f - 0.5f, -bone.pivot[1] / 16.0f, -bone.pivot[2] / 16.0f - 0.5f);
        }
        for (Cube cube : bone.cubes) renderCube(cube, geometry.textureWidth, geometry.textureHeight, poseStack, vertices, packedLight);
        for (Bone child : bone.children) renderWheelBone(geometry, child, angleRadians, poseStack, vertices, packedLight);
        poseStack.popPose();
    }

    private static Bone findBone(List<Bone> bones, String name) {
        for (Bone bone : bones) {
            if (name.equals(bone.name)) return bone;
            Bone found = findBone(bone.children, name);
            if (found != null) return found;
        }
        return null;
    }

    static void bakeStatic(ResourceLocation model, Set<String> includedBones, IGeometryBakingContext context,
                           IModelBuilder<?> builder,
                           java.util.function.Function<Material, net.minecraft.client.renderer.texture.TextureAtlasSprite> spriteGetter,
                           ModelState modelState) {
        Geometry geometry = geometry(model);
        if (geometry == null) return;
        List<Cube> cubes = new ArrayList<>();
        Set<String> excludedBones = includedBones.isEmpty() && model.equals(PROJECTOR)
                ? Set.of("bone2", "bone3") : Set.of();
        for (Bone bone : geometry.roots) {
            collectStaticCubes(bone, cubes, includedBones, excludedBones);
        }
        var sprite = spriteGetter.apply(context.getMaterial("#texture"));
        for (Cube cube : cubes) bakeCube(cube, geometry.textureWidth, geometry.textureHeight, sprite, modelState, builder);
    }

    private static void collectStaticCubes(Bone bone, List<Cube> cubes, Set<String> includedBones,
                                           Set<String> excludedBones) {
        if (excludedBones.contains(bone.name)) return;
        if (bone.rotation[0] != 0.0f || bone.rotation[1] != 0.0f || bone.rotation[2] != 0.0f) {
            CreateCinema.LOGGER.warn("Bedrock static model bone {} has an unsupported bone rotation", bone.name);
            return;
        }
        if (includedBones.isEmpty() || includedBones.contains(bone.name)) for (Cube cube : bone.cubes) {
            int zeroAxes = (cube.size[0] <= 0.0001f ? 1 : 0) + (cube.size[1] <= 0.0001f ? 1 : 0)
                    + (cube.size[2] <= 0.0001f ? 1 : 0);
            if (zeroAxes == 0) cubes.add(cube);
        }
        for (Bone child : bone.children) {
            collectStaticCubes(child, cubes, includedBones, excludedBones);
        }
    }

    private static void bakeCube(Cube cube, int textureWidth, int textureHeight,
                                 net.minecraft.client.renderer.texture.TextureAtlasSprite sprite, ModelState modelState,
                                 IModelBuilder<?> builder) {
        float epsilon = 0.001f;
        float x0 = cube.origin[0] + 8.0f - cube.inflate;
        float y0 = cube.origin[1] - cube.inflate;
        float z0 = cube.origin[2] + 8.0f - cube.inflate;
        float x1 = cube.origin[0] + cube.size[0] + 8.0f + cube.inflate;
        float y1 = cube.origin[1] + cube.size[1] + cube.inflate;
        float z1 = cube.origin[2] + cube.size[2] + 8.0f + cube.inflate;
        if (cube.size[0] <= 0.0001f) { x0 -= epsilon; x1 += epsilon; }
        if (cube.size[1] <= 0.0001f) { y0 -= epsilon; y1 += epsilon; }
        if (cube.size[2] <= 0.0001f) { z0 -= epsilon; z1 += epsilon; }
        for (Map.Entry<Direction, Uv> entry : cube.faces.entrySet()) {
            Direction.Axis flatAxis = cube.size[0] <= 0.0001f ? Direction.Axis.X
                    : cube.size[1] <= 0.0001f ? Direction.Axis.Y
                    : cube.size[2] <= 0.0001f ? Direction.Axis.Z : null;
            if (flatAxis != null && entry.getKey().getAxis() != flatAxis) continue;
            Vector3f[] points = facePoints(entry.getKey(), x0, y0, z0, x1, y1, z1);
            for (Vector3f point : points) transformStaticPoint(point, cube, modelState);
            Vector3f edge1 = new Vector3f(points[1]).sub(points[0]);
            Vector3f edge2 = new Vector3f(points[2]).sub(points[0]);
            Vector3f normal = edge1.cross(edge2).normalize();
            Uv uv = entry.getValue();
            float u0 = sprite.getU(uv.u / textureWidth);
            float v0 = sprite.getV(uv.v / textureHeight);
            float u1 = sprite.getU((uv.u + uv.width) / textureWidth);
            float v1 = sprite.getV((uv.v + uv.height) / textureHeight);
            QuadBakingVertexConsumer consumer = new QuadBakingVertexConsumer();
            consumer.setSprite(sprite);
            consumer.setDirection(Direction.getNearest(normal.x, normal.y, normal.z));
            consumer.setShade(true);
            bakedVertex(consumer, points[0], u0, v1, normal);
            bakedVertex(consumer, points[1], u1, v1, normal);
            bakedVertex(consumer, points[2], u1, v0, normal);
            bakedVertex(consumer, points[3], u0, v0, normal);
            builder.addUnculledFace(consumer.bakeQuad());
        }
    }

    private static Vector3f[] facePoints(Direction face, float x0, float y0, float z0,
                                         float x1, float y1, float z1) {
        return switch (face) {
            case NORTH -> points(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0);
            case SOUTH -> points(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
            case WEST -> points(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
            case EAST -> points(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1);
            case UP -> points(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);
            case DOWN -> points(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
        };
    }

    private static Vector3f[] points(float... values) {
        return new Vector3f[]{new Vector3f(values[0], values[1], values[2]),
                new Vector3f(values[3], values[4], values[5]), new Vector3f(values[6], values[7], values[8]),
                new Vector3f(values[9], values[10], values[11])};
    }

    private static void transformStaticPoint(Vector3f point, Cube cube, ModelState modelState) {
        Vector3f pivot = new Vector3f(cube.pivot[0] + 8.0f, cube.pivot[1], cube.pivot[2] + 8.0f);
        point.sub(pivot);
        if (cube.rotation[0] != 0.0f) point.rotateX((float) Math.toRadians(cube.rotation[0]));
        if (cube.rotation[1] != 0.0f) point.rotateY((float) Math.toRadians(cube.rotation[1]));
        if (cube.rotation[2] != 0.0f) point.rotateZ((float) Math.toRadians(cube.rotation[2]));
        point.add(pivot).div(16.0f).sub(0.5f, 0.5f, 0.5f);
        modelState.getRotation().getMatrix().transformPosition(point);
        point.add(0.5f, 0.5f, 0.5f);
    }

    private static void bakedVertex(QuadBakingVertexConsumer consumer, Vector3f point, float u, float v,
                                    Vector3f normal) {
        consumer.addVertex(point.x, point.y, point.z).setColor(255, 255, 255, 255).setUv(u, v)
                .setNormal(normal.x, normal.y, normal.z);
    }

    static float projectorAnimationRotation(String bone, float seconds, boolean powered) {
        if (!powered) return 0.0f;
        Geometry geometry = geometry(PROJECTOR);
        return geometry == null ? 0.0f : geometry.spinRates.getOrDefault(bone, 0.0f) * seconds;
    }

    private static BlockElement toBlockElement(Cube cube, int textureWidth, int textureHeight) {
        Map<Direction, BlockElementFace> faces = new EnumMap<>(Direction.class);
        for (Map.Entry<Direction, Uv> entry : cube.faces.entrySet()) {
            Uv uv = entry.getValue();
            faces.put(entry.getKey(), new BlockElementFace(null, -1, "#texture",
                    new BlockFaceUV(new float[]{uv.u * 16.0f / textureWidth, uv.v * 16.0f / textureHeight,
                            (uv.u + uv.width) * 16.0f / textureWidth,
                            (uv.v + uv.height) * 16.0f / textureHeight}, 0)));
        }
        int axes = (cube.rotation[0] == 0.0f ? 0 : 1) + (cube.rotation[1] == 0.0f ? 0 : 1)
                + (cube.rotation[2] == 0.0f ? 0 : 1);
        BlockElementRotation rotation = null;
        if (axes == 1) {
            Direction.Axis axis = cube.rotation[0] != 0.0f ? Direction.Axis.X
                    : cube.rotation[1] != 0.0f ? Direction.Axis.Y : Direction.Axis.Z;
            float angle = cube.rotation[0] != 0.0f ? cube.rotation[0]
                    : cube.rotation[1] != 0.0f ? cube.rotation[1] : cube.rotation[2];
            rotation = new BlockElementRotation(new Vector3f(cube.pivot[0] + 8.0f, cube.pivot[1], cube.pivot[2] + 8.0f),
                    axis, angle, false);
        } else if (axes > 1) {
            CreateCinema.LOGGER.warn("Bedrock static cube has unsupported multi-axis rotation");
        }
        return new BlockElement(new Vector3f(cube.origin[0] + 8.0f, cube.origin[1], cube.origin[2] + 8.0f),
                new Vector3f(cube.origin[0] + cube.size[0] + 8.0f, cube.origin[1] + cube.size[1],
                        cube.origin[2] + cube.size[2] + 8.0f), faces, rotation, true);
    }

    private static void rotateToFacing(PoseStack poseStack, Direction facing) {
        float rotation = switch (facing) {
            case NORTH -> 0.0f;
            case EAST -> 90.0f;
            case SOUTH -> 180.0f;
            case WEST -> 270.0f;
            default -> 0.0f;
        };
        if (rotation == 0.0f) return;
        poseStack.translate(0.5f, 0.0f, 0.5f);
        poseStack.mulPose(Axis.YP.rotationDegrees(rotation));
        poseStack.translate(-0.5f, 0.0f, -0.5f);
    }

    private static void renderBone(Geometry geometry, Bone bone, float seconds, boolean powered, PoseStack poseStack,
                                   VertexConsumer vertices, int packedLight) {
        poseStack.pushPose();
        rotateAround(poseStack, bone.pivot, bone.rotation[0] + (powered ? geometry.spinRates.getOrDefault(bone.name, 0.0f) * seconds : 0.0f),
                bone.rotation[1], bone.rotation[2]);
        for (Cube cube : bone.cubes) renderCube(cube, geometry.textureWidth, geometry.textureHeight, poseStack, vertices, packedLight);
        for (Bone child : bone.children) renderBone(geometry, child, seconds, powered, poseStack, vertices, packedLight);
        poseStack.popPose();
    }

    private static void renderCube(Cube cube, int textureWidth, int textureHeight, PoseStack poseStack,
                                   VertexConsumer vertices, int packedLight) {
        if (cube.size[0] <= 0.0001f || cube.size[1] <= 0.0001f || cube.size[2] <= 0.0001f) return;
        poseStack.pushPose();
        rotateAround(poseStack, cube.pivot, cube.rotation[0], cube.rotation[1], cube.rotation[2]);
        float inflate = cube.inflate / 16.0f;
        float x0 = cube.origin[0] / 16.0f + 0.5f - inflate;
        float y0 = cube.origin[1] / 16.0f - inflate;
        float z0 = cube.origin[2] / 16.0f + 0.5f - inflate;
        float x1 = (cube.origin[0] + cube.size[0]) / 16.0f + 0.5f + inflate;
        float y1 = (cube.origin[1] + cube.size[1]) / 16.0f + inflate;
        float z1 = (cube.origin[2] + cube.size[2]) / 16.0f + 0.5f + inflate;
        for (Map.Entry<Direction, Uv> entry : cube.faces.entrySet()) {
            Uv uv = entry.getValue();
            renderFace(vertices, poseStack.last().pose(), entry.getKey(), x0, y0, z0, x1, y1, z1,
                    uv, textureWidth, textureHeight, packedLight);
        }
        poseStack.popPose();
    }

    private static void rotateAround(PoseStack poseStack, float[] pivot, float x, float y, float z) {
        if (x == 0.0f && y == 0.0f && z == 0.0f) return;
        poseStack.translate(pivot[0] / 16.0f + 0.5f, pivot[1] / 16.0f, pivot[2] / 16.0f + 0.5f);
        if (z != 0.0f) poseStack.mulPose(Axis.ZP.rotationDegrees(z));
        if (y != 0.0f) poseStack.mulPose(Axis.YP.rotationDegrees(y));
        if (x != 0.0f) poseStack.mulPose(Axis.XP.rotationDegrees(x));
        poseStack.translate(-pivot[0] / 16.0f - 0.5f, -pivot[1] / 16.0f, -pivot[2] / 16.0f - 0.5f);
    }

    private static void renderFace(VertexConsumer vertices, Matrix4f matrix, Direction face,
                                   float x0, float y0, float z0, float x1, float y1, float z1,
                                   Uv uv, int textureWidth, int textureHeight, int packedLight) {
        float u0 = uv.u / textureWidth;
        float v0 = uv.v / textureHeight;
        float u1 = (uv.u + uv.width) / textureWidth;
        float v1 = (uv.v + uv.height) / textureHeight;
        switch (face) {
            case NORTH -> face(vertices, matrix, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, u0, v0, u1, v1, face, packedLight);
            case SOUTH -> face(vertices, matrix, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, u0, v0, u1, v1, face, packedLight);
            case WEST -> face(vertices, matrix, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, u0, v0, u1, v1, face, packedLight);
            case EAST -> face(vertices, matrix, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, u0, v0, u1, v1, face, packedLight);
            case UP -> face(vertices, matrix, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, u0, v0, u1, v1, face, packedLight);
            case DOWN -> face(vertices, matrix, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, u0, v0, u1, v1, face, packedLight);
        }
    }

    private static void face(VertexConsumer vertices, Matrix4f matrix,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3,
                             float u0, float v0, float u1, float v1, Direction normal, int packedLight) {
        vertex(vertices, matrix, x0, y0, z0, u0, v1, normal, packedLight);
        vertex(vertices, matrix, x1, y1, z1, u1, v1, normal, packedLight);
        vertex(vertices, matrix, x2, y2, z2, u1, v0, normal, packedLight);
        vertex(vertices, matrix, x3, y3, z3, u0, v0, normal, packedLight);
    }

    private static void vertex(VertexConsumer vertices, Matrix4f matrix, float x, float y, float z,
                               float u, float v, Direction normal, int packedLight) {
        vertices.addVertex(matrix, x, y, z).setColor(255, 255, 255, 255).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight)
                .setNormal(normal.getStepX(), normal.getStepY(), normal.getStepZ());
    }

    private static Geometry geometry(ResourceLocation model) {
        synchronized (GEOMETRIES) {
            if (GEOMETRIES.containsKey(model)) return GEOMETRIES.get(model);
            Geometry loaded = loadGeometry(model);
            GEOMETRIES.put(model, loaded);
            return loaded;
        }
    }

    private static Geometry loadGeometry(ResourceLocation model) {
        try {
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(model);
            if (resource.isEmpty()) throw new IOException("resource is missing");
            try (InputStreamReader reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
                JsonArray geometries = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("minecraft:geometry");
                if (geometries == null || geometries.isEmpty()) throw new IOException("geometry array is empty");
                JsonObject geometry = geometries.get(0).getAsJsonObject();
                JsonObject description = geometry.getAsJsonObject("description");
                int textureWidth = description.get("texture_width").getAsInt();
                int textureHeight = description.get("texture_height").getAsInt();
                Map<String, Bone> bones = new HashMap<>();
                for (JsonElement element : geometry.getAsJsonArray("bones")) {
                    Bone bone = parseBone(element.getAsJsonObject());
                    bones.put(bone.name, bone);
                }
                List<Bone> roots = new ArrayList<>();
                for (Bone bone : bones.values()) {
                    if (bone.parent == null || !bones.containsKey(bone.parent)) roots.add(bone);
                    else bones.get(bone.parent).children.add(bone);
                }
                return new Geometry(textureWidth, textureHeight, roots, model.equals(PROJECTOR) ? loadProjectorSpinRates() : Map.of());
            }
        } catch (Exception error) {
            CreateCinema.LOGGER.error("Unable to load Bedrock model {}", model, error);
            return null;
        }
    }

    private static Bone parseBone(JsonObject object) {
        String name = object.get("name").getAsString();
        String parent = object.has("parent") ? object.get("parent").getAsString() : null;
        List<Cube> cubes = new ArrayList<>();
        if (object.has("cubes")) for (JsonElement element : object.getAsJsonArray("cubes")) cubes.add(parseCube(element.getAsJsonObject()));
        return new Bone(name, parent, vector(object, "pivot", 0.0f, 0.0f, 0.0f),
                vector(object, "rotation", 0.0f, 0.0f, 0.0f), cubes);
    }

    private static Cube parseCube(JsonObject object) {
        EnumMap<Direction, Uv> faces = new EnumMap<>(Direction.class);
        float[] origin = vector(object, "origin", 0.0f, 0.0f, 0.0f);
        float[] size = vector(object, "size", 0.0f, 0.0f, 0.0f);
        JsonElement uvElement = object.get("uv");
        if (uvElement != null && uvElement.isJsonArray()) {
            addBoxUvs(faces, pair(uvElement.getAsJsonArray()), size);
        } else if (uvElement != null && uvElement.isJsonObject()) {
            JsonObject uv = uvElement.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : uv.entrySet()) {
            Direction direction = switch (entry.getKey()) {
                case "north" -> Direction.NORTH;
                case "south" -> Direction.SOUTH;
                case "west" -> Direction.WEST;
                case "east" -> Direction.EAST;
                case "up" -> Direction.UP;
                case "down" -> Direction.DOWN;
                default -> null;
            };
            if (direction == null || !entry.getValue().isJsonObject()) continue;
            JsonObject face = entry.getValue().getAsJsonObject();
            float[] point = pair(face.getAsJsonArray("uv"));
            float[] faceSize = pair(face.getAsJsonArray("uv_size"));
            faces.put(direction, new Uv(point[0], point[1], faceSize[0], faceSize[1]));
            }
        }
        return new Cube(origin, size,
                vector(object, "pivot", 0.0f, 0.0f, 0.0f), vector(object, "rotation", 0.0f, 0.0f, 0.0f),
                object.has("inflate") ? object.get("inflate").getAsFloat() : 0.0f, faces);
    }

    private static void addBoxUvs(EnumMap<Direction, Uv> faces, float[] uv, float[] size) {
        float dx = size[0];
        float dy = size[1];
        float dz = size[2];
        float u = uv[0];
        float v = uv[1];
        faces.put(Direction.DOWN, new Uv(u + dz, v, dx, dz));
        faces.put(Direction.UP, new Uv(u + dz + dx, v, dx, dz));
        faces.put(Direction.WEST, new Uv(u, v + dz, dz, dy));
        faces.put(Direction.NORTH, new Uv(u + dz, v + dz, dx, dy));
        faces.put(Direction.EAST, new Uv(u + dz + dx, v + dz, dz, dy));
        faces.put(Direction.SOUTH, new Uv(u + dz + dx + dz, v + dz, dx, dy));
    }

    private static Map<String, Float> loadProjectorSpinRates() {
        try {
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(PROJECTOR_ANIMATION);
            if (resource.isEmpty()) return Map.of();
            try (InputStreamReader reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
                JsonObject animations = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("animations");
                JsonObject animation = animations.getAsJsonObject("animation.model.projector");
                Map<String, Float> rates = new HashMap<>();
                for (Map.Entry<String, JsonElement> entry : animation.getAsJsonObject("bones").entrySet()) {
                    JsonArray rotation = entry.getValue().getAsJsonObject().getAsJsonArray("rotation");
                    if (rotation == null || rotation.isEmpty() || !rotation.get(0).isJsonPrimitive()) continue;
                    String expression = rotation.get(0).getAsString();
                    if (!expression.contains("query.anim_time") || !expression.contains("query.is_powered")) continue;
                    Matcher matcher = NUMBER.matcher(expression);
                    float rate = 0.0f;
                    while (matcher.find()) rate = Float.parseFloat(matcher.group());
                    if (expression.stripLeading().startsWith("-")) rate = -rate;
                    rates.put(entry.getKey(), rate);
                }
                return Map.copyOf(rates);
            }
        } catch (Exception error) {
            CreateCinema.LOGGER.warn("Unable to load Bedrock projector animation", error);
            return Map.of();
        }
    }

    private static float[] vector(JsonObject object, String key, float x, float y, float z) {
        return object.has(key) ? array(object.getAsJsonArray(key)) : new float[]{x, y, z};
    }

    private static float[] array(JsonArray array) {
        return new float[]{array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat()};
    }

    private static float[] pair(JsonArray array) {
        return new float[]{array.get(0).getAsFloat(), array.get(1).getAsFloat()};
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID, path);
    }

    private record Geometry(int textureWidth, int textureHeight, List<Bone> roots, Map<String, Float> spinRates) {
    }

    private static final class Bone {
        private final String name;
        private final String parent;
        private final float[] pivot;
        private final float[] rotation;
        private final List<Cube> cubes;
        private final List<Bone> children = new ArrayList<>();

        private Bone(String name, String parent, float[] pivot, float[] rotation, List<Cube> cubes) {
            this.name = name;
            this.parent = parent;
            this.pivot = pivot;
            this.rotation = rotation;
            this.cubes = cubes;
        }
    }

    private record Cube(float[] origin, float[] size, float[] pivot, float[] rotation, float inflate,
                        EnumMap<Direction, Uv> faces) {
    }

    private record Uv(float u, float v, float width, float height) {
    }
}
