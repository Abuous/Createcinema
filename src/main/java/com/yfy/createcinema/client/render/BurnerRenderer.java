package com.yfy.createcinema.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yfy.createcinema.blockentity.BurnerBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;

public final class BurnerRenderer implements BlockEntityRenderer<BurnerBlockEntity> {
    public BurnerRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(BurnerBlockEntity burner, float partialTick, PoseStack poseStack, MultiBufferSource buffer,
                       int packedLight, int packedOverlay) {
        BedrockGeometryRenderer.render(BedrockGeometryRenderer.BURNER, BedrockGeometryRenderer.BURNER_TEXTURE,
                Direction.NORTH, 0.0f, false, poseStack, buffer, packedLight);
    }
}
