package com.yfy.createcinema;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ClientConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.ConfigValue<String> LEGACY_DOUYIN_COOKIE;
    private static final ModConfigSpec.BooleanValue DOUYIN_BROWSER_AUTHORIZATION;
    private static volatile Runnable douyinBrowserShutdown = () -> { };

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("douyin");
        LEGACY_DOUYIN_COOKIE = builder
                .comment("Legacy value retained only so older plaintext Cookies can be removed automatically.")
                .define("cookie", "");
        DOUYIN_BROWSER_AUTHORIZATION = builder
                .comment("Use an embedded local WebView2 profile for signed Douyin requests (Windows only).")
                .define("browserAuthorization", false);
        builder.pop();
        SPEC = builder.build();
    }

    private ClientConfig() {
    }

    public static boolean douyinBrowserAuthorization() {
        return DOUYIN_BROWSER_AUTHORIZATION.get();
    }

    public static void setDouyinBrowserAuthorization(boolean enabled) {
        boolean wasEnabled = DOUYIN_BROWSER_AUTHORIZATION.get();
        DOUYIN_BROWSER_AUTHORIZATION.set(enabled);
        DOUYIN_BROWSER_AUTHORIZATION.save();
        clearLegacyDouyinCookie();
        if (wasEnabled && !enabled) douyinBrowserShutdown.run();
    }

    public static void setDouyinBrowserShutdown(Runnable shutdown) {
        douyinBrowserShutdown = shutdown == null ? () -> { } : shutdown;
    }

    public static void clearLegacyDouyinCookie() {
        if (LEGACY_DOUYIN_COOKIE.get().isBlank()) return;
        LEGACY_DOUYIN_COOKIE.set("");
        LEGACY_DOUYIN_COOKIE.save();
    }
}
