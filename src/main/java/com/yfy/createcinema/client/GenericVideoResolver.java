package com.yfy.createcinema.client;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GenericVideoResolver {
    private static final Pattern SCRIPT_MEDIA = Pattern.compile(
            "(?i)((?:https?:)?\\\\?/\\\\?/[^\\\"'\\s<>]+?\\.(?:m3u8|mpd|mp4|webm|m4v)(?:\\?[^\\\"'\\s<>]*)?)");
    private static final Pattern PERCENT_MEDIA = Pattern.compile(
            "(?i)(https?%3a%2f%2f[^\\\"'\\s<>]+?\\.(?:m3u8|mpd|mp4|webm|m4v)(?:%3f[^\\\"'\\s<>]*)?)");
    private static final Pattern RELATIVE_MEDIA = Pattern.compile(
            "(?i)(?:[\\\"'=:(,\\s])((?:/|\\.\\.?/)[^\\\"'\\s<>]+?\\.(?:m3u8|mpd|mp4|webm|m4v)(?:\\?[^\\\"'\\s<>]*)?)");
    private static final int MAX_HTML_BYTES = 2 * 1024 * 1024;
    private static final int MAX_FRAME_DEPTH = 1;

    private GenericVideoResolver() {
    }

    static BilibiliResolver.ResolvedMedia resolve(String input) throws IOException, InterruptedException {
        URI inputUri = URI.create(input);
        if (isLikelyDirectMedia(input)) return media(input, input);
        HttpRequest request = HttpRequest.newBuilder(inputUri).timeout(Duration.ofSeconds(20))
                .header("User-Agent", BilibiliResolver.USER_AGENT)
                .header("Accept", "text/html,video/*,audio/*,*/*;q=0.8").GET().build();
        HttpResponse<InputStream> response = VideoResolverHttp.HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) throw new IOException("HTTP " + response.statusCode());
        URI finalUri = response.uri();
        String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        try (InputStream body = response.body()) {
            if (isMediaContentType(contentType)) return media(finalUri.toString(), input);
            if (!contentType.isBlank() && !contentType.contains("html") && !contentType.startsWith("text/")) {
                throw new IOException("URL is not a supported video or HTML page: " + contentType);
            }
            byte[] htmlBytes = body.readNBytes(MAX_HTML_BYTES + 1);
            if (htmlBytes.length > MAX_HTML_BYTES) throw new IOException("Video page HTML exceeds 2 MiB");
            Document document = Jsoup.parse(new String(htmlBytes, StandardCharsets.UTF_8), finalUri.toString());
            Optional<String> found = findHtmlMedia(document, finalUri, 0);
            if (found.isEmpty()) throw new IOException("No directly playable media was exposed by this page");
            return media(found.get(), finalUri.toString());
        }
    }

    private static BilibiliResolver.ResolvedMedia media(String mediaUrl, String referer) throws IOException, InterruptedException {
        double duration = 0.0;
        if (HlsStreamCache.isHls(mediaUrl)) {
            HlsStreamCache.prepareAsync(mediaUrl, referer);
            duration = HlsStreamCache.duration(mediaUrl);
        }
        return new BilibiliResolver.ResolvedMedia(mediaUrl, mediaUrl, referer, duration);
    }

    private static Optional<String> findHtmlMedia(Document document, URI pageUri, int depth) throws IOException, InterruptedException {
        Set<String> candidates = new LinkedHashSet<>();
        List<SelectorAttribute> selectors = List.of(
                new SelectorAttribute("video[src]", "src"),
                new SelectorAttribute("video source[src]", "src"),
                new SelectorAttribute("source[src]", "src"),
                new SelectorAttribute("meta[property=og:video:secure_url]", "content"),
                new SelectorAttribute("meta[property=og:video:url]", "content"),
                new SelectorAttribute("meta[property=og:video]", "content"),
                new SelectorAttribute("meta[name=twitter:player:stream]", "content"),
                new SelectorAttribute("meta[itemprop=contentUrl]", "content"),
                new SelectorAttribute("link[rel=preload][as=video]", "href")
        );
        for (SelectorAttribute candidate : selectors) {
            Element element = document.selectFirst(candidate.selector);
            if (element == null) continue;
            String resolved = element.absUrl(candidate.attribute);
            if (resolved.isBlank()) resolved = resolveRelative(pageUri, element.attr(candidate.attribute));
            addCandidate(candidates, pageUri, resolved);
        }
        for (Element element : document.select("[src],[href],[data-src],[data-url],[data-video],[data-play],[content]")) {
            for (Attribute attribute : element.attributes()) {
                String key = attribute.getKey().toLowerCase(Locale.ROOT);
                if (key.equals("src") || key.equals("href") || key.startsWith("data-") || key.equals("content")) {
                    addCandidate(candidates, pageUri, attribute.getValue());
                }
            }
        }
        collectScriptMedia(candidates, pageUri, document.html());
        Optional<String> direct = bestCandidate(candidates);
        if (direct.isPresent()) return direct;
        if (depth < MAX_FRAME_DEPTH) {
            for (Element frame : document.select("iframe[src],frame[src],embed[src]")) {
                String frameUrl = frame.absUrl("src");
                if (frameUrl.isBlank()) frameUrl = resolveRelative(pageUri, frame.attr("src"));
                if (!VideoResolverHttp.isWebUrl(frameUrl)) continue;
                Optional<String> found = fetchHtmlMedia(frameUrl, pageUri.toString(), depth + 1);
                if (found.isPresent()) return found;
            }
        }
        return Optional.empty();
    }

    private static void collectScriptMedia(Set<String> candidates, URI pageUri, String html) {
        collectPattern(candidates, pageUri, SCRIPT_MEDIA.matcher(html), 1);
        collectPattern(candidates, pageUri, SCRIPT_MEDIA.matcher(unescapeMediaValue(html)), 1);
        collectPattern(candidates, pageUri, PERCENT_MEDIA.matcher(html), 1);
        collectPattern(candidates, pageUri, RELATIVE_MEDIA.matcher(html), 1);
    }

    private static void collectPattern(Set<String> candidates, URI pageUri, Matcher matcher, int group) {
        while (matcher.find()) addCandidate(candidates, pageUri, matcher.group(group));
    }

    private static Optional<String> fetchHtmlMedia(String url, String referer, int depth) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("User-Agent", BilibiliResolver.USER_AGENT)
                .header("Accept", "text/html,video/*,audio/*,*/*;q=0.8")
                .header("Referer", referer).GET().build();
        HttpResponse<InputStream> response = VideoResolverHttp.HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) return Optional.empty();
        String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        try (InputStream body = response.body()) {
            if (isMediaContentType(contentType)) return Optional.of(response.uri().toString());
            if (!contentType.isBlank() && !contentType.contains("html") && !contentType.startsWith("text/")) return Optional.empty();
            byte[] bytes = body.readNBytes(MAX_HTML_BYTES + 1);
            if (bytes.length > MAX_HTML_BYTES) return Optional.empty();
            Document document = Jsoup.parse(new String(bytes, StandardCharsets.UTF_8), response.uri().toString());
            return findHtmlMedia(document, response.uri(), depth);
        }
    }

    private static void addCandidate(Set<String> candidates, URI pageUri, String raw) {
        String value = normalizeMediaValue(raw);
        if (value.isBlank()) return;
        if (value.startsWith("//")) value = pageUri.getScheme() + ":" + value;
        if (!VideoResolverHttp.isWebUrl(value)) value = resolveRelative(pageUri, value);
        if (VideoResolverHttp.isWebUrl(value) && isLikelyDirectMedia(value)) candidates.add(value);
    }

    private static Optional<String> bestCandidate(Set<String> candidates) {
        return candidates.stream().max((left, right) -> Integer.compare(mediaScore(left), mediaScore(right)));
    }

    private static int mediaScore(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains(".m3u8")) return 50;
        if (lower.contains(".mpd")) return 45;
        if (lower.contains(".mp4")) return 35;
        if (lower.contains(".webm") || lower.contains(".m4v")) return 30;
        return 0;
    }

    private static String normalizeMediaValue(String raw) {
        if (raw == null) return "";
        String value = unescapeMediaValue(raw.trim());
        try {
            String decoded = URLDecoder.decode(value, StandardCharsets.UTF_8);
            if (isLikelyDirectMedia(decoded)) value = decoded;
        } catch (IllegalArgumentException ignored) {
        }
        while (!value.isBlank() && ";,)]}".indexOf(value.charAt(value.length() - 1)) >= 0) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String unescapeMediaValue(String value) {
        return org.jsoup.parser.Parser.unescapeEntities(value, false)
                .replace("\\/", "/")
                .replace("\\u002F", "/").replace("\\u002f", "/")
                .replace("\\u003A", ":").replace("\\u003a", ":")
                .replace("\\u0026", "&");
    }

    private static boolean isLikelyDirectMedia(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\.(mp4|webm|m4v|mov|mkv|avi|ogv|m3u8|mpd)(?:$|[?#].*)");
    }

    private static boolean isMediaContentType(String type) {
        return type.startsWith("video/") || type.startsWith("audio/")
                || type.contains("mpegurl") || type.contains("dash+xml") || type.contains("application/octet-stream");
    }

    private static String resolveRelative(URI base, String value) {
        try {
            return base.resolve(value).toString();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private record SelectorAttribute(String selector, String attribute) {
    }
}
