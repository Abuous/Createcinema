package com.yfy.createcinema.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BilibiliVideoResolver {
    private static final Pattern BVID = Pattern.compile("(?i)(BV[0-9A-Za-z]{10})");

    private BilibiliVideoResolver() {
    }

    static boolean canResolve(String input) {
        return input.toLowerCase().contains("bilibili.com") && BVID.matcher(input).find();
    }

    static BilibiliResolver.ResolvedMedia resolve(String input) throws IOException, InterruptedException {
        Matcher matcher = BVID.matcher(input);
        if (!matcher.find()) throw new IOException("Bilibili URL has no BV id");
        String bvid = matcher.group(1);
        String referer = "https://www.bilibili.com/video/" + bvid + "/";
        JsonObject view = VideoResolverHttp.getJson("https://api.bilibili.com/x/web-interface/view?bvid=" + bvid, referer);
        JsonObject data = requireData(view, "video metadata");
        JsonArray pages = data.getAsJsonArray("pages");
        if (pages == null || pages.isEmpty()) throw new IOException("Bilibili video has no playable page");
        long cid = pages.get(0).getAsJsonObject().get("cid").getAsLong();
        String endpoint = "https://api.bilibili.com/x/player/playurl?bvid=" + bvid + "&cid=" + cid
                + "&qn=32&fnver=0&fnval=4048&fourk=0&try_look=1";
        JsonObject play = requireData(VideoResolverHttp.getJson(endpoint, referer), "play URL");
        JsonObject dash = play.getAsJsonObject("dash");
        if (dash == null) throw new IOException("Bilibili returned no DASH media");
        String videoUrl = selectDashVideo(dash.getAsJsonArray("video"));
        String audioUrl = selectDashAudio(dash.getAsJsonArray("audio"));
        double duration = play.has("timelength") ? play.get("timelength").getAsDouble() / 1000.0 : 0.0;
        return new BilibiliResolver.ResolvedMedia(videoUrl, audioUrl, referer, duration);
    }

    private static String selectDashVideo(JsonArray streams) throws IOException {
        if (streams == null) throw new IOException("Bilibili returned no video stream");
        JsonObject fallback = null;
        for (var element : streams) {
            JsonObject stream = element.getAsJsonObject();
            if (!stream.get("codecs").getAsString().startsWith("avc1")) continue;
            if (fallback == null) fallback = stream;
            if (stream.get("id").getAsInt() == 32) return dashUrl(stream);
        }
        if (fallback == null) throw new IOException("Bilibili returned no AVC video stream");
        return dashUrl(fallback);
    }

    private static String selectDashAudio(JsonArray streams) throws IOException {
        if (streams == null || streams.isEmpty()) throw new IOException("Bilibili returned no audio stream");
        JsonObject selected = streams.get(0).getAsJsonObject();
        for (var element : streams) {
            JsonObject candidate = element.getAsJsonObject();
            if (candidate.get("bandwidth").getAsInt() > selected.get("bandwidth").getAsInt()) selected = candidate;
        }
        return dashUrl(selected);
    }

    private static String dashUrl(JsonObject stream) throws IOException {
        if (stream.has("baseUrl")) return stream.get("baseUrl").getAsString();
        if (stream.has("base_url")) return stream.get("base_url").getAsString();
        throw new IOException("Bilibili DASH stream has no URL");
    }

    private static JsonObject requireData(JsonObject response, String operation) throws IOException {
        int code = response.has("code") ? response.get("code").getAsInt() : -1;
        if (code != 0 || !response.has("data")) {
            String message = response.has("message") ? response.get("message").getAsString() : "unknown error";
            throw new IOException("Bilibili " + operation + " failed: " + message);
        }
        return response.getAsJsonObject("data");
    }
}
