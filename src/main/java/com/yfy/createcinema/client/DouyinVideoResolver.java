package com.yfy.createcinema.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.NetworkVideoQuality;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DouyinVideoResolver {
    private static final String MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TD1A.220804.031; wv) "
            + "AppleWebKit/537.36 Mobile Safari/537.36";
    private static final Pattern VIDEO_ID = Pattern.compile("/(?:video|share/video|share/forward)/(\\d+)");
    private static final Pattern MODAL_ID = Pattern.compile("[?&]modal_id=(\\d+)");
    private static final Pattern LONG_VIDEO_ID = Pattern.compile("/lvdetail/(\\d+)");
    private static final Pattern WEB_URL = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE);
    private static final long CACHE_MILLIS = 2 * 60_000L;
    private static final int MAX_RELATED_PAGES = 4;
    private static final HttpClient HTTP = createHttpClient();
    private static final Map<String, CachedPage> CACHE = new ConcurrentHashMap<>();

    private DouyinVideoResolver() {
    }

    static boolean canResolve(String input) {
        try {
            String host = URI.create(inputUrl(input)).getHost();
            if (host == null) return false;
            String lower = host.toLowerCase(Locale.ROOT);
            String url = inputUrl(input);
            if (lower.equals("v.douyin.com")) return true;
            boolean douyinHost = lower.equals("douyin.com") || lower.endsWith(".douyin.com")
                    || lower.equals("iesdouyin.com") || lower.endsWith(".iesdouyin.com");
            return douyinHost && (videoId(url) != null || longVideoId(url) != null || isHomepageFeed(url));
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    static BilibiliResolver.ResolvedMedia resolve(String input, NetworkVideoQuality quality)
            throws IOException, InterruptedException {
        String sourceUrl = expandShortUrl(inputUrl(input));
        if (DouyinLiveResolver.canResolve(sourceUrl)) return DouyinLiveResolver.resolve(sourceUrl, quality);
        String longVideoId = longVideoId(sourceUrl);
        if (longVideoId != null) return resolveLongVideo(longVideoId, quality);
        if (isHomepageFeed(input)) {
            List<JsonObject> feed = authenticatedFeed();
            JsonObject item = feed.getFirst();
            VideoCandidate selected = selectVideo(item, quality);
            return new BilibiliResolver.ResolvedMedia(normalizePlayUrl(selected.url),
                    normalizePlayUrl(selected.url), "https://www.douyin.com/", duration(item));
        }
        PageData page = page(sourceUrl);
        VideoCandidate selected = selectVideo(page.item, quality);
        double duration = duration(page.item);
        return new BilibiliResolver.ResolvedMedia(normalizePlayUrl(selected.url),
                normalizePlayUrl(selected.url), page.pageUrl, duration);
    }

    static BilibiliResolver.ResolvedPlaylist discoverPlaylist(String input)
            throws IOException, InterruptedException {
        String sourceUrl = expandShortUrl(inputUrl(input));
        if (DouyinLiveResolver.canResolve(sourceUrl)) return BilibiliResolver.ResolvedPlaylist.single(sourceUrl);
        String longVideoId = longVideoId(sourceUrl);
        if (longVideoId != null) return BilibiliResolver.ResolvedPlaylist.single(sourceUrl);
        if (isHomepageFeed(input)) return playlist(authenticatedFeed(), null);
        PageData page = page(input);
        LinkedHashMap<String, BilibiliResolver.PlaylistEntry> entries = new LinkedHashMap<>();
        addEntry(entries, page.item);
        if (DouyinAuthenticatedApi.canRequestRecommendations()) {
            try {
                List<JsonObject> recommendations = new ArrayList<>(DouyinAuthenticatedApi.recommendations(page.id));
                Collections.shuffle(recommendations);
                String seedAuthor = string(object(page.item, "author"), "sec_uid");
                for (JsonObject item : recommendations) {
                    String author = string(object(item, "author"), "sec_uid");
                    if (!seedAuthor.isBlank() && seedAuthor.equals(author)) continue;
                    cacheAuthenticatedItem(item);
                    addEntry(entries, item);
                }
            } catch (IOException error) {
                CreateCinema.LOGGER.debug("Douyin recommendations unavailable for {}; playing the public video only: {}",
                        page.id, error.getMessage());
            }
        }
        return new BilibiliResolver.ResolvedPlaylist(new ArrayList<>(entries.values()), 0);
    }

    private static List<JsonObject> authenticatedFeed() throws IOException, InterruptedException {
        if (!DouyinAuthenticatedApi.hasAuthorization())
            throw new IOException("Douyin browser authorization is not configured");
        List<JsonObject> feed = new ArrayList<>(DouyinAuthenticatedApi.feed());
        feed.removeIf(item -> {
            String id = string(item, "aweme_id");
            JsonObject risk = object(item, "risk_infos");
            return id.isBlank() || object(item, "video") == null
                    || risk != null && integer(risk, "reflow_unplayable") != 0;
        });
        if (feed.isEmpty()) throw new IOException("Douyin recommendation feed returned no playable videos");
        Collections.shuffle(feed);
        feed.forEach(DouyinVideoResolver::cacheAuthenticatedItem);
        return feed;
    }

    private static BilibiliResolver.ResolvedPlaylist playlist(List<JsonObject> items, String firstId) {
        LinkedHashMap<String, BilibiliResolver.PlaylistEntry> entries = new LinkedHashMap<>();
        if (firstId != null) {
            items.stream().filter(item -> firstId.equals(string(item, "aweme_id"))).findFirst()
                    .ifPresent(item -> addEntry(entries, item));
        }
        items.forEach(item -> addEntry(entries, item));
        return new BilibiliResolver.ResolvedPlaylist(new ArrayList<>(entries.values()), 0);
    }

    private static void cacheAuthenticatedItem(JsonObject item) {
        String id = string(item, "aweme_id");
        if (id.isBlank()) return;
        PageData page = new PageData(id, "https://www.douyin.com/video/" + id, item, "", "", "");
        CACHE.put(id, new CachedPage(page, System.currentTimeMillis() + CACHE_MILLIS));
    }

    static boolean isHomepageFeed(String input) {
        try {
            URI uri = URI.create(inputUrl(input));
            String host = uri.getHost();
            if (host == null || !(host.equalsIgnoreCase("douyin.com")
                    || host.equalsIgnoreCase("www.douyin.com"))) return false;
            String path = uri.getPath();
            if (path == null) return false;
            return (path.isBlank() || path.equals("/")) && videoId(uri.toString()) == null
                    || path.startsWith("/jingxuan") && videoId(uri.toString()) == null;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    static boolean isLongVideo(String input) {
        return longVideoId(inputUrl(input)) != null;
    }

    private static PageData page(String input) throws IOException, InterruptedException {
        String sourceUrl = inputUrl(input);
        String id = videoId(sourceUrl);
        if (id == null) {
            HttpResponse<String> response = get(sourceUrl, "https://www.douyin.com/");
            id = videoId(response.uri().toString());
            if (id == null) id = videoId(response.body());
        }
        if (id == null) throw new IOException("Douyin URL has no public video id");
        CachedPage cached = CACHE.get(id);
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) return cached.page;
        PageData loaded = fetchPage(id);
        CACHE.put(id, new CachedPage(loaded, System.currentTimeMillis() + CACHE_MILLIS));
        return loaded;
    }

    private static PageData fetchPage(String id) throws IOException, InterruptedException {
        String pageUrl = "https://www.iesdouyin.com/share/video/" + id + "/";
        HttpResponse<String> response = get(pageUrl, "https://www.douyin.com/video/" + id);
        Document document = Jsoup.parse(response.body(), response.uri().toString());
        JsonObject router = routerData(document);
        JsonObject page = videoPage(router);
        JsonObject videoResponse = object(page, "videoInfoRes");
        JsonArray items = array(videoResponse, "item_list");
        if (items == null || items.isEmpty()) {
            throw new IOException("Douyin video is unavailable, private, or requires login");
        }
        JsonObject item = items.get(0).getAsJsonObject();
        JsonObject risk = object(item, "risk_infos");
        if (risk != null && integer(risk, "reflow_unplayable") != 0) {
            throw new IOException("Douyin video is not publicly playable");
        }
        Element context = document.selectFirst("#douyin_reflow_webId");
        Element token = document.selectFirst("#douyin_reflow_token");
        String webId = context == null ? string(page, "webId") : context.attr("webid");
        String userIp = context == null ? "" : context.attr("usercip");
        String xsToken = token == null ? "" : token.attr("xsstoken");
        return new PageData(id, response.uri().toString(), item, webId, userIp, xsToken);
    }

    private static JsonObject routerData(Document document) throws IOException {
        for (Element script : document.select("script")) {
            String data = script.data();
            int marker = data.indexOf("window._ROUTER_DATA = ");
            if (marker < 0) continue;
            String json = data.substring(marker + "window._ROUTER_DATA = ".length()).trim();
            if (json.endsWith(";")) json = json.substring(0, json.length() - 1);
            try {
                return JsonParser.parseString(json).getAsJsonObject();
            } catch (RuntimeException error) {
                throw new IOException("Douyin returned invalid page data", error);
            }
        }
        throw new IOException("Douyin did not expose public video data");
    }

    private static JsonObject videoPage(JsonObject router) throws IOException {
        JsonObject loader = object(router, "loaderData");
        if (loader != null) {
            for (Map.Entry<String, JsonElement> entry : loader.entrySet()) {
                if (entry.getValue().isJsonObject()
                        && entry.getValue().getAsJsonObject().has("videoInfoRes")) {
                    return entry.getValue().getAsJsonObject();
                }
            }
        }
        throw new IOException("Douyin page has no video response");
    }

    private static VideoCandidate selectVideo(JsonObject item, NetworkVideoQuality quality) throws IOException {
        JsonObject video = object(item, "video");
        if (video == null) throw new IOException("Douyin item has no video stream");
        List<VideoCandidate> candidates = new ArrayList<>();
        JsonArray bitRates = array(video, "bit_rate");
        if (bitRates != null) {
            for (JsonElement element : bitRates) {
                if (!element.isJsonObject()) continue;
                JsonObject bitrate = element.getAsJsonObject();
                if (integer(bitrate, "is_bytevc1") != 0) continue;
                addCandidate(candidates, object(bitrate, "play_addr"),
                        integer(bitrate, "width"), integer(bitrate, "height"), integer(bitrate, "bit_rate"));
            }
        }
        addCandidate(candidates, object(video, "play_addr"),
                integer(video, "width"), integer(video, "height"), 0);
        if (candidates.isEmpty()) throw new IOException("Douyin returned no public AVC video stream");

        int targetLong = Math.max(quality.maxWidth(), quality.maxHeight());
        int targetShort = Math.min(quality.maxWidth(), quality.maxHeight());
        List<VideoCandidate> fitting = candidates.stream().filter(candidate -> {
            if (candidate.width <= 0 || candidate.height <= 0) return false;
            return Math.max(candidate.width, candidate.height) <= targetLong
                    && Math.min(candidate.width, candidate.height) <= targetShort;
        }).toList();
        List<VideoCandidate> pool = fitting.isEmpty() ? candidates : fitting;
        return pool.stream().max(Comparator.comparingLong(VideoCandidate::score)).orElseThrow();
    }

    private static BilibiliResolver.ResolvedMedia resolveLongVideo(String id, NetworkVideoQuality quality)
            throws IOException, InterruptedException {
        String referer = "https://m.ixigua.com/video/" + id;
        JsonObject response = getJson("https://m.ixigua.com/i" + id + "/info/video/?aid=1768", referer);
        if (!booleanValue(response, "success")) throw new IOException("Douyin long video metadata is unavailable");
        JsonObject data = object(response, "data");
        String modelText = string(data, "video_model");
        if (modelText.isBlank()) {
            int playMode = integer(object(data, "attributes"), "play_mode");
            throw new IOException("Douyin long video has no anonymous public media (play mode " + playMode + ")");
        }
        JsonObject model;
        try {
            model = JsonParser.parseString(modelText).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("Douyin long video returned invalid media data", error);
        }
        LongVideoSources sources = selectLongVideoSources(model, quality);
        double duration = number(model, "video_duration");
        if (duration <= 0.0) duration = number(data, "duration");
        return new BilibiliResolver.ResolvedMedia(sources.videoUrl, sources.audioUrl, "", duration);
    }

    private static LongVideoSources selectLongVideoSources(JsonObject model, NetworkVideoQuality quality)
            throws IOException {
        List<VideoCandidate> progressive = mediaCandidates(model.get("video_list"));
        if (!progressive.isEmpty()) {
            VideoCandidate selected = selectCandidate(progressive, quality);
            return new LongVideoSources(selected.url, selected.url);
        }
        JsonObject dynamic = object(model, "dynamic_video");
        List<VideoCandidate> videos = mediaCandidates(dynamic == null ? null : dynamic.get("dynamic_video_list"));
        List<VideoCandidate> audios = mediaCandidates(dynamic == null ? null : dynamic.get("dynamic_audio_list"));
        if (videos.isEmpty()) throw new IOException("Douyin long video returned no public AVC stream");
        VideoCandidate selectedVideo = selectCandidate(videos, quality);
        String audioUrl = audios.stream().max(Comparator.comparingInt(VideoCandidate::bitrate))
                .map(VideoCandidate::url).orElse(selectedVideo.url);
        return new LongVideoSources(selectedVideo.url, audioUrl);
    }

    private static List<VideoCandidate> mediaCandidates(JsonElement container) {
        List<VideoCandidate> candidates = new ArrayList<>();
        if (container == null || container.isJsonNull()) return candidates;
        if (container.isJsonArray()) {
            for (JsonElement element : container.getAsJsonArray()) addLongCandidate(candidates, element);
        } else if (container.isJsonObject()) {
            JsonObject object = container.getAsJsonObject();
            if (object.has("main_url")) addLongCandidate(candidates, object);
            else for (JsonElement element : object.asMap().values()) addLongCandidate(candidates, element);
        }
        return candidates;
    }

    private static void addLongCandidate(List<VideoCandidate> candidates, JsonElement element) {
        if (element == null || !element.isJsonObject()) return;
        JsonObject media = element.getAsJsonObject();
        JsonObject metadata = object(media, "video_meta");
        String codec = string(media, "codec_type").toLowerCase(Locale.ROOT);
        if (codec.isBlank()) codec = string(metadata, "codec_type").toLowerCase(Locale.ROOT);
        if (codec.contains("h265") || codec.contains("hevc") || codec.contains("bytevc")) return;
        String url = decodeMediaUrl(string(media, "main_url"));
        if (url.isBlank()) {
            JsonArray backups = array(media, "backup_url");
            if (backups != null && !backups.isEmpty()) url = decodeMediaUrl(backups.get(0).getAsString());
            else url = decodeMediaUrl(string(media, "backup_url"));
        }
        if (!VideoResolverHttp.isWebUrl(url)) return;
        int width = firstPositive(integer(media, "vwidth"), integer(metadata, "vwidth"));
        int height = firstPositive(integer(media, "vheight"), integer(metadata, "vheight"));
        int bitrate = firstPositive(integer(media, "bitrate"), integer(metadata, "bitrate"));
        candidates.add(new VideoCandidate(url, width, height, bitrate));
    }

    private static VideoCandidate selectCandidate(List<VideoCandidate> candidates, NetworkVideoQuality quality) {
        int targetLong = Math.max(quality.maxWidth(), quality.maxHeight());
        int targetShort = Math.min(quality.maxWidth(), quality.maxHeight());
        List<VideoCandidate> fitting = candidates.stream().filter(candidate -> candidate.width > 0 && candidate.height > 0
                && Math.max(candidate.width, candidate.height) <= targetLong
                && Math.min(candidate.width, candidate.height) <= targetShort).toList();
        List<VideoCandidate> pool = fitting.isEmpty() ? candidates : fitting;
        return pool.stream().max(Comparator.comparingLong(VideoCandidate::score)).orElseThrow();
    }

    private static String decodeMediaUrl(String value) {
        if (VideoResolverHttp.isWebUrl(value)) return value;
        for (Base64.Decoder decoder : List.of(Base64.getDecoder(), Base64.getUrlDecoder())) {
            try {
                String decoded = new String(decoder.decode(value), StandardCharsets.UTF_8);
                if (VideoResolverHttp.isWebUrl(decoded)) return decoded;
            } catch (IllegalArgumentException ignored) {
            }
        }
        return "";
    }

    private static void addCandidate(List<VideoCandidate> candidates, JsonObject address,
                                     int fallbackWidth, int fallbackHeight, int bitrate) {
        if (address == null) return;
        String urlKey = string(address, "url_key").toLowerCase(Locale.ROOT);
        if (urlKey.contains("bytevc1") || urlKey.contains("h265") || urlKey.contains("bytevc2")) return;
        JsonArray urls = array(address, "url_list");
        if (urls == null) return;
        int width = integer(address, "width");
        int height = integer(address, "height");
        if (width <= 0) width = fallbackWidth;
        if (height <= 0) height = fallbackHeight;
        for (JsonElement url : urls) {
            if (url.isJsonPrimitive() && VideoResolverHttp.isWebUrl(url.getAsString())
                    && !url.getAsString().matches(".*[?&]video_id=(?:&|$).*$")) {
                candidates.add(new VideoCandidate(url.getAsString(), width, height, bitrate));
            }
        }
    }

    private static void collectRelated(PageData page,
                                       LinkedHashMap<String, BilibiliResolver.PlaylistEntry> entries)
            throws IOException, InterruptedException {
        JsonObject author = object(page.item, "author");
        String secUid = string(author, "sec_uid");
        if (secUid.isBlank() || page.webId.length() < 16 || page.xsToken.isBlank()) return;
        String cursor = "0";
        String reflowId = reflowId(page.webId, page.xsToken);
        for (int pageNumber = 0; pageNumber < MAX_RELATED_PAGES; pageNumber++) {
            String endpoint = "https://www.iesdouyin.com/web/api/v2/aweme/post/"
                    + "?reflow_source=reflow_page"
                    + "&web_id=" + VideoResolverHttp.urlEncode(page.webId)
                    + "&device_id=" + VideoResolverHttp.urlEncode(page.webId)
                    + "&user_cip=" + VideoResolverHttp.urlEncode(page.userIp)
                    + "&sec_uid=" + VideoResolverHttp.urlEncode(secUid)
                    + "&count=15&max_cursor=" + VideoResolverHttp.urlEncode(cursor)
                    + "&reflow_id=" + VideoResolverHttp.urlEncode(reflowId)
                    + "&item_ids=" + VideoResolverHttp.urlEncode(page.id);
            JsonObject response = getJson(endpoint, page.pageUrl);
            if (integer(response, "status_code") != 0) break;
            JsonArray awemes = array(response, "aweme_list");
            if (awemes == null || awemes.isEmpty()) break;
            for (JsonElement element : awemes) {
                if (element.isJsonObject()) addEntry(entries, element.getAsJsonObject());
            }
            if (integer(response, "has_more") == 0) break;
            String next = string(response, "max_cursor");
            if (next.isBlank() || next.equals(cursor)) break;
            cursor = next;
        }
    }

    private static void addEntry(LinkedHashMap<String, BilibiliResolver.PlaylistEntry> entries, JsonObject item) {
        String id = string(item, "aweme_id");
        JsonObject video = object(item, "video");
        JsonObject risk = object(item, "risk_infos");
        if (id.isBlank() || video == null || (risk != null && integer(risk, "reflow_unplayable") != 0)) return;
        entries.putIfAbsent(id, new BilibiliResolver.PlaylistEntry(
                "https://www.douyin.com/video/" + id, string(item, "desc")));
    }

    private static double duration(JsonObject item) {
        JsonObject video = object(item, "video");
        double milliseconds = number(video, "duration");
        if (milliseconds > 0.0) return milliseconds / 1000.0;
        return number(object(item, "music"), "duration");
    }

    private static String normalizePlayUrl(String input) {
        try {
            URI uri = URI.create(input.startsWith("//") ? "https:" + input : input);
            String path = uri.getPath();
            if (path != null && (path.contains("/aweme/v1/playwm/") || path.contains("/aweme/v1/play/"))) {
                return new URI("https", "www.douyin.com", "/aweme/v1/play/", uri.getQuery(), null).toString();
            }
            return uri.toString();
        } catch (Exception error) {
            return input;
        }
    }

    private static String videoId(String value) {
        if (value == null) return null;
        Matcher matcher = VIDEO_ID.matcher(value);
        if (matcher.find()) return matcher.group(1);
        matcher = MODAL_ID.matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String longVideoId(String value) {
        if (value == null) return null;
        Matcher matcher = LONG_VIDEO_ID.matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static boolean queryContains(URI uri, String name, String value) {
        String query = uri.getRawQuery();
        if (query == null) return false;
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair[0].equalsIgnoreCase(name) && pair.length == 2 && pair[1].equals(value)) return true;
        }
        return false;
    }

    private static String inputUrl(String input) {
        Matcher matcher = WEB_URL.matcher(input == null ? "" : input);
        if (!matcher.find()) return input == null ? "" : input.trim();
        String url = matcher.group();
        while (!url.isEmpty() && ",.;!?)\uFF09\u3011]}>\"'".indexOf(url.charAt(url.length() - 1)) >= 0) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static String expandShortUrl(String url) throws IOException, InterruptedException {
        try {
            String host = URI.create(url).getHost();
            if (host != null && host.equalsIgnoreCase("v.douyin.com")) {
                return get(url, "https://www.douyin.com/").uri().toString();
            }
        } catch (IllegalArgumentException ignored) {
        }
        return url;
    }

    private static String reflowId(String webId, String token) throws IOException {
        try {
            byte[] key = webId.substring(0, 16).getBytes(StandardCharsets.UTF_8);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key));
            return Base64.getEncoder().encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IOException("Douyin could not create a public recommendation token", error);
        }
    }

    private static HttpResponse<String> get(String url, String referer) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12))
                .header("User-Agent", MOBILE_USER_AGENT)
                .header("Accept", "text/html,application/json,*/*;q=0.8")
                .header("Referer", referer).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) throw new IOException("Douyin HTTP " + response.statusCode());
        return response;
    }

    private static JsonObject getJson(String url, String referer) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(12))
                .header("User-Agent", MOBILE_USER_AGENT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Agw-Js-Conv", "str")
                .header("Referer", referer).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) throw new IOException("Douyin HTTP " + response.statusCode());
        if (response.body().isBlank()) throw new IOException("Douyin returned an empty recommendation response");
        try {
            return JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("Douyin returned invalid recommendation data", error);
        }
    }

    private static HttpClient createHttpClient() {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        return HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.ALWAYS).build();
    }

    private static JsonObject object(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonObject()) return null;
        return parent.getAsJsonObject(name);
    }

    private static JsonArray array(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonArray()) return null;
        return parent.getAsJsonArray(name);
    }

    private static String string(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) return "";
        try {
            return object.get(name).getAsString();
        } catch (RuntimeException error) {
            return "";
        }
    }

    private static int integer(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) return 0;
        try {
            return object.get(name).getAsInt();
        } catch (RuntimeException error) {
            return 0;
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

    private static boolean booleanValue(JsonObject object, String name) {
        if (object == null || !object.has(name) || object.get(name).isJsonNull()) return false;
        try {
            return object.get(name).getAsBoolean();
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static int firstPositive(int first, int second) {
        return first > 0 ? first : Math.max(second, 0);
    }

    private record PageData(String id, String pageUrl, JsonObject item, String webId, String userIp, String xsToken) {
    }

    private record VideoCandidate(String url, int width, int height, int bitrate) {
        private long score() {
            return (long) Math.max(width, 0) * Math.max(height, 0) * 1_000_000L + Math.max(bitrate, 0);
        }
    }

    private record LongVideoSources(String videoUrl, String audioUrl) {
    }

    private record CachedPage(PageData page, long expiresAt) {
    }
}
