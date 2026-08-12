package com.yfy.createcinema.client.bilibili;

import com.yfy.createcinema.client.douyin.DouyinBrowserBridge;
import com.yfy.createcinema.ClientConfig;
import com.yfy.createcinema.CreateCinema;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Browser-backed Bilibili login that keeps the request fingerprint in the browser profile. */
public final class BilibiliBrowserLogin {
    private BilibiliBrowserLogin() {
    }

    public static void open() throws IOException {
        DouyinBrowserBridge.openBilibiliLogin();
    }

    /** Saves a completed browser login and returns false while the user has not confirmed it yet. */
    public static boolean saveCookiesIfAvailable() throws IOException {
        String cookies = DouyinBrowserBridge.bilibiliCookieHeader();
        Map<String, String> values = parseCookies(cookies);
        String sessdata = values.get("SESSDATA");
        if (sessdata == null || sessdata.isBlank()) return false;
        ClientConfig.setBilibiliSessdata(sessdata);
        setIfPresent(values, "bili_jct", ClientConfig::setBilibiliBiliJct);
        setIfPresent(values, "buvid3", ClientConfig::setBilibiliBuvid3);
        ClientConfig.setBilibiliBrowserCookies(cookies);
        BilibiliSession.onLoginChanged();
        CreateCinema.LOGGER.info("Bilibili browser sign-in saved {} cookies", values.size());
        return true;
    }

    public static void hide() {
        DouyinBrowserBridge.hideBilibiliLogin();
    }

    private static Map<String, String> parseCookies(String header) {
        Map<String, String> values = new HashMap<>();
        if (header == null) return values;
        for (String part : header.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && !pair[0].isBlank()) values.put(pair[0], pair[1]);
        }
        return values;
    }

    private static void setIfPresent(Map<String, String> values, String name,
                                     java.util.function.Consumer<String> setter) {
        String value = values.get(name);
        if (value != null && !value.isBlank()) setter.accept(value);
    }
}
