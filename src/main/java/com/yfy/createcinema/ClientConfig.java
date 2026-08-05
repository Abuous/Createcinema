package com.yfy.createcinema;

import com.yfy.createcinema.client.PlatformInfo;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class ClientConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.ConfigValue<String> LEGACY_DOUYIN_COOKIE;
    private static final ModConfigSpec.BooleanValue DOUYIN_BROWSER_AUTHORIZATION;
    private static final ModConfigSpec.IntValue SCREEN_MAX_WIDTH;
    private static final ModConfigSpec.IntValue SCREEN_MAX_HEIGHT;
    private static final ModConfigSpec.IntValue SCREEN_ANCHOR_RADIUS;
    private static final ModConfigSpec.IntValue SCREEN_MAX_DISTANCE;
    private static final ModConfigSpec.BooleanValue BURN_CACHE_ENABLED;
    private static final ModConfigSpec.IntValue BURN_CACHE_MAX_GIB;
    private static final ModConfigSpec.BooleanValue BURN_HARDWARE_DECODING;
    private static final ModConfigSpec.BooleanValue BURN_HARDWARE_ENCODING;
    private static final ModConfigSpec.BooleanValue PROJECTOR_HARDWARE_DECODING;
    private static volatile Runnable douyinBrowserShutdown = () -> { };

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("douyin");
        LEGACY_DOUYIN_COOKIE = builder
                .comment("Plaintext Douyin cookies used as a login fallback where no visible browser ",
                        "session is available (e.g. Android). On desktop this value is cleared automatically.")
                .define("cookie", "");
        DOUYIN_BROWSER_AUTHORIZATION = builder
                .comment("Use an embedded local browser session for signed Douyin requests ",
                        "(WebView2 on Windows x86_64, Chromium/Edge over DevTools Protocol on Linux ",
                        "and Windows-on-ARM, Android WebView).")
                .define("browserAuthorization", false);
        builder.pop();
        builder.push("projector");
        SCREEN_MAX_WIDTH = builder
                .comment("Maximum detected screen width in blocks.")
                .defineInRange("screenMaxWidth", 17, 1, 64);
        SCREEN_MAX_HEIGHT = builder
                .comment("Maximum detected screen height in blocks.")
                .defineInRange("screenMaxHeight", 17, 1, 64);
        SCREEN_ANCHOR_RADIUS = builder
                .comment("Horizontal and vertical search radius around the projector center when locating a screen.")
                .defineInRange("screenAnchorRadius", 4, 0, 32);
        SCREEN_MAX_DISTANCE = builder
                .comment("Maximum distance in blocks in front of a projector when locating a screen.")
                .defineInRange("screenMaxDistance", 16, 1, 64);
        PROJECTOR_HARDWARE_DECODING = builder
                .comment("Try a platform hardware decoder for H.264 film playback, then fall back to FFmpeg software decoding.")
                .define("hardwareDecoding", true);
        builder.pop();
        builder.push("burner");
        BURN_CACHE_ENABLED = builder
                .comment("Reuse encoded frames and audio when the same source file and quality are burned again.")
                .define("cacheEnabled", true);
        BURN_CACHE_MAX_GIB = builder
                .comment("Maximum disk space used by reusable burn packages, in GiB.")
                .defineInRange("cacheMaxGiB", 10, 1, 100);
        BURN_HARDWARE_DECODING = builder
                .comment("Try an available platform hardware video decoder, then fall back to software decoding.")
                .define("hardwareDecoding", true);
        BURN_HARDWARE_ENCODING = builder
                .comment("Try an available platform H.264 hardware encoder, then fall back to software encoding.")
                .define("hardwareEncoding", true);
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

    public static String legacyDouyinCookie() {
        return LEGACY_DOUYIN_COOKIE.get();
    }

    public static void setLegacyDouyinCookie(String cookies) {
        if (cookies == null) cookies = "";
        LEGACY_DOUYIN_COOKIE.set(cookies);
        LEGACY_DOUYIN_COOKIE.save();
    }

    public static void clearLegacyDouyinCookie() {
        if (PlatformInfo.isAndroid()) return;
        if (LEGACY_DOUYIN_COOKIE.get().isBlank()) return;
        LEGACY_DOUYIN_COOKIE.set("");
        LEGACY_DOUYIN_COOKIE.save();
    }

    public static int screenMaxWidth() {
        return SCREEN_MAX_WIDTH.get();
    }

    public static int screenMaxHeight() {
        return SCREEN_MAX_HEIGHT.get();
    }

    public static int screenAnchorRadius() {
        return SCREEN_ANCHOR_RADIUS.get();
    }

    public static int screenMaxDistance() {
        return SCREEN_MAX_DISTANCE.get();
    }

    public static boolean projectorHardwareDecoding() {
        return PROJECTOR_HARDWARE_DECODING.get();
    }

    public static boolean burnCacheEnabled() {
        return BURN_CACHE_ENABLED.get();
    }

    public static long burnCacheMaxBytes() {
        return BURN_CACHE_MAX_GIB.get() * 1024L * 1024L * 1024L;
    }

    public static boolean burnHardwareDecoding() {
        return BURN_HARDWARE_DECODING.get();
    }

    public static boolean burnHardwareEncoding() {
        return BURN_HARDWARE_ENCODING.get();
    }
}
