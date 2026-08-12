package com.yfy.createcinema.client.douyin;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Platform-specific browser session used for signed playback requests.
 *
 * <p>Every implementation mirrors the semantic of the native WebView2 engine: keep a persistent
 * browser profile, let the user log in through a real browser session, then intercept the matching
 * authenticated XHR/JSON response body and hand it to the caller as raw bytes.</p>
 */
public interface DouyinBrowserBackend {
    enum AuthorizationState {
        AUTHORIZED,
        UNAUTHORIZED,
        UNKNOWN
    }

    /** Profile directory name under {@code <game>/createcinema/browser}. */
    String profileDirName();

    /** Name used in logs (e.g. "WebView2", "Chromium CDP", "Android WebView"). */
    String name();

    boolean isRuntimeAvailable(Path gameDirectory) throws IOException;

    void initialize(Path gameDirectory, Path profile) throws IOException;

    void showLogin(String url) throws IOException;

    byte[] capture(String navigationUrl, String expectedHost, List<String> expectedPaths,
                   String expectedQueryName, String expectedQueryValue, Duration timeout) throws IOException;

    default String cookieHeader(String url) throws IOException {
        return "";
    }

    void hide();

    boolean isAuthorized();

    default AuthorizationState authorizationState(String url, List<String> cookieNames) {
        return isAuthorized() ? AuthorizationState.AUTHORIZED : AuthorizationState.UNAUTHORIZED;
    }

    void shutdown();
}
