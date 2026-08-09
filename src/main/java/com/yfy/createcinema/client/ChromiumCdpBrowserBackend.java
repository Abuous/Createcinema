package com.yfy.createcinema.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Browser backend for Linux (any architecture) and Windows-on-ARM: launches the system
 * Chromium-family browser (Edge on Windows ARM64) with a dedicated profile and drives it
 * over the DevTools Protocol using pure Java.
 */
final class ChromiumCdpBrowserBackend implements DouyinBrowserBackend {
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
    private static final long BROWSER_START_TIMEOUT_MILLIS = 20_000L;
    private static final long COMMAND_TIMEOUT_MILLIS = 10_000L;

    private final List<Path> candidates;
    private final boolean headless;

    private Path profile;
    private Process browserProcess;
    private CdpConnection cdp;
    private String targetId;
    private String sessionId;
    private Integer windowId;
    private final AtomicBoolean started = new AtomicBoolean();
    private final Object captureMonitor = new Object();
    private long matchedRequestId = -1;
    private volatile boolean capturePending;

    ChromiumCdpBrowserBackend(List<Path> candidates, boolean headless) {
        this.candidates = candidates;
        this.headless = headless;
    }

    @Override
    public String profileDirName() {
        return "chromium";
    }

    @Override
    public String name() {
        return "Chromium";
    }

    @Override
    public boolean isRuntimeAvailable(Path gameDirectory) {
        return discoverBinary() != null;
    }

    @Override
    public void initialize(Path gameDirectory, Path profile) throws IOException {
        this.profile = profile;
        ensureStarted();
    }

    private Path discoverBinary() {
        String override = System.getenv("CREATE_CINEMA_CHROMIUM");
        if (override != null && !override.isBlank()) {
            Path path = Path.of(override.trim());
            if (Files.isExecutable(path)) return path;
        }
        for (Path candidate : candidates) {
            if (Files.isExecutable(candidate)) return candidate;
        }
        return null;
    }

    private void ensureStarted() throws IOException {
        if (started.get() && browserProcess != null && browserProcess.isAlive() && cdp != null) return;
        synchronized (this) {
            if (started.get() && browserProcess != null && browserProcess.isAlive() && cdp != null) return;
            if (profile == null) throw new IOException("Chromium backend was not initialized");
            Path binary = discoverBinary();
            if (binary == null) throw new IOException("No Chromium-family browser found on this platform");
            Files.createDirectories(profile);
            int port = findFreePort();
            List<String> command = new ArrayList<>();
            command.add(binary.toString());
            command.add("--remote-debugging-port=" + port);
            command.add("--user-data-dir=" + profile.toAbsolutePath());
            command.add("--no-first-run");
            command.add("--no-default-browser-check");
            command.add("--disable-component-update");
            command.add("--disable-background-networking");
            command.add("--disable-sync");
            command.add("--mute-audio");
            command.add("--hide-scrollbars");
            command.add("--window-size=1280,720");
            command.add("--user-agent=" + USER_AGENT);
            command.add("--disable-blink-features=AutomationControlled");
            if (PlatformInfo.isLinux()) command.add("--disable-dev-shm-usage");
            if (headless) {
                command.add("--headless=new");
                command.add("--disable-gpu");
            }
            if ("root".equals(System.getProperty("user.name"))) command.add("--no-sandbox");

            Path logFile = profile.resolve("chromium.log");
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
            Process process;
            try {
                process = builder.start();
            } catch (IOException error) {
                if (command.contains("--no-sandbox")) throw error;
                command.add("--no-sandbox");
                builder.command(command);
                process = builder.start();
            }
            browserProcess = process;
            URI browserWs;
            try {
                browserWs = waitForDevTools(port);
            } catch (IOException error) {
                process.destroy();
                throw error;
            }
            cdp = new CdpConnection(browserWs, new CdpConnection.EventListener() {
                @Override
                public void onEvent(String method, JsonObject params, String sessionId) {
                    onCdpEvent(method, params, sessionId);
                }

                @Override
                public void onError(Throwable error) {
                    synchronized (captureMonitor) {
                        if (capturePending) captureMonitor.notifyAll();
                    }
                }
            });
            started.set(true);
            ensureTab();
        }
    }

    private void onCdpEvent(String method, JsonObject params, String sessionId) {
        if (!"Network.responseReceived".equals(method)) return;
        if (this.sessionId == null || !this.sessionId.equals(sessionId)) return;
        synchronized (captureMonitor) {
            if (!capturePending || matchedRequestId >= 0) return;
            if (!params.has("response")) return;
            JsonObject response = params.getAsJsonObject("response");
            if (response == null || !params.has("requestId")) return;
            if (!matches(response.has("url") ? response.get("url").getAsString() : "", expectedHost, expectedPaths,
                    expectedQueryName, expectedQueryValue)) return;
            matchedRequestId = params.get("requestId").getAsLong();
            captureMonitor.notifyAll();
        }
    }

