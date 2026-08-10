package com.yfy.createcinema.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.yfy.createcinema.block.ProjectorBlock;
import com.yfy.createcinema.blockentity.ProjectorBlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;

public final class PartialProjectorRenderer extends ProjectorRenderer<ProjectorBlockEntity> {

    public PartialProjectorRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(ProjectorBlockEntity projector, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Direction facing = projector.getBlockState().getValue(ProjectorBlock.FACING);
        float angle = KineticBlockEntityRenderer.getAngleForBe(projector, projector.getBlockPos(), facing.getAxis());

        ProjectorRenderHelper.renderShaft(projector, facing, poseStack, buffers, packedLight);
        BedrockGeometryRenderer.renderWheel(BedrockGeometryRenderer.PROJECTOR_WHEEL_TOP, "bone2", angle, facing,
                poseStack, buffers, packedLight);
        BedrockGeometryRenderer.renderWheel(BedrockGeometryRenderer.PROJECTOR_WHEEL_BOTTOM, "bone3", angle, facing,
                poseStack, buffers, packedLight);

        super.render(projector, partialTick, poseStack, buffers, packedLight, packedOverlay);
    }
}
