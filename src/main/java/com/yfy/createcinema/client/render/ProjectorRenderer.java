package com.yfy.createcinema.client.render;

import com.yfy.createcinema.client.browser.PlatformInfo;
import com.yfy.createcinema.client.audio.ClientProjectorAudio;
import com.yfy.createcinema.client.network.ClientNetworkProjectorStreams;
import com.yfy.createcinema.client.audio.ClientNetworkProjectorAudio;
import com.yfy.createcinema.client.film.ClientFilmVideoStreams;
import com.yfy.createcinema.client.film.ClientFilmCache;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.yfy.createcinema.ClientConfig;
import com.yfy.createcinema.ModRegistry;
import com.yfy.createcinema.PlaybackSpeeds;
import com.yfy.createcinema.block.CableBlock;
import com.yfy.createcinema.block.ProjectorBlock;
import com.yfy.createcinema.block.ScreenBlock;
import com.yfy.createcinema.blockentity.ProjectorBlockEntity;
import com.yfy.createcinema.blockentity.NetworkProjectorBlockEntity;
import com.yfy.createcinema.film.FilmMetadata;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProjectorRenderer<T extends com.simibubi.create.content.kinetics.base.KineticBlockEntity> implements BlockEntityRenderer<T> {
    private final Map<String, CachedScreen> screenCache = new HashMap<>();

    public ProjectorRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public AABB getRenderBoundingBox(T be) {
        return be.getRenderBoundingBox();
    }

    @Override
    public boolean shouldRenderOffScreen(T be) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 96;
    }

    @Override
    public void render(T be, float partialTick, PoseStack poseStack, MultiBufferSource buffer, int packedLight, int packedOverlay) {
        Level level = be.getLevel();
        if (level == null) return;
        Direction facing = be.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING);

        ResourceLocation texture;
        int videoWidth;
        int videoHeight;
        ClientNetworkProjectorStreams.Status networkStatus = null;
        boolean filmDeleted = false;
        if (be instanceof ProjectorBlockEntity projector) {
            if (projector.hasDisplayUpgrade()) {
                ClientFilmVideoStreams.stop(projector);
                ScreenRect screen = findScreenCached(level, projector.getBlockPos(), facing);
                if (screen == null) return;
                ProjectionSurface surface = createProjectionSurface(be.getBlockPos(), facing, screen, 16, 9);
                if (projector.hasDisplayConflict()) {
                    renderDisplayText(poseStack, buffer, level, screen, surface, facing,
                            List.of(Component.translatable("display_target.createcinema.projector.conflict")));
                } else if (projector.canDisplay()) {
                    renderDisplayText(poseStack, buffer, level, screen, surface, facing, projector.getDisplayLines());
                }
                return;
            }
            if (!projector.canProject()) {
                ClientFilmVideoStreams.stop(projector);
                return;
            }
            ClientProjectorAudio.mark(projector);
            if (ClientFilmCache.isDeleted(projector.getFilmId())) {
                texture = null;
                videoWidth = 16;
                videoHeight = 9;
                filmDeleted = true;
            } else {
                FilmMetadata metadata = ClientFilmCache.metadata(projector.getFilmId());
                if (metadata == null || metadata.frameCount() <= 0 || metadata.fps() <= 0) return;
                double playTime = interpolatedPlayTime(projector.getPlayTime(), projector.getSpeed(), partialTick);
                if (metadata.mediaTypeValue().isStatic()) {
                    int page = Math.max(0, Math.min(metadata.frameCount() - 1, projector.getCurrentPage()));
                    texture = ClientFilmCache.frameTexture(projector.getFilmId(), page);
                } else if (metadata.formatVersion() >= 3) {
                    texture = ClientFilmVideoStreams.frame(projector, metadata, playTime);
                } else {
                    int frame = (int) Math.floor(playTime * metadata.fps()) % metadata.frameCount();
                    texture = ClientFilmCache.frameTexture(projector.getFilmId(), frame);
                }
                videoWidth = metadata.width();
                videoHeight = metadata.height();
            }
        } else if (be instanceof NetworkProjectorBlockEntity projector) {
            if (!projector.canProject()) {
                return;
            }
            ClientNetworkProjectorAudio.mark(projector);
            ScreenRect screen = findScreenCached(level, projector.getBlockPos(), facing);
            if (screen == null) {
                return;
            }
            double playTime = interpolatedPlayTime(projector.getPlayTime(), projector.getSpeed(), partialTick);
            NetworkProjectionFrame frame = ClientNetworkProjectorStreams.frame(projector, playTime);
            networkStatus = ClientNetworkProjectorStreams.status(projector);
            texture = frame == null ? null : frame.texture();
            videoWidth = frame == null ? 16 : frame.width();
            videoHeight = frame == null ? 9 : frame.height();
        } else {
            return;
        }

        ScreenRect screen = findScreenCached(level, be.getBlockPos(), facing);
        if (screen == null) return;

        ProjectionSurface surface = createProjectionSurface(be.getBlockPos(), facing, screen, videoWidth, videoHeight);
        if (filmDeleted) {
            renderStatusImage(poseStack, buffer, surface, facing,
                    Component.translatable("message.createcinema.film_deleted"), true);
            return;
        }
        if (networkStatus != null && networkStatus != ClientNetworkProjectorStreams.Status.PLAYING) {
            Component message = be instanceof NetworkProjectorBlockEntity projector
                    ? ClientNetworkProjectorStreams.message(projector)
                    : ClientNetworkProjectorStreams.message(be.getBlockPos());
            renderStatusImage(poseStack, buffer, surface, facing, message,
                    networkStatus == ClientNetworkProjectorStreams.Status.ERROR);
            return;
        }
        if (texture == null) return;

        Matrix4f matrix = poseStack.last().pose();
        VertexConsumer consumer = buffer.getBuffer(RenderType.entityCutoutNoCull(texture));
        float ambientExposure = ambientExposure(level, screen, facing);
        // Keep the video emissive while gently preserving highlights in direct light.
        int videoTint = Math.round(255.0f - ambientExposure * 32.0f);
        renderVideo(consumer, matrix, surface, facing, LightTexture.FULL_BRIGHT, videoTint);
        renderBeam(buffer.getBuffer(RenderType.lightning()), matrix, facing, surface);

    }


    private static double interpolatedPlayTime(double playTime, float speed, float partialTick) {
        return playTime + PlaybackSpeeds.secondsPerTick(speed) * partialTick;
    }

    private ScreenRect findScreenCached(Level level, BlockPos projector, Direction facing) {
        String key = Integer.toUnsignedString(System.identityHashCode(level)) + "/"
                + level.dimension().location() + "/" + projector.asLong();
        long gameTime = level.getGameTime();
        CachedScreen cached = screenCache.get(key);
        if (cached != null && cached.facing == facing && gameTime < cached.refreshAt) return cached.screen;
        ScreenRect screen = findScreen(level, projector, facing);
        screenCache.put(key, new CachedScreen(facing, screen, gameTime + 10L));
        return screen;
    }

    private static void renderStatusImage(PoseStack poseStack, MultiBufferSource buffer, ProjectionSurface surface,
                                          Direction facing, Component message, boolean error) {
        if (!PlatformInfo.isAndroid()) {
            Matrix4f matrix = poseStack.last().pose();
            ResourceLocation texture = ClientStatusMessageTextures.texture(message, error);
            VertexConsumer consumer = buffer.getBuffer(RenderType.entityTranslucent(texture));
            ProjectionSurface image = surface.offset(facing, -0.018).region(0.13, 0.87, 0.48, 0.66);
            renderVideo(consumer, matrix, image, facing, LightTexture.FULL_BRIGHT, 255);
            return;
        }
        ProjectionSurface panel = surface.offset(facing, -0.018).region(0.13, 0.87, 0.48, 0.66);
        Matrix4f matrix = poseStack.last().pose();
        VertexConsumer consumer = buffer.getBuffer(RenderType.lightning());
        int accentRed = error ? 255 : 66;
        int accentGreen = error ? 76 : 205;
        int accentBlue = error ? 76 : 255;
        renderColoredQuad(consumer, matrix, panel, 10, 14, 20, 220);
        renderColoredQuad(consumer, matrix, panel.region(0.04, 0.07, 0.12, 0.88),
                accentRed, accentGreen, accentBlue, 235);
        renderColoredQuad(consumer, matrix, panel.region(0.10, 0.96, 0.12, 0.88),
                accentRed, accentGreen, accentBlue, 32);
        renderStatusText(poseStack, buffer, panel, message);
    }

    private static void renderStatusText(PoseStack poseStack, MultiBufferSource buffer,
                                         ProjectionSurface panel, Component message) {
        Font font = Minecraft.getInstance().font;
        List<FormattedCharSequence> lines = font.split(message, 220);
        if (lines.isEmpty()) return;
        if (lines.size() > 3) lines = lines.subList(0, 3);
        int textWidth = lines.stream().mapToInt(font::width).max().orElse(1);
        int textHeight = lines.size() * font.lineHeight;
        float scale = (float) Math.min(panel.width() * 0.60 / textWidth, panel.height() * 0.48 / textHeight);
        if (scale <= 0.0f) return;

        Vec3 horizontal = panel.bottomRight.subtract(panel.bottomLeft).normalize();
        float rotation = (float) Math.atan2(-horizontal.z, horizontal.x);
        float localWidth = (float) (panel.width() / scale);
        float localHeight = (float) (panel.height() / scale);
        float y = (localHeight - textHeight) / 2.0f;
        poseStack.pushPose();
        poseStack.translate(panel.topLeft.x, panel.topLeft.y, panel.topLeft.z);
        poseStack.mulPose(Axis.YP.rotation(rotation));
        poseStack.scale(scale, -scale, scale);
        Matrix4f textMatrix = poseStack.last().pose();
        for (FormattedCharSequence line : lines) {
            float x = (localWidth - font.width(line)) / 2.0f;
            font.drawInBatch(line, x, y, 0xFFFFFFFF, false, textMatrix, buffer,
                    Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
            y += font.lineHeight;
        }
        poseStack.popPose();
    }

    private static void renderDisplayText(PoseStack poseStack, MultiBufferSource buffer, Level level,
                                          ScreenRect screen, ProjectionSurface surface, Direction facing,
                                          List<Component> messages) {
        Font font = Minecraft.getInstance().font;
        int screenWidth = screen.maxHorizontal - screen.minHorizontal + 1;
        int screenHeight = screen.maxVertical - screen.minVertical + 1;
        int maxRows = Math.max(1, screenHeight * 2);
        int wrapWidth = Math.max(12, screenWidth * 12);
        List<FormattedCharSequence> lines = new java.util.ArrayList<>();
        for (Component message : messages) lines.addAll(font.split(message, wrapWidth));
        if (lines.isEmpty()) return;
        if (lines.size() > maxRows) lines = lines.subList(0, maxRows);

        ProjectionSurface panel = surface.offset(facing, -0.018).region(0.06, 0.94, 0.08, 0.92);
        int textWidth = lines.stream().mapToInt(font::width).max().orElse(1);
        int textHeight = lines.size() * font.lineHeight;
        float scale = (float) Math.min(panel.width() * 0.86 / textWidth,
                panel.height() * 0.86 / textHeight);
        if (scale <= 0.0f) return;

        Vec3 horizontal = panel.bottomRight.subtract(panel.bottomLeft).normalize();
        float rotation = (float) Math.atan2(-horizontal.z, horizontal.x);
        float localWidth = (float) (panel.width() / scale);
        float localHeight = (float) (panel.height() / scale);
        float y = (localHeight - textHeight) / 2.0f;
        int textColor = displayTextColor(level, screen);

        poseStack.pushPose();
        poseStack.translate(panel.topLeft.x, panel.topLeft.y, panel.topLeft.z);
        poseStack.mulPose(Axis.YP.rotation(rotation));
        poseStack.scale(scale, -scale, scale);
        Matrix4f textMatrix = poseStack.last().pose();
        for (FormattedCharSequence line : lines) {
            float x = (localWidth - font.width(line)) / 2.0f;
            font.drawInBatch(line, x, y, textColor, textColor == 0xFFFFFFFF, textMatrix, buffer,
                    Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
            y += font.lineHeight;
        }
        poseStack.popPose();
    }

    private static int displayTextColor(Level level, ScreenRect screen) {
        int blackScreens = 0;
        int lightScreens = 0;
        for (int vertical = screen.minVertical; vertical <= screen.maxVertical; vertical++) {
            for (int side = screen.minHorizontal; side <= screen.maxHorizontal; side++) {
                BlockState state = level.getBlockState(screen.anchor.relative(screen.horizontal, side).above(vertical));
                if (state.is(ModRegistry.BLACK_SCREEN.get())) blackScreens++;
                else if (state.is(ModRegistry.SCREEN.get())) lightScreens++;
            }
        }
        return blackScreens > lightScreens ? 0xFFFFFFFF : 0xFF101010;
    }

    private static void renderStatusProgress(VertexConsumer consumer, Matrix4f matrix, ProjectionSurface surface,
                                             Direction facing, float progress, boolean error) {
        ProjectionSurface front = surface.offset(facing, -0.012);
        renderColoredQuad(consumer, matrix, front.region(0.16, 0.84, 0.32, 0.40), 170, 188, 204, 72);
        if (error) {
            renderColoredQuad(consumer, matrix, front.region(0.16, 0.84, 0.32, 0.40), 255, 82, 72, 220);
            return;
        }
        double fillEnd = 0.16 + 0.68 * Math.max(0.0f, Math.min(1.0f, progress));
        if (fillEnd > 0.161) {
            renderColoredQuad(consumer, matrix, front.region(0.16, fillEnd, 0.32, 0.40), 86, 210, 255, 230);
        }
    }

    private static void renderColoredQuad(VertexConsumer consumer, Matrix4f matrix, ProjectionSurface surface,
                                          int red, int green, int blue, int alpha) {
        colorVertex(consumer, matrix, surface.bottomLeft, red, green, blue, alpha);
        colorVertex(consumer, matrix, surface.bottomRight, red, green, blue, alpha);
        colorVertex(consumer, matrix, surface.topRight, red, green, blue, alpha);
        colorVertex(consumer, matrix, surface.topLeft, red, green, blue, alpha);
    }

    private static ScreenRect findScreen(Level level, BlockPos projector, Direction facing) {
        Direction horizontal = facing.getClockWise();
        int anchorRadius = ClientConfig.screenAnchorRadius();
        for (int distance = 1; distance <= ClientConfig.screenMaxDistance(); distance++) {
            BlockPos center = projector.relative(facing, distance);
            BlockPos anchor = null;
            int bestScore = Integer.MAX_VALUE;
            for (int vertical = -anchorRadius; vertical <= anchorRadius; vertical++) {
                for (int side = -anchorRadius; side <= anchorRadius; side++) {
                    BlockPos candidate = center.relative(horizontal, side).above(vertical);
                    int score = Math.abs(side) + Math.abs(vertical);
                    if (score < bestScore && isScreen(level, candidate, facing.getOpposite())) {
                        anchor = candidate;
                        bestScore = score;
                    }
                }
            }
            if (anchor != null) return expandScreen(level, anchor, horizontal, facing.getOpposite());
        }
        return null;
    }

    private static ScreenRect expandScreen(Level level, BlockPos anchor, Direction horizontal, Direction screenFacing) {
        int maxWidth = ClientConfig.screenMaxWidth();
        int maxHeight = ClientConfig.screenMaxHeight();
        int minHorizontalLimit = -((maxWidth - 1) / 2);
        int maxHorizontalLimit = maxWidth - 1 + minHorizontalLimit;
        int minVerticalLimit = -((maxHeight - 1) / 2);
        int maxVerticalLimit = maxHeight - 1 + minVerticalLimit;
        int minHorizontal = 0;
        int maxHorizontal = 0;
        while (minHorizontal > minHorizontalLimit
                && isScreen(level, anchor.relative(horizontal, minHorizontal - 1), screenFacing)) minHorizontal--;
        while (maxHorizontal < maxHorizontalLimit
                && isScreen(level, anchor.relative(horizontal, maxHorizontal + 1), screenFacing)) maxHorizontal++;

        int minVertical = 0;
        int maxVertical = 0;
        while (minVertical > minVerticalLimit
                && isCompleteRow(level, anchor, horizontal, minHorizontal, maxHorizontal, minVertical - 1, screenFacing)) minVertical--;
        while (maxVertical < maxVerticalLimit
                && isCompleteRow(level, anchor, horizontal, minHorizontal, maxHorizontal, maxVertical + 1, screenFacing)) maxVertical++;
        return new ScreenRect(anchor, horizontal, minHorizontal, maxHorizontal, minVertical, maxVertical);
    }

    private static boolean isCompleteRow(Level level, BlockPos anchor, Direction horizontal, int min, int max, int vertical,
                                         Direction screenFacing) {
        for (int side = min; side <= max; side++) {
            if (!isScreen(level, anchor.relative(horizontal, side).above(vertical), screenFacing)) return false;
        }
        return true;
    }

    private static boolean isScreen(Level level, BlockPos pos, Direction screenFacing) {
        BlockState state = level.getBlockState(pos);
        return (state.is(ModRegistry.SCREEN.get()) || state.is(ModRegistry.BLACK_SCREEN.get()))
                && state.getValue(ScreenBlock.FACING) == screenFacing;
    }

    private static float ambientExposure(Level level, ScreenRect screen, Direction facing) {
        int middleHorizontal = Math.round((screen.minHorizontal + screen.maxHorizontal) / 2.0f);
        int middleVertical = Math.round((screen.minVertical + screen.maxVertical) / 2.0f);
        BlockPos sample = screen.anchor.relative(screen.horizontal, middleHorizontal).above(middleVertical)
                .relative(facing.getOpposite());
        if (isDarkroomEnclosed(level, sample)) return 0.0f;
        int brightness = level.getMaxLocalRawBrightness(sample);
        float exposure = Math.max(0.0f, Math.min(1.0f, (brightness - 8) / 7.0f));
        return exposure * exposure;
    }

    private static boolean isDarkroomEnclosed(Level level, BlockPos sample) {
        for (Direction direction : Direction.values()) {
            boolean sealed = false;
            BlockPos.MutableBlockPos cursor = sample.mutable();
            for (int distance = 1; distance <= 16; distance++) {
                cursor.move(direction);
                BlockState state = level.getBlockState(cursor);
                if (state.is(ModRegistry.DARKROOM_BLOCK.get())) {
                    sealed = true;
                    break;
                }
                if (!canSeeThroughDarkroom(level, cursor, state)) break;
            }
            if (!sealed) return false;
        }
        return true;
    }

    private static boolean canSeeThroughDarkroom(Level level, BlockPos pos, BlockState state) {
        if (state.isAir()) return true;
        if (state.is(ModRegistry.SCREEN.get()) || state.is(ModRegistry.BLACK_SCREEN.get())
                || state.is(ModRegistry.PROJECTOR.get())
                || state.is(ModRegistry.NETWORK_PROJECTOR.get())
                || state.is(ModRegistry.SPEAKER.get())) return true;
        if (state.is(ModRegistry.CABLE.get())) {
            return state.getValue(CableBlock.CASING) == CableBlock.CasingType.NONE;
        }
        return !state.isSolidRender(level, pos);
    }

    private static ProjectionSurface createProjectionSurface(BlockPos projector, Direction facing, ScreenRect screen,
                                                                int videoWidth, int videoHeight) {
        double middleHorizontal = (screen.minHorizontal + screen.maxHorizontal) / 2.0;
        double middleVertical = (screen.minVertical + screen.maxVertical) / 2.0;
        double screenWidth = screen.maxHorizontal - screen.minHorizontal + 1.0;
        double screenHeight = screen.maxVertical - screen.minVertical + 1.0;
        double videoAspect = videoWidth / (double) videoHeight;
        double displayWidth = screenWidth;
        double displayHeight = screenHeight;
        if (screenWidth / screenHeight > videoAspect) displayWidth = screenHeight * videoAspect;
        else displayHeight = screenWidth / videoAspect;

        Vec3 center = new Vec3(
                screen.anchor.getX() - projector.getX() + 0.5,
                screen.anchor.getY() - projector.getY() + 0.5 + middleVertical,
                screen.anchor.getZ() - projector.getZ() + 0.5
        ).add(screen.horizontal.getStepX() * middleHorizontal, 0.0, screen.horizontal.getStepZ() * middleHorizontal)
                .add(-facing.getStepX() * 0.501, 0.0, -facing.getStepZ() * 0.501);
        Vec3 horizontalOffset = new Vec3(screen.horizontal.getStepX(), 0.0, screen.horizontal.getStepZ()).scale(displayWidth / 2.0);
        Vec3 bottomLeft = center.subtract(horizontalOffset).add(0.0, -displayHeight / 2.0, 0.0);
        Vec3 bottomRight = center.add(horizontalOffset).add(0.0, -displayHeight / 2.0, 0.0);
        Vec3 topRight = center.add(horizontalOffset).add(0.0, displayHeight / 2.0, 0.0);
        Vec3 topLeft = center.subtract(horizontalOffset).add(0.0, displayHeight / 2.0, 0.0);
        return new ProjectionSurface(bottomLeft, bottomRight, topRight, topLeft);
    }

    private static void renderVideo(VertexConsumer consumer, Matrix4f matrix, ProjectionSurface surface, Direction facing,
                                    int light, int tint) {
        Direction normal = facing.getOpposite();
        texturedVertex(consumer, matrix, surface.bottomLeft, 0, 1, normal, light, tint);
        texturedVertex(consumer, matrix, surface.bottomRight, 1, 1, normal, light, tint);
        texturedVertex(consumer, matrix, surface.topRight, 1, 0, normal, light, tint);
        texturedVertex(consumer, matrix, surface.topLeft, 0, 0, normal, light, tint);
    }

    private static void renderBeam(VertexConsumer consumer, Matrix4f matrix, Direction facing, ProjectionSurface surface) {
        Vec3 lens = new Vec3(0.5 + facing.getStepX() * 0.52, 0.5, 0.5 + facing.getStepZ() * 0.52);
        Direction horizontal = facing.getClockWise();
        Vec3 right = new Vec3(horizontal.getStepX(), 0.0, horizontal.getStepZ()).scale(0.04);
        Vec3 up = new Vec3(0.0, 0.04, 0.0);
        Vec3 lensBottomLeft = lens.subtract(right).subtract(up);
        Vec3 lensBottomRight = lens.add(right).subtract(up);
        Vec3 lensTopRight = lens.add(right).add(up);
        Vec3 lensTopLeft = lens.subtract(right).add(up);

        beamSide(consumer, matrix, lensBottomLeft, lensBottomRight, surface.bottomRight, surface.bottomLeft);
        beamSide(consumer, matrix, lensBottomRight, lensTopRight, surface.topRight, surface.bottomRight);
        beamSide(consumer, matrix, lensTopRight, lensTopLeft, surface.topLeft, surface.topRight);
        beamSide(consumer, matrix, lensTopLeft, lensBottomLeft, surface.bottomLeft, surface.topLeft);
    }

    private static void beamSide(VertexConsumer consumer, Matrix4f matrix, Vec3 lensFirst, Vec3 lensSecond,
                                 Vec3 screenFirst, Vec3 screenSecond) {
        beamVertex(consumer, matrix, lensFirst, 6);
        beamVertex(consumer, matrix, lensSecond, 6);
        beamVertex(consumer, matrix, screenFirst, 12);
        beamVertex(consumer, matrix, screenSecond, 12);
    }

    private static void beamVertex(VertexConsumer consumer, Matrix4f matrix, Vec3 point, int alpha) {
        colorVertex(consumer, matrix, point, 205, 225, 255, alpha);
    }

    private static void colorVertex(VertexConsumer consumer, Matrix4f matrix, Vec3 point, int red, int green, int blue, int alpha) {
        consumer.addVertex(matrix, (float) point.x, (float) point.y, (float) point.z).setColor(red, green, blue, alpha);
    }

    private static void texturedVertex(VertexConsumer consumer, Matrix4f matrix, Vec3 point, float u, float v,
                                       Direction normal, int light, int tint) {
        consumer.addVertex(matrix, (float) point.x, (float) point.y, (float) point.z)
                .setColor(tint, tint, tint, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(normal.getStepX(), normal.getStepY(), normal.getStepZ());
    }

    private record ScreenRect(BlockPos anchor, Direction horizontal, int minHorizontal, int maxHorizontal,
                              int minVertical, int maxVertical) {
    }

    private record CachedScreen(Direction facing, ScreenRect screen, long refreshAt) {
    }

    private record ProjectionSurface(Vec3 bottomLeft, Vec3 bottomRight, Vec3 topRight, Vec3 topLeft) {
        private ProjectionSurface offset(Direction direction, double distance) {
            Vec3 offset = new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ()).scale(distance);
            return new ProjectionSurface(bottomLeft.add(offset), bottomRight.add(offset), topRight.add(offset), topLeft.add(offset));
        }

        private Vec3 center() {
            return bottomLeft.add(topRight).scale(0.5);
        }

        private Vec3 point(double horizontal, double vertical) {
            return bottomLeft.add(bottomRight.subtract(bottomLeft).scale(horizontal))
                    .add(topLeft.subtract(bottomLeft).scale(vertical));
        }

        private ProjectionSurface region(double left, double right, double bottom, double top) {
            return new ProjectionSurface(point(left, bottom), point(right, bottom), point(right, top), point(left, top));
        }

        private double width() {
            return bottomLeft.distanceTo(bottomRight);
        }

        private double height() {
            return bottomLeft.distanceTo(topLeft);
        }
    }
}
