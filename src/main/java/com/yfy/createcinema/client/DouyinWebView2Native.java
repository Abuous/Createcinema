package com.yfy.createcinema.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

final class DouyinWebView2Native {
    private static final String RESOURCE =
            "/assets/createcinema/native/windows-x86_64/douyinwebview.dll";
    private static boolean loaded;

    private DouyinWebView2Native() {
    }

    static synchronized void load(Path gameDirectory) throws IOException {
        if (loaded) return;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win") || !(arch.equals("amd64") || arch.equals("x86_64"))) {
            throw new IOException("Douyin WebView2 authorization requires 64-bit Windows");
        }

        Path cache = gameDirectory.toAbsolutePath().normalize()
                .resolve("createcinema").resolve("native-cache");
        Files.createDirectories(cache);
        Path library = cache.resolve("douyinwebview.dll");
        Path temporary = cache.resolve("douyinwebview.dll.tmp");
        try (InputStream input = DouyinWebView2Native.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IOException("The embedded Douyin WebView2 library is missing");
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(temporary, library, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, library, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            System.load(library.toString());
        } catch (UnsatisfiedLinkError error) {
            throw new IOException("Could not load the embedded Douyin WebView2 library", error);
        }
        loaded = true;
    }

    static boolean isRuntimeAvailable(Path gameDirectory) throws IOException {
        load(gameDirectory);
        return isRuntimeAvailable0();
    }

    static void initialize(Path gameDirectory, Path profile) throws IOException {
        load(gameDirectory);
        Files.createDirectories(profile);
        initialize0(profile.toAbsolutePath().normalize().toString());
    }

    static void showLogin(String url) throws IOException {
        showLogin0(url);
    }

    static byte[] capture(String navigationUrl, String host, List<String> paths,
                           String queryName, String queryValue, Duration timeout) throws IOException {
        return capture0(navigationUrl, host, paths.toArray(String[]::new), queryName, queryValue,
                Math.toIntExact(timeout.toMillis()));
    }

    static void hide() {
        if (loaded) hide0();
    }

    static boolean isAuthorized() {
        try {
            return loaded && hasAuthorizationCookies0("https://www.douyin.com/",
                    new String[]{"sessionid", "sessionid_ss", "sid_tt"});
        } catch (IOException error) {
            return false;
        }
    }

    static boolean hasAuthorizationCookies(String url, List<String> cookieNames) throws IOException {
        if (!loaded) throw new IOException("WebView2 is not initialized");
        return hasAuthorizationCookies0(url, cookieNames.toArray(String[]::new));
    }

    static synchronized void shutdown() {
        if (loaded) shutdown0();
    }

    private static native boolean isRuntimeAvailable0();

    private static native void initialize0(String profile) throws IOException;

    private static native void showLogin0(String url) throws IOException;

    private static native byte[] capture0(String navigationUrl, String expectedHost,
                                          String[] expectedPaths, String queryName,
                                          String queryValue, int timeoutMillis) throws IOException;

    private static native void hide0();

    private static native boolean isAuthorized0();

    private static native boolean hasAuthorizationCookies0(String url, String[] cookieNames) throws IOException;

    private static native void shutdown0();
}
