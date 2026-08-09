package com.yfy.createcinema.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.core.Direction;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

public final class ProjectorRenderHelper {

    private static final float SHAFT_EXPOSURE = -0.01f;

    // 渲染向后伸出的半轴
    public static void renderShaft(KineticBlockEntity be, Direction facing, PoseStack poseStack,
                                   MultiBufferSource buffers, int packedLight) {
        Direction rear = facing.getOpposite();
        Direction.Axis axis = facing.getAxis();
        float angle = KineticBlockEntityRenderer.getAngleForBe(be, be.getBlockPos(), axis);
        angle = -angle;

        SuperByteBuffer shaft = CachedBuffers.partial(AllPartialModels.SHAFT_HALF,
                        KineticBlockEntityRenderer.shaft(axis))
                .reset()
                .center()
                .rotateYDegrees(AngleHelper.horizontalAngle(rear))
                .uncenter()
                .translate(rear.getStepX() * SHAFT_EXPOSURE, 0.0f, rear.getStepZ() * SHAFT_EXPOSURE);
        KineticBlockEntityRenderer.kineticRotationTransform(shaft, be, axis, angle, packedLight)
                .renderInto(poseStack, buffers.getBuffer(RenderType.cutout()));
    }

    // 渲染一个侧面的轮子
    public static void renderWheel(KineticBlockEntity be, Direction facing,
                                   dev.engine_room.flywheel.lib.model.baked.PartialModel wheel,
                                   float pivotX, float pivotY, float pivotZ,
                                   float wheelSideOffset,
                                   PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        float angle = KineticBlockEntityRenderer.getAngleForBe(be, be.getBlockPos(), facing.getAxis());

        SuperByteBuffer buffer = CachedBuffers.partial(wheel, be.getBlockState()).reset();
        buffer.center()
                .rotateYDegrees(AngleHelper.horizontalAngle(facing))
                .uncenter()
                .translate(-wheelSideOffset, 0.0f, 0.0f)
                .rotateAround(Axis.XP.rotation(angle), pivotX, pivotY, pivotZ)
                .light(packedLight)
                .renderInto(poseStack, buffers.getBuffer(RenderType.cutout()));
    }

    private ProjectorRenderHelper() {}
}