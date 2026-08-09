package com.yfy.createcinema.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yfy.createcinema.NetworkVideoQuality;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CctvVideoResolver {
    private static final Pattern VIDEO_ID = Pattern.compile("(?i)\\bVID[A-Z0-9]{12,}\\b");
    private static final Pattern GUID = Pattern.compile("(?i)\\bguid\\s*=\\s*['\"]([0-9a-f]{32})['\"]");

    private CctvVideoResolver() {
    }

    static boolean canResolve(String input) {
        try {
            String host = URI.create(input).getHost();
            return host != null && (host.equalsIgnoreCase("cctv.com") || host.endsWith(".cctv.com"))
                    && VIDEO_ID.matcher(input).find();
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    static BilibiliResolver.ResolvedMedia resolve(String input, NetworkVideoQuality quality)
            throws IOException, InterruptedException {
        String guid = guid(VideoResolverHttp.getText(input, input));
        if (guid == null) throw new IOException("CCTV video page has no playback id");
        String endpoint = "https://vdn.apps.cntv.cn/api/getHttpVideoInfo.do?pid=" + guid;
        JsonObject response = parseMetadata(VideoResolverHttp.getText(endpoint, input));
        if ("0".equals(string(response, "play")) || "1".equals(string(response, "is_protected"))) {
            throw new IOException("CCTV video stream is DRM-protected or unavailable");
        }
        String hls = string(response, "hls_url");
        if (!VideoResolverHttp.isWebUrl(hls)) throw new IOException("CCTV video API returned no playable HLS stream");
        double duration = number(object(response, "video"), "totalLength");
        HlsStreamCache.prepareAsync(hls, input);
        return new BilibiliResolver.ResolvedMedia(hls, hls, input, duration);
    }

    static BilibiliResolver.ResolvedPlaylist discoverPlaylist(String input) {
        return new BilibiliResolver.ResolvedPlaylist(
                List.of(new BilibiliResolver.PlaylistEntry(input, "CCTV")), 0);
    }

    private static String guid(String page) {
        Matcher matcher = GUID.matcher(page);
        return matcher.find() ? matcher.group(1).toLowerCase(java.util.Locale.ROOT) : null;
    }

    private static JsonObject parseMetadata(String text) throws IOException {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IOException("CCTV video API returned invalid metadata");
        try {
            return JsonParser.parseString(text.substring(start, end + 1)).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("CCTV video API returned invalid metadata", error);
        }
    }

    private static JsonObject object(JsonObject parent, String name) {
        return parent.has(name) && parent.get(name).isJsonObject() ? parent.getAsJsonObject(name) : null;
    }

    private static String string(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) return "";
        try {
            return object.get(name).getAsString();
        } catch (RuntimeException error) {
            return "";
        }
    }

    private static double number(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) return 0.0;
        try {
            return object.get(name).getAsDouble();
        } catch (RuntimeException error) {
            return 0.0;
        }
    }
}
