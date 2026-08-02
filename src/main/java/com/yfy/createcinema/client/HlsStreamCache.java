package com.yfy.createcinema.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class HlsStreamCache {
    private static final int MAX_PLAYLIST_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SEGMENT_BYTES = 16 * 1024 * 1024;
    private static final int PREFETCH_QUEUE_SIZE = 24;
    private static final long MAX_CACHE_BYTES = 32L * 1024 * 1024;
    private static final long SEGMENT_WAIT_MILLIS = 7_000L;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Map<String, Playlist> PLAYLISTS = new ConcurrentHashMap<>();
    private static final Map<URI, CompletableFuture<byte[]>> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final LinkedHashMap<URI, byte[]> SEGMENTS = new LinkedHashMap<>(16, .75f, true);
    private static final ExecutorService PREFETCH = new ThreadPoolExecutor(2, 2, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(PREFETCH_QUEUE_SIZE), runnable -> {
        Thread thread = new Thread(runnable, "CreateCinema HLS Prefetch");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.DiscardPolicy());
    private static long cachedBytes;

    private HlsStreamCache() {
    }

    static boolean isHls(String url) {
        return url != null && url.toLowerCase(java.util.Locale.ROOT).contains(".m3u8");
    }

    static void prepare(String url, String referer) throws IOException, InterruptedException {
        if (!isHls(url) || PLAYLISTS.containsKey(url)) return;
        Playlist playlist = loadPlaylist(URI.create(url), referer, 0);
        PLAYLISTS.put(url, playlist);
    }

    static void prepareAsync(String url, String referer) {
        if (!isHls(url) || PLAYLISTS.containsKey(url)) return;
        submitPrefetch(() -> {
            try {
                prepare(url, referer);
            } catch (IOException | InterruptedException ignored) {
            }
        });
    }

    static boolean isPrepared(String url) {
        return PLAYLISTS.containsKey(url);
    }

    static void prefetch(String url, double startSeconds, int count) {
        Playlist playlist = PLAYLISTS.get(url);
        if (playlist == null) return;
        int start = 0;
        while (start + 1 < playlist.segments.size() && playlist.segments.get(start + 1).start <= startSeconds) start++;
        for (int offset = 0; offset < count && start + offset < playlist.segments.size(); offset++) {
            Segment segment = playlist.segments.get(start + offset);
            submitPrefetch(() -> {
                try {
                    segmentBytes(segment.uri, playlist.referer);
                } catch (IOException ignored) {
                }
            });
        }
    }

    static InputStream open(String url, String referer, double startSeconds) throws IOException {
        Playlist playlist = PLAYLISTS.get(url);
        if (playlist == null) {
            try {
                playlist = loadPlaylist(URI.create(url), referer, 0);
                PLAYLISTS.put(url, playlist);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while loading HLS playlist", error);
            }
        }
        return new SegmentInputStream(playlist, Math.max(0, startSeconds));
    }

    static double duration(String url) {
        Playlist playlist = PLAYLISTS.get(url);
        return playlist == null ? 0 : playlist.duration;
    }

    static double segmentStart(String url, double time) {
        Playlist playlist = PLAYLISTS.get(url);
        if (playlist == null || playlist.segments.isEmpty()) return 0;
        int index = 0;
        while (index + 1 < playlist.segments.size() && playlist.segments.get(index + 1).start <= time) index++;
        return playlist.segments.get(index).start;
    }

    static void clear() {
        PLAYLISTS.clear();
        IN_FLIGHT.clear();
        synchronized (SEGMENTS) {
            SEGMENTS.clear();
            cachedBytes = 0;
        }
    }

    private static Playlist loadPlaylist(URI uri, String referer, int depth) throws IOException, InterruptedException {
        if (depth > 2) throw new IOException("HLS master playlist nesting is too deep");
        String text = requestBytes(uri, referer, MAX_PLAYLIST_BYTES, "HLS playlist").body;
        String[] lines = text.replace("\r", "").split("\n");
        List<Variant> variants = new ArrayList<>();
        List<Segment> segments = new ArrayList<>();
        double pendingDuration = -1;
        double totalDuration = 0;
        long pendingBandwidth = -1;
        boolean unsupportedMap = false;
        boolean encrypted = false;
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.startsWith("#EXT-X-STREAM-INF:")) pendingBandwidth = attributeLong(line, "BANDWIDTH");
            else if (line.startsWith("#EXTINF:")) {
                int comma = line.indexOf(',');
                String value = line.substring(8, comma >= 0 ? comma : line.length());
                try {
                    pendingDuration = Double.parseDouble(value);
                } catch (NumberFormatException ignored) {
                    pendingDuration = 0;
                }
            } else if (line.startsWith("#EXT-X-MAP")) unsupportedMap = true;
            else if (line.startsWith("#EXT-X-KEY") && !line.contains("METHOD=NONE")) encrypted = true;
            else if (!line.isEmpty() && !line.startsWith("#")) {
                URI resolved = uri.resolve(line);
                if (pendingBandwidth >= 0) {
                    variants.add(new Variant(resolved, pendingBandwidth));
                    pendingBandwidth = -1;
                } else if (pendingDuration >= 0) {
                    segments.add(new Segment(resolved, totalDuration, pendingDuration));
                    totalDuration += pendingDuration;
                    pendingDuration = -1;
                }
            }
        }
        if (!variants.isEmpty()) {
            Variant selected = variants.stream().filter(v -> v.bandwidth <= 1_500_000)
                    .max(Comparator.comparingLong(Variant::bandwidth))
                    .orElseGet(() -> variants.stream().min(Comparator.comparingLong(Variant::bandwidth)).orElseThrow());
            return loadPlaylist(selected.uri, referer, depth + 1);
        }
        if (unsupportedMap || encrypted || segments.isEmpty()) {
            throw new IOException("HLS playlist requires unsupported encryption or fragmented MP4");
        }
        Playlist playlist = new Playlist(List.copyOf(segments), totalDuration, referer);
        if (PLAYLISTS.size() >= 16) {
            String oldest = PLAYLISTS.keySet().stream().findFirst().orElse(null);
            if (oldest != null) PLAYLISTS.remove(oldest);
        }
        return playlist;
    }

    private static long attributeLong(String line, String name) {
        int start = line.indexOf(name + "=");
        if (start < 0) return 0;
        start += name.length() + 1;
        int end = line.indexOf(',', start);
        try {
            return Long.parseLong(line.substring(start, end >= 0 ? end : line.length()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static byte[] segmentBytes(URI uri, String referer) throws IOException {
        synchronized (SEGMENTS) {
            byte[] cached = SEGMENTS.get(uri);
            if (cached != null) return cached;
        }
        CompletableFuture<byte[]> created = new CompletableFuture<>();
        CompletableFuture<byte[]> existing = IN_FLIGHT.putIfAbsent(uri, created);
        CompletableFuture<byte[]> future = existing == null ? created : existing;
        if (existing == null) {
            try {
                created.complete(requestBytes(uri, referer, MAX_SEGMENT_BYTES, "HLS segment").bytes);
            } catch (IOException | InterruptedException error) {
                created.completeExceptionally(error);
            }
        }
        try {
            byte[] bytes = future.get(SEGMENT_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            synchronized (SEGMENTS) {
                if (!SEGMENTS.containsKey(uri)) {
                    SEGMENTS.put(uri, bytes);
                    cachedBytes += bytes.length;
                    while (cachedBytes > MAX_CACHE_BYTES && !SEGMENTS.isEmpty()) {
                        var oldest = SEGMENTS.entrySet().iterator().next();
                        cachedBytes -= oldest.getValue().length;
                        SEGMENTS.remove(oldest.getKey());
                    }
                }
            }
            return bytes;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading HLS segment", error);
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new IOException("Timed out while downloading HLS segment", error);
        } catch (CompletionException | java.util.concurrent.ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
            if (cause instanceof IOException io) throw io;
            throw new IOException("Failed to download HLS segment", cause);
        } finally {
            IN_FLIGHT.remove(uri, future);
        }
    }

    private static ResponseBytes requestBytes(URI uri, String referer, int limit, String kind)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds("HLS segment".equals(kind) ? 5 : 10))
                .header("User-Agent", BilibiliResolver.USER_AGENT).GET();
        if (referer != null && !referer.isBlank()) builder.header("Referer", referer);
        HttpResponse<byte[]> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) throw new IOException(kind + " returned HTTP " + response.statusCode());
        byte[] bytes = response.body();
        if (bytes.length > limit) throw new IOException(kind + " exceeds " + limit + " bytes");
        return new ResponseBytes(bytes, new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void submitPrefetch(Runnable task) {
        try {
            PREFETCH.execute(task);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private static final class SegmentInputStream extends InputStream {
        private final Playlist playlist;
        private int index;
        private ByteArrayInputStream current;

        private SegmentInputStream(Playlist playlist, double startSeconds) {
            this.playlist = playlist;
            while (index + 1 < playlist.segments.size() && playlist.segments.get(index + 1).start <= startSeconds) index++;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count < 0 ? -1 : one[0] & 255;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            while (current == null || current.available() == 0) {
                if (index >= playlist.segments.size()) return -1;
                current = new ByteArrayInputStream(segmentBytes(playlist.segments.get(index).uri, playlist.referer));
                prefetch(index + 1);
                prefetch(index + 2);
                index++;
            }
            return current.read(bytes, offset, length);
        }

        private void prefetch(int i) {
            if (i >= playlist.segments.size()) return;
            Segment segment = playlist.segments.get(i);
            submitPrefetch(() -> {
                try {
                    segmentBytes(segment.uri, playlist.referer);
                } catch (IOException ignored) {
                }
            });
        }
    }

    private record Playlist(List<Segment> segments, double duration, String referer) {
    }

    private record Segment(URI uri, double start, double duration) {
    }

    private record Variant(URI uri, long bandwidth) {
    }

    private record ResponseBytes(byte[] bytes, String body) {
    }
}
