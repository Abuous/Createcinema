package com.yfy.createcinema.gui;
import com.yfy.createcinema.blockentity.NetworkProjectorBlockEntity;
import com.yfy.createcinema.client.ClientNetworkProjectorStreams;
import com.yfy.createcinema.packet.C2SSetNetworkProjectorUrlPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
public class NetworkProjectorScreen extends AbstractContainerScreen<NetworkProjectorMenu> {
    private EditBox urlField;
    private boolean saveRequested;
    public NetworkProjectorScreen(NetworkProjectorMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); imageWidth = 280; imageHeight = 112; }
    @Override protected void init() {
        super.init();
        urlField = new EditBox(font, leftPos + 12, topPos + 38, 256, 20, Component.translatable("gui.createcinema.network_projector_url"));
        urlField.setMaxLength(NetworkProjectorBlockEntity.MAX_URL_LENGTH); urlField.setValue(menu.getUrl()); addRenderableWidget(urlField);
        addRenderableWidget(Button.builder(Component.translatable("gui.createcinema.save_url"), button -> save()).bounds(leftPos + 104, topPos + 70, 72, 20).build());
        setInitialFocus(urlField);
    }
    private void save() { saveRequested = true; ClientNetworkProjectorStreams.requestRetry(menu.pos); new C2SSetNetworkProjectorUrlPacket(menu.pos, urlField.getValue()).send(); }
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) { save(); return true; }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF202327);
        graphics.fill(leftPos + 4, topPos + 4, leftPos + imageWidth - 4, topPos + imageHeight - 4, 0xFF30343A);
    }
    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 12, 10, 0xF2F2F2, false);
        graphics.drawString(font, Component.translatable("gui.createcinema.network_projector_url"), 12, 26, 0xC9CDD2, false);
        ClientNetworkProjectorStreams.Status status = ClientNetworkProjectorStreams.status(menu.pos);
        if (saveRequested && status == ClientNetworkProjectorStreams.Status.IDLE) status = ClientNetworkProjectorStreams.Status.LOADING;
        Component statusText = switch (status) {
            case LOADING -> Component.translatable("gui.createcinema.stream.loading");
            case PLAYING -> Component.translatable("gui.createcinema.stream.playing");
            case ERROR -> Component.translatable("gui.createcinema.stream.error");
            case IDLE -> Component.empty();
        };
        if (!statusText.getString().isEmpty()) graphics.drawCenteredString(font, statusText, imageWidth / 2, 95,
                status == ClientNetworkProjectorStreams.Status.ERROR ? 0xFF7777 : 0xAEB5BD);
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick); super.render(graphics, mouseX, mouseY, partialTick); renderTooltip(graphics, mouseX, mouseY);
    }
}
