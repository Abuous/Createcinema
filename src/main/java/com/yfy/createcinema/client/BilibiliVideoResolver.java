package com.yfy.createcinema.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.yfy.createcinema.NetworkVideoQuality;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
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
        return resolve(input, NetworkVideoQuality.HIGH);
    }

    static BilibiliResolver.ResolvedMedia resolve(String input, NetworkVideoQuality quality)
            throws IOException, InterruptedException {
        Matcher matcher = BVID.matcher(input);
        if (!matcher.find()) throw new IOException("Bilibili URL has no BV id");
        String bvid = matcher.group(1);
        String referer = "https://www.bilibili.com/video/" + bvid + "/";
        JsonObject view = VideoResolverHttp.getJson("https://api.bilibili.com/x/web-interface/view?bvid=" + bvid, referer);
        JsonObject data = requireData(view, "video metadata");
        JsonArray pages = data.getAsJsonArray("pages");
        if (pages == null || pages.isEmpty()) throw new IOException("Bilibili video has no playable page");
        int pageIndex = pageIndex(input, pages.size());
        long cid = pages.get(pageIndex).getAsJsonObject().get("cid").getAsLong();
        String endpoint = "https://api.bilibili.com/x/player/playurl?bvid=" + bvid + "&cid=" + cid
                + "&qn=" + quality.bilibiliQn() + "&fnver=0&fnval=4048&fourk=0&try_look=1";
        JsonObject play = requireData(VideoResolverHttp.getJson(endpoint, referer), "play URL");
        JsonObject dash = play.getAsJsonObject("dash");
        if (dash == null) throw new IOException("Bilibili returned no DASH media");
        String videoUrl = selectDashVideo(dash.getAsJsonArray("video"), quality.bilibiliQn());
        String audioUrl = selectDashAudio(dash.getAsJsonArray("audio"));
        double duration = play.has("timelength") ? play.get("timelength").getAsDouble() / 1000.0 : 0.0;
        return new BilibiliResolver.ResolvedMedia(videoUrl, audioUrl, referer, duration);
    }

    static BilibiliResolver.ResolvedPlaylist discoverPlaylist(String input) throws IOException, InterruptedException {
        Matcher matcher = BVID.matcher(input);
        if (!matcher.find()) throw new IOException("Bilibili URL has no BV id");
        String bvid = matcher.group(1);
        String referer = "https://www.bilibili.com/video/" + bvid + "/";
        JsonObject data = requireData(VideoResolverHttp.getJson(
                "https://api.bilibili.com/x/web-interface/view?bvid=" + bvid, referer), "video metadata");
        BilibiliResolver.ResolvedPlaylist season = seasonPlaylist(data, bvid, input);
        if (season != null) return season;
        JsonArray pages = data.getAsJsonArray("pages");
        if (pages == null || pages.isEmpty()) throw new IOException("Bilibili video has no playable page");
        List<BilibiliResolver.PlaylistEntry> entries = new ArrayList<>(pages.size());
        for (int index = 0; index < pages.size(); index++) {
            JsonObject page = pages.get(index).getAsJsonObject();
            String title = page.has("part") ? page.get("part").getAsString() : "P" + (index + 1);
            entries.add(new BilibiliResolver.PlaylistEntry(referer + "?p=" + (index + 1), title));
        }
        return new BilibiliResolver.ResolvedPlaylist(entries, pageIndex(input, pages.size()));
    }

    private static BilibiliResolver.ResolvedPlaylist seasonPlaylist(JsonObject data, String selectedBvid, String input) {
        JsonObject season = data.has("ugc_season") && data.get("ugc_season").isJsonObject()
                ? data.getAsJsonObject("ugc_season") : null;
        JsonArray sections = season == null ? null : season.getAsJsonArray("sections");
        if (sections == null || sections.isEmpty()) return null;
        List<BilibiliResolver.PlaylistEntry> entries = new ArrayList<>();
        int startIndex = 0;
        int selectedPage = pageIndex(input, Integer.MAX_VALUE);
        for (var sectionElement : sections) {
            JsonObject section = sectionElement.getAsJsonObject();
            JsonArray episodes = section.getAsJsonArray("episodes");
            if (episodes == null) continue;
            for (var episodeElement : episodes) {
                JsonObject episode = episodeElement.getAsJsonObject();
                if (!episode.has("bvid")) continue;
                String episodeBvid = episode.get("bvid").getAsString();
                String title = episode.has("title") ? episode.get("title").getAsString() : "";
                JsonArray episodePages = episode.getAsJsonArray("pages");
                int pageCount = episodePages == null || episodePages.isEmpty() ? 1 : episodePages.size();
                for (int page = 0; page < pageCount; page++) {
                    String pageTitle = title;
                    if (pageCount > 1) {
                        JsonObject pageData = episodePages.get(page).getAsJsonObject();
                        String part = pageData.has("part") ? pageData.get("part").getAsString() : "P" + (page + 1);
                        pageTitle = title.isBlank() ? part : title + " - " + part;
                    }
                    if (episodeBvid.equalsIgnoreCase(selectedBvid) && page == selectedPage) startIndex = entries.size();
                    entries.add(new BilibiliResolver.PlaylistEntry(
                            "https://www.bilibili.com/video/" + episodeBvid + "/?p=" + (page + 1), pageTitle));
                }
            }
        }
        return entries.size() > 1 ? new BilibiliResolver.ResolvedPlaylist(entries, startIndex) : null;
    }

    private static int pageIndex(String input, int pageCount) {
        try {
            String query = URI.create(input).getQuery();
            if (query != null) {
                for (String part : query.split("&")) {
                    String[] pair = part.split("=", 2);
                    if (pair.length == 2 && pair[0].equalsIgnoreCase("p")) {
                        return Math.max(0, Math.min(Integer.parseInt(pair[1]) - 1, pageCount - 1));
                    }
                }
            }
        } catch (IllegalArgumentException ignored) {
        }
        return 0;
    }

    private static String selectDashVideo(JsonArray streams, int targetQuality) throws IOException {
        if (streams == null) throw new IOException("Bilibili returned no video stream");
        JsonObject selected = null;
        for (var element : streams) {
            JsonObject stream = element.getAsJsonObject();
            if (!stream.get("codecs").getAsString().startsWith("avc1")) continue;
            int quality = stream.get("id").getAsInt();
            if (quality <= targetQuality && (selected == null
                    || quality > selected.get("id").getAsInt())) selected = stream;
        }
        if (selected == null) {
            for (var element : streams) {
                JsonObject stream = element.getAsJsonObject();
                if (stream.get("codecs").getAsString().startsWith("avc1")) {
                    selected = stream;
                    break;
                }
            }
        }
        if (selected == null) throw new IOException("Bilibili returned no AVC video stream");
        return dashUrl(selected);
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
