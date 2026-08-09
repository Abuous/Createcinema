package com.yfy.createcinema.client;

import com.yfy.createcinema.NetworkVideoQuality;

import java.io.IOException;
import java.net.URI;

final class IqiyiVideoResolver {
    private IqiyiVideoResolver() {
    }

    static boolean canResolve(String input) {
        try {
            String host = URI.create(input).getHost();
            return host != null && (host.equalsIgnoreCase("iqiyi.com") || host.endsWith(".iqiyi.com")
                    || host.equalsIgnoreCase("pps.tv") || host.endsWith(".pps.tv"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static BilibiliResolver.ResolvedMedia resolve(String input, NetworkVideoQuality quality) throws IOException {
        return BrowserPlaybackResolver.resolve(input, quality, BrowserPlaybackResolver.Provider.IQIYI);
    }

    static BilibiliResolver.ResolvedPlaylist discoverPlaylist(String input) {
        return new BilibiliResolver.ResolvedPlaylist(
                BrowserPlaybackResolver.playlist(input, BrowserPlaybackResolver.Provider.IQIYI), 0);
    }
}
