package com.yfy.createcinema.client;

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
    private static final int PANEL_HEIGHT = 168;

    private final Screen parent;
    private Button browserOpenButton;
    private Button browserTestButton;
    private Button browserDisableButton;
    private final Button[] providerButtons = new Button[BrowserProvider.values().length];
    private BrowserProvider selectedProvider = BrowserProvider.DOUYIN;
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
        int providerButtonWidth = (panelWidth - 28 - gap) / 2;
        int buttonWidth = (panelWidth - 28 - gap * 2) / 3;

        for (BrowserProvider provider : BrowserProvider.values()) {
            int index = provider.ordinal();
            providerButtons[index] = addRenderableWidget(Button.builder(
                    Component.translatable(provider.translationKey()), button -> selectProvider(provider))
                    .bounds(left + 14 + (providerButtonWidth + gap) * index, top + 42,
                            providerButtonWidth, 20).build());
        }

        browserOpenButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.createcinema.config_manager.browser_open"), button -> browserAction(false))
                .bounds(left + 14, top + 68, buttonWidth, 20).build());
        browserTestButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.createcinema.config_manager.browser_test"), button -> browserAction(true))
                .bounds(left + 14 + buttonWidth + gap, top + 68, buttonWidth, 20).build());
        browserDisableButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.createcinema.config_manager.browser_disable"), button -> disableBrowser())
                .bounds(left + 14 + (buttonWidth + gap) * 2, top + 68, buttonWidth, 20).build());
        updateProviderButtons();
    }

    private void selectProvider(BrowserProvider provider) {
        if (selectedProvider == provider) return;
        actionGeneration++;
        selectedProvider = provider;
        status = Component.empty();
        setButtonsActive(true);
        updateProviderButtons();
    }

    private void updateProviderButtons() {
        for (BrowserProvider provider : BrowserProvider.values()) {
            Button button = providerButtons[provider.ordinal()];
            if (button != null) button.active = provider != selectedProvider;
        }
    }

    private void browserAction(boolean test) {
        int generation = ++actionGeneration;
        BrowserProvider provider = selectedProvider;
        provider.setEnabled(true);
        status = Component.translatable("gui.createcinema.config_manager.browser_starting");
        setButtonsActive(false);
        CompletableFuture.supplyAsync(() -> {
            if (!provider.enabled() || generation != actionGeneration) return null;
            try {
                if (test) return DouyinBrowserBridge.testAuthorization(provider);
                DouyinBrowserBridge.openAuthorizationPage(provider);
                return DouyinBrowserBackend.AuthorizationState.UNKNOWN;
            } catch (java.io.IOException error) {
                throw new CompletionException(error);
            }
        }).whenComplete((authorization, error) -> Minecraft.getInstance().execute(() -> {
            if (generation != actionGeneration || !provider.enabled()) return;
            setButtonsActive(true);
            if (error == null) {
                Component providerName = Component.translatable(provider.translationKey());
                if (!test) {
                    status = Component.translatable("gui.createcinema.config_manager.browser_login_prompt", providerName);
                } else if (authorization == DouyinBrowserBackend.AuthorizationState.AUTHORIZED) {
                    status = Component.translatable("gui.createcinema.config_manager.browser_authorized", providerName);
                    ClientNetworkProjectorStreams.requestBrowserRetry(provider);
                } else if (authorization == DouyinBrowserBackend.AuthorizationState.UNKNOWN) {
                    status = Component.translatable("gui.createcinema.config_manager.browser_verify_on_play", providerName);
                } else {
                    status = Component.translatable("gui.createcinema.config_manager.browser_failed", providerName);
                }
            } else {
                status = Component.translatable("gui.createcinema.config_manager.browser_failed",
                        Component.translatable(provider.translationKey()));
            }
        }));
    }

    private void disableBrowser() {
        actionGeneration++;
        BrowserProvider provider = selectedProvider;
        provider.setEnabled(false);
        setButtonsActive(true);
        status = Component.translatable("gui.createcinema.config_manager.browser_disabled",
                Component.translatable(provider.translationKey()));
        ClientNetworkProjectorStreams.requestBrowserRetry(provider);
    }

    private void setButtonsActive(boolean active) {
        browserOpenButton.active = active;
        browserTestButton.active = active;
        browserDisableButton.active = active;
        for (Button button : providerButtons) if (button != null) button.active = active;
        if (active) updateProviderButtons();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        if (selectedProvider.enabled()
                && DouyinBrowserBridge.status(selectedProvider) == DouyinBrowserBridge.Status.READY) {
            status = Component.translatable("gui.createcinema.config_manager.browser_authorized",
                    Component.translatable(selectedProvider.translationKey()));
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
                    top + 98 + line * 10, 0x8FC7B5);
        }
        if (!status.getString().isEmpty()) {
            java.util.List<FormattedCharSequence> lines = font.split(status, panelWidth - 28);
            for (int line = 0; line < Math.min(3, lines.size()); line++) {
                graphics.drawCenteredString(font, lines.get(line), left + panelWidth / 2,
                    top + 122 + line * 10, 0xD9DEE3);
            }
        }
        for (net.minecraft.client.gui.components.Renderable renderable : renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    private Component browserStatus() {
        if (!selectedProvider.enabled())
            return Component.translatable("gui.createcinema.config_manager.browser_status.disabled");
        return Component.translatable(switch (DouyinBrowserBridge.status(selectedProvider)) {
            case STOPPED -> "gui.createcinema.config_manager.browser_status.stopped";
            case STARTING -> "gui.createcinema.config_manager.browser_status.starting";
            case WAITING_LOGIN -> "gui.createcinema.config_manager.browser_status.waiting";
            case READY -> "gui.createcinema.config_manager.browser_status.ready";
            case FAILED -> "gui.createcinema.config_manager.browser_status.failed";
        });
    }

    private int browserStatusColor() {
        if (!selectedProvider.enabled()) return 0x8C939B;
        return switch (DouyinBrowserBridge.status(selectedProvider)) {
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
