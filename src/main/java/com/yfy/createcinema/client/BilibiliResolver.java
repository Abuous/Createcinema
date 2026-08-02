package com.yfy.createcinema.client;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

public final class BilibiliResolver {
    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/138.0.0.0 Safari/537.36";
    private static final long CACHE_MILLIS = 2 * 60_000L;
    private static final ConcurrentHashMap<String, CachedMedia> CACHE = new ConcurrentHashMap<>();

    private BilibiliResolver() {
    }

    public static ResolvedMedia resolve(String input) throws IOException, InterruptedException {
        CachedMedia cached = CACHE.get(input);
        if (cached != null && System.currentTimeMillis() < cached.expiresAt) {
            if (HlsStreamCache.isHls(cached.media.videoUrl())) {
                HlsStreamCache.prepareAsync(cached.media.videoUrl(), cached.media.referer());
            }
            return cached.media;
        }
        ResolvedMedia media = resolveUncached(input);
        CACHE.put(input, new CachedMedia(media, System.currentTimeMillis() + CACHE_MILLIS));
        return media;
    }

    private static ResolvedMedia resolveUncached(String input) throws IOException, InterruptedException {
        if (BilibiliVideoResolver.canResolve(input)) return BilibiliVideoResolver.resolve(input);
        if (TencentVideoResolver.canResolve(input)) return TencentVideoResolver.resolve(input);
        try {
            return GenericVideoResolver.resolve(input);
        } catch (IOException error) {
            if (IqiyiVideoResolver.canResolve(input)) throw IqiyiVideoResolver.unsupported(error);
            if (YoukuVideoResolver.canResolve(input)) throw YoukuVideoResolver.unsupported(error);
            throw error;
        }
    }

    public record ResolvedMedia(String videoUrl, String audioUrl, String referer, double durationSeconds) {
    }

    private record CachedMedia(ResolvedMedia media, long expiresAt) {
    }
}
