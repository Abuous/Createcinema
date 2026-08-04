package com.yfy.createcinema.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yfy.createcinema.NetworkVideoQuality;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TencentVideoResolver {
    private static final String PAGE_SERVICE = "https://pbaccess.video.qq.com/trpc.vector_layout.page_view.PageService/getPage"
            + "?video_appid=3000010&vversion_platform=2&vversion_name=8.5.96&vdevice_guid=";
    private static final Pattern COVER = Pattern.compile("^/x/cover(?:_seo)?/([^/]+)(?:/([^/]+))?\\.html$");
    private static final Pattern PAGE = Pattern.compile("^/x/page(?:_seo)?/([^/]+)\\.html$");
    private static final Pattern COVER_LINK = Pattern.compile("/x/cover(?:_seo)?/([^/]+)/([^/?]+)\\.html");

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
        return resolve(input, NetworkVideoQuality.HIGH);
    }

    static BilibiliResolver.ResolvedMedia resolve(String input, NetworkVideoQuality quality)
            throws IOException, InterruptedException {
        TencentIds ids = ids(input);
        if (ids.vid.isBlank()) throw new IOException("Tencent Video URL has no vid");
        String guid = "createcinema" + Integer.toUnsignedString(input.hashCode(), 36);
        String endpoint = "https://vv.video.qq.com/getinfo?otype=json&platform=11001&defnpayver=1"
                + "&appVer=3.5.57&defn=" + quality.tencentDefinition()
                + "&fhdswitch=0&show1080p=1&isHLS=1&dtype=3&sdtfrom=v1010"
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

    static BilibiliResolver.ResolvedPlaylist discoverPlaylist(String input) throws IOException, InterruptedException {
        TencentIds selected = ids(input);
        if (selected.cid.isBlank()) return BilibiliResolver.ResolvedPlaylist.single(input);
        Map<String, BilibiliResolver.PlaylistEntry> byVid;
        try {
            byVid = discoverFromPageService(input, selected);
        } catch (IOException error) {
            byVid = new LinkedHashMap<>();
        }
        if (byVid.isEmpty()) {
            try {
                discoverFromHtml(input, selected.cid, byVid);
            } catch (IOException ignored) {
            }
        }
        if (byVid.isEmpty()) return BilibiliResolver.ResolvedPlaylist.single(input);
        List<BilibiliResolver.PlaylistEntry> entries = new ArrayList<>(byVid.values());
        int startIndex = 0;
        if (!selected.vid.isBlank()) {
            for (int index = 0; index < entries.size(); index++) {
                if (ids(entries.get(index).url()).vid.equals(selected.vid)) {
                    startIndex = index;
                    break;
                }
            }
        }
        return new BilibiliResolver.ResolvedPlaylist(entries, startIndex);
    }

    private static Map<String, BilibiliResolver.PlaylistEntry> discoverFromPageService(
            String input, TencentIds selected) throws IOException, InterruptedException {
        String guid = String.format("%016x", Integer.toUnsignedLong(selected.cid.hashCode()));
        Map<String, BilibiliResolver.PlaylistEntry> byVid = new LinkedHashMap<>();
        JsonObject response = VideoResolverHttp.postJson(PAGE_SERVICE + guid, input,
                pageRequest(selected.cid, selected.vid, guid, "", ""));
        JsonObject module = episodeModule(response);
        List<String> tabContexts = tabContexts(module);
        String tabPageId = module == null ? "" : string(object(module, "params"), "page_id");
        for (int page = 0; module != null && page < 10; page++) {
            collectEpisodeCards(module, selected.cid, byVid);
            JsonObject params = object(module, "params");
            if (params == null || !"true".equalsIgnoreCase(string(params, "has_next"))) break;
            String context = string(params, "next_page_context");
            String pageId = string(params, "page_id");
            if (context.isBlank() || pageId.isBlank()) break;
            response = VideoResolverHttp.postJson(PAGE_SERVICE + guid, input,
                    pageRequest(selected.cid, selected.vid, guid, pageId, context));
            module = episodeModule(response);
        }
        for (String tabContext : tabContexts) {
            response = VideoResolverHttp.postJson(PAGE_SERVICE + guid, input,
                    pageRequest(selected.cid, selected.vid, guid, tabPageId, tabContext));
            module = episodeModule(response);
            for (int page = 0; module != null && page < 10; page++) {
                collectEpisodeCards(module, selected.cid, byVid);
                JsonObject params = object(module, "params");
                if (params == null || !"true".equalsIgnoreCase(string(params, "has_next"))) break;
                String context = string(params, "next_page_context");
                String pageId = string(params, "page_id");
                if (context.isBlank() || pageId.isBlank()) break;
                response = VideoResolverHttp.postJson(PAGE_SERVICE + guid, input,
                        pageRequest(selected.cid, selected.vid, guid, pageId, context));
                module = episodeModule(response);
            }
        }
        return byVid;
    }

    private static List<String> tabContexts(JsonObject module) {
        JsonObject params = object(module, "params");
        String encoded = string(params, "tabs");
        if (encoded.isBlank()) return List.of();
        try {
            List<String> contexts = new ArrayList<>();
            for (JsonElement element : JsonParser.parseString(encoded).getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject tab = element.getAsJsonObject();
                if (tab.has("selected") && tab.get("selected").getAsBoolean()) continue;
                String context = string(tab, "page_context");
                if (!context.isBlank()) contexts.add(context);
            }
            return contexts;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static JsonObject pageRequest(String cid, String vid, String guid, String pageId, String context) {
        JsonObject pageParams = new JsonObject();
        pageParams.addProperty("ad_wechat_authorization_status", "0");
        pageParams.addProperty("ad_exp_ids", "");
        pageParams.addProperty("pc_sdk_version", "");
        pageParams.addProperty("pc_oaid", "");
        pageParams.addProperty("pc_device_info", "");
        pageParams.addProperty("support_pc_yyb_mobile_app_engine", "0");
        pageParams.addProperty("pc_wegame_version", "");
        pageParams.addProperty("req_from", context.isBlank() ? "web_vsite" : "");
        pageParams.addProperty("new_mark_label_enabled", "1");
        pageParams.addProperty("is_pc_new_detail_page", "0");
        pageParams.addProperty("is_from_web_flyflow", "1");
        pageParams.addProperty("cid", cid);
        pageParams.addProperty("vid", vid);

        JsonObject bypassParams = new JsonObject();
        bypassParams.addProperty("caller_id", "3000010");
        bypassParams.addProperty("platform_id", "2");
        JsonObject bypass = new JsonObject();
        bypass.add("params", bypassParams);
        bypass.addProperty("scene", context.isBlank() ? "desk_detail" : "operation");
        bypass.addProperty("app_version", "");
        bypass.addProperty("abtest_bypass_id", guid);

        JsonObject pageContext = new JsonObject();
        if (!context.isBlank()) {
            pageParams.addProperty("page_id", pageId);
            pageParams.addProperty("page_context", context);
            pageParams.addProperty("page_type", "detail_operation");
            bypassParams.addProperty("page_type", "detail_operation");
            bypassParams.addProperty("page_id", pageId);
            bypassParams.addProperty("data_mode", "default");
            bypassParams.addProperty("user_mode", "default");
            bypassParams.addProperty("new_mark_label_enabled", "1");
            pageContext.addProperty("latestPageContext", context);
        }

        JsonObject body = new JsonObject();
        body.add("page_params", pageParams);
        body.add("page_bypass_params", bypass);
        body.add("page_context", pageContext);
        return body;
    }

    private static JsonObject episodeModule(JsonObject response) {
        JsonObject data = object(response, "data");
        JsonArray cards = data == null ? null : array(data, "CardList");
        if (cards == null) return null;
        for (JsonElement element : cards) {
            if (!element.isJsonObject()) continue;
            JsonObject card = element.getAsJsonObject();
            if ("pc_web_episode_list".equals(string(card, "type"))) return card;
        }
        return null;
    }

    private static void collectEpisodeCards(JsonElement element, String selectedCid,
                                            Map<String, BilibiliResolver.PlaylistEntry> byVid) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) collectEpisodeCards(child, selectedCid, byVid);
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        JsonObject params = object(object, "params");
        if (params != null) {
            String vid = string(params, "vid");
            String cid = first(params, "cid", "last_cover", "last_feature_cover_id");
            String itemType = string(params, "card_render@item_type");
            if (!vid.isBlank() && (cid.isBlank() || cid.equals(selectedCid))
                    && !itemType.equals("28") && !itemType.equals("60")) {
                String title = first(params, "title", "_title", "title_new", "c_title_detail",
                        "_c_title_detail", "vname_title", "_column", "collection_title");
                String url = "https://v.qq.com/x/cover/" + selectedCid + "/" + vid + ".html";
                byVid.putIfAbsent(vid, new BilibiliResolver.PlaylistEntry(url,
                        title.isBlank() ? Integer.toString(byVid.size() + 1) : title));
            }
        }
        JsonObject children = object(object, "children_list");
        if (children != null) {
            children.entrySet().stream().sorted(Comparator.comparingInt(entry -> numericKey(entry.getKey())))
                    .forEach(entry -> collectEpisodeCards(entry.getValue(), selectedCid, byVid));
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!entry.getKey().equals("params") && !entry.getKey().equals("children_list")) {
                collectEpisodeCards(entry.getValue(), selectedCid, byVid);
            }
        }
    }

    private static int numericKey(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private static void discoverFromHtml(String input, String cid,
                                         Map<String, BilibiliResolver.PlaylistEntry> byVid)
            throws IOException, InterruptedException {
        Document document = Jsoup.parse(VideoResolverHttp.getText(input, input), input);
        for (Element link : document.select("a[href]")) {
            addEpisode(byVid, cid, link.absUrl("href"), link.text());
        }
        String unescaped = document.html().replace("\\/", "/");
        Matcher matcher = COVER_LINK.matcher(unescaped);
        while (matcher.find()) {
            if (!matcher.group(1).equals(cid)) continue;
            String url = "https://v.qq.com/x/cover/" + cid + "/" + matcher.group(2) + ".html";
            addEpisode(byVid, cid, url, "");
        }
    }

    private static JsonObject object(JsonObject parent, String name) {
        return parent != null && parent.has(name) && parent.get(name).isJsonObject()
                ? parent.getAsJsonObject(name) : null;
    }

    private static JsonArray array(JsonObject parent, String name) {
        return parent != null && parent.has(name) && parent.get(name).isJsonArray()
                ? parent.getAsJsonArray(name) : null;
    }

    private static String first(JsonObject object, String... names) {
        for (String name : names) {
            String value = string(object, name);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static String string(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) return "";
        try {
            return object.get(name).getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static void addEpisode(Map<String, BilibiliResolver.PlaylistEntry> byVid, String cid,
                                   String candidate, String title) {
        if (candidate == null || candidate.isBlank()) return;
        try {
            TencentIds ids = ids(candidate);
            if (!ids.cid.equals(cid) || ids.vid.isBlank()) return;
            byVid.putIfAbsent(ids.vid, new BilibiliResolver.PlaylistEntry(candidate,
                    title == null || title.isBlank() ? Integer.toString(byVid.size() + 1) : title.strip()));
        } catch (IllegalArgumentException ignored) {
        }
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
        String path = uri.getPath();
        if (path == null) return new TencentIds("", "");
        Matcher cover = COVER.matcher(path);
        if (cover.matches()) return new TencentIds(cover.group(1), cover.group(2) == null ? "" : cover.group(2));
        Matcher page = PAGE.matcher(path);
        if (page.matches()) return new TencentIds("", page.group(1));
        return new TencentIds("", "");
    }

    private record TencentIds(String cid, String vid) {
    }
}
