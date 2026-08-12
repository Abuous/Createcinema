package com.yfy.createcinema.gui;

import com.yfy.createcinema.client.network.ClientPacketHandlers;
import com.yfy.createcinema.client.render.ClientVideoBurner;
import com.yfy.createcinema.film.FilmQuality;
import com.yfy.createcinema.item.FilmItem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class BurnerScreen extends AbstractContainerScreen<BurnerMenu> {
    private EditBox pathField;
    private Button burnButton;
    private FilmQuality quality = FilmQuality.STANDARD;

    public BurnerScreen(BurnerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 260;
        imageHeight = 212;
    }

    @Override
    protected void init() {
        super.init();
        pathField = new EditBox(font, leftPos + 12, topPos + 32, 200, 18, Component.translatable("gui.createcinema.media_path"));
        pathField.setMaxLength(1024);
        addRenderableWidget(pathField);
        addRenderableWidget(CycleButton.builder(this::qualityLabel)
                .withValues(FilmQuality.values())
                .withInitialValue(quality)
                .create(leftPos + 12, topPos + 56, 150, 20,
                        Component.translatable("gui.createcinema.quality"),
                        (button, value) -> quality = value));
        burnButton = addRenderableWidget(Button.builder(Component.translatable("gui.createcinema.burn"), button -> {
            if (menu.hasBlankFilm()) {
                ClientVideoBurner.startBurn(menu.pos, pathField.getValue(), quality, menu.getBlankMediaType());
            }
        }).bounds(leftPos + 176, topPos + 56, 72, 20).build());
    }

    private Component qualityLabel(FilmQuality value) {
        return Component.translatable("gui.createcinema.quality." + value.id());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderTooltip(guiGraphics, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(font, title, 12, 10, 0x404040, false);
        guiGraphics.drawString(font, Component.translatable("gui.createcinema.media_path"), 12, 22, 0x404040, false);

        var burner = menu.getBurner();
        if (burner != null && !burner.hasNoFilm()) {
            String filmId = FilmItem.getFilmId(burner.getFilm());
            String filmLabel = filmId.isBlank() ? Component.translatable("item.createcinema.film.empty").getString()
                    : filmId.substring(0, Math.min(8, filmId.length()));
            guiGraphics.drawString(font, Component.literal("Film: " + filmLabel), 12, 78, 0x404040, false);
        } else {
            guiGraphics.drawString(font, Component.translatable("gui.createcinema.insert_film"), 12, 78, 0x8A4A32, false);
        }

        ClientPacketHandlers.BurnProgress progress = ClientPacketHandlers.getBurnProgress(menu.pos);
        guiGraphics.drawString(font, progress.message(), 12, 90, 0x404040, false);
        drawProgressBar(guiGraphics, 12, 102, 236, 8, progress.progress());
        if (burnButton != null) {
            burnButton.active = !progress.active() && menu.hasBlankFilm();
        }
        guiGraphics.drawString(font, playerInventoryTitle, 49, 115, 0x404040, false);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (menu.getBurner() != null && menu.getBurner().hasNoFilm()) {
            ClientVideoBurner.cancel(menu.pos, "Film removed - burn cancelled");
        }
    }

    private void drawProgressBar(GuiGraphics guiGraphics, int x, int y, int width, int height, float progress) {
        guiGraphics.fill(x, y, x + width, y + height, 0xFF514C45);
        guiGraphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFFE0D8CA);
        int fillWidth = Math.round((width - 2) * Math.max(0.0f, Math.min(1.0f, progress)));
        guiGraphics.fill(x + 1, y + 1, x + 1 + fillWidth, y + height - 1, 0xFFB8643C);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFFE9E4DA);
        guiGraphics.fill(leftPos + 4, topPos + 4, leftPos + imageWidth - 4, topPos + imageHeight - 4, 0xFFF7F4EC);
        drawSlot(guiGraphics, leftPos + 221, topPos + 31);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                drawSlot(guiGraphics, leftPos + 48 + col * 18, topPos + 124 + row * 18);
            }
        }
        for (int col = 0; col < 9; col++) {
            drawSlot(guiGraphics, leftPos + 48 + col * 18, topPos + 184);
        }
    }

    private void drawSlot(GuiGraphics guiGraphics, int x, int y) {
        guiGraphics.fill(x, y, x + 18, y + 18, 0xFF6B6256);
        guiGraphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFFD8CFBE);
        guiGraphics.fill(x + 2, y + 2, x + 16, y + 16, 0xFFF1EADC);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode) && getFocused() instanceof EditBox focused) {
            return focused.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
