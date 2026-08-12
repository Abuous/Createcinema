package com.yfy.createcinema.client.video;

import com.yfy.createcinema.client.douyin.DouyinBrowserBridge;
import com.yfy.createcinema.client.browser.BrowserProvider;
import com.yfy.createcinema.client.bilibili.BilibiliResolver;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yfy.createcinema.NetworkVideoQuality;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BrowserPlaybackResolver {
    enum Provider {
        IQIYI(BrowserProvider.IQIYI, "iQiyi", "https://www.iqiyi.com", List.of(
                new CaptureTarget("www.iqiyi.com", List.of("/prelw/player/lw/lwplay/accelerator.js")),
                new CaptureTarget("mesh.if.iqiyi.com", List.of("/player/lw/lwplay/accelerator.js")),
                new CaptureTarget("cache.video.iqiyi.com", List.of("/dash", "/jp/dash", "/vps")),
                new CaptureTarget("retry-cache.video.iqiyi.com", List.of("/dash")),
                new CaptureTarget("meta.video.iqiyi.com", List.of("/vps", "/dash")),
                new CaptureTarget("iface2.iqiyi.com", List.of("/video/3.0/v_play"))));

        private final BrowserProvider browserProvider;
        private final String label;
        private final String origin;
        private final List<CaptureTarget> targets;

        Provider(BrowserProvider browserProvider, String label, String origin, List<CaptureTarget> targets) {
            this.browserProvider = browserProvider;
            this.label = label;
            this.origin = origin;
            this.targets = targets;
        }
    }

    private BrowserPlaybackResolver() {
    }

    static BilibiliResolver.ResolvedMedia resolve(String input, NetworkVideoQuality quality, Provider provider)
            throws IOException {
        IOException lastError = null;
        for (CaptureTarget target : provider.targets) {
            DouyinBrowserBridge.CapturedResponse capture;
            try {
                capture = DouyinBrowserBridge.captureProvider(provider.browserProvider, input, target.host,
                        target.paths);
            } catch (IOException error) {
                lastError = error;
                continue;
            }
            try {
                return resolveCaptured(capture.body(), input, quality, provider, capture.cookies());
            } catch (IOException error) {
                throw new IOException(provider.label + " playback response from " + target.host
                        + " could not be resolved: " + error.getMessage(), error);
            }
        }
        throw lastError == null ? new IOException(provider.label + " browser playback capture failed") : lastError;
    }

    static BilibiliResolver.ResolvedMedia resolveCaptured(byte[] body, String input, NetworkVideoQuality quality,
                                                            Provider provider, String cookies) throws IOException {
        JsonElement response = parseResponse(body, provider);
        validateResponse(response, provider);
        List<Candidate> candidates = new ArrayList<>();
        collect(response, "", 0, 0, 0, false, candidates, 0);
        List<Candidate> playable = candidates.stream().filter(candidate -> !candidate.drm).toList();
        if (playable.isEmpty()) {
            if (!candidates.isEmpty() || containsDrm(response, 0)) {
                throw new IOException(provider.label + " returned only DRM-protected streams");
            }
            throw new IOException(provider.label + " playback response contained no HLS or MP4 stream");
        }
        Candidate selected = select(playable, quality);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Origin", provider.origin);
        if (cookies != null && !cookies.isBlank()) headers.put("Cookie", cookies);
        return new BilibiliResolver.ResolvedMedia(selected.url, selected.url, input,
                findDuration(response, 0), false, headers);
    }

    static List<BilibiliResolver.PlaylistEntry> playlist(String input, Provider provider) {
        return List.of(new BilibiliResolver.PlaylistEntry(input, provider.label));
    }

    private static JsonElement parseResponse(byte[] body, Provider provider) throws IOException {
        String text = new String(body, StandardCharsets.UTF_8).trim();
        if (provider == Provider.IQIYI && text.contains("window.QiyiPlayerProphetData")) {
            int marker = text.indexOf("window.QiyiPlayerProphetData");
            int start = text.indexOf('{', marker);
            if (start >= 0) text = balancedObject(text, start);
        }
        int object = text.indexOf('{');
        int array = text.indexOf('[');
        int start = object < 0 ? array : array < 0 ? object : Math.min(object, array);
        int end = Math.max(text.lastIndexOf('}'), text.lastIndexOf(']'));
        if (start < 0 || end < start) throw new IOException("Browser playback response was not JSON");
        try {
            JsonElement parsed = JsonParser.parseString(text.substring(start, end + 1));
            if (provider == Provider.IQIYI && parsed.isJsonObject() && parsed.getAsJsonObject().has("ev")) {
                String encoded = parsed.getAsJsonObject().get("ev").getAsString();
                StringBuilder decoded = new StringBuilder(encoded.length());
                for (int index = 0; index < encoded.length(); index++) {
                    decoded.append((char) (encoded.charAt(index) ^ 0x5a));
                }
                parsed = JsonParser.parseString(decoded.toString());
            }
            return parsed;
        } catch (RuntimeException error) {
            throw new IOException("Browser playback response contained invalid JSON", error);
        }
    }

    private static String balancedObject(String text, int start) throws IOException {
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int index = start; index < text.length(); index++) {
            char character = text.charAt(index);
            if (quoted) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') quoted = false;
            } else if (character == '"') {
                quoted = true;
            } else if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return text.substring(start, index + 1);
            }
        }
        throw new IOException("Browser playback response contained an incomplete JSON object");
    }

    private static void validateResponse(JsonElement response, Provider provider) throws IOException {
        if (!response.isJsonObject()) return;
        JsonObject root = response.getAsJsonObject();
        if (provider == Provider.IQIYI && root.has("code")
                && !"A00000".equalsIgnoreCase(string(root, "code"))) {
            throw new IOException("iQiyi browser playback API returned " + string(root, "code"));
        }
        return;
    }

    private static void collect(JsonElement element, String key, int inheritedWidth, int inheritedHeight,
                                int inheritedBitrate, boolean inheritedDrm, List<Candidate> output, int depth) {
        if (element == null || element.isJsonNull() || depth > 16) return;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            int width = positive(object, "width", "w", "screen_width");
            int height = positive(object, "height", "h", "screen_height");
            int bitrate = positive(object, "bitrate", "bit_rate", "vbitrate", "bandwidth");
            if ((width <= 0 || height <= 0) && object.has("scrsz")) {
                String[] dimensions = string(object, "scrsz").toLowerCase(Locale.ROOT).split("x", 2);
                if (dimensions.length == 2) {
                    try {
                        width = Integer.parseInt(dimensions[0]);
                        height = Integer.parseInt(dimensions[1]);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (width <= 0) width = inheritedWidth;
            if (height <= 0) height = inheritedHeight;
            if (bitrate <= 0) bitrate = inheritedBitrate;
            int hintedHeight = qualityHeight(object);
            if (height <= 0 && hintedHeight > 0) height = hintedHeight;
            boolean drm = objectDrm(object);
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                collect(entry.getValue(), entry.getKey(), width, height, bitrate, drm, output, depth + 1);
            }
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collect(child, key, inheritedWidth, inheritedHeight, inheritedBitrate, inheritedDrm, output,
                        depth + 1);
            }
            return;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) return;
        String value = element.getAsString().trim();
        if ((value.startsWith("{") || value.startsWith("[")) && value.length() < 16 * 1024 * 1024) {
            try {
                collect(JsonParser.parseString(value), key, inheritedWidth, inheritedHeight, inheritedBitrate,
                        inheritedDrm, output, depth + 1);
            } catch (RuntimeException ignored) {
            }
        }
        String url = normalizeUrl(value);
        if (!isMediaUrl(url, key)) return;
        output.add(new Candidate(url, inheritedWidth, inheritedHeight, inheritedBitrate, inheritedDrm,
                mediaScore(url, key)));
    }

    private static Candidate select(List<Candidate> candidates, NetworkVideoQuality quality) {
        int targetLong = Math.max(quality.maxWidth(), quality.maxHeight());
        int targetShort = Math.min(quality.maxWidth(), quality.maxHeight());
        List<Candidate> fitting = candidates.stream().filter(candidate -> {
            int longEdge = Math.max(candidate.width, candidate.height);
            int shortEdge = Math.min(candidate.width, candidate.height);
            return longEdge > 0 && longEdge <= targetLong && (shortEdge <= 0 || shortEdge <= targetShort);
        }).toList();
        List<Candidate> pool = fitting.isEmpty() ? candidates : fitting;
        return pool.stream().max(Comparator.comparingLong(Candidate::score)).orElseThrow();
    }

    private static String normalizeUrl(String raw) {
        String value = raw.replace("\\/", "/").replace("&amp;", "&").trim();
        try {
            String decoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
            if (VideoResolverHttp.isWebUrl(decoded)) value = decoded;
        } catch (IllegalArgumentException ignored) {
        }
        if (value.startsWith("//")) value = "https:" + value;
        return VideoResolverHttp.isWebUrl(value) ? value : "";
    }

    private static boolean isMediaUrl(String url, String key) {
        if (url.isBlank()) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        String keyLower = key.toLowerCase(Locale.ROOT);
        return lower.contains(".m3u8") || lower.matches(".*\\.(mp4|m4v)(?:$|[?#].*)")
                || keyLower.contains("m3u8") || keyLower.equals("m3u") || keyLower.equals("m3utx")
                || keyLower.equals("ml") || keyLower.contains("play_url") || keyLower.contains("playurl");
    }

    private static int mediaScore(String url, String key) {
        String lower = url.toLowerCase(Locale.ROOT);
        String keyLower = key.toLowerCase(Locale.ROOT);
        int score = lower.contains(".m3u8") ? 500 : lower.contains(".mp4") ? 300 : 100;
        if (keyLower.contains("m3u8") || keyLower.equals("m3u") || keyLower.equals("m3utx")) score += 200;
        if (keyLower.contains("preview") || keyLower.contains("ad")) score -= 500;
        return score;
    }

    private static int qualityHeight(JsonObject object) {
        String stream = string(object, "stream_type").toLowerCase(Locale.ROOT);
        if (stream.contains("mp4hd3") || stream.contains("4k")) return 2160;
        if (stream.contains("mp4hd2") || stream.contains("1080")) return 1080;
        if (stream.contains("mp4hd") || stream.contains("720")) return 720;
        int bid = positive(object, "bid", "vd");
        if (bid >= 600) return 2160;
        if (bid >= 500) return 1080;
        if (bid >= 300) return 720;
        if (bid >= 200) return 480;
        if (bid > 0) return 360;
        return 0;
    }

    private static boolean objectDrm(JsonObject object) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (!(key.contains("drm") || key.contains("widevine") || key.contains("fairplay")
                    || key.contains("license") || key.contains("cenc") || key.contains("keyid"))) continue;
            JsonElement value = entry.getValue();
            if (value.isJsonNull()) continue;
            if (value.isJsonPrimitive()) {
                String text = value.getAsString().trim().toLowerCase(Locale.ROOT);
                if (text.isBlank() || text.equals("0") || text.equals("false") || text.equals("none")) continue;
                if (key.equals("drmtype")) {
                    try {
                        if (Integer.parseInt(text) <= 1) continue;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            return true;
        }
        return false;
    }

    private static boolean containsDrm(JsonElement element, int depth) {
        if (element == null || depth > 16) return false;
        if (element.isJsonObject()) {
            if (objectDrm(element.getAsJsonObject())) return true;
            for (JsonElement child : element.getAsJsonObject().asMap().values()) {
                if (containsDrm(child, depth + 1)) return true;
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) if (containsDrm(child, depth + 1)) return true;
        }
        return false;
    }

    private static double findDuration(JsonElement element, int depth) {
        if (element == null || depth > 12) return 0.0;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            for (String key : List.of("duration", "seconds", "video_duration", "total_duration")) {
                if (!object.has(key) || !object.get(key).isJsonPrimitive()) continue;
                try {
                    double value = object.get(key).getAsDouble();
                    if (value > 10_000) value /= 1_000.0;
                    if (value > 0) return value;
                } catch (RuntimeException ignored) {
                }
            }
            for (JsonElement child : object.asMap().values()) {
                double found = findDuration(child, depth + 1);
                if (found > 0) return found;
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                double found = findDuration(child, depth + 1);
                if (found > 0) return found;
            }
        }
        return 0.0;
    }

    private static int positive(JsonObject object, String... keys) {
        for (String key : keys) {
            if (!object.has(key)) continue;
            try {
                int value = object.get(key).getAsInt();
                if (value > 0) return value;
            } catch (RuntimeException ignored) {
            }
        }
        return 0;
    }

    private static String string(JsonObject object, String key) {
        try {
            return object.has(key) ? object.get(key).getAsString() : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private record CaptureTarget(String host, List<String> paths, String queryName) {
        private CaptureTarget(String host, List<String> paths) {
            this(host, paths, null);
        }
    }

    private record Candidate(String url, int width, int height, int bitrate, boolean drm, int mediaScore) {
        private long score() {
            return (long) mediaScore * 1_000_000_000L + (long) Math.max(width, height) * 1_000_000L
                    + Math.max(0, bitrate);
        }
    }
}
