package com.yfy.createcinema.client.douyin;

import com.yfy.createcinema.client.video.VideoResolverHttp;
import com.yfy.createcinema.client.bilibili.BilibiliResolver;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yfy.createcinema.NetworkVideoQuality;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DouyinLiveResolver {
    private static final Pattern WEB_URL = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEB_RID = Pattern.compile("[A-Za-z0-9_-]{2,64}");
    private static final java.util.Set<String> NON_ROOM_PATHS = java.util.Set.of(
            "follow", "category", "rank", "search", "activity", "download", "hot");

    private DouyinLiveResolver() {
    }

    public static boolean canResolve(String input) {
        return webRid(input) != null;
    }

    public static BilibiliResolver.ResolvedMedia resolve(String input, NetworkVideoQuality quality)
            throws IOException, InterruptedException {
        String rid = webRid(input);
        if (rid == null) throw new IOException("Douyin Live URL has no web room id");
        JsonObject response = DouyinAuthenticatedApi.liveRoom(rid);
        JsonObject data = object(response, "data");
        JsonArray rooms = array(data, "data");
        if (rooms == null || rooms.isEmpty() || !rooms.get(0).isJsonObject())
            throw new IOException("Douyin Live room metadata is unavailable");
        JsonObject room = rooms.get(0).getAsJsonObject();
        if (integer(room, "status") != 2) throw new IOException("Douyin Live room is not currently live");
        LiveCandidate selected = selectStream(object(room, "stream_url"), quality);
        String referer = "https://live.douyin.com/" + rid;
        return new BilibiliResolver.ResolvedMedia(selected.url, selected.url, referer, 0.0, true);
    }

    private static LiveCandidate selectStream(JsonObject streamUrl, NetworkVideoQuality quality) throws IOException {
        if (streamUrl == null) throw new IOException("Douyin Live returned no stream data");
        List<LiveCandidate> candidates = new ArrayList<>();
        JsonObject sdk = object(streamUrl, "live_core_sdk_data");
        JsonObject pullData = object(sdk, "pull_data");
        String streamData = string(pullData, "stream_data");
        if (!streamData.isBlank()) {
            try {
                JsonObject decoded = JsonParser.parseString(streamData).getAsJsonObject();
                JsonObject ladder = object(decoded, "data");
                if (ladder != null) {
                    for (var entry : ladder.entrySet()) addSdkCandidate(candidates, entry.getKey(), entry.getValue());
                }
            } catch (RuntimeException ignored) {
            }
        }
        addFlatCandidates(candidates, object(streamUrl, "flv_pull_url"));
        if (candidates.isEmpty()) throw new IOException("Douyin Live returned no compatible FLV stream");

        List<LiveCandidate> avc = candidates.stream().filter(LiveCandidate::avc).toList();
        if (!avc.isEmpty()) candidates = avc;

        int targetLong = Math.max(quality.maxWidth(), quality.maxHeight());
        List<LiveCandidate> fitting = candidates.stream()
                .filter(candidate -> candidate.longEdge > 0 && candidate.longEdge <= targetLong).toList();
        List<LiveCandidate> pool = fitting.isEmpty() ? candidates : fitting;
        LiveCandidate selected = pool.stream().max(Comparator.comparingLong(LiveCandidate::score)).orElseThrow();
        com.yfy.createcinema.CreateCinema.LOGGER.debug("Selected Douyin Live {} stream (codec={}, longEdge={})",
                selected.key, selected.codec, selected.longEdge);
        return selected;
    }

    private static void addSdkCandidate(List<LiveCandidate> candidates, String key, JsonElement value) {
        if (!value.isJsonObject()) return;
        JsonObject main = object(value.getAsJsonObject(), "main");
        String flv = string(main, "flv");
        if (!VideoResolverHttp.isWebUrl(flv)) return;
        int longEdge = 0;
        int bitrate = 0;
        String codec = "";
        String parameters = string(main, "sdk_params");
        if (!parameters.isBlank()) {
            try {
                JsonObject params = JsonParser.parseString(parameters).getAsJsonObject();
                String resolution = string(params, "resolution");
                for (String part : resolution.toLowerCase(Locale.ROOT).split("x")) {
                    try { longEdge = Math.max(longEdge, Integer.parseInt(part)); } catch (NumberFormatException ignored) { }
                }
                bitrate = integer(params, "vbitrate");
                codec = string(params, "vcodec").toLowerCase(Locale.ROOT);
                if (codec.isBlank()) codec = string(params, "codec").toLowerCase(Locale.ROOT);
            } catch (RuntimeException ignored) {
            }
        }
        if (codec.contains("hevc") || codec.contains("h265") || codec.contains("av1") || codec.contains("bytevc")) {
            return;
        }
        candidates.add(new LiveCandidate(flv, longEdge, bitrate, key,
                codec.contains("avc") || codec.contains("h264"), codec));
    }

    private static void addFlatCandidates(List<LiveCandidate> candidates, JsonObject urls) {
        if (urls == null) return;
        for (var entry : urls.entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) continue;
            String url = entry.getValue().getAsString();
            if (VideoResolverHttp.isWebUrl(url)) {
                candidates.add(new LiveCandidate(url, qualityHint(entry.getKey()), 0, entry.getKey(), false, "unknown"));
            }
        }
    }

    private static int qualityHint(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.contains("origin") || lower.contains("uhd")) return 2160;
        if (lower.contains("hd")) return 1080;
        if (lower.contains("sd")) return 720;
        return 0;
    }

    private static String webRid(String input) {
        Matcher matcher = WEB_URL.matcher(input == null ? "" : input);
        String value = matcher.find() ? matcher.group() : input == null ? "" : input.trim();
        try {
            URI uri = URI.create(value.contains("://") ? value : "https://" + value);
            String host = uri.getHost();
            if (host == null || !host.equalsIgnoreCase("live.douyin.com")) return null;
            String path = uri.getPath();
            if (path == null) return null;
            for (String segment : path.split("/")) {
                if (WEB_RID.matcher(segment).matches()
                        && !NON_ROOM_PATHS.contains(segment.toLowerCase(Locale.ROOT))) return segment;
            }
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    private static JsonObject object(JsonObject parent, String name) {
        return parent != null && parent.has(name) && parent.get(name).isJsonObject() ? parent.getAsJsonObject(name) : null;
    }

    private static JsonArray array(JsonObject parent, String name) {
        return parent != null && parent.has(name) && parent.get(name).isJsonArray() ? parent.getAsJsonArray(name) : null;
    }

    private static String string(JsonObject parent, String name) {
        try { return parent != null && parent.has(name) ? parent.get(name).getAsString() : ""; }
        catch (RuntimeException ignored) { return ""; }
    }

    private static int integer(JsonObject parent, String name) {
        try { return parent != null && parent.has(name) ? parent.get(name).getAsInt() : 0; }
        catch (RuntimeException ignored) { return 0; }
    }

    private record LiveCandidate(String url, int longEdge, int bitrate, String key, boolean avc, String codec) {
        private long score() {
            return (long) longEdge * 1_000_000L + Math.max(0, bitrate) + (key.contains("origin") ? 1 : 0);
        }
    }
}
