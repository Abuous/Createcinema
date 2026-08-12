package com.yfy.createcinema.client.browser;

import com.yfy.createcinema.ClientConfig;

import java.util.List;

public enum BrowserProvider {
    DOUYIN("douyin", "https://www.douyin.com/?recommend=1", "https://www.douyin.com/",
            List.of("sessionid", "sessionid_ss", "sid_tt")),
    IQIYI("iqiyi", "https://www.iqiyi.com/", "https://www.iqiyi.com/",
            List.of("p00001", "p00003"));

    private final String id;
    private final String homeUrl;
    private final String cookieUrl;
    private final List<String> loginCookieNames;

    BrowserProvider(String id, String homeUrl, String cookieUrl, List<String> loginCookieNames) {
        this.id = id;
        this.homeUrl = homeUrl;
        this.cookieUrl = cookieUrl;
        this.loginCookieNames = loginCookieNames;
    }

    public String id() {
        return id;
    }

    public static BrowserProvider byId(String id) {
        for (BrowserProvider provider : values()) if (provider.id.equalsIgnoreCase(id)) return provider;
        return DOUYIN;
    }

    public String homeUrl() {
        return homeUrl;
    }

    public String cookieUrl() {
        return cookieUrl;
    }

    public List<String> loginCookieNames() {
        return loginCookieNames;
    }

    public String translationKey() {
        return "gui.createcinema.config_manager.provider." + id;
    }

    public boolean enabled() {
        return switch (this) {
            case DOUYIN -> ClientConfig.douyinBrowserAuthorization();
            case IQIYI -> ClientConfig.iqiyiBrowserAuthorization();
        };
    }

    public void setEnabled(boolean enabled) {
        switch (this) {
            case DOUYIN -> ClientConfig.setDouyinBrowserAuthorization(enabled);
            case IQIYI -> ClientConfig.setIqiyiBrowserAuthorization(enabled);
        }
    }
}
