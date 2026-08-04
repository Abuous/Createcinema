package com.yfy.createcinema.client;

import java.io.IOException;
import java.net.URI;

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

    static IOException unsupported(IOException cause) {
        return new IOException("Youku did not expose a directly playable m3u8/mp4 stream. "
                + "The page metadata for this video is not free-to-access and the playback API is signed/browser-bound.", cause);
    }
}
