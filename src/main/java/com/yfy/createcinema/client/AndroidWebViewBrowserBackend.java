package com.yfy.createcinema.client;

import com.yfy.createcinema.ClientConfig;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Android backend: drives the embedded {@code AndroidWebViewBridge} (compiled against android.jar)
 * reflectively. On non-Android runtimes {@link #isRuntimeAvailable} reports false and no Android
 * class is ever loaded.
 */
final class AndroidWebViewBrowserBackend implements DouyinBrowserBackend {
    private static final String BRIDGE = "com.yfy.createcinema.androidbridge.AndroidWebViewBridge";
    private static final String CONFIG_COOKIE = "douyin.cookie";

    private Class<?> bridge;
    private boolean initialized;

    AndroidWebViewBrowserBackend() {
    }

    private Class<?> bridge() throws IOException {
        if (bridge != null) return bridge;
        try {
            bridge = Class.forName(BRIDGE);
        } catch (ClassNotFoundException error) {
            throw new IOException("The embedded Android WebView bridge is missing", error);
        }
        return bridge;
    }

    private static IOException wrap(String message, Throwable error) {
        Throwable cause = error;
        while (cause instanceof InvocationTargetException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof IOException io) return io;
        return new IOException(message, cause);
    }

    @Override
    public String profileDirName() {
        return "android";
    }

    @Override
    public String name() {
        return "Android WebView";
    }

    @Override
    public boolean isRuntimeAvailable(Path gameDirectory) {
        try {
            return (boolean) bridge().getMethod("isRuntimeAvailable").invoke(null);
        } catch (Throwable error) {
            return false;
        }
    }

    @Override
    public void initialize(Path gameDirectory, Path profile) throws IOException {
        if (initialized) return;
        if (!isRuntimeAvailable(gameDirectory)) {
            throw new IOException("Android WebView is unavailable in this launcher; configure douyin.cookie manually");
        }
        try {
            Class<?> type = bridge();
            type.getMethod("initialize", String.class)
                    .invoke(null, profile.toAbsolutePath().toString());
            type.getMethod("setConfigCookies", String.class)
                    .invoke(null, ClientConfig.legacyDouyinCookie());
            initialized = true;
        } catch (Throwable error) {
            throw wrap("Could not initialize the Android WebView", error);
        }
    }

    @Override
    public void showLogin(String url) throws IOException {
        try {
            bridge().getMethod("showLogin", String.class).invoke(null, url);
        } catch (Throwable error) {
            throw wrap("Could not open the Android WebView login page", error);
        }
    }

    @Override
    public byte[] capture(String navigationUrl, String expectedHost, List<String> expectedPaths,
                           String expectedQueryName, String expectedQueryValue, Duration timeout) throws IOException {
        try {
            Object body = bridge().getMethod("capture", String.class, String.class,
                            String[].class, String.class, String.class, int.class)
                            .invoke(null, navigationUrl, expectedHost,
                            expectedPaths.toArray(String[]::new), expectedQueryName,
                            expectedQueryValue, Math.toIntExact(timeout.toMillis()));
            return body == null ? null : (byte[]) body;
        } catch (Throwable error) {
            throw wrap("Android WebView capture failed", error);
        }
    }

    @Override
    public void hide() {
        try {
            bridge().getMethod("hide").invoke(null);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean isAuthorized() {
        try {
            return (boolean) bridge().getMethod("isAuthorized").invoke(null);
        } catch (Throwable error) {
            return false;
        }
    }

    @Override
    public AuthorizationState authorizationState(String url, List<String> cookieNames) {
        try {
            boolean authorized = (boolean) bridge().getMethod("isAuthorized", String.class, String[].class)
                    .invoke(null, url, cookieNames.toArray(String[]::new));
            return authorized ? AuthorizationState.AUTHORIZED : AuthorizationState.UNAUTHORIZED;
        } catch (Throwable error) {
            return AuthorizationState.UNKNOWN;
        }
    }

    @Override
    public String cookieHeader(String url) throws IOException {
        try {
            Object cookies = bridge().getMethod("cookieHeader", String.class).invoke(null, url);
            return cookies == null ? "" : cookies.toString();
        } catch (Throwable error) {
            throw wrap("Could not read Android WebView cookies", error);
        }
    }

    @Override
    public void shutdown() {
        try {
            bridge().getMethod("shutdown").invoke(null);
        } catch (Throwable ignored) {
        }
        initialized = false;
    }
}
