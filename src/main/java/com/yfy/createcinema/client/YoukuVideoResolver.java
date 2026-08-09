package com.yfy.createcinema.client;

import com.yfy.createcinema.NetworkVideoQuality;

import java.io.IOException;
import java.net.URI;
import java.util.List;

final class YoukuVideoResolver {
    private YoukuVideoResolver() {
    }

    static boolean canResolve(String input) {
        try {
            String host = URI.create(input).getHost();
            return host != null && (host.equalsIgnoreCase("youku.com") || host.endsWith(".youku.com"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static BilibiliResolver.ResolvedMedia resolve(String input, NetworkVideoQuality quality) throws IOException {
        throw new IOException("Youku uses DRM encryption; video stream is unavailable");
    }

    static BilibiliResolver.ResolvedPlaylist discoverPlaylist(String input) {
        return new BilibiliResolver.ResolvedPlaylist(
                List.of(new BilibiliResolver.PlaylistEntry(input, "Youku")), 0);
    }
}
