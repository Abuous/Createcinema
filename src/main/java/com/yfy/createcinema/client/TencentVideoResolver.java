package com.yfy.createcinema.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TencentVideoResolver {
    private static final Pattern COVER = Pattern.compile("^/x/cover(?:_seo)?/([^/]+)(?:/([^/]+))?\\.html$");
    private static final Pattern PAGE = Pattern.compile("^/x/page(?:_seo)?/([^/]+)\\.html$");

    private TencentVideoResolver() {
    }

    static boolean canResolve(String input) {
        try {
            String host = URI.create(input).getHost();
            return host != null && (host.equalsIgnoreCase("v.qq.com") || host.endsWith(".v.qq.com")
                    || host.equalsIgnoreCase("video.qq.com") || host.endsWith(".video.qq.com"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static BilibiliResolver.ResolvedMedia resolve(String input) throws IOException, InterruptedException {
        TencentIds ids = ids(input);
        if (ids.vid.isBlank()) throw new IOException("Tencent Video URL has no vid");
        String guid = "createcinema" + Integer.toUnsignedString(input.hashCode(), 36);
        String endpoint = "https://vv.video.qq.com/getinfo?otype=json&platform=11001&defnpayver=1"
                + "&appVer=3.5.57&defn=hd&fhdswitch=0&show1080p=1&isHLS=1&dtype=3&sdtfrom=v1010"
                + "&vid=" + VideoResolverHttp.urlEncode(ids.vid) + "&guid=" + guid;
        JsonObject response = parseJson(VideoResolverHttp.getText(endpoint, input));
        int error = response.has("em") ? response.get("em").getAsInt() : 0;
        if (error != 0) {
            String message = response.has("msg") ? response.get("msg").getAsString() : "error " + error;
            throw new IOException("Tencent Video getinfo failed: " + message);
        }
        JsonObject vl = response.getAsJsonObject("vl");
        JsonArray videos = vl == null ? null : vl.getAsJsonArray("vi");
        if (videos == null || videos.isEmpty()) throw new IOException("Tencent Video returned no playable video");
        JsonObject video = videos.get(0).getAsJsonObject();
        String m3u8 = hlsUrl(video);
        double duration = duration(response, video);
        HlsStreamCache.prepareAsync(m3u8, input);
        return new BilibiliResolver.ResolvedMedia(m3u8, m3u8, input, duration);
    }

    private static String hlsUrl(JsonObject video) throws IOException {
        JsonObject ul = video.getAsJsonObject("ul");
        JsonArray urls = ul == null ? null : ul.getAsJsonArray("ui");
        if (urls == null || urls.isEmpty()) throw new IOException("Tencent Video returned no media host");
        for (var element : urls) {
            JsonObject candidate = element.getAsJsonObject();
            JsonObject hls = candidate.getAsJsonObject("hls");
            if (hls == null || !hls.has("pt")) continue;
            String base = candidate.has("url") ? candidate.get("url").getAsString() : "";
            String path = hls.get("pt").getAsString();
            if (!base.isBlank() && !path.isBlank()) return base + path;
        }
        throw new IOException("Tencent Video returned no HLS playlist");
    }

    private static double duration(JsonObject response, JsonObject video) {
        if (response.has("preview") && response.get("preview").getAsDouble() > 0) return response.get("preview").getAsDouble();
        if (video.has("td")) return video.get("td").getAsDouble();
        if (video.has("totalduration")) return video.get("totalduration").getAsDouble() / 1000.0;
        return 0.0;
    }

    private static JsonObject parseJson(String body) throws IOException {
        String text = body.trim();
        if (text.startsWith("QZOutputJson=")) text = text.substring("QZOutputJson=".length());
        if (text.endsWith(";")) text = text.substring(0, text.length() - 1);
        try {
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Tencent Video returned invalid JSON", e);
        }
    }

    private static TencentIds ids(String input) {
        URI uri = URI.create(input);
        Matcher cover = COVER.matcher(uri.getPath());
        if (cover.matches()) return new TencentIds(cover.group(1), cover.group(2) == null ? "" : cover.group(2));
        Matcher page = PAGE.matcher(uri.getPath());
        if (page.matches()) return new TencentIds("", page.group(1));
        return new TencentIds("", "");
    }

    private record TencentIds(String cid, String vid) {
    }
}
