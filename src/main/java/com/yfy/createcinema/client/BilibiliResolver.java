package com.yfy.createcinema.client;

import com.yfy.createcinema.NetworkVideoQuality;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class BilibiliResolver {
    public static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/138.0.0.0 Safari/537.36";
    private static final long CACHE_MILLIS = 2 * 60_000L;
    private static final ConcurrentHashMap<String, CachedMedia> CACHE = new ConcurrentHashMap<>();

    private BilibiliResolver() {
    }

    public static ResolvedMedia resolve(String input) throws IOException, InterruptedException {
        return resolve(input, NetworkVideoQuality.HIGH);
    }

    public static ResolvedMedia resolve(String input, NetworkVideoQuality quality) throws IOException, InterruptedException {
        if (isLive(input)) return resolveUncached(input, quality);
        String cacheKey = quality.id() + "\n" + input;
        CachedMedia cached = CACHE.get(cacheKey);
        if (cached != null && System.currentTimeMillis() < cached.expiresAt) {
            if (HlsStreamCache.isHls(cached.media.videoUrl())) {
                HlsStreamCache.prepareAsync(cached.media.videoUrl(), cached.media.referer());
            }
            return cached.media;
        }
        ResolvedMedia media = resolveUncached(input, quality);
        CACHE.put(cacheKey, new CachedMedia(media, System.currentTimeMillis() + CACHE_MILLIS));
        return media;
    }

    public static ResolvedPlaylist discoverPlaylist(String input) throws IOException, InterruptedException {
        if (BilibiliLiveResolver.canResolve(input) || DouyinLiveResolver.canResolve(input))
            return ResolvedPlaylist.single(input);
        if (BilibiliVideoResolver.canResolve(input)) return BilibiliVideoResolver.discoverPlaylist(input);
        if (TencentVideoResolver.canResolve(input)) return TencentVideoResolver.discoverPlaylist(input);
        if (DouyinVideoResolver.canResolve(input)) return DouyinVideoResolver.discoverPlaylist(input);
        return ResolvedPlaylist.single(input);
    }

    private static ResolvedMedia resolveUncached(String input, NetworkVideoQuality quality)
            throws IOException, InterruptedException {
        if (BilibiliLiveResolver.canResolve(input)) return BilibiliLiveResolver.resolve(input, quality);
        if (DouyinLiveResolver.canResolve(input)) return DouyinLiveResolver.resolve(input, quality);
        if (BilibiliVideoResolver.canResolve(input)) return BilibiliVideoResolver.resolve(input, quality);
        if (TencentVideoResolver.canResolve(input)) return TencentVideoResolver.resolve(input, quality);
        if (DouyinVideoResolver.canResolve(input)) return DouyinVideoResolver.resolve(input, quality);
        try {
            return GenericVideoResolver.resolve(input);
        } catch (IOException error) {
            if (IqiyiVideoResolver.canResolve(input)) throw IqiyiVideoResolver.unsupported(error);
            if (YoukuVideoResolver.canResolve(input)) throw YoukuVideoResolver.unsupported(error);
            throw error;
        }
    }

    private static boolean isLive(String input) {
        return BilibiliLiveResolver.canResolve(input) || DouyinLiveResolver.canResolve(input);
    }

    public record ResolvedMedia(String videoUrl, String audioUrl, String referer, double durationSeconds, boolean live) {
        public ResolvedMedia(String videoUrl, String audioUrl, String referer, double durationSeconds) {
            this(videoUrl, audioUrl, referer, durationSeconds, false);
        }
    }

    public record PlaylistEntry(String url, String title) {
    }

    public record ResolvedPlaylist(List<PlaylistEntry> entries, int startIndex) {
        public ResolvedPlaylist {
            entries = List.copyOf(entries);
            if (entries.isEmpty()) throw new IllegalArgumentException("Playlist must contain at least one entry");
            startIndex = Math.max(0, Math.min(startIndex, entries.size() - 1));
        }

        public static ResolvedPlaylist single(String url) {
            return new ResolvedPlaylist(List.of(new PlaylistEntry(url, "")), 0);
        }
    }

    private record CachedMedia(ResolvedMedia media, long expiresAt) {
    }
}
