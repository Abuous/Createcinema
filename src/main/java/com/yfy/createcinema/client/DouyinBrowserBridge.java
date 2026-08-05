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
    private static final Duration CAPTURE_TIMEOUT = Duration.ofSeconds(12);
    private static final int MAX_BODY_BYTES = 64 * 1024 * 1024;
    private static final long LOCK_TIMEOUT_MILLIS = 15_000L;
    private static final ReentrantLock CAPTURE_LOCK = new ReentrantLock(true);
    private static final AtomicLong GENERATION = new AtomicLong();

    private static volatile Status currentStatus = Status.STOPPED;
    private static volatile boolean nativeInitialized;
    private static volatile boolean closing;
    private static volatile boolean detectLoginCompletion;
    private static volatile DouyinBrowserBackend backend;

    private DouyinBrowserBridge() {
    }

    static void openAuthorizationPage() throws IOException {
        long generation = GENERATION.get();
        acquireCaptureLock(generation);
        try {
            ensureWebView(generation);
            backend().showLogin(HOME_URL);
            detectLoginCompletion = true;
            setStatus(generation, Status.WAITING_LOGIN);
            CreateCinema.LOGGER.info("CreateCinema {}: authorization page opened", backend().name());
        } catch (IOException error) {
            detectLoginCompletion = false;
            setStatus(generation, Status.FAILED);
            CreateCinema.LOGGER.warn("CreateCinema browser: failed to open authorization page: {}",
                    error.getMessage());
            throw new IOException("Could not open the embedded Douyin authorization page", error);
        } finally {
            CAPTURE_LOCK.unlock();
        }
    }

    static JsonObject captureFeed() throws IOException {
        return capture(HOME_URL, "www.douyin.com", FEED_PATHS, null, null, false);
    }

    static JsonObject captureRecommendations(String awemeId) throws IOException {
        if (awemeId == null || !AWEME_ID.matcher(awemeId).matches()) {
            throw new IllegalArgumentException("Douyin aweme id must contain only digits");
        }
        return capture("https://www.douyin.com/video/" + awemeId,
                "www.douyin.com", List.of(RELATED_PATH), "aweme_id", awemeId, false);
    }

    static JsonObject captureLive(String webRid) throws IOException {
        if (webRid == null || !WEB_RID.matcher(webRid).matches()) {
            throw new IllegalArgumentException("Douyin live room id is invalid");
        }
        return capture("https://live.douyin.com/" + webRid + "?createcinema_capture=" + System.nanoTime(),
                "live.douyin.com", List.of(LIVE_PATH), "web_rid", webRid, true);
    }

    static Status status() {
        if (detectLoginCompletion && currentStatus == Status.WAITING_LOGIN && isAuthorized()) {
            detectLoginCompletion = false;
            currentStatus = Status.READY;
            hide();
            ClientNetworkProjectorStreams.requestDouyinRetry();
            CreateCinema.LOGGER.info("CreateCinema browser: authorization completed");
        }
        return currentStatus;
    }

    static boolean isAvailable() {
        try {
            return backend().isRuntimeAvailable(gameDirectory());
        } catch (IOException error) {
            return false;
        }
    }

    static void cancelPendingCapture() {
        GENERATION.incrementAndGet();
        detectLoginCompletion = false;
        currentStatus = Status.STOPPED;
        if (!nativeInitialized) return;
        nativeInitialized = false;
        try {
            shutdownBackend();
        } catch (RuntimeException | LinkageError error) {
            CreateCinema.LOGGER.debug("CreateCinema browser: capture cancellation failed", error);
        }
    }

    static void close() {
        GENERATION.incrementAndGet();
        closing = true;
        currentStatus = Status.STOPPED;
        nativeInitialized = false;
        detectLoginCompletion = false;
        try {
            shutdownBackend();
            CreateCinema.LOGGER.info("CreateCinema browser: closed");
        } catch (RuntimeException | LinkageError error) {
            CreateCinema.LOGGER.debug("CreateCinema browser: close failed", error);
        } finally {
            closing = false;
        }
    }

    private static DouyinBrowserBackend backend() throws IOException {
        DouyinBrowserBackend current = backend;
        if (current != null) return current;
        synchronized (DouyinBrowserBridge.class) {
            current = backend;
            if (current != null) return current;
            current = createBackend();
            if (current == null) {
                throw new IOException("Douyin browser authorization is not supported on " + PlatformInfo.displayName());
            }
            backend = current;
            return current;
        }
    }

    private static DouyinBrowserBackend createBackend() {
        switch (PlatformInfo.os()) {
            case WINDOWS:
                if (PlatformInfo.isX86_64()) return new WebView2BrowserBackend();
                if (PlatformInfo.isArm64()) {
                    List<Path> edge = ChromiumCdpBrowserBackend.windowsArmCandidates();
                    return new ChromiumCdpBrowserBackend(edge, false);
                }
                return null;
            case LINUX:
            case MACOS:
                return new ChromiumCdpBrowserBackend(ChromiumCdpBrowserBackend.linuxCandidates(), !hasDisplay());
            case ANDROID:
                return new AndroidWebViewBrowserBackend();
            default:
                return null;
        }
    }

    private static boolean hasDisplay() {
        String display = System.getenv("DISPLAY");
        String wayland = System.getenv("WAYLAND_DISPLAY");
        return display != null && !display.isBlank() || wayland != null && !wayland.isBlank();
    }

    private static boolean isAuthorized() {
        try {
            return backend().isAuthorized();
        } catch (IOException error) {
            return false;
        }
    }

    private static void hide() {
        try {
            backend().hide();
        } catch (IOException ignored) {
        }
    }

    private static void shutdownBackend() {
        DouyinBrowserBackend current = backend;
        backend = null;
        if (current != null) current.shutdown();
    }

    private static JsonObject capture(String navigationUrl, String expectedHost,
                                       List<String> expectedPaths, String expectedQueryName,
                                       String expectedQueryValue, boolean freshWebView) throws IOException {
        long generation = GENERATION.get();
        acquireCaptureLock(generation);
        try {
            if (freshWebView && nativeInitialized) {
                nativeInitialized = false;
                shutdownBackend();
            }
            ensureWebView(generation);
            CreateCinema.LOGGER.debug("CreateCinema browser: capture starting, host={}", expectedHost);
            byte[] body;
            try {
                body = backend().capture(navigationUrl, expectedHost, expectedPaths,
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
            backend().hide();
            detectLoginCompletion = false;
            setStatus(generation, Status.READY);
            CreateCinema.LOGGER.info("CreateCinema browser: capture ok, host={}", expectedHost);
            return captured;
        } catch (CaptureUnavailableException error) {
            if (generation != GENERATION.get()) {
                throw new IOException("Douyin browser capture was cancelled", error);
            }
            if (error.getMessage() != null && error.getMessage().contains("before timeout")) {
                detectLoginCompletion = false;
                setStatus(generation, Status.READY);
                CreateCinema.LOGGER.warn("CreateCinema browser: live capture timed out; it will be retried");
                throw new IOException("Douyin browser live capture timed out", error);
            }
            detectLoginCompletion = false;
            setStatus(generation, Status.WAITING_LOGIN);
            CreateCinema.LOGGER.warn("CreateCinema browser: capture unavailable: {}", error.getMessage());
            throw new IOException(
                    "Douyin browser feed or recommendations unavailable; the page requires verification", error);
        } catch (IOException error) {
            if (generation != GENERATION.get()) {
                throw new IOException("Douyin browser capture was cancelled", error);
            }
            detectLoginCompletion = false;
            setStatus(generation, Status.FAILED);
            CreateCinema.LOGGER.error("CreateCinema browser: capture failed: {}", error.getMessage());
            throw new IOException("The embedded Douyin browser connection failed", error);
        } finally {
            CAPTURE_LOCK.unlock();
        }
    }

    private static void ensureWebView(long generation) throws IOException {
        requireOperationAllowed(generation);
        if (nativeInitialized) return;
        setStatus(generation, Status.STARTING);
        DouyinBrowserBackend active = backend();
        Path gameDirectory = gameDirectory();
        Path profile = gameDirectory.resolve("createcinema").resolve("browser")
                .resolve(active.profileDirName()).resolve("profile");
        active.initialize(gameDirectory, profile);
        requireOperationAllowed(generation);
        nativeInitialized = true;
        CreateCinema.LOGGER.info("CreateCinema {}: initialized", active.name());
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
            throw new IOException("Interrupted while waiting for Douyin browser authorization");
        }
        if (!acquired) throw new IOException("The embedded Douyin browser is busy");
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
