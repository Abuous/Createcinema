package com.yfy.createcinema.client.render;

import com.yfy.createcinema.client.render.ProjectorRenderer;
import com.yfy.createcinema.client.render.ProjectorRenderHelper;
import com.yfy.createcinema.client.render.NetworkProjectorRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.yfy.createcinema.block.NetworkProjectorBlock;
import com.yfy.createcinema.blockentity.NetworkProjectorBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;

public class NetworkProjectorRenderer extends ProjectorRenderer<NetworkProjectorBlockEntity> {

    public NetworkProjectorRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(NetworkProjectorBlockEntity be, float partialTicks,
                       PoseStack poseStack, MultiBufferSource buffers,
                       int packedLight, int packedOverlay) {
        Direction facing = be.getBlockState().getValue(NetworkProjectorBlock.FACING);
        ProjectorRenderHelper.renderShaft(be, facing, poseStack, buffers, packedLight);
        super.render(be, partialTicks, poseStack, buffers, packedLight, packedOverlay);
    }
}
