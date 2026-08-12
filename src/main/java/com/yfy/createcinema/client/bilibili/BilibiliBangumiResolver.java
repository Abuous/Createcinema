package com.yfy.createcinema.client.bilibili;

import com.yfy.createcinema.client.video.VideoResolverHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.yfy.createcinema.NetworkVideoQuality;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves Bilibili PGC episodes through their authenticated DASH endpoint. */
public final class BilibiliBangumiResolver {
    private static final Pattern EPISODE_ID = Pattern.compile("(?i)\\bep(\\d+)");
    private static final String API_BASE = "https://api.bilibili.com";

    private BilibiliBangumiResolver() {
    }

    public static boolean canResolve(String input) {
        return input.toLowerCase(java.util.Locale.ROOT).contains("bilibili.com")
                && EPISODE_ID.matcher(input).find();
    }

    static BilibiliResolver.ResolvedMedia resolve(String input, NetworkVideoQuality quality)
            throws IOException, InterruptedException {
        Matcher matcher = EPISODE_ID.matcher(input);
        if (!matcher.find()) throw new IOException("Bilibili Bangumi URL has no episode id");
        long episodeId = Long.parseLong(matcher.group(1));
        String referer = "https://www.bilibili.com/bangumi/play/ep" + episodeId;
        Map<String, String> headers = BilibiliSession.authHeaders();

        JsonObject season = requireResult(VideoResolverHttp.getJson(
                API_BASE + "/pgc/view/web/season?ep_id=" + episodeId, referer, headers), "episode metadata");
        JsonObject episode = findEpisode(season, episodeId);
        if (episode == null) throw new IOException("Bilibili Bangumi episode was not found in its season");
        long aid = requiredLong(episode, "aid");
        long cid = requiredLong(episode, "cid");
        String bvid = requiredString(episode, "bvid");
        BilibiliPlaybackProfile profile = BilibiliSession.playbackProfile(quality);
        int qn = profile.requestedQn();
        String endpoint = API_BASE + "/pgc/player/web/playurl?avid=" + aid + "&bvid=" + bvid + "&cid=" + cid
                + "&qn=" + qn + "&ep_id=" + episodeId + "&fnver=0&fnval=4048&fourk="
                + BilibiliSession.fourk(qn);
        JsonObject play = requireResult(VideoResolverHttp.getJson(endpoint, referer, headers), "episode play URL");
        if (play.has("is_preview") && play.get("is_preview").getAsBoolean()) {
            throw new IOException("Bilibili episode requires an active VIP session");
        }
        JsonObject dash = play.has("dash") && play.get("dash").isJsonObject() ? play.getAsJsonObject("dash") : null;
        if (dash == null) throw new IOException("Bilibili episode returned no DASH media");
        String videoUrl = BilibiliVideoResolver.selectDashVideo(dash.getAsJsonArray("video"), profile);
        String audioUrl = BilibiliVideoResolver.selectDashAudio(dash.getAsJsonArray("audio"));
        double duration = play.has("timelength") ? play.get("timelength").getAsDouble() / 1000.0 : 0.0;
        return new BilibiliResolver.ResolvedMedia(videoUrl, audioUrl, referer, duration, false, headers);
    }

    private static JsonObject findEpisode(JsonObject season, long episodeId) {
        JsonObject episode = findEpisode(season.getAsJsonArray("episodes"), episodeId);
        if (episode != null) return episode;
        JsonElement positive = season.get("positive");
        if (positive != null && positive.isJsonArray()) {
            episode = findEpisode(positive.getAsJsonArray(), episodeId);
            if (episode != null) return episode;
        }
        JsonArray sections = season.getAsJsonArray("section");
        if (sections == null) return null;
        for (JsonElement section : sections) {
            if (!section.isJsonObject()) continue;
            episode = findEpisode(section.getAsJsonObject().getAsJsonArray("episodes"), episodeId);
            if (episode != null) return episode;
        }
        return null;
    }

    private static JsonObject findEpisode(JsonArray episodes, long episodeId) {
        if (episodes == null) return null;
        for (JsonElement element : episodes) {
            if (!element.isJsonObject()) continue;
            JsonObject episode = element.getAsJsonObject();
            if (episode.has("id") && episode.get("id").getAsLong() == episodeId) return episode;
        }
        return null;
    }

    private static JsonObject requireResult(JsonObject response, String operation) throws IOException {
        int code = response.has("code") ? response.get("code").getAsInt() : -1;
        if (code != 0 || !response.has("result") || !response.get("result").isJsonObject()) {
            String message = response.has("message") ? response.get("message").getAsString() : "unknown error";
            throw new IOException("Bilibili " + operation + " failed (" + code + "): " + message);
        }
        return response.getAsJsonObject("result");
    }

    private static long requiredLong(JsonObject value, String name) throws IOException {
        if (!value.has(name) || value.get(name).isJsonNull()) {
            throw new IOException("Bilibili Bangumi episode returned no " + name);
        }
        return value.get(name).getAsLong();
    }

    private static String requiredString(JsonObject value, String name) throws IOException {
        if (!value.has(name) || value.get(name).isJsonNull() || value.get(name).getAsString().isBlank()) {
            throw new IOException("Bilibili Bangumi episode returned no " + name);
        }
        return value.get(name).getAsString();
    }
}
