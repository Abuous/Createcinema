package com.yfy.createcinema.client.bilibili;

import com.yfy.createcinema.client.video.VideoResolverHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.yfy.createcinema.NetworkVideoQuality;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class BilibiliLiveResolver {
    private static final Pattern NUMERIC = Pattern.compile("\\d+");

    private BilibiliLiveResolver() {
    }

    static boolean canResolve(String input) {
        return roomId(input) != null;
    }

    static BilibiliResolver.ResolvedMedia resolve(String input, NetworkVideoQuality quality)
            throws IOException, InterruptedException {
        String initialRoomId = roomId(input);
        if (initialRoomId == null) throw new IOException("Bilibili live URL has no room id");
        String initialReferer = "https://live.bilibili.com/" + initialRoomId;
        JsonObject init = requireData(VideoResolverHttp.getJson(
                "https://api.live.bilibili.com/room/v1/Room/room_init?id=" + initialRoomId,
                initialReferer), "live room metadata");
        long roomId = number(init, "room_id");
        if (roomId <= 0) throw new IOException("Bilibili live room metadata has no canonical room id");
        if (number(init, "live_status") != 1) throw new IOException("Bilibili live room is not currently live");

        String referer = "https://live.bilibili.com/" + roomId;
        int targetQn = liveQn(quality);
        String endpoint = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo"
                + "?room_id=" + roomId
                + "&protocol=0,1&format=0,1,2&codec=0,1"
                + "&qn=" + targetQn
                + "&platform=web&ptype=8";
        JsonObject play = requireData(VideoResolverHttp.getJson(endpoint, referer), "live play URL");
        LiveCandidate selected = selectStream(play, targetQn);
        return new BilibiliResolver.ResolvedMedia(selected.url, selected.url, referer, 0.0, true);
    }

    private static LiveCandidate selectStream(JsonObject play, int targetQn) throws IOException {
        JsonObject playUrlInfo = object(play, "playurl_info");
        JsonObject playUrl = object(playUrlInfo, "playurl");
        JsonArray streams = array(playUrl, "stream");
        if (streams == null || streams.isEmpty()) throw new IOException("Bilibili live returned no stream ladder");

        List<LiveCandidate> candidates = new ArrayList<>();
        for (JsonElement stream : streams) {
            if (stream.isJsonObject()) addStreamCandidates(candidates, stream.getAsJsonObject());
        }
        candidates.removeIf(candidate -> candidate.url.isBlank());
        if (candidates.isEmpty()) throw new IOException("Bilibili live returned no compatible anonymous FLV stream");

        Comparator<LiveCandidate> comparator = Comparator
                .comparingInt((LiveCandidate candidate) -> candidate.qn <= targetQn ? 1 : 0)
                .thenComparingInt(candidate -> candidate.qn <= targetQn ? candidate.qn : -candidate.qn)
                .thenComparingInt(candidate -> candidate.priority);
        return candidates.stream().max(comparator).orElseThrow();
    }

    private static void addStreamCandidates(List<LiveCandidate> candidates, JsonObject stream) {
        String protocolName = string(stream, "protocol_name").toLowerCase(Locale.ROOT);
        JsonArray formats = array(stream, "format");
        if (formats == null) return;
        for (JsonElement format : formats) {
            if (format.isJsonObject()) addFormatCandidates(candidates, protocolName, format.getAsJsonObject());
        }
    }

    private static void addFormatCandidates(List<LiveCandidate> candidates, String protocolName, JsonObject format) {
        String formatName = string(format, "format_name").toLowerCase(Locale.ROOT);
        if (!protocolName.equals("http_stream") || !formatName.equals("flv")) return;
        JsonArray codecs = array(format, "codec");
        if (codecs == null) return;
        for (JsonElement codec : codecs) {
            if (codec.isJsonObject()) addCodecCandidates(candidates, codec.getAsJsonObject());
        }
    }

    private static void addCodecCandidates(List<LiveCandidate> candidates, JsonObject codec) {
        String codecName = string(codec, "codec_name").toLowerCase(Locale.ROOT);
        if (codecName.contains("hevc") || codecName.contains("h265") || codecName.contains("av01")) return;
        JsonArray urlInfos = array(codec, "url_info");
        if (urlInfos == null) return;
        int qn = (int) number(codec, "current_qn");
        int priority = codecName.contains("avc") || codecName.contains("h264") ? 1 : 0;
        for (JsonElement urlInfo : urlInfos) {
            if (!urlInfo.isJsonObject()) continue;
            String url = playbackUrl(codec, urlInfo.getAsJsonObject());
            if (VideoResolverHttp.isWebUrl(url)) candidates.add(new LiveCandidate(url, qn, priority));
        }
    }

    private static String playbackUrl(JsonObject codec, JsonObject urlInfo) {
        String baseUrl = string(codec, "base_url");
        String extra = string(urlInfo, "extra");
        if (baseUrl.isBlank()) return "";
        if (VideoResolverHttp.isWebUrl(baseUrl)) return baseUrl + extra;
        String host = string(urlInfo, "host");
        if (host.isBlank()) return "";
        if (!host.endsWith("/") && !baseUrl.startsWith("/")) return host + "/" + baseUrl + extra;
        return host + baseUrl + extra;
    }

    private static int liveQn(NetworkVideoQuality quality) {
        return switch (quality) {
            case SMOOTH -> 80;
            case HIGH -> 150;
            case ULTRA -> 400;
            case MEMBER -> 400;
        };
    }

    private static String roomId(String input) {
        try {
            URI uri = URI.create(inputUrl(input));
            String host = uri.getHost();
            if (host == null) return null;
            String lowerHost = host.toLowerCase(Locale.ROOT);
            if (!lowerHost.equals("live.bilibili.com") && !lowerHost.endsWith(".live.bilibili.com")) return null;
            String path = uri.getPath();
            if (path != null) {
                for (String segment : path.split("/")) {
                    if (NUMERIC.matcher(segment).matches()) return segment;
                }
            }
            return queryRoomId(uri.getQuery());
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static String inputUrl(String input) {
        String trimmed = input.trim();
        return trimmed.contains("://") ? trimmed : "https://" + trimmed;
    }

    private static String queryRoomId(String query) {
        if (query == null || query.isBlank()) return null;
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && (pair[0].equalsIgnoreCase("room_id") || pair[0].equalsIgnoreCase("id"))
                    && NUMERIC.matcher(pair[1]).matches()) return pair[1];
        }
        return null;
    }

    private static JsonObject requireData(JsonObject response, String operation) throws IOException {
        int code = response.has("code") ? response.get("code").getAsInt() : -1;
        if (code != 0 || !response.has("data") || !response.get("data").isJsonObject()) {
            String message = response.has("message") ? response.get("message").getAsString() : "unknown error";
            throw new IOException("Bilibili " + operation + " failed: " + message);
        }
        return response.getAsJsonObject("data");
    }

    private static JsonObject object(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonObject()) return null;
        return parent.getAsJsonObject(name);
    }

    private static JsonArray array(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonArray()) return null;
        return parent.getAsJsonArray(name);
    }

    private static String string(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonPrimitive()) return "";
        return parent.get(name).getAsString();
    }

    private static long number(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonPrimitive()) return 0;
        try {
            return parent.get(name).getAsLong();
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private record LiveCandidate(String url, int qn, int priority) {
    }
}
