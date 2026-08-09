package com.yfy.createcinema.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.yfy.createcinema.block.ProjectorBlock;
import com.yfy.createcinema.blockentity.ProjectorBlockEntity;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.core.Direction;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

public final class PartialProjectorRenderer extends ProjectorRenderer<ProjectorBlockEntity> {
    private static final float TOP_WHEEL_X = (9.75547f + 8.0f) / 16.0f;
    private static final float TOP_WHEEL_Y = 10.5933f / 16.0f;
    private static final float TOP_WHEEL_Z = (2.88836f + 8.0f) / 16.0f;
    private static final float BOTTOM_WHEEL_X = (9.75547f + 8.0f) / 16.0f;
    private static final float BOTTOM_WHEEL_Y = 3.7933f / 16.0f;
    private static final float BOTTOM_WHEEL_Z = (2.63836f + 8.0f) / 16.0f;
    private static final float WHEEL_SIDE_OFFSET = TOP_WHEEL_X * 2.0f - 1.0f;
    private static final float SHAFT_EXPOSURE = 0.03f;

    public PartialProjectorRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(ProjectorBlockEntity projector, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Direction facing = projector.getBlockState().getValue(ProjectorBlock.FACING);

        ProjectorRenderHelper.renderShaft(projector, facing, poseStack, buffers, packedLight);
        ProjectorRenderHelper.renderWheel(projector, facing,
                CinemaPartialModels.PROJECTOR_WHEEL_TOP,
                TOP_WHEEL_X, TOP_WHEEL_Y, TOP_WHEEL_Z,
                WHEEL_SIDE_OFFSET,
                poseStack, buffers, packedLight);
        ProjectorRenderHelper.renderWheel(projector, facing,
                CinemaPartialModels.PROJECTOR_WHEEL_BOTTOM,
                BOTTOM_WHEEL_X, BOTTOM_WHEEL_Y, BOTTOM_WHEEL_Z,
                WHEEL_SIDE_OFFSET,
                poseStack, buffers, packedLight);

        super.render(projector, partialTick, poseStack, buffers, packedLight, packedOverlay);
    }

    private static void renderShaft(ProjectorBlockEntity projector, PoseStack poseStack,
                                    MultiBufferSource buffers, int packedLight) {
        Direction facing = projector.getBlockState().getValue(ProjectorBlock.FACING);
        Direction rear = facing.getOpposite();
        Direction.Axis axis = facing.getAxis();
        float angle = KineticBlockEntityRenderer.getAngleForBe(projector, projector.getBlockPos(), axis);

        // Keep the standard half-shaft inside the housing; only its rear tip clears the block boundary.
        SuperByteBuffer shaft = CachedBuffers.partial(AllPartialModels.SHAFT_HALF,
                        KineticBlockEntityRenderer.shaft(axis))
                .reset()
                .center()
                .rotateYDegrees(AngleHelper.horizontalAngle(rear))
                .uncenter()
                .translate(rear.getStepX() * SHAFT_EXPOSURE, 0.0f, rear.getStepZ() * SHAFT_EXPOSURE);
        KineticBlockEntityRenderer.kineticRotationTransform(shaft, projector, axis, angle, packedLight)
                .renderInto(poseStack, buffers.getBuffer(RenderType.cutout()));
    }

    private static void renderWheel(ProjectorBlockEntity projector,
                                    dev.engine_room.flywheel.lib.model.baked.PartialModel wheel,
                                    float pivotX, float pivotY, float pivotZ, PoseStack poseStack,
                                    MultiBufferSource buffers, int packedLight) {
        float angle = KineticBlockEntityRenderer.getAngleForBe(projector, projector.getBlockPos(),
                projector.getBlockState().getValue(ProjectorBlock.FACING).getAxis());
        SuperByteBuffer buffer = CachedBuffers.partial(wheel, projector.getBlockState()).reset();
        buffer.center()
                .rotateYDegrees(AngleHelper.horizontalAngle(projector.getBlockState()
                        .getValue(ProjectorBlock.FACING)))
                .uncenter()
                .translate(-WHEEL_SIDE_OFFSET, 0.0f, 0.0f)
                .rotateAround(Axis.XP.rotation(angle), pivotX, pivotY, pivotZ)
                .light(packedLight)
                .renderInto(poseStack, buffers.getBuffer(RenderType.cutout()));
    }
}
