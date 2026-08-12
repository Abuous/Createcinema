package com.yfy.createcinema.client.config;

import com.yfy.createcinema.client.douyin.DouyinBrowserBridge;
import com.yfy.createcinema.client.douyin.DouyinBrowserBackend;
import com.yfy.createcinema.client.network.ClientNetworkProjectorStreams;
import com.yfy.createcinema.client.browser.BrowserProvider;
import com.yfy.createcinema.client.bilibili.BilibiliSession;
import com.yfy.createcinema.client.bilibili.BilibiliBrowserLogin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import com.yfy.createcinema.BilibiliMemberQuality;
import com.yfy.createcinema.ClientConfig;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class ClientConfigManagerScreen extends Screen {
    private static final int PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 232;

    private final Screen parent;
    private Button browserOpenButton;
    private Button browserTestButton;
    private Button browserDisableButton;
    private Button bilibiliLoginButton;
    private Button bilibiliLogoutButton;
    private Button bilibiliQualityButton;
    private final Button[] providerButtons = new Button[BrowserProvider.values().length];
    private BrowserProvider selectedProvider = BrowserProvider.DOUYIN;
    private Component status = Component.empty();
    private int actionGeneration;
    private int ticks;
    private boolean bilibiliLoginPending;
    private boolean bilibiliCheckInFlight;

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
        bilibiliLoginButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.createcinema.config_manager.bilibili_login"), button -> openBilibiliLogin())
                .bounds(left + 14, top + 184, buttonWidth, 20).build());
        bilibiliLogoutButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.createcinema.config_manager.bilibili_logout"), button -> logoutBilibili())
                .bounds(left + 14 + buttonWidth + gap, top + 184, buttonWidth, 20).build());
        bilibiliQualityButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.createcinema.config_manager.bilibili_quality",
                                memberQualityLabel(ClientConfig.bilibiliMemberQuality())), button -> cycleBilibiliQuality())
                .bounds(left + 14 + (buttonWidth + gap) * 2, top + 184, buttonWidth, 20).build());
        updateProviderButtons();
        updateBilibiliButtons();
    }

    private void updateBilibiliButtons() {
        boolean loggedIn = BilibiliSession.hasSession();
        bilibiliLoginButton.active = !loggedIn && !bilibiliLoginPending;
        bilibiliLogoutButton.active = loggedIn;
    }

    @Override
    public void tick() {
        ticks++;
        if (BilibiliSession.hasSession() && ticks % 40 == 0) {
            CompletableFuture.supplyAsync(BilibiliSession::vipStatus);
        }
        if (bilibiliLoginPending && !bilibiliCheckInFlight && ticks % 20 == 0) {
            checkBilibiliLogin();
        }
    }

    private void openBilibiliLogin() {
        int generation = ++actionGeneration;
        bilibiliLoginPending = true;
        updateBilibiliButtons();
        status = Component.translatable("gui.createcinema.config_manager.bilibili_browser_starting");
        CompletableFuture.runAsync(() -> {
            try {
                BilibiliBrowserLogin.open();
            } catch (java.io.IOException error) {
                throw new CompletionException(error);
            }
        }).whenComplete((ignored, error) -> Minecraft.getInstance().execute(() -> {
            if (generation != actionGeneration) return;
            if (error != null) {
                bilibiliLoginPending = false;
                updateBilibiliButtons();
                status = Component.translatable("gui.createcinema.config_manager.bilibili_browser_failed",
                        rootMessage(error));
                return;
            }
            status = Component.translatable("gui.createcinema.config_manager.bilibili_browser_prompt");
        }));
    }

    private void checkBilibiliLogin() {
        bilibiliCheckInFlight = true;
        int generation = actionGeneration;
        CompletableFuture.supplyAsync(() -> {
            try {
                return BilibiliBrowserLogin.saveCookiesIfAvailable();
            } catch (java.io.IOException error) {
                throw new CompletionException(error);
            }
        }).whenComplete((authorized, error) -> Minecraft.getInstance().execute(() -> {
            bilibiliCheckInFlight = false;
            if (generation != actionGeneration || !bilibiliLoginPending) return;
            if (error != null) {
                bilibiliLoginPending = false;
                BilibiliBrowserLogin.hide();
                updateBilibiliButtons();
                status = Component.translatable("gui.createcinema.config_manager.bilibili_browser_failed",
                        rootMessage(error));
            } else if (Boolean.TRUE.equals(authorized)) {
                bilibiliLoginPending = false;
                BilibiliBrowserLogin.hide();
                updateBilibiliButtons();
                status = Component.translatable("gui.createcinema.config_manager.bilibili_browser_success");
            }
        }));
    }

    private void logoutBilibili() {
        BilibiliSession.logout();
        updateBilibiliButtons();
        status = Component.translatable("gui.createcinema.config_manager.bilibili_logged_out");
    }

    private void cycleBilibiliQuality() {
        BilibiliMemberQuality quality = ClientConfig.bilibiliMemberQuality().next();
        ClientConfig.setBilibiliVipQuality(quality.configId());
        BilibiliSession.onMemberQualityChanged();
        bilibiliQualityButton.setMessage(Component.translatable(
                "gui.createcinema.config_manager.bilibili_quality", memberQualityLabel(quality)));
    }

    private static Component memberQualityLabel(BilibiliMemberQuality quality) {
        return Component.translatable(quality.translationKey());
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
        actionGeneration++;
        bilibiliLoginPending = false;
        BilibiliBrowserLogin.hide();
        minecraft.setScreen(parent);
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause() : error;
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
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
        graphics.fill(left + 10, top + 152, left + panelWidth - 10, top + 154, 0xFF4A5058);
        graphics.drawString(font, Component.translatable("gui.createcinema.config_manager.bilibili_section"),
                left + 14, top + 162, 0xC9CDD2, false);
        Component bilibiliStatus = bilibiliStatus();
        int bilibiliStatusX = Math.max(left + 130, left + panelWidth - 14 - font.width(bilibiliStatus));
        graphics.drawString(font, bilibiliStatus, bilibiliStatusX, top + 162, bilibiliStatusColor(), false);
        for (net.minecraft.client.gui.components.Renderable renderable : renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    private Component bilibiliStatus() {
        if (!BilibiliSession.hasSession()) {
            return Component.translatable("gui.createcinema.config_manager.bilibili_status.none");
        }
        return Component.translatable(BilibiliSession.vipStatusCached() > 0
                ? "gui.createcinema.config_manager.bilibili_status.vip"
                : "gui.createcinema.config_manager.bilibili_status.user");
    }

    private int bilibiliStatusColor() {
        if (!BilibiliSession.hasSession()) return 0x8C939B;
        return BilibiliSession.vipStatusCached() > 0 ? 0x78D69A : 0xE4C66A;
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
