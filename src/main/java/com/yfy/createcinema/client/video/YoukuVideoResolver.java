package com.yfy.createcinema.client.video;

import com.yfy.createcinema.client.bilibili.BilibiliResolver;
import com.yfy.createcinema.NetworkVideoQuality;

import java.io.IOException;
import java.net.URI;
import java.util.List;

public final class YoukuVideoResolver {
    private YoukuVideoResolver() {
    }

    public static boolean canResolve(String input) {
        try {
            String host = URI.create(input).getHost();
            return host != null && (host.equalsIgnoreCase("youku.com") || host.endsWith(".youku.com"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static BilibiliResolver.ResolvedMedia resolve(String input, NetworkVideoQuality quality) throws IOException {
        throw new IOException("Youku uses DRM encryption; video stream is unavailable");
    }

    public static BilibiliResolver.ResolvedPlaylist discoverPlaylist(String input) {
        return new BilibiliResolver.ResolvedPlaylist(
                List.of(new BilibiliResolver.PlaylistEntry(input, "Youku")), 0);
    }
}
