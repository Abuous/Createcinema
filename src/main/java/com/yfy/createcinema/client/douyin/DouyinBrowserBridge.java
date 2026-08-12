package com.yfy.createcinema.client.douyin;

import com.yfy.createcinema.client.browser.WebView2BrowserBackend;
import com.yfy.createcinema.client.browser.PlatformInfo;
import com.yfy.createcinema.client.network.ClientNetworkProjectorStreams;
import com.yfy.createcinema.client.browser.ChromiumCdpBrowserBackend;
import com.yfy.createcinema.client.browser.BrowserProvider;
import com.yfy.createcinema.client.browser.AndroidWebViewBrowserBackend;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

public final class DouyinBrowserBridge {
    public enum Status {
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
    private static final String DETAIL_PATH = "/aweme/v1/web/aweme/detail/";
    private static final String LIVE_PATH = "/webcast/room/web/enter/";
    private static final Pattern AWEME_ID = Pattern.compile("[0-9]{1,32}");
    private static final Pattern WEB_RID = Pattern.compile("[A-Za-z0-9_-]{2,64}");
    private static final Duration CAPTURE_TIMEOUT = Duration.ofSeconds(12);
    private static final int MAX_BODY_BYTES = 64 * 1024 * 1024;
    private static final long LOCK_TIMEOUT_MILLIS = 15_000L;
    private static final ReentrantLock CAPTURE_LOCK = new ReentrantLock(true);
    private static final AtomicLong GENERATION = new AtomicLong();

    private static final Map<BrowserProvider, Status> STATUSES = new ConcurrentHashMap<>();
    private static final Map<BrowserProvider, Long> LAST_AUTH_CHECKS = new ConcurrentHashMap<>();
    private static volatile boolean nativeInitialized;
    private static volatile boolean closing;
    private static volatile BrowserProvider detectLoginProvider;
    private static volatile BrowserProvider activeProvider;
    private static volatile DouyinBrowserBackend backend;
    private static volatile boolean bilibiliActive;

    private DouyinBrowserBridge() {
    }

    public static void openAuthorizationPage() throws IOException {
        openAuthorizationPage(BrowserProvider.DOUYIN);
    }

    public static void openAuthorizationPage(BrowserProvider provider) throws IOException {
        long generation = GENERATION.get();
        acquireCaptureLock(generation, provider);
        try {
            ensureWebView(generation, provider);
            backend().showLogin(provider.homeUrl());
            LAST_AUTH_CHECKS.remove(provider);
            detectLoginProvider = provider;
            setStatus(generation, provider, Status.WAITING_LOGIN);
            CreateCinema.LOGGER.info("CreateCinema {}: {} authorization page opened", backend().name(), provider.id());
        } catch (IOException error) {
            detectLoginProvider = null;
            setStatus(generation, provider, Status.FAILED);
            CreateCinema.LOGGER.warn("CreateCinema browser: failed to open authorization page: {}",
                    error.getMessage());
            throw new IOException("Could not open the embedded " + provider.id() + " authorization page", error);
        } finally {
            CAPTURE_LOCK.unlock();
        }
    }

    public static void openBilibiliLogin() throws IOException {
        acquireBilibiliLock();
        try {
            ensureBilibiliWebView();
            backend().showLogin("https://passport.bilibili.com/pc/passport/login");
            CreateCinema.LOGGER.info("CreateCinema {}: Bilibili authorization page opened", backend().name());
        } finally {
            CAPTURE_LOCK.unlock();
        }
    }

    public static String bilibiliCookieHeader() throws IOException {
        acquireBilibiliLock();
        try {
            ensureBilibiliWebView();
            return backend().cookieHeader("https://www.bilibili.com/");
        } finally {
            CAPTURE_LOCK.unlock();
        }
    }

    public static void hideBilibiliLogin() {
        if (!nativeInitialized || !bilibiliActive) return;
        try {
            backend().hide();
        } catch (IOException ignored) {
        }
    }

