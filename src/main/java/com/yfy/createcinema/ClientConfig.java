package com.yfy.createcinema;

import com.yfy.createcinema.client.browser.PlatformInfo;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class ClientConfig {
    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.ConfigValue<String> LEGACY_DOUYIN_COOKIE;
    private static final ModConfigSpec.BooleanValue DOUYIN_BROWSER_AUTHORIZATION;
    private static final ModConfigSpec.BooleanValue IQIYI_BROWSER_AUTHORIZATION;
    private static final ModConfigSpec.IntValue SCREEN_MAX_WIDTH;
    private static final ModConfigSpec.IntValue SCREEN_MAX_HEIGHT;
    private static final ModConfigSpec.IntValue SCREEN_ANCHOR_RADIUS;
    private static final ModConfigSpec.IntValue SCREEN_MAX_DISTANCE;
    private static final ModConfigSpec.DoubleValue SPEAKER_CLUSTER_DISTANCE;
    private static final ModConfigSpec.IntValue SPEAKER_ATTENUATION_DISTANCE;
    private static final ModConfigSpec.BooleanValue BURN_CACHE_ENABLED;
    private static final ModConfigSpec.IntValue BURN_CACHE_MAX_GIB;
    private static final ModConfigSpec.BooleanValue BURN_HARDWARE_DECODING;
    private static final ModConfigSpec.BooleanValue BURN_HARDWARE_ENCODING;
    private static final ModConfigSpec.BooleanValue PROJECTOR_HARDWARE_DECODING;
    private static final ModConfigSpec.ConfigValue<String> BILIBILI_SESSDATA;
    private static final ModConfigSpec.ConfigValue<String> BILIBILI_BILL_JCT;
    private static final ModConfigSpec.ConfigValue<String> BILIBILI_BUVID3;
    private static final ModConfigSpec.ConfigValue<String> BILIBILI_BROWSER_COOKIES;
    private static final ModConfigSpec.ConfigValue<String> BILIBILI_REFRESH_TOKEN;
    private static final ModConfigSpec.IntValue BILIBILI_VIP_QUALITY;
    private static volatile java.util.function.Consumer<String> browserShutdown = provider -> { };

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
        builder.push("iqiyi");
        IQIYI_BROWSER_AUTHORIZATION = builder
                .comment("Use an isolated local browser profile for authorized iQiyi playback requests.")
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
        SPEAKER_CLUSTER_DISTANCE = builder
                .comment("Deprecated compatibility value. Network audio now uses one source at the nearest ",
                        "redstone-powered speaker regardless of speaker distance.")
                .defineInRange("speakerClusterDistance", 4.0, 0.5, 64.0);
        SPEAKER_ATTENUATION_DISTANCE = builder
                .comment("Distance in blocks over which speaker audio fades out linearly; beyond it the sound is silent.")
                .defineInRange("speakerAttenuationDistance", 48, 1, 128);
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
        builder.push("bilibili");
        BILIBILI_SESSDATA = builder
                .comment("Bilibili SESSDATA cookie obtained via the local browser QR login. Plaintext; ",
                        "treat like a password. Only used to fetch media for your own account.")
                .define("sessdata", "");
        BILIBILI_BILL_JCT = builder
                .comment("Bilibili CSRF token paired with SESSDATA.")
                .define("biliJct", "");
        BILIBILI_BUVID3 = builder
                .comment("Bilibili device id used to avoid risk control on authenticated requests.")
                .define("buvid3", "");
        BILIBILI_BROWSER_COOKIES = builder
                .comment("Full cookie set captured from the local browser session used for Bilibili ",
                        "login (buvid_fp, b_nut, b_lsid, buvid4, ...). Sent with API requests so risk ",
                        "control does not block them. Plaintext; treat like a password.")
                .define("browserCookies", "");
        BILIBILI_REFRESH_TOKEN = builder
                .comment("Bilibili refresh token; used to silently renew SESSDATA before it expires.")
                .define("refreshToken", "");
        BILIBILI_VIP_QUALITY = builder
                .comment("Local member-quality target used only when a network projector selects member quality: ",
                        "1 = 1080P60, 3 = 2K60, 2 = 4K60. Legacy value 0 maps to 1080P60.")
                .defineInRange("vipQuality", 1, 0, 3);
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
        if (wasEnabled && !enabled) browserShutdown.accept("douyin");
    }

    public static boolean iqiyiBrowserAuthorization() {
        return IQIYI_BROWSER_AUTHORIZATION.get();
    }

    public static void setIqiyiBrowserAuthorization(boolean enabled) {
        setBrowserAuthorization(IQIYI_BROWSER_AUTHORIZATION, "iqiyi", enabled);
    }

    private static void setBrowserAuthorization(ModConfigSpec.BooleanValue setting, String provider, boolean enabled) {
        boolean wasEnabled = setting.get();
        setting.set(enabled);
        setting.save();
        if (wasEnabled && !enabled) browserShutdown.accept(provider);
    }

    public static void setDouyinBrowserShutdown(Runnable shutdown) {
        browserShutdown = shutdown == null ? provider -> { } : provider -> shutdown.run();
    }

    public static void setBrowserShutdown(java.util.function.Consumer<String> shutdown) {
        browserShutdown = shutdown == null ? provider -> { } : shutdown;
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

    public static double speakerClusterDistance() {
        return SPEAKER_CLUSTER_DISTANCE.get();
    }

    public static int speakerAttenuationDistance() {
        return SPEAKER_ATTENUATION_DISTANCE.get();
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

    public static String bilibiliSessdata() {
        return BILIBILI_SESSDATA.get();
    }

    public static void setBilibiliSessdata(String value) {
        setBilibiliString(BILIBILI_SESSDATA, value);
    }

    public static String bilibiliBiliJct() {
        return BILIBILI_BILL_JCT.get();
    }

    public static void setBilibiliBiliJct(String value) {
        setBilibiliString(BILIBILI_BILL_JCT, value);
    }

    public static String bilibiliBuvid3() {
        return BILIBILI_BUVID3.get();
    }

    public static void setBilibiliBuvid3(String value) {
        setBilibiliString(BILIBILI_BUVID3, value);
    }

    public static String bilibiliBrowserCookies() {
        return BILIBILI_BROWSER_COOKIES.get();
    }

    public static void setBilibiliBrowserCookies(String value) {
        setBilibiliString(BILIBILI_BROWSER_COOKIES, value);
    }

    public static String bilibiliRefreshToken() {
        return BILIBILI_REFRESH_TOKEN.get();
    }

    public static void setBilibiliRefreshToken(String value) {
        setBilibiliString(BILIBILI_REFRESH_TOKEN, value);
    }

    public static void clearBilibiliSession() {
        BILIBILI_SESSDATA.set("");
        BILIBILI_BILL_JCT.set("");
        BILIBILI_BUVID3.set("");
        BILIBILI_BROWSER_COOKIES.set("");
        BILIBILI_REFRESH_TOKEN.set("");
        BILIBILI_SESSDATA.save();
    }

    public static int bilibiliVipQuality() {
        return BILIBILI_VIP_QUALITY.get();
    }

    public static BilibiliMemberQuality bilibiliMemberQuality() {
        return BilibiliMemberQuality.byConfigId(BILIBILI_VIP_QUALITY.get());
    }

    public static void setBilibiliVipQuality(int quality) {
        BILIBILI_VIP_QUALITY.set(quality);
        BILIBILI_VIP_QUALITY.save();
    }

    private static void setBilibiliString(ModConfigSpec.ConfigValue<String> setting, String value) {
        setting.set(value == null ? "" : value);
        setting.save();
    }
}
