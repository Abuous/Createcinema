package com.yfy.createcinema.client.video;

import com.yfy.createcinema.client.bilibili.BilibiliResolver;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

public final class VideoResolverHttp {
    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NORMAL).build();

    private VideoResolverHttp() {
    }

    public static JsonObject getJson(String url, String referer) throws IOException, InterruptedException {
        return JsonParser.parseString(getText(url, referer)).getAsJsonObject();
    }

    public static JsonObject getJson(String url, String referer, Map<String, String> additionalHeaders)
            throws IOException, InterruptedException {
        return JsonParser.parseString(getText(url, referer, additionalHeaders)).getAsJsonObject();
    }

    public static JsonObject getJson(String url, String referer, Map<String, String> additionalHeaders, int timeoutSeconds)
            throws IOException, InterruptedException {
        return JsonParser.parseString(getText(url, referer, additionalHeaders, timeoutSeconds)).getAsJsonObject();
    }

    static HttpResponse<String> getResponse(String url, String referer, Map<String, String> additionalHeaders)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("User-Agent", BilibiliResolver.USER_AGENT).header("Referer", referer);
        if (additionalHeaders != null) additionalHeaders.forEach(builder::header);
        HttpRequest request = builder.GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw httpError(response.statusCode());
        return response;
    }

    static HttpResponse<String> getResponse(String url, String referer, Map<String, String> additionalHeaders,
                                            int timeoutSeconds) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", BilibiliResolver.USER_AGENT).header("Referer", referer);
        if (additionalHeaders != null) additionalHeaders.forEach(builder::header);
        HttpRequest request = builder.GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw httpError(response.statusCode());
        return response;
    }

    static JsonObject postJson(String url, String referer, JsonObject body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                .header("User-Agent", BilibiliResolver.USER_AGENT).header("Referer", referer)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw httpError(response.statusCode());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    static String getText(String url, String referer) throws IOException, InterruptedException {
        return getText(url, referer, null);
    }

    static String getText(String url, String referer, Map<String, String> additionalHeaders)
            throws IOException, InterruptedException {
        return getText(url, referer, additionalHeaders, 10);
    }

    static String getText(String url, String referer, Map<String, String> additionalHeaders, int timeoutSeconds)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", BilibiliResolver.USER_AGENT).header("Referer", referer);
        if (additionalHeaders != null) additionalHeaders.forEach(builder::header);
        HttpRequest request = builder.GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw httpError(response.statusCode());
        return response.body();
    }

    public static byte[] getBytes(String url, String referer) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("User-Agent", BilibiliResolver.USER_AGENT).header("Referer", referer)
                .GET().build();
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) throw httpError(response.statusCode());
        return response.body();
    }

    public record SnapshotProbe(String etag, String lastModified) {
        public boolean changedFrom(String etag, String lastModified) {
            if (this.etag != null && !this.etag.equals(etag)) return true;
            if (this.etag != null) return false;
            return this.lastModified != null && !this.lastModified.equals(lastModified);
        }
    }

    public static SnapshotProbe probe(String url, String referer) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("User-Agent", BilibiliResolver.USER_AGENT).header("Referer", referer)
                .header("Range", "bytes=0-0").GET().build();
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) throw httpError(response.statusCode());
        return new SnapshotProbe(response.headers().firstValue("ETag").orElse(null),
                response.headers().firstValue("Last-Modified").orElse(null));
    }

    public static boolean supportsRange(String url, String referer, Map<String, String> additionalHeaders) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("User-Agent", BilibiliResolver.USER_AGENT).header("Referer", referer)
                    .header("Range", "bytes=0-0");
            if (additionalHeaders != null) additionalHeaders.forEach(builder::header);
            HttpRequest request = builder.GET().build();
            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream ignored = response.body()) {
                if (response.statusCode() == 206) return true;
                if (response.statusCode() / 100 == 2) return false;
                throw new IOException("HTTP " + response.statusCode());
            }
        } catch (IOException | InterruptedException error) {
            return false;
        }
    }

    public static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public static boolean isWebUrl(String value) {
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static IOException httpError(int statusCode) {
        if (statusCode == 412) {
            return new IOException("HTTP 412 (Bilibili risk control; retry later or use browser login)");
        }
        return new IOException("HTTP " + statusCode);
    }
}
