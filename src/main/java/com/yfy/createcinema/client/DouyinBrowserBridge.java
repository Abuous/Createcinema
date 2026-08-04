package com.yfy.createcinema.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yfy.createcinema.ClientConfig;
import com.yfy.createcinema.CreateCinema;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

final class DouyinBrowserBridge {
    enum Status {
        STOPPED,
        STARTING,
        WAITING_LOGIN,
        READY,
        FAILED
    }

    private static final String HOME_URL = "https://www.douyin.com/?recommend=1";
    private static final List<String> FEED_PATHS = List.of(
            "/aweme/v2/web/module/feed/", "/aweme/v1/web/tab/feed/",
            "/aweme/v2/web/tab/feed/", "/aweme/v1/web/feed/",
            "/aweme/v2/web/feed/");
    private static final String RELATED_PATH = "/aweme/v1/web/aweme/related/";
    private static final String LIVE_PATH = "/webcast/room/web/enter/";
    private static final Pattern AWEME_ID = Pattern.compile("[0-9]{1,32}");
    private static final Pattern WEB_RID = Pattern.compile("[A-Za-z0-9_-]{2,64}");
    private static final Duration CAPTURE_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_BODY_BYTES = 64 * 1024 * 1024;
    private static final long LOCK_TIMEOUT_MILLIS = 2_000L;
    private static final ReentrantLock CAPTURE_LOCK = new ReentrantLock(true);
    private static final AtomicLong GENERATION = new AtomicLong();

    private static volatile Status currentStatus = Status.STOPPED;
    private static volatile boolean nativeInitialized;
    private static volatile boolean closing;
    private static volatile boolean detectLoginCompletion;

    private DouyinBrowserBridge() {
    }

    static void openAuthorizationPage() throws IOException {
        long generation = GENERATION.get();
        acquireCaptureLock(generation);
        try {
            ensureWebView(generation);
            DouyinWebView2Native.showLogin(HOME_URL);
            detectLoginCompletion = true;
            setStatus(generation, Status.WAITING_LOGIN);
            CreateCinema.LOGGER.info("CreateCinema WebView2: authorization page opened");
        } catch (IOException error) {
            detectLoginCompletion = false;
            setStatus(generation, Status.FAILED);
            CreateCinema.LOGGER.warn("CreateCinema WebView2: failed to open authorization page: {}",
                    error.getMessage());
            throw new IOException("Could not open the embedded Douyin authorization page", error);
        } finally {
            CAPTURE_LOCK.unlock();
        }
    }

    static JsonObject captureFeed() throws IOException {
        return capture(HOME_URL, "www.douyin.com", FEED_PATHS, null, null);
    }

    static JsonObject captureRecommendations(String awemeId) throws IOException {
        if (awemeId == null || !AWEME_ID.matcher(awemeId).matches()) {
            throw new IllegalArgumentException("Douyin aweme id must contain only digits");
        }
        return capture("https://www.douyin.com/video/" + awemeId,
                "www.douyin.com", List.of(RELATED_PATH), "aweme_id", awemeId);
    }

    static JsonObject captureLive(String webRid) throws IOException {
        if (webRid == null || !WEB_RID.matcher(webRid).matches()) {
            throw new IllegalArgumentException("Douyin live room id is invalid");
        }
        return capture("https://live.douyin.com/" + webRid,
                "live.douyin.com", List.of(LIVE_PATH), "web_rid", webRid);
    }

    static Status status() {
        if (detectLoginCompletion && currentStatus == Status.WAITING_LOGIN
                && DouyinWebView2Native.isAuthorized()) {
            detectLoginCompletion = false;
            currentStatus = Status.READY;
            DouyinWebView2Native.hide();
            ClientNetworkProjectorStreams.requestDouyinRetry();
            CreateCinema.LOGGER.info("CreateCinema WebView2: authorization completed");
        }
        return currentStatus;
    }

    static boolean isAvailable() {
        try {
            return DouyinWebView2Native.isRuntimeAvailable(gameDirectory());
        } catch (IOException error) {
            return false;
        }
    }

    static void close() {
        GENERATION.incrementAndGet();
        closing = true;
        currentStatus = Status.STOPPED;
        nativeInitialized = false;
        detectLoginCompletion = false;
        try {
            DouyinWebView2Native.shutdown();
            CreateCinema.LOGGER.info("CreateCinema WebView2: closed");
        } catch (RuntimeException | LinkageError error) {
            CreateCinema.LOGGER.debug("CreateCinema WebView2: close failed", error);
        } finally {
            closing = false;
        }
    }

