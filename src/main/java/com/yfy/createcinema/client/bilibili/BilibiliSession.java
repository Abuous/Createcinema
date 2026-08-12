package com.yfy.createcinema.client.bilibili;

import com.yfy.createcinema.client.video.VideoResolverHttp;
import com.yfy.createcinema.client.network.ClientNetworkProjectorStreams;
import com.google.gson.JsonObject;
import com.yfy.createcinema.BilibiliMemberQuality;
import com.yfy.createcinema.ClientConfig;
import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.NetworkVideoQuality;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Bilibili session management for browser-backed login. All requests are made with the
 * player's own account credentials; media fetched this way is for personal viewing only.
 */
public final class BilibiliSession {
    private static final String API_BASE = "https://api.bilibili.com";
    private static final String REFERER = "https://www.bilibili.com";
    private static final long VIP_CACHE_MILLIS = 10 * 60_000L;

    private static volatile int cachedVipStatus;
    private static volatile long vipCacheAt;

    private BilibiliSession() {
    }

    public static boolean hasSession() {
        return !ClientConfig.bilibiliSessdata().isBlank();
    }

    /** Assembles the Cookie header used for authenticated Bilibili requests. */
    public static String cookieHeader() {
        Map<String, String> cookies = new LinkedHashMap<>();
        String sessdata = ClientConfig.bilibiliSessdata();
        String biliJct = ClientConfig.bilibiliBiliJct();
        String buvid3 = ClientConfig.bilibiliBuvid3();
        if (!sessdata.isBlank()) cookies.put("SESSDATA", sessdata);
        if (!biliJct.isBlank()) cookies.put("bili_jct", biliJct);
        if (!buvid3.isBlank()) cookies.put("buvid3", buvid3);
        for (String part : ClientConfig.bilibiliBrowserCookies().split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && !pair[0].isBlank()) cookies.putIfAbsent(pair[0], pair[1]);
        }
        return cookies.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
    }

    public static void logout() {
        cachedVipStatus = 0;
        vipCacheAt = 0L;
        BilibiliResolver.clearCache();
        ClientConfig.clearBilibiliSession();
        ClientNetworkProjectorStreams.refreshBilibiliMemberSources();
    }

    static void onLoginChanged() {
        cachedVipStatus = 0;
        vipCacheAt = 0L;
        BilibiliResolver.clearCache();
        ClientNetworkProjectorStreams.refreshBilibiliMemberSources();
    }

    public static void onMemberQualityChanged() {
        BilibiliResolver.clearCache();
        ClientNetworkProjectorStreams.refreshBilibiliMemberSources();
    }

    /** Returns the last known VIP status without performing a network request. */
    public static int vipStatusCached() {
        return cachedVipStatus;
    }

    /** Returns 1 when a VIP (大会员) membership is active on the logged-in account. */
    public static int vipStatus() {
        if (!hasSession()) return 0;
        long now = System.currentTimeMillis();
        if (vipCacheAt != 0L && now - vipCacheAt < VIP_CACHE_MILLIS) return cachedVipStatus;
        try {
            JsonObject data = requireData(VideoResolverHttp.getJson(
                    API_BASE + "/x/space/myinfo", REFERER, authHeaders()), "VIP status");
            int status = data.has("vipStatus") ? data.get("vipStatus").getAsInt() : 0;
            cachedVipStatus = status;
            vipCacheAt = now;
            return status;
        } catch (IOException | InterruptedException error) {
            CreateCinema.LOGGER.warn("Bilibili VIP status check failed; using last known status", error);
            if (vipCacheAt == 0L) vipCacheAt = now;
            return cachedVipStatus;
        }
    }

    /** Returns the local decode profile for this projector mode without sharing account state. */
    public static BilibiliPlaybackProfile playbackProfile(NetworkVideoQuality projectorQuality) {
        BilibiliMemberQuality memberQuality = ClientConfig.bilibiliMemberQuality();
        boolean vip = projectorQuality.isMemberQuality() && hasSession() && vipStatus() > 0;
        return BilibiliPlaybackProfile.forProjector(projectorQuality, vip, memberQuality);
    }

    /** Extra query parameter value for 4K requests. */
    public static String fourk(int qn) {
        return qn >= 120 ? "1" : "0";
    }

    /** Headers carrying the session cookie; empty when no session is active. */
    public static Map<String, String> authHeaders() {
        String cookie = cookieHeader();
        if (cookie.isEmpty()) return Map.of();
        Map<String, String> headers = new HashMap<>(2);
        headers.put("Cookie", cookie);
        return headers;
    }

    private static JsonObject requireData(JsonObject response, String operation) throws IOException {
        int code = response.has("code") ? response.get("code").getAsInt() : -1;
        if (code != 0 || !response.has("data")) {
            String message = response.has("message") ? response.get("message").getAsString() : "unknown error";
            throw new IOException("Bilibili " + operation + " failed (" + code + "): " + message);
        }
        return response.getAsJsonObject("data");
    }

}
