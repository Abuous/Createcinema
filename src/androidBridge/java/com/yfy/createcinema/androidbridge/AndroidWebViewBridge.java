package com.yfy.createcinema.androidbridge;

import android.content.Context;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Android WebView bridge, compiled against android.jar and embedded into the mod jar.
 * The desktop JVM never loads this class; it is only used on Android runtimes (e.g. PojavLauncher)
 * through the reflective {@code AndroidWebViewBrowserBackend}.
 *
 * <p>It mirrors the Windows WebView2 engine semantics: a persistent browser session whose
 * authenticated XHR responses (feed / related / webcast-enter) are captured and handed to
 * the mod as raw JSON bytes.</p>
 */
public final class AndroidWebViewBridge {
    private static volatile WebView webView;
    private static volatile String configCookies = "";

    private static final Object CAPTURE_LOCK = new Object();
    private static volatile boolean capturePending;
    private static volatile boolean captureFetching;
    private static volatile byte[] capturedBody;
    private static volatile String captureHost;
    private static volatile String[] capturePaths;
    private static volatile String captureQueryName;
    private static volatile String captureQueryValue;

    private AndroidWebViewBridge() {
    }

    public static boolean isRuntimeAvailable() {
        return true;
    }

    public static void initialize(String profilePath) throws IOException {
        final Context context = context();
        onMain(() -> {
            WebView view = new WebView(context);
            view.setWebViewClient(new WebViewClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                    return intercept(url);
                }
            });
            view.getSettings().setJavaScriptEnabled(true);
            webView = view;
        });
    }

    public static void setConfigCookies(String cookies) {
        configCookies = cookies == null ? "" : cookies;
        if (cookies == null || cookies.isEmpty()) return;
        try {
            onMain(() -> CookieManager.getInstance().setCookie("https://www.douyin.com", cookies));
        } catch (IOException ignored) {
        }
    }

    public static void showLogin(String url) throws IOException {
        requireWebView();
        onMain(() -> {
            attachLoginOverlay(webView);
            webView.loadUrl(url);
        });
    }

    public static byte[] capture(String navigationUrl, String host, String[] paths,
                                 String queryName, String queryValue, int timeoutMillis) throws IOException {
        requireWebView();
        synchronized (CAPTURE_LOCK) {
            captureHost = host;
            capturePaths = paths;
            captureQueryName = queryName;
            captureQueryValue = queryValue;
            capturedBody = null;
            capturePending = true;
        }
        try {
            onMain(() -> webView.loadUrl(navigationUrl));
            long deadline = System.currentTimeMillis() + Math.max(1_000, timeoutMillis);
            synchronized (CAPTURE_LOCK) {
                while (capturedBody == null && System.currentTimeMillis() < deadline) {
                    CAPTURE_LOCK.wait(Math.max(10, deadline - System.currentTimeMillis()));
                }
                if (capturedBody == null) {
                    throw new IOException("Android WebView did not capture the requested Douyin response before timeout");
                }
                return capturedBody;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while capturing Douyin response", error);
        } finally {
            synchronized (CAPTURE_LOCK) {
                capturePending = false;
            }
        }
    }

    public static void hide() {
        WebView view = webView;
        if (view != null) {
            try {
                onMain(() -> {
                    view.stopLoading();
                    detachOverlay(view);
                });
            } catch (IOException ignored) {
            }
        }
    }

    public static boolean isAuthorized() {
        try {
            String cookies = cookiesFor("https://www.douyin.com/");
            return cookies != null && (cookies.contains("sessionid=")
                    || cookies.contains("sessionid_ss=") || cookies.contains("sid_tt="));
        } catch (Throwable error) {
            return false;
        }
    }

    public static void shutdown() {
        WebView view = webView;
        webView = null;
        if (view != null) {
            try {
                onMain(() -> {
                    view.stopLoading();
                    view.destroy();
                });
            } catch (IOException ignored) {
            }
        }
    }

    private static void requireWebView() throws IOException {
        if (webView == null) throw new IOException("Android WebView is not initialized");
    }

    private static WebResourceResponse intercept(String url) {
        synchronized (CAPTURE_LOCK) {
            if (!capturePending || capturedBody != null || captureFetching) return null;
            if (!matches(url, captureHost, capturePaths, captureQueryName, captureQueryValue)) return null;
            captureFetching = true;
        }
        byte[] body = fetch(url);
        synchronized (CAPTURE_LOCK) {
            captureFetching = false;
            if (body != null) {
                capturedBody = body;
                CAPTURE_LOCK.notifyAll();
            }
        }
        if (body == null) return null;
        return new WebResourceResponse("application/json", "utf-8", new ByteArrayInputStream(body));
    }

    private static byte[] fetch(String url) {
        try {
            String cookies = cookiesFor(url);
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36");
            connection.setRequestProperty("Referer", "https://www.douyin.com/");
            if (cookies != null && !cookies.isEmpty()) connection.setRequestProperty("Cookie", cookies);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            int code = connection.getResponseCode();
            InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (input == null) return null;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) output.write(buffer, 0, read);
            input.close();
            if (output.size() == 0) return null;
            return output.toByteArray();
        } catch (Throwable error) {
            return null;
        }
    }

    private static boolean matches(String url, String host, String[] paths,
                                   String queryName, String queryValue) {
        if (url == null || host == null || paths == null) return false;
        int scheme = url.indexOf("://");
        if (scheme < 0) return false;
        int hostStart = scheme + 3;
        int hostEnd = url.indexOf('/', hostStart);
        if (hostEnd < 0) hostEnd = url.length();
        int portSeparator = url.indexOf(':', hostStart);
        int effectiveEnd = portSeparator > hostStart && portSeparator < hostEnd ? portSeparator : hostEnd;
        if (!url.substring(hostStart, effectiveEnd).equalsIgnoreCase(host)) return false;
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
            if (pair.substring(0, equals).equals(queryName)
                    && pair.substring(equals + 1).equals(queryValue)) {
                return true;
            }
        }
        return false;
    }

    private static String cookiesFor(String url) {
        String manager = CookieManager.getInstance().getCookie(url);
        StringBuilder builder = new StringBuilder();
        if (manager != null && !manager.isEmpty()) builder.append(manager);
        if (configCookies != null && !configCookies.isEmpty()) {
            if (builder.length() > 0) builder.append("; ");
            builder.append(configCookies);
        }
        return builder.toString();
    }

    private static Context context() throws IOException {
        Throwable last = null;
        for (String methodName : new String[]{"currentApplication", "currentActivityThread"}) {
            try {
                Class<?> activityThread = Class.forName("android.app.ActivityThread");
                Object instance = activityThread.getMethod(methodName).invoke(null);
                if (instance == null) continue;
                if (methodName.equals("currentApplication")) return (Context) instance;
                Object application = instance.getClass().getMethod("getApplication").invoke(instance);
                if (application instanceof Context) return (Context) application;
            } catch (Throwable error) {
                last = error;
            }
        }
        throw new IOException("Android application context unavailable"
                + (last == null ? "" : " (" + last + ")"));
    }

    /** Best-effort PojavLauncher activity lookup; headless capture still works if this is blocked. */
    private static void attachLoginOverlay(WebView view) {
        if (view.getParent() != null) return;
        Activity activity = currentActivity();
        if (activity == null || activity.isFinishing()) return;
        try {
            activity.addContentView(view, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            view.setOnKeyListener((ignored, keyCode, event) -> {
                if (keyCode != KeyEvent.KEYCODE_BACK || event.getAction() != KeyEvent.ACTION_UP) return false;
                detachOverlay(view);
                return true;
            });
        } catch (Throwable ignored) {
        }
    }

    private static void detachOverlay(WebView view) {
        if (view.getParent() instanceof ViewGroup parent) parent.removeView(view);
    }

    private static Activity currentActivity() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("currentActivityThread").invoke(null);
            Field activitiesField = activityThread.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);
            Object activities = activitiesField.get(thread);
            if (!(activities instanceof Map<?, ?> records)) return null;
            for (Object record : records.values()) {
                Field pausedField = record.getClass().getDeclaredField("paused");
                pausedField.setAccessible(true);
                if (pausedField.getBoolean(record)) continue;
                Field activityField = record.getClass().getDeclaredField("activity");
                activityField.setAccessible(true);
                Object activity = activityField.get(record);
                if (activity instanceof Activity current) return current;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private interface MainAction {
        void run() throws Exception;
    }

    private static void onMain(MainAction action) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                action.run();
                return;
            } catch (Exception error) {
                throw new IOException("Android WebView action failed", error);
            }
        }
        final Throwable[] error = new Throwable[1];
        final CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                action.run();
            } catch (Throwable failure) {
                error[0] = failure;
            } finally {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IOException("Android main thread is busy");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the Android main thread", interrupted);
        }
        if (error[0] != null) throw new IOException("Android WebView action failed", error[0]);
    }
}