    private String expectedHost;
    private List<String> expectedPaths;
    private String expectedQueryName;
    private String expectedQueryValue;

    private static boolean matches(String url, String host, List<String> paths,
                                   String queryName, String queryValue) {
        if (url == null || host == null) return false;
        int scheme = url.indexOf("://");
        if (scheme < 0) return false;
        int hostStart = scheme + 3;
        int hostEnd = url.indexOf('/', hostStart);
        if (hostEnd < 0) hostEnd = url.length();
        int portSeparator = url.indexOf(':', hostStart);
        int hostEndEffective = portSeparator > hostStart && portSeparator < hostEnd ? portSeparator : hostEnd;
        String urlHost = url.substring(hostStart, hostEndEffective);
        if (!urlHost.equalsIgnoreCase(host)) return false;
        String pathAndQuery = url.substring(hostEnd);
        int queryStart = pathAndQuery.indexOf('?');
        String path = queryStart < 0 ? pathAndQuery : pathAndQuery.substring(0, queryStart);
        boolean pathMatch = false;
        for (String expected : paths) {
            if (expected != null && path.startsWith(expected)) {
                pathMatch = true;
                break;
            }
        }
        if (!pathMatch) return false;
        if (queryName == null || queryValue == null) return true;
        if (queryStart < 0) return false;
        String query = pathAndQuery.substring(queryStart + 1);
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals < 0) continue;
            if (!pair.substring(0, equals).equals(queryName)) continue;
            String candidate = pair.substring(equals + 1);
            if (candidate.equals(queryValue)) return true;
        }
        return false;
    }

    private void ensureTab() throws IOException {
        if (targetId != null && sessionId != null) return;
        JsonObject created = cdp.send("Target.createTarget",
                JsonParams.of("url", "about:blank"), null, COMMAND_TIMEOUT_MILLIS);
        targetId = created.get("targetId").getAsString();
        JsonObject attached = cdp.send("Target.attachToTarget",
                JsonParams.of("targetId", targetId, "flatten", true), null, COMMAND_TIMEOUT_MILLIS);
        sessionId = attached.get("sessionId").getAsString();
        cdp.send("Page.enable", null, sessionId, COMMAND_TIMEOUT_MILLIS);
        cdp.send("Network.enable", null, sessionId, COMMAND_TIMEOUT_MILLIS);
        try {
            JsonObject window = cdp.send("Browser.getWindowForTarget",
                    JsonParams.of("targetId", targetId), null, COMMAND_TIMEOUT_MILLIS);
            if (window.has("windowId")) windowId = window.get("windowId").getAsInt();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void showLogin(String url) throws IOException {
        ensureStarted();
        ensureTab();
        setWindowState("normal");
        cdp.send("Page.navigate", JsonParams.of("url", url), sessionId, COMMAND_TIMEOUT_MILLIS);
    }

    @Override
    public byte[] capture(String navigationUrl, String expectedHost, List<String> expectedPaths,
                           String expectedQueryName, String expectedQueryValue, Duration timeout) throws IOException {
        ensureStarted();
        ensureTab();
        synchronized (this) {
            this.expectedHost = expectedHost;
            this.expectedPaths = expectedPaths;
            this.expectedQueryName = expectedQueryName;
            this.expectedQueryValue = expectedQueryValue;
        }
        setWindowState("normal");
        synchronized (captureMonitor) {
            matchedRequestId = -1;
            capturePending = true;
        }
        try {
            cdp.send("Page.navigate", JsonParams.of("url", navigationUrl), sessionId, COMMAND_TIMEOUT_MILLIS);
            long deadline = System.currentTimeMillis() + timeout.toMillis();
            synchronized (captureMonitor) {
                while (matchedRequestId < 0 && System.currentTimeMillis() < deadline) {
                    try {
                        captureMonitor.wait(Math.max(10, deadline - System.currentTimeMillis()));
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while capturing Douyin response", error);
                    }
                }
                if (matchedRequestId < 0) {
                    throw new IOException("Chromium did not capture the requested Douyin response before timeout");
                }
                long requestId = matchedRequestId;
                while (System.currentTimeMillis() < deadline) {
                    JsonObject result;
                    try {
                        result = cdp.send("Network.getResponseBody",
                                JsonParams.of("requestId", requestId), sessionId, COMMAND_TIMEOUT_MILLIS);
                    } catch (IOException error) {
                        sleepQuietly(50);
                        continue;
                    }
                    if (result.has("body")) {
                        String body = result.get("body").getAsString();
                        boolean base64 = result.has("base64Encoded") && result.get("base64Encoded").getAsBoolean();
                        return base64 ? Base64.getDecoder().decode(body)
                                : body.getBytes(StandardCharsets.UTF_8);
                    }
                    sleepQuietly(50);
                }
                throw new IOException("Chromium captured the response but its body was unavailable before timeout");
            }
        } finally {
            synchronized (captureMonitor) {
                capturePending = false;
                matchedRequestId = -1;
            }
        }
    }

    @Override
    public String cookieHeader(String url) throws IOException {
        ensureStarted();
        ensureTab();
        JsonArray urls = new JsonArray();
        urls.add(url);
        JsonObject parameters = new JsonObject();
        parameters.add("urls", urls);
        JsonObject result = cdp.send("Network.getCookies", parameters, sessionId,
                COMMAND_TIMEOUT_MILLIS);
        JsonArray cookies = result.has("cookies") ? result.getAsJsonArray("cookies") : new JsonArray();
        StringBuilder header = new StringBuilder();
        for (JsonElement element : cookies) {
            if (!element.isJsonObject()) continue;
            JsonObject cookie = element.getAsJsonObject();
            if (!cookie.has("name") || !cookie.has("value")) continue;
            if (!header.isEmpty()) header.append("; ");
            header.append(cookie.get("name").getAsString()).append('=').append(cookie.get("value").getAsString());
        }
        return header.toString();
    }

    private void setWindowState(String state) {
        if (windowId == null || headless) return;
        JsonObject bounds = new JsonObject();
        bounds.addProperty("windowState", state);
        try {
            cdp.send("Browser.setWindowBounds",
                    JsonParams.of("windowId", windowId, "bounds", bounds), null, COMMAND_TIMEOUT_MILLIS);
        } catch (IOException ignored) {
        }
    }

    @Override
    public void hide() {
        setWindowState("minimized");
    }

    @Override
    public boolean isAuthorized() {
        return authorizationState("https://www.douyin.com/", List.of(
                "sessionid", "sessionid_ss", "sid_tt")) == AuthorizationState.AUTHORIZED;
    }

    @Override
    public AuthorizationState authorizationState(String url, List<String> cookieNames) {
        try {
            ensureStarted();
            ensureTab();
            JsonArray urls = new JsonArray();
            urls.add(url);
            JsonObject parameters = new JsonObject();
            parameters.add("urls", urls);
            JsonObject result = cdp.send("Network.getCookies", parameters, sessionId, COMMAND_TIMEOUT_MILLIS);
            JsonArray cookies = result.has("cookies") ? result.getAsJsonArray("cookies") : new JsonArray();
            for (int i = 0; i < cookies.size(); i++) {
                JsonObject cookie = cookies.get(i).getAsJsonObject();
                if (!cookie.has("name")) continue;
                String name = cookie.get("name").getAsString().toLowerCase(java.util.Locale.ROOT);
                if (cookieNames.contains(name)) return AuthorizationState.AUTHORIZED;
            }
            return AuthorizationState.UNAUTHORIZED;
        } catch (IOException | RuntimeException error) {
            return AuthorizationState.UNKNOWN;
        }
    }

    @Override
    public void shutdown() {
        CdpConnection connection = cdp;
        cdp = null;
        started.set(false);
        targetId = null;
        sessionId = null;
        windowId = null;
        if (connection != null) connection.close();
        Process process = browserProcess;
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        browserProcess = null;
    }

    private URI waitForDevTools(int port) throws IOException {
        long deadline = System.currentTimeMillis() + BROWSER_START_TIMEOUT_MILLIS;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        URI version = URI.create("http://127.0.0.1:" + port + "/json/version");
        while (System.currentTimeMillis() < deadline) {
            if (!browserProcess.isAlive()) throw new IOException("The Chromium browser exited during startup");
            try {
                HttpRequest request = HttpRequest.newBuilder(version).GET().timeout(Duration.ofSeconds(2)).build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    return URI.create(json.get("webSocketDebuggerUrl").getAsString());
                }
            } catch (Exception ignored) {
            }
            sleepQuietly(100);
        }
        throw new IOException("Chromium DevTools did not become ready within " + BROWSER_START_TIMEOUT_MILLIS + " ms");
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    static List<Path> linuxCandidates() {
        List<Path> paths = new ArrayList<>();
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String directory : pathEnv.split(java.io.File.pathSeparator)) {
                for (String name : List.of("chromium", "chromium-browser", "google-chrome",
                        "google-chrome-stable", "chrome", "microsoft-edge", "msedge")) {
                    Path candidate = Path.of(directory, name);
                    if (Files.isExecutable(candidate) && !paths.contains(candidate)) paths.add(candidate);
                }
            }
        }
        return paths;
    }

    static List<Path> windowsArmCandidates() {
        List<Path> paths = new ArrayList<>();
        for (String root : new String[]{System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles"),
                System.getenv("LOCALAPPDATA")}) {
            if (root == null || root.isBlank()) continue;
            for (String relative : List.of(
                    "Microsoft\\Edge\\Application\\msedge.exe",
                    "Microsoft\\EdgeWebView\\Application\\msedge.exe")) {
                Path candidate = Path.of(root, relative);
                if (Files.exists(candidate)) paths.add(candidate);
            }
        }
        return paths;
    }
}
