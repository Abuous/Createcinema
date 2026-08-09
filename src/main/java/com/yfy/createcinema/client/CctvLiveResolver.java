package com.yfy.createcinema.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yfy.createcinema.NetworkVideoQuality;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CctvLiveResolver {
    private static final Pattern CHANNEL_LINK = Pattern.compile(
            "(?i)https?://tv\\.cctv\\.com/live/([a-z0-9_-]+)/?");
    private static final Pattern CHANNEL_SEGMENT = Pattern.compile("(?i)[a-z0-9_-]{2,64}");
    private static final Pattern BANDWIDTH = Pattern.compile("(?i)BANDWIDTH\\s*=\\s*(\\d+)");
    private static final Pattern B_PARAM = Pattern.compile("(?i)([?&])b=\\d+-\\d+");
    private static final String VDN_KEY = "a4220a71b31746908fa3e7fdd7a6852a";
    private static final String[] VDN_HOSTS = {
            "https://vdnx.live.cntv.cn", "https://vdnxbk.live.cntv.cn"};

    private CctvLiveResolver() {
    }

    static boolean canResolve(String input) {
        try {
            URI uri = URI.create(input);
            String host = uri.getHost();
            if (host == null || !(host.equalsIgnoreCase("cctv.com") || host.endsWith(".cctv.com"))) return false;
            String path = uri.getPath();
            return path != null && path.regionMatches(true, 0, "/live/", 0, 6);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    static BilibiliResolver.ResolvedMedia resolve(String input, NetworkVideoQuality quality)
            throws IOException, InterruptedException {
        String channel = channel(input);
        if (channel == null) throw new IOException("CCTV live URL has no channel; use a specific channel page");
        if (channel.isEmpty() || channel.equalsIgnoreCase("index")) {
            channel = firstChannel(input);
            if (channel == null) {
                throw new IOException("CCTV live index page exposed no playable channel");
            }
        }
        String referer = "https://tv.cctv.com/live/" + channel + "/";
        String lastTip = null;
        for (String host : VDN_HOSTS) {
            long now = System.currentTimeMillis();
            int random = ThreadLocalRandom.current().nextInt(100, 1000);
            String api = host + "/api/v3/vdn/live?channel=" + VideoResolverHttp.urlEncode(channel)
                    + "&vn=1&pdrm=0&uid=&hbss=" + now;
            String authKey = now + "-" + random + "-" + md5Hex(channel + now + random + VDN_KEY);
            String text;
            try {
                text = VideoResolverHttp.getText(api, referer,
                        Map.of("auth-key", authKey, "Origin", "https://tv.cctv.com"));
            } catch (IOException error) {
                continue;
            }
            JsonObject response = parsePayload(text);
            if ("no".equalsIgnoreCase(string(response, "ack"))) {
                String tip = string(response, "tip_msg");
                if (tip != null && !tip.isEmpty()) lastTip = tip;
                continue;
            }
            String stream = nestedString(response, "manifest", "hls_cdrm");
            if (stream == null) stream = nestedString(response, "backup", "hls_cdrm");
            if (stream == null) {
                List<Candidate> candidates = new ArrayList<>();
                collectCandidates(response, "", candidates);
                Candidate selected = candidates.stream().max(Comparator.comparingInt(Candidate::score)).orElse(null);
                if (selected != null) stream = selected.url;
            }
            if (stream != null) {
                String pinned = pinTopVariant(stream, referer);
                String snapshot = nestedString(response, "manifest", "hls_pic");
                if (snapshot == null) snapshot = nestedString(response, "backup", "hls_pic");
                return new BilibiliResolver.ResolvedMedia(pinned, pinned, referer, 0.0, true, Map.of(), snapshot);
            }
        }
        throw new IOException("CCTV live API returned no playable stream"
                + (lastTip == null ? "" : ": " + lastTip));
    }

    private static String pinTopVariant(String masterUrl, String referer) {
        if (masterUrl == null || !masterUrl.toLowerCase(Locale.ROOT).contains(".m3u8")) return masterUrl;
        String master;
        try {
            master = VideoResolverHttp.getText(masterUrl, referer);
        } catch (IOException | InterruptedException error) {
            return masterUrl;
        }
        long top = -1L;
        Matcher matcher = BANDWIDTH.matcher(master);
        while (matcher.find()) {
            long value = Long.parseLong(matcher.group(1));
            if (value > top) top = value;
        }
        if (top <= 0L) return masterUrl;
        long kbps = top / 1000L;
        String pinned = B_PARAM.matcher(masterUrl).replaceFirst("$1b=" + kbps + "-" + (kbps + 50));
        if (pinned.equals(masterUrl)) {
            pinned = masterUrl + (masterUrl.contains("?") ? "&" : "?") + "b=" + kbps + "-" + (kbps + 50);
        }
        return pinned;
    }

    private static String channel(String input) {
        try {
            URI uri = URI.create(input);
            String path = uri.getPath();
            if (path == null || !path.regionMatches(true, 0, "/live/", 0, 6)) return null;
            String value = path.substring(6).split("/", 2)[0].split("\\.", 2)[0];
            if (value.isEmpty()) return "";
            return CHANNEL_SEGMENT.matcher(value).matches() ? value.toLowerCase(Locale.ROOT) : null;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static String firstChannel(String input) throws IOException, InterruptedException {
        String page = VideoResolverHttp.getText("https://tv.cctv.com/live/index.shtml", input);
        Document document = Jsoup.parse(page, "https://tv.cctv.com/live/index.shtml");
        for (Element link : document.select("a[href]")) {
            String href = link.absUrl("href");
            Matcher matcher = CHANNEL_LINK.matcher(href);
            if (matcher.matches() && !matcher.group(1).equalsIgnoreCase("index")) {
                return matcher.group(1).toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    private static JsonObject parsePayload(String text) throws IOException {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IOException("CCTV live API returned invalid metadata");
        try {
            return JsonParser.parseString(text.substring(start, end + 1)).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("CCTV live API returned invalid metadata", error);
        }
    }

    private static void collectCandidates(JsonElement value, String key, List<Candidate> candidates) {
        if (value == null || value.isJsonNull()) return;
        if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) {
                collectCandidates(entry.getValue(), entry.getKey(), candidates);
            }
            return;
        }
        if (value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) collectCandidates(element, key, candidates);
            return;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return;
        String url = value.getAsString();
        if (!VideoResolverHttp.isWebUrl(url)) return;
        String lowerKey = key.toLowerCase(Locale.ROOT);
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        if (lowerKey.contains("timeshift")) return;
        int score = lowerKey.contains("streamurl") ? 500
                : lowerKey.contains("hls") ? 450
                : lowerKey.contains("flv") ? 400 : 0;
        if (lowerUrl.contains(".m3u8")) score += 150;
        if (lowerUrl.contains(".flv")) score += 120;
        if (score > 0) candidates.add(new Candidate(url, score));
    }

    private static String string(JsonObject object, String name) {
        if (object == null || !object.has(name) || !object.get(name).isJsonPrimitive()) return null;
        try {
            return object.get(name).getAsString();
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static String nestedString(JsonObject root, String outer, String inner) {
        if (root == null || !root.has(outer) || !root.get(outer).isJsonObject()) return null;
        return string(root.getAsJsonObject(outer), inner);
    }

    private static String md5Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte b : digest) builder.append(String.format("%02x", b));
            return builder.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private record Candidate(String url, int score) {
    }
}
