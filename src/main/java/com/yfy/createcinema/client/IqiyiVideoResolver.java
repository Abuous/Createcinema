package com.yfy.createcinema.client;

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

    static IOException unsupported(IOException cause) {
        return new IOException("iQiyi did not expose a directly playable m3u8/mp4 stream. "
                + "This page uses the web player VRS/signature path and may require browser state, login, VIP, or DRM.", cause);
    }
}
