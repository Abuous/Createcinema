package com.yfy.createcinema.client;

import com.yfy.createcinema.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class ClientConfigManagerScreen extends Screen {
    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 132;

    private final Screen parent;
    private Button browserOpenButton;
    private Button browserTestButton;
    private Button browserDisableButton;
    private Component status = Component.empty();
    private int actionGeneration;

    private ClientConfigManagerScreen(Screen parent) {
        super(Component.translatable("gui.createcinema.config_manager.title"));
        this.parent = parent;
    }

    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new ClientConfigManagerScreen(minecraft.screen));
    }

    @Override
    protected void init() {
        int panelWidth = Math.min(PANEL_WIDTH, width - 24);
        int left = (width - panelWidth) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        int gap = 6;
        int buttonWidth = (panelWidth - 28 - gap * 2) / 3;

        browserOpenButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.createcinema.config_manager.browser_open"), button -> browserAction(false))
                .bounds(left + 14, top + 42, buttonWidth, 20).build());
        browserTestButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.createcinema.config_manager.browser_test"), button -> browserAction(true))
                .bounds(left + 14 + buttonWidth + gap, top + 42, buttonWidth, 20).build());
        browserDisableButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.createcinema.config_manager.browser_disable"), button -> disableBrowser())
                .bounds(left + 14 + (buttonWidth + gap) * 2, top + 42, buttonWidth, 20).build());
    }

    private void browserAction(boolean test) {
        int generation = ++actionGeneration;
        ClientConfig.setDouyinBrowserAuthorization(true);
        status = Component.translatable("gui.createcinema.config_manager.browser_starting");
        setButtonsActive(false);
        CompletableFuture.runAsync(() -> {
            if (!ClientConfig.douyinBrowserAuthorization() || generation != actionGeneration) return;
            try {
                if (test) DouyinBrowserBridge.captureFeed();
                else DouyinBrowserBridge.openAuthorizationPage();
            } catch (java.io.IOException error) {
                throw new CompletionException(error);
            }
        }).whenComplete((unused, error) -> Minecraft.getInstance().execute(() -> {
            if (generation != actionGeneration || !ClientConfig.douyinBrowserAuthorization()) return;
            setButtonsActive(true);
            if (error == null) {
                status = Component.translatable(test
                        ? "gui.createcinema.config_manager.browser_authorized"
                        : "gui.createcinema.config_manager.browser_login_prompt");
                if (test) ClientNetworkProjectorStreams.requestDouyinRetry();
            } else {
                status = Component.translatable(PlatformInfo.isAndroid()
                        ? "gui.createcinema.config_manager.browser_android_cookie"
                        : "gui.createcinema.config_manager.browser_failed");
            }
        }));
    }

    private void disableBrowser() {
        actionGeneration++;
        ClientConfig.setDouyinBrowserAuthorization(false);
        setButtonsActive(true);
        status = Component.translatable("gui.createcinema.config_manager.browser_disabled");
        ClientNetworkProjectorStreams.requestDouyinRetry();
    }

    private void setButtonsActive(boolean active) {
        browserOpenButton.active = active;
        browserTestButton.active = active;
        browserDisableButton.active = active;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        if (ClientConfig.douyinBrowserAuthorization()
                && DouyinBrowserBridge.status() == DouyinBrowserBridge.Status.READY) {
            status = Component.translatable("gui.createcinema.config_manager.browser_authorized");
        }
        int panelWidth = Math.min(PANEL_WIDTH, width - 24);
        int left = (width - panelWidth) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        graphics.fill(left, top, left + panelWidth, top + PANEL_HEIGHT, 0xF0202327);
        graphics.fill(left + 3, top + 3, left + panelWidth - 3, top + PANEL_HEIGHT - 3, 0xF030343A);
        graphics.drawString(font, title, left + 14, top + 12, 0xF2F2F2, false);
        graphics.drawString(font, Component.translatable("gui.createcinema.config_manager.browser_authorization"),
                left + 14, top + 28, 0xC9CDD2, false);
        Component bridgeStatus = browserStatus();
        int statusX = Math.max(left + 130, left + panelWidth - 14 - font.width(bridgeStatus));
        graphics.drawString(font, bridgeStatus, statusX, top + 28, browserStatusColor(), false);
        java.util.List<FormattedCharSequence> localLines = font.split(
                Component.translatable("gui.createcinema.config_manager.browser_local_only"), panelWidth - 28);
        for (int line = 0; line < Math.min(2, localLines.size()); line++) {
            graphics.drawCenteredString(font, localLines.get(line), left + panelWidth / 2,
                    top + 72 + line * 10, 0x8FC7B5);
        }
        if (!status.getString().isEmpty()) {
            java.util.List<FormattedCharSequence> lines = font.split(status, panelWidth - 28);
            for (int line = 0; line < Math.min(3, lines.size()); line++) {
                graphics.drawCenteredString(font, lines.get(line), left + panelWidth / 2,
                        top + 94 + line * 10, 0xD9DEE3);
            }
        }
        for (net.minecraft.client.gui.components.Renderable renderable : renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    private Component browserStatus() {
        if (!ClientConfig.douyinBrowserAuthorization())
            return Component.translatable("gui.createcinema.config_manager.browser_status.disabled");
        return Component.translatable(switch (DouyinBrowserBridge.status()) {
            case STOPPED -> "gui.createcinema.config_manager.browser_status.stopped";
            case STARTING -> "gui.createcinema.config_manager.browser_status.starting";
            case WAITING_LOGIN -> "gui.createcinema.config_manager.browser_status.waiting";
            case READY -> "gui.createcinema.config_manager.browser_status.ready";
            case FAILED -> "gui.createcinema.config_manager.browser_status.failed";
        });
    }

    private int browserStatusColor() {
        if (!ClientConfig.douyinBrowserAuthorization()) return 0x8C939B;
        return switch (DouyinBrowserBridge.status()) {
            case READY -> 0x78D69A;
            case FAILED -> 0xFF7777;
            case STARTING, WAITING_LOGIN -> 0xE4C66A;
            case STOPPED -> 0xAEB5BD;
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
