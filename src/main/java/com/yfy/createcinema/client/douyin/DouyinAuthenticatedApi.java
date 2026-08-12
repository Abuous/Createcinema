package com.yfy.createcinema.client.douyin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.yfy.createcinema.ClientConfig;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class DouyinAuthenticatedApi {
    private DouyinAuthenticatedApi() {
    }

    static boolean canRequestRecommendations() {
        return ClientConfig.douyinBrowserAuthorization();
    }

    static boolean hasAuthorization() {
        return ClientConfig.douyinBrowserAuthorization();
    }

    static List<JsonObject> recommendations(String awemeId) throws IOException {
        return items(DouyinBrowserBridge.captureRecommendations(awemeId));
    }

    static JsonObject detail(String awemeId) throws IOException {
        JsonObject response = DouyinBrowserBridge.captureDetail(awemeId);
        JsonObject detail = object(response, "aweme_detail");
        if (detail != null) return detail;
        JsonObject data = object(response, "data");
        detail = object(data, "aweme_detail");
        if (detail != null) return detail;
        List<JsonObject> candidates = items(response);
        if (!candidates.isEmpty()) return candidates.getFirst();
        throw new IOException("Douyin browser detail response has no playable video");
    }

    static List<JsonObject> feed() throws IOException {
        List<JsonObject> items = items(DouyinBrowserBridge.captureFeed());
        if (items.isEmpty()) throw new IOException("Douyin browser feed returned no playable entries");
        return items;
    }

    static JsonObject liveRoom(String webRid) throws IOException {
        JsonObject response = DouyinBrowserBridge.captureLive(webRid);
        JsonObject data = object(response, "data");
        JsonArray rooms = array(data, "data");
        if (rooms == null || rooms.isEmpty())
            throw new IOException("Douyin browser live response has no room data");
        return response;
    }

    private static List<JsonObject> items(JsonObject response) {
        JsonArray array = response.has("aweme_list") && response.get("aweme_list").isJsonArray()
                ? response.getAsJsonArray("aweme_list")
                : response.has("data") && response.get("data").isJsonArray() ? response.getAsJsonArray("data") : null;
        List<JsonObject> items = new ArrayList<>();
        if (array == null) return items;
        array.forEach(element -> {
            if (!element.isJsonObject()) return;
            JsonObject item = unwrap(element.getAsJsonObject());
            if (item != null) items.add(item);
        });
        return items;
    }

    private static JsonObject unwrap(JsonObject value) {
        for (String key : List.of("aweme_info", "item", "aweme")) {
            if (value.has(key) && value.get(key).isJsonObject()) return value.getAsJsonObject(key);
        }
        return value;
    }

    private static JsonObject object(JsonObject parent, String name) {
        return parent != null && parent.has(name) && parent.get(name).isJsonObject()
                ? parent.getAsJsonObject(name) : null;
    }

    private static JsonArray array(JsonObject parent, String name) {
        return parent != null && parent.has(name) && parent.get(name).isJsonArray()
                ? parent.getAsJsonArray(name) : null;
    }
}
