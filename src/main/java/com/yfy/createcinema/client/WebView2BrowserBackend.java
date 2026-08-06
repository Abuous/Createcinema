package com.yfy.createcinema.client;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Windows x86_64 backend: the embedded native WebView2 engine (unchanged behaviour). */
final class WebView2BrowserBackend implements DouyinBrowserBackend {
    WebView2BrowserBackend() {
    }

    @Override
    public String profileDirName() {
        return "webview2";
    }

    @Override
    public String name() {
        return "WebView2";
    }

    @Override
    public boolean isRuntimeAvailable(Path gameDirectory) throws IOException {
        return DouyinWebView2Native.isRuntimeAvailable(gameDirectory);
    }

    @Override
    public void initialize(Path gameDirectory, Path profile) throws IOException {
        DouyinWebView2Native.initialize(gameDirectory, profile);
    }

    @Override
    public void showLogin(String url) throws IOException {
        DouyinWebView2Native.showLogin(url);
    }

    @Override
    public byte[] capture(String navigationUrl, String expectedHost, List<String> expectedPaths,
                          String expectedQueryName, String expectedQueryValue, Duration timeout) throws IOException {
        return DouyinWebView2Native.capture(navigationUrl, expectedHost, expectedPaths,
                expectedQueryName, expectedQueryValue, timeout);
    }

    @Override
    public void hide() {
        DouyinWebView2Native.hide();
    }

    @Override
    public boolean isAuthorized() {
        return DouyinWebView2Native.isAuthorized();
    }

    @Override
    public void shutdown() {
        DouyinWebView2Native.shutdown();
    }
}