package com.yfy.createcinema.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.yfy.createcinema.ModRegistry;
import com.yfy.createcinema.PlaybackSpeeds;
import com.yfy.createcinema.block.ProjectorBlock;
import com.yfy.createcinema.blockentity.ProjectorBlockEntity;
import com.yfy.createcinema.blockentity.NetworkProjectorBlockEntity;
import com.yfy.createcinema.film.FilmMetadata;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.HashMap;
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
        renderDriveShaft(be, facing, poseStack, buffer, packedLight);

        ResourceLocation texture;
        int videoWidth;
        int videoHeight;
        ClientNetworkProjectorStreams.Status networkStatus = null;
        float networkProgress = 0.0f;
        if (be instanceof ProjectorBlockEntity projector) {
            if (!projector.canProject()) {
                ClientProjectorAudio.stop(projector);
                return;
            }
            ClientProjectorAudio.mark(projector);
            FilmMetadata metadata = ClientFilmCache.metadata(projector.getFilmId());
            if (metadata == null || metadata.frameCount() <= 0 || metadata.fps() <= 0) return;
            double playTime = interpolatedPlayTime(projector.getPlayTime(), projector.getSpeed(), partialTick);
            int frame = (int) Math.floor(playTime * metadata.fps()) % metadata.frameCount();
            texture = ClientFilmCache.frameTexture(projector.getFilmId(), frame);
            videoWidth = metadata.width();
            videoHeight = metadata.height();
        } else if (be instanceof NetworkProjectorBlockEntity projector) {
            if (!projector.canProject()) {
                ClientNetworkProjectorAudio.stop(projector);
                ClientNetworkProjectorStreams.stop(projector);
                return;
            }
            ClientNetworkProjectorAudio.mark(projector);
            ScreenRect screen = findScreenCached(level, projector.getBlockPos(), facing);
            if (screen == null) {
                ClientNetworkProjectorAudio.stop(projector);
                ClientNetworkProjectorStreams.stop(projector);
                return;
            }
            double playTime = interpolatedPlayTime(projector.getPlayTime(), projector.getSpeed(), partialTick);
            NetworkProjectionFrame frame = ClientNetworkProjectorStreams.frame(projector, playTime);
            networkStatus = ClientNetworkProjectorStreams.status(projector);
            networkProgress = ClientNetworkProjectorStreams.progress(projector);
            texture = frame == null ? null : frame.texture();
            videoWidth = frame == null ? 16 : frame.width();
            videoHeight = frame == null ? 9 : frame.height();
        } else {
            return;
        }

        ScreenRect screen = findScreenCached(level, be.getBlockPos(), facing);
        if (screen == null) return;

        ProjectionSurface surface = createProjectionSurface(be.getBlockPos(), facing, screen, videoWidth, videoHeight);
        if (networkStatus != null && networkStatus != ClientNetworkProjectorStreams.Status.PLAYING) {
            Matrix4f matrix = poseStack.last().pose();
            VertexConsumer statusConsumer = buffer.getBuffer(RenderType.lightning());
            renderBeam(statusConsumer, matrix, facing, surface);
            renderStatusProgress(statusConsumer, matrix, surface, facing, networkProgress,
                    networkStatus == ClientNetworkProjectorStreams.Status.ERROR);
            Component message = be instanceof NetworkProjectorBlockEntity projector
                    ? ClientNetworkProjectorStreams.message(projector)
                    : ClientNetworkProjectorStreams.message(be.getBlockPos());
            renderStatusImage(buffer, matrix, surface, facing, message,
                    networkStatus == ClientNetworkProjectorStreams.Status.ERROR);
            return;
        }
        if (texture == null) return;

        Matrix4f matrix = poseStack.last().pose();
        VertexConsumer consumer = buffer.getBuffer(RenderType.entityCutoutNoCull(texture));
        float ambientExposure = ambientExposure(level, screen, facing);
        int videoTint = Math.round(255.0f - ambientExposure * 95.0f);
        renderVideo(consumer, matrix, surface, facing, LightTexture.FULL_BRIGHT, videoTint);

        VertexConsumer lightConsumer = buffer.getBuffer(RenderType.lightning());
        renderBeam(lightConsumer, matrix, facing, surface);
        int washAlpha = Math.round(ambientExposure * 110.0f);
        if (washAlpha > 0) renderLightWash(lightConsumer, matrix, surface.offset(facing, -0.004), washAlpha);
    }

    private static void renderDriveShaft(com.simibubi.create.content.kinetics.base.KineticBlockEntity be, Direction facing,
                                         PoseStack poseStack, MultiBufferSource buffer, int light) {
        var shaft = KineticBlockEntityRenderer.shaft(facing.getAxis());
        Direction back = facing.getOpposite();
        poseStack.pushPose();
        poseStack.translate(back.getStepX() * 0.5, 0.0, back.getStepZ() * 0.5);
        KineticBlockEntityRenderer.renderRotatingKineticBlock(be, shaft, poseStack, buffer.getBuffer(RenderType.solid()), light);
        poseStack.popPose();
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

    private static void renderStatusImage(MultiBufferSource buffer, Matrix4f matrix, ProjectionSurface surface,
                                          Direction facing, Component message, boolean error) {
        ResourceLocation texture = ClientStatusMessageTextures.texture(message, error);
        VertexConsumer consumer = buffer.getBuffer(RenderType.entityTranslucent(texture));
        ProjectionSurface image = surface.offset(facing, -0.018).region(0.13, 0.87, 0.48, 0.66);
        renderVideo(consumer, matrix, image, facing, LightTexture.FULL_BRIGHT, 255);
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
        for (int distance = 1; distance <= 16; distance++) {
            BlockPos center = projector.relative(facing, distance);
            BlockPos anchor = null;
            int bestScore = Integer.MAX_VALUE;
            for (int vertical = -4; vertical <= 4; vertical++) {
                for (int side = -4; side <= 4; side++) {
                    BlockPos candidate = center.relative(horizontal, side).above(vertical);
                    int score = Math.abs(side) + Math.abs(vertical);
                    if (score < bestScore && isScreen(level, candidate)) {
                        anchor = candidate;
                        bestScore = score;
                    }
                }
            }
            if (anchor != null) return expandScreen(level, anchor, horizontal);
        }
        return null;
    }

    private static ScreenRect expandScreen(Level level, BlockPos anchor, Direction horizontal) {
        int minHorizontal = 0;
        int maxHorizontal = 0;
        while (minHorizontal > -8 && isScreen(level, anchor.relative(horizontal, minHorizontal - 1))) minHorizontal--;
        while (maxHorizontal < 8 && isScreen(level, anchor.relative(horizontal, maxHorizontal + 1))) maxHorizontal++;

        int minVertical = 0;
        int maxVertical = 0;
        while (minVertical > -8 && isCompleteRow(level, anchor, horizontal, minHorizontal, maxHorizontal, minVertical - 1)) minVertical--;
        while (maxVertical < 8 && isCompleteRow(level, anchor, horizontal, minHorizontal, maxHorizontal, maxVertical + 1)) maxVertical++;
        return new ScreenRect(anchor, horizontal, minHorizontal, maxHorizontal, minVertical, maxVertical);
    }

    private static boolean isCompleteRow(Level level, BlockPos anchor, Direction horizontal, int min, int max, int vertical) {
        for (int side = min; side <= max; side++) {
            if (!isScreen(level, anchor.relative(horizontal, side).above(vertical))) return false;
        }
        return true;
    }

    private static boolean isScreen(Level level, BlockPos pos) {
        return level.getBlockState(pos).is(ModRegistry.SCREEN.get())
                || level.getBlockState(pos).is(ModRegistry.BLACK_SCREEN.get());
    }

    private static float ambientExposure(Level level, ScreenRect screen, Direction facing) {
        int middleHorizontal = Math.round((screen.minHorizontal + screen.maxHorizontal) / 2.0f);
        int middleVertical = Math.round((screen.minVertical + screen.maxVertical) / 2.0f);
        BlockPos sample = screen.anchor.relative(screen.horizontal, middleHorizontal).above(middleVertical)
                .relative(facing.getOpposite());
        if (isDarkroomEnclosed(level, sample)) return 0.0f;
        int brightness = level.getMaxLocalRawBrightness(sample);
        return Math.max(0.0f, Math.min(1.0f, (brightness - 5) / 10.0f));
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
                || state.is(ModRegistry.NETWORK_PROJECTOR.get()) || state.is(ModRegistry.CABLE.get())
                || state.is(ModRegistry.SPEAKER.get())) return true;
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
                .add(-facing.getStepX() * 0.53, 0.0, -facing.getStepZ() * 0.53);
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

    private static void renderLightWash(VertexConsumer consumer, Matrix4f matrix, ProjectionSurface surface, int alpha) {
        colorVertex(consumer, matrix, surface.bottomLeft, 255, 255, 255, alpha);
        colorVertex(consumer, matrix, surface.bottomRight, 255, 255, 255, alpha);
        colorVertex(consumer, matrix, surface.topRight, 255, 255, 255, alpha);
        colorVertex(consumer, matrix, surface.topLeft, 255, 255, 255, alpha);
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
