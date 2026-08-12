package com.yfy.createcinema.client.browser;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Minimal Chrome DevTools Protocol connection over WebSocket. */
public final class CdpConnection {
    interface EventListener {
        void onEvent(String method, JsonObject params, String sessionId);

        void onError(Throwable error);
    }

    private final WebSocket socket;
    private final EventListener listener;
    private final java.util.concurrent.atomic.AtomicLong nextId = new java.util.concurrent.atomic.AtomicLong(1);
    private final java.util.Map<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final Object sendLock = new Object();
    private StringBuilder partialText = new StringBuilder();

    CdpConnection(URI uri, EventListener listener) throws IOException {
        this.listener = listener;
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        CompletableFuture<WebSocket> future = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, new WebListener());
        WebSocket connected;
        try {
            connected = future.join();
        } catch (CompletionException error) {
            throw new IOException("Could not connect to the DevTools WebSocket", error.getCause());
        }
        this.socket = connected;
        socket.request(Long.MAX_VALUE);
    }

    JsonObject send(String method, JsonObject params, String sessionId, long timeoutMillis) throws IOException {
        long id = nextId.getAndIncrement();
        JsonObject message = new JsonObject();
        message.addProperty("id", id);
        message.addProperty("method", method);
        if (sessionId != null && !sessionId.isEmpty()) message.addProperty("sessionId", sessionId);
        if (params != null) message.add("params", params);
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            synchronized (sendLock) {
                socket.sendText(message.toString(), true).join();
            }
            JsonObject response = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (response == null) throw new IOException("No response for " + method);
            if (response.has("error")) {
                JsonObject error = response.getAsJsonObject("error");
                String text = error == null ? "unknown error" : error.toString();
                throw new IOException("Chromium command " + method + " failed: " + text);
            }
            JsonObject result = response.getAsJsonObject("result");
            return result == null ? new JsonObject() : result;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for " + method, error);
        } catch (java.util.concurrent.ExecutionException error) {
            throw new IOException("Chromium command " + method + " failed", error.getCause());
        } catch (java.util.concurrent.TimeoutException error) {
            throw new IOException("Chromium command " + method + " timed out", error);
        } catch (CompletionException error) {
            throw new IOException("Chromium WebSocket command " + method + " failed", error.getCause());
        } finally {
            pending.remove(id);
        }
    }

    void close() {
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        } catch (Throwable ignored) {
        }
    }

    private void handleMessage(String text) {
        try {
            JsonObject message = JsonParser.parseString(text).getAsJsonObject();
            if (message.has("id")) {
                CompletableFuture<JsonObject> future = pending.remove(message.get("id").getAsLong());
                if (future != null) future.complete(message);
                return;
            }
            if (!message.has("method")) return;
            String method = message.get("method").getAsString();
            JsonObject params = message.has("params") ? message.getAsJsonObject("params") : new JsonObject();
            String sessionId = message.has("sessionId") ? message.get("sessionId").getAsString() : null;
            listener.onEvent(method, params, sessionId);
        } catch (Throwable ignored) {
        }
    }

    private final class WebListener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (last) {
                partialText.append(data);
                String message = partialText.toString();
                partialText.setLength(0);
                handleMessage(message);
            } else {
                partialText.append(data);
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            pending.values().forEach(future -> future.completeExceptionally(error));
            listener.onError(error);
        }
    }
}