    private static JsonObject capture(String navigationUrl, String expectedHost,
                                      List<String> expectedPaths, String expectedQueryName,
                                      String expectedQueryValue) throws IOException {
        long generation = GENERATION.get();
        acquireCaptureLock(generation);
        try {
            ensureWebView(generation);
            CreateCinema.LOGGER.debug("CreateCinema WebView2: capture starting, host={}", expectedHost);
            byte[] body;
            try {
                body = DouyinWebView2Native.capture(navigationUrl, expectedHost, expectedPaths,
                        expectedQueryName, expectedQueryValue, CAPTURE_TIMEOUT);
            } catch (IOException error) {
                if (error.getMessage() != null && error.getMessage().startsWith("CAPTURE_UNAVAILABLE:")) {
                    throw new CaptureUnavailableException(error.getMessage());
                }
                throw error;
            }
            JsonObject captured = parseBody(body);
            if (hasRejectedStatus(captured)) throw new CaptureUnavailableException("Douyin rejected the response");
            requireOperationAllowed(generation);
            DouyinWebView2Native.hide();
            detectLoginCompletion = false;
            setStatus(generation, Status.READY);
            CreateCinema.LOGGER.info("CreateCinema WebView2: capture ok, host={}", expectedHost);
            return captured;
        } catch (CaptureUnavailableException error) {
            detectLoginCompletion = false;
            setStatus(generation, Status.WAITING_LOGIN);
            CreateCinema.LOGGER.warn("CreateCinema WebView2: capture unavailable: {}", error.getMessage());
            throw new IOException(
                    "Douyin browser feed or recommendations unavailable; the page requires verification", error);
        } catch (IOException error) {
            detectLoginCompletion = false;
            setStatus(generation, Status.FAILED);
            CreateCinema.LOGGER.error("CreateCinema WebView2: capture failed: {}", error.getMessage());
            throw new IOException("The embedded Douyin WebView2 connection failed", error);
        } finally {
            CAPTURE_LOCK.unlock();
        }
    }

    private static void ensureWebView(long generation) throws IOException {
        requireOperationAllowed(generation);
        if (nativeInitialized) return;
        setStatus(generation, Status.STARTING);
        Path gameDirectory = gameDirectory();
        Path profile = gameDirectory.resolve("createcinema").resolve("browser")
                .resolve("webview2").resolve("profile");
        DouyinWebView2Native.initialize(gameDirectory, profile);
        requireOperationAllowed(generation);
        nativeInitialized = true;
        CreateCinema.LOGGER.info("CreateCinema WebView2: initialized");
    }

    private static JsonObject parseBody(byte[] body) throws IOException {
        if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) {
            throw new CaptureUnavailableException("Douyin response body was unavailable");
        }
        try {
            JsonElement parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new CaptureUnavailableException("Douyin response was not JSON");
            return parsed.getAsJsonObject();
        } catch (CaptureUnavailableException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new CaptureUnavailableException("Douyin response contained invalid JSON");
        }
    }

    private static boolean hasRejectedStatus(JsonObject captured) {
        if (!captured.has("status_code") || !captured.get("status_code").isJsonPrimitive()) return false;
        try {
            return captured.get("status_code").getAsInt() != 0;
        } catch (RuntimeException error) {
            return true;
        }
    }

    private static void acquireCaptureLock(long generation) throws IOException {
        boolean acquired;
        try {
            acquired = CAPTURE_LOCK.tryLock(LOCK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for Douyin WebView2");
        }
        if (!acquired) throw new IOException("The embedded Douyin WebView2 is busy");
        try {
            requireOperationAllowed(generation);
        } catch (IOException error) {
            CAPTURE_LOCK.unlock();
            throw error;
        }
    }

    private static void requireOperationAllowed(long generation) throws IOException {
        if (generation != GENERATION.get() || closing || !ClientConfig.douyinBrowserAuthorization()) {
            throw new IOException("Douyin browser authorization is disabled");
        }
    }

    private static Path gameDirectory() {
        return Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize();
    }

    private static void setStatus(long generation, Status status) {
        if (generation == GENERATION.get()) currentStatus = status;
    }

    private static final class CaptureUnavailableException extends IOException {
        private CaptureUnavailableException(String message) {
            super(message);
        }
    }
}
