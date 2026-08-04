package com.yfy.createcinema.gui;
import com.yfy.createcinema.NetworkVideoQuality;
import com.yfy.createcinema.blockentity.NetworkProjectorBlockEntity;
import com.yfy.createcinema.client.ClientNetworkProjectorStreams;
import com.yfy.createcinema.packet.C2SSetNetworkProjectorQualityPacket;
import com.yfy.createcinema.packet.C2SSetNetworkProjectorUrlPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
public class NetworkProjectorScreen extends AbstractContainerScreen<NetworkProjectorMenu> {
    private EditBox urlField;
    private Button qualityButton;
    private NetworkVideoQuality selectedQuality;
    private boolean saveRequested;
    public NetworkProjectorScreen(NetworkProjectorMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); imageWidth = 304; imageHeight = 244; }
    @Override protected void init() {
        super.init();
        urlField = new EditBox(font, leftPos + 12, topPos + 38, 232, 20, Component.translatable("gui.createcinema.network_projector_url"));
        urlField.setMaxLength(NetworkProjectorBlockEntity.MAX_URL_LENGTH); urlField.setValue(menu.getUrl()); addRenderableWidget(urlField);
        selectedQuality = menu.getQuality();
        qualityButton = addRenderableWidget(Button.builder(qualityLabel(), button -> cycleQuality())
                .bounds(leftPos + 12, topPos + 70, 152, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.createcinema.save_url"), button -> save())
                .bounds(leftPos + 172, topPos + 70, 72, 20).build());
        setInitialFocus(urlField);
    }
    private void save() {
        saveRequested = true;
        String requestedUrl = urlField.getValue().trim();
        if (requestedUrl.equals(menu.getUrl().trim())) ClientNetworkProjectorStreams.requestRetry(menu.pos);
        new C2SSetNetworkProjectorUrlPacket(menu.pos, requestedUrl).send();
    }
    private void cycleQuality() {
        selectedQuality = selectedQuality.next();
        qualityButton.setMessage(qualityLabel());
        new C2SSetNetworkProjectorQualityPacket(menu.pos, selectedQuality.id()).send();
    }
    private Component qualityLabel() {
        return Component.translatable("gui.createcinema.network_quality",
                Component.translatable(selectedQuality.translationKey()));
    }
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) { save(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF202327);
        graphics.fill(leftPos + 4, topPos + 4, leftPos + imageWidth - 4, topPos + imageHeight - 4, 0xFF30343A);
        graphics.fill(leftPos + 252, topPos + 4, leftPos + 253, topPos + 94, 0xFF17191C);
        graphics.fill(leftPos + 253, topPos + 4, leftPos + 300, topPos + 94, 0xFF292D32);
        drawUpgradeSlot(graphics, leftPos + 258, topPos + 39);
        drawUpgradeSlot(graphics, leftPos + 278, topPos + 39);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) drawSlot(graphics, leftPos + 44 + col * 18, topPos + 153 + row * 18);
        }
        for (int col = 0; col < 9; col++) drawSlot(graphics, leftPos + 44 + col * 18, topPos + 211);
    }
    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 12, 10, 0xF2F2F2, false);
        graphics.drawString(font, Component.translatable("gui.createcinema.network_projector_url"), 12, 26, 0xC9CDD2, false);
        graphics.drawCenteredString(font, Component.translatable("gui.createcinema.upgrade_slot_short"), 276, 25, 0xC9CDD2);
        graphics.drawString(font, playerInventoryTitle, 45, 144, 0xC9CDD2, false);
        ClientNetworkProjectorStreams.Status status = ClientNetworkProjectorStreams.status(menu.pos);
        if (saveRequested && status == ClientNetworkProjectorStreams.Status.IDLE) status = ClientNetworkProjectorStreams.Status.LOADING;
        Component statusText = switch (status) {
            case LOADING -> Component.translatable("gui.createcinema.stream.loading");
            case PLAYING -> Component.translatable("gui.createcinema.stream.playing");
            case ENDED -> Component.translatable("gui.createcinema.stream.ended");
            case ERROR -> ClientNetworkProjectorStreams.message(menu.pos);
            case IDLE -> Component.empty();
        };
        if (!statusText.getString().isEmpty()) {
            java.util.List<net.minecraft.util.FormattedCharSequence> lines = font.split(statusText, 220);
            int shown = Math.min(3, lines.size());
            int startY = 130 - (shown - 1) * 9;
            for (int line = 0; line < shown; line++) {
                graphics.drawCenteredString(font, lines.get(line), 126, startY + line * 10,
                        status == ClientNetworkProjectorStreams.Status.ERROR ? 0xFF7777 : 0xAEB5BD);
            }
        }
        Component playlist = ClientNetworkProjectorStreams.playlistMessage(menu.pos);
        if (status != ClientNetworkProjectorStreams.Status.ERROR && !playlist.getString().isEmpty())
            graphics.drawCenteredString(font, playlist, 126, 112, 0x8FC7B5);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick); super.render(graphics, mouseX, mouseY, partialTick); renderTooltip(graphics, mouseX, mouseY);
    }
    private void drawSlot(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 18, y + 18, 0xFF151719);
        graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF5A5E63);
        graphics.fill(x + 2, y + 2, x + 16, y + 16, 0xFF272A2E);
    }

    private void drawUpgradeSlot(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 3, y - 3, x + 21, y + 21, 0xFF17191C);
        drawSlot(graphics, x, y);
    }
}
