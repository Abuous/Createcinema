package com.yfy.createcinema.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class RemoteControlUpgradeScreen extends AbstractContainerScreen<RemoteControlUpgradeMenu> {
    public RemoteControlUpgradeScreen(RemoteControlUpgradeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 176;
        imageHeight = 182;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF202327);
        graphics.fill(leftPos + 4, topPos + 4, leftPos + imageWidth - 4, topPos + imageHeight - 4, 0xFF30343A);
        drawPair(graphics, leftPos + 47, topPos + 39, 0xFF507A9A);
        drawPair(graphics, leftPos + 109, topPos + 39, 0xFF9A654D);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) drawSlot(graphics, leftPos + 7 + col * 18, topPos + 99 + row * 18);
        }
        for (int col = 0; col < 9; col++) drawSlot(graphics, leftPos + 7 + col * 18, topPos + 157);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 8, 0xF2F2F2, false);
        graphics.drawCenteredString(font, Component.translatable("gui.createcinema.remote.previous"), 66, 26, 0xC9CDD2);
        graphics.drawCenteredString(font, Component.translatable("gui.createcinema.remote.next"), 128, 26, 0xC9CDD2);
        graphics.drawString(font, playerInventoryTitle, 8, 88, 0xC9CDD2, false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    private void drawPair(GuiGraphics graphics, int x, int y, int accent) {
        graphics.fill(x - 3, y - 3, x + 39, y + 21, accent);
        drawSlot(graphics, x, y);
        drawSlot(graphics, x + 18, y);
    }

    private void drawSlot(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 18, y + 18, 0xFF151719);
        graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF5A5E63);
        graphics.fill(x + 2, y + 2, x + 16, y + 16, 0xFF272A2E);
    }
}