    private static void acquireBilibiliLock() throws IOException {
        try {
            if (!CAPTURE_LOCK.tryLock(LOCK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IOException("The embedded browser is busy");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the embedded browser", error);
        }
    }

    private static void ensureBilibiliWebView() throws IOException {
        if (nativeInitialized && bilibiliActive) return;
        if (nativeInitialized) {
            nativeInitialized = false;
            shutdownBackend();
        }
        DouyinBrowserBackend active = backend();
        Path gameDirectory = gameDirectory();
        Path profile = gameDirectory.resolve("createcinema").resolve("browser")
                .resolve(active.profileDirName()).resolve("bilibili").resolve("profile");
        active.initialize(gameDirectory, profile);
        nativeInitialized = true;
        activeProvider = null;
        bilibiliActive = true;
        CreateCinema.LOGGER.info("CreateCinema {}: initialized Bilibili browser profile", active.name());
    }

    public static DouyinBrowserBackend.AuthorizationState testAuthorization(BrowserProvider provider) throws IOException {
        long generation = GENERATION.get();
        acquireCaptureLock(generation, provider);
        try {
            ensureWebView(generation, provider);
            DouyinBrowserBackend.AuthorizationState state = backend().authorizationState(
                    provider.cookieUrl(), provider.loginCookieNames());
            if (state == DouyinBrowserBackend.AuthorizationState.AUTHORIZED) {
                backend().hide();
                detectLoginProvider = null;
                setStatus(generation, provider, Status.READY);
            } else {
                detectLoginProvider = provider;
                setStatus(generation, provider, Status.WAITING_LOGIN);
            }
            return state;
        } finally {
            CAPTURE_LOCK.unlock();
        }
    }

    static JsonObject captureFeed() throws IOException {
        return capture(BrowserProvider.DOUYIN, HOME_URL, "www.douyin.com", FEED_PATHS, null, null, false);
    }

    static JsonObject captureRecommendations(String awemeId) throws IOException {
        if (awemeId == null || !AWEME_ID.matcher(awemeId).matches()) {
            throw new IllegalArgumentException("Douyin aweme id must contain only digits");
        }
        return capture(BrowserProvider.DOUYIN, "https://www.douyin.com/video/" + awemeId,
                "www.douyin.com", List.of(RELATED_PATH), "aweme_id", awemeId, false);
    }

    static JsonObject captureDetail(String awemeId) throws IOException {
        if (awemeId == null || !AWEME_ID.matcher(awemeId).matches()) {
            throw new IllegalArgumentException("Douyin aweme id must contain only digits");
        }
        return capture(BrowserProvider.DOUYIN, "https://www.douyin.com/video/" + awemeId,
                "www.douyin.com", List.of(DETAIL_PATH), "aweme_id", awemeId, false);
    }

    static JsonObject captureLive(String webRid) throws IOException {
        if (webRid == null || !WEB_RID.matcher(webRid).matches()) {
            throw new IllegalArgumentException("Douyin live room id is invalid");
        }
        return capture(BrowserProvider.DOUYIN,
                "https://live.douyin.com/" + webRid + "?createcinema_capture=" + System.nanoTime(),
                "live.douyin.com", List.of(LIVE_PATH), "web_rid", webRid, true);
    }

    public static CapturedResponse captureProvider(BrowserProvider provider, String navigationUrl, String expectedHost,
                                             List<String> expectedPaths) throws IOException {
        return captureProvider(provider, navigationUrl, expectedHost, expectedPaths, null, null);
    }

    public static CapturedResponse captureProvider(BrowserProvider provider, String navigationUrl, String expectedHost,
                                             List<String> expectedPaths, String expectedQueryName,
                                             String expectedQueryValue) throws IOException {
        long generation = GENERATION.get();
        acquireCaptureLock(generation, provider);
        try {
            ensureWebView(generation, provider);
            CreateCinema.LOGGER.info("CreateCinema browser: capturing {} playback response from {}", provider,
                    expectedHost);
            byte[] body = backend().capture(navigationUrl, expectedHost, expectedPaths,
                    expectedQueryName, expectedQueryValue, Duration.ofSeconds(20));
            if (body == null || body.length == 0 || body.length > MAX_BODY_BYTES) {
                throw new CaptureUnavailableException(provider.id() + " playback response body was unavailable");
            }
            requireOperationAllowed(generation, provider);
            String cookies = backend().cookieHeader(navigationUrl);
            backend().hide();
            detectLoginProvider = null;
            setStatus(generation, provider, Status.READY);
            CreateCinema.LOGGER.info("CreateCinema browser: captured {} playback response ({} bytes, cookies={})",
                    provider.id(), body.length, cookies.isBlank() ? "unavailable" : "available");
            return new CapturedResponse(body, cookies);
        } catch (IOException error) {
            if (generation != GENERATION.get()) {
                throw new IOException(provider.id() + " browser capture was cancelled", error);
            }
            DouyinBrowserBackend.AuthorizationState authorization = backend().authorizationState(
                    provider.cookieUrl(), provider.loginCookieNames());
            if (authorization == DouyinBrowserBackend.AuthorizationState.UNAUTHORIZED) {
                detectLoginProvider = provider;
                setStatus(generation, provider, Status.WAITING_LOGIN);
                throw new IOException(provider.id()
                        + " browser playback capture failed; log in in the opened browser and retry", error);
            }
            detectLoginProvider = null;
            setStatus(generation, provider, Status.FAILED);
            throw new IOException(provider.id() + " browser playback capture failed", error);
        } finally {
            CAPTURE_LOCK.unlock();
        }
    }

    public static Status status() {
        return status(BrowserProvider.DOUYIN);
    }

    public static Status status(BrowserProvider provider) {
        Status currentStatus = STATUSES.getOrDefault(provider, Status.STOPPED);
        long now = System.currentTimeMillis();
        boolean checkAuthorization = now - LAST_AUTH_CHECKS.getOrDefault(provider, 0L) >= 1_000L;
        if (detectLoginProvider == provider && activeProvider == provider
                && currentStatus == Status.WAITING_LOGIN && checkAuthorization) {
            LAST_AUTH_CHECKS.put(provider, now);
        }
        if (detectLoginProvider == provider && activeProvider == provider
                && currentStatus == Status.WAITING_LOGIN && checkAuthorization && isAuthorized(provider)) {
            detectLoginProvider = null;
            STATUSES.put(provider, Status.READY);
            hide();
            ClientNetworkProjectorStreams.requestBrowserRetry(provider);
            CreateCinema.LOGGER.info("CreateCinema browser: {} authorization completed", provider.id());
            return Status.READY;
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

    public static void cancelPendingCapture() {
        GENERATION.incrementAndGet();
        detectLoginProvider = null;
        STATUSES.replaceAll((provider, status) -> Status.STOPPED);
        if (!nativeInitialized) return;
        nativeInitialized = false;
        try {
            shutdownBackend();
        } catch (RuntimeException | LinkageError error) {
            CreateCinema.LOGGER.debug("CreateCinema browser: capture cancellation failed", error);
        }
    }

    public static void disable(BrowserProvider provider) {
        STATUSES.put(provider, Status.STOPPED);
        if (detectLoginProvider == provider) detectLoginProvider = null;
        if (activeProvider != provider) return;
        GENERATION.incrementAndGet();
        nativeInitialized = false;
        try {
            shutdownBackend();
            CreateCinema.LOGGER.info("CreateCinema browser: disabled {} session", provider.id());
        } catch (RuntimeException | LinkageError error) {
            CreateCinema.LOGGER.debug("CreateCinema browser: failed to disable {} session", provider.id(), error);
        }
    }

    public static void close() {
        GENERATION.incrementAndGet();
        closing = true;
        STATUSES.replaceAll((provider, status) -> Status.STOPPED);
        nativeInitialized = false;
        detectLoginProvider = null;
        try {
            shutdownBackend();
            CreateCinema.LOGGER.info("CreateCinema browser: closed");
        } catch (RuntimeException | LinkageError error) {
            CreateCinema.LOGGER.debug("CreateCinema browser: close failed", error);
        } finally {
            closing = false;
        }
    }

    static DouyinBrowserBackend backend() throws IOException {
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

    private static boolean isAuthorized(BrowserProvider provider) {
        try {
            return backend().authorizationState(provider.cookieUrl(), provider.loginCookieNames())
                    == DouyinBrowserBackend.AuthorizationState.AUTHORIZED;
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
        activeProvider = null;
        bilibiliActive = false;
        if (current != null) current.shutdown();
    }

    private static JsonObject capture(BrowserProvider provider, String navigationUrl, String expectedHost,
                                       List<String> expectedPaths, String expectedQueryName,
                                       String expectedQueryValue, boolean freshWebView) throws IOException {
        long generation = GENERATION.get();
        acquireCaptureLock(generation, provider);
        try {
            if (freshWebView && nativeInitialized) {
                nativeInitialized = false;
                shutdownBackend();
            }
            ensureWebView(generation, provider);
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
            requireOperationAllowed(generation, provider);
            backend().hide();
            detectLoginProvider = null;
            setStatus(generation, provider, Status.READY);
            CreateCinema.LOGGER.info("CreateCinema browser: capture ok, host={}", expectedHost);
            return captured;
        } catch (CaptureUnavailableException error) {
            if (generation != GENERATION.get()) {
                throw new IOException("Douyin browser capture was cancelled", error);
            }
            if (error.getMessage() != null && error.getMessage().contains("before timeout")) {
                detectLoginProvider = null;
                setStatus(generation, provider, Status.READY);
                CreateCinema.LOGGER.warn("CreateCinema browser: live capture timed out; it will be retried");
                throw new IOException("Douyin browser live capture timed out", error);
            }
            detectLoginProvider = provider;
            setStatus(generation, provider, Status.WAITING_LOGIN);
            CreateCinema.LOGGER.warn("CreateCinema browser: capture unavailable: {}", error.getMessage());
            throw new IOException(
                    "Douyin browser feed or recommendations unavailable; the page requires verification", error);
        } catch (IOException error) {
            if (generation != GENERATION.get()) {
                throw new IOException("Douyin browser capture was cancelled", error);
            }
            detectLoginProvider = null;
            setStatus(generation, provider, Status.FAILED);
            CreateCinema.LOGGER.error("CreateCinema browser: capture failed: {}", error.getMessage());
            throw new IOException("The embedded Douyin browser connection failed", error);
        } finally {
            CAPTURE_LOCK.unlock();
        }
    }

    private static void ensureWebView(long generation, BrowserProvider provider) throws IOException {
        requireOperationAllowed(generation, provider);
        if (nativeInitialized && activeProvider == provider) return;
        if (nativeInitialized) {
            nativeInitialized = false;
            shutdownBackend();
        }
        setStatus(generation, provider, Status.STARTING);
        DouyinBrowserBackend active = backend();
        Path gameDirectory = gameDirectory();
        Path backendRoot = gameDirectory.resolve("createcinema").resolve("browser")
                .resolve(active.profileDirName());
        Path profile = provider == BrowserProvider.DOUYIN
                ? backendRoot.resolve("profile")
                : backendRoot.resolve(provider.id()).resolve("profile");
        active.initialize(gameDirectory, profile);
        requireOperationAllowed(generation, provider);
        activeProvider = provider;
        bilibiliActive = false;
        nativeInitialized = true;
        CreateCinema.LOGGER.info("CreateCinema {}: initialized {} profile", active.name(), provider.id());
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

    private static void acquireCaptureLock(long generation, BrowserProvider provider) throws IOException {
        boolean acquired;
        try {
            acquired = CAPTURE_LOCK.tryLock(LOCK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for Douyin browser authorization");
        }
        if (!acquired) throw new IOException("The embedded Douyin browser is busy");
        try {
            requireOperationAllowed(generation, provider);
        } catch (IOException error) {
            CAPTURE_LOCK.unlock();
            throw error;
        }
    }

    private static void requireOperationAllowed(long generation, BrowserProvider provider) throws IOException {
        if (generation != GENERATION.get() || closing || !provider.enabled()) {
            throw new IOException(provider.id() + " browser authorization is disabled");
        }
    }

    static Path gameDirectory() {
        return Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize();
    }

    private static void setStatus(long generation, BrowserProvider provider, Status status) {
        if (generation == GENERATION.get()) STATUSES.put(provider, status);
    }

    private static final class CaptureUnavailableException extends IOException {
        private CaptureUnavailableException(String message) {
            super(message);
        }
    }

    public record CapturedResponse(byte[] body, String cookies) {
    }
}
