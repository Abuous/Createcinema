package com.yfy.createcinema.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Tiny builder for CDP command parameters. */
final class JsonParams {
    private JsonParams() {
    }

    static JsonObject of(String key, String value) {
        JsonObject params = new JsonObject();
        params.addProperty(key, value);
        return params;
    }

    static JsonObject of(String key, long value) {
        JsonObject params = new JsonObject();
        params.addProperty(key, value);
        return params;
    }

    static JsonObject of(String key1, String value1, String key2, int value2) {
        JsonObject params = new JsonObject();
        params.addProperty(key1, value1);
        params.addProperty(key2, value2);
        return params;
    }

    static JsonObject of(String key1, String value1, String key2, boolean value2) {
        JsonObject params = new JsonObject();
        params.addProperty(key1, value1);
        params.addProperty(key2, value2);
        return params;
    }

    static JsonObject of(String key1, String value1, String key2, JsonElement value2) {
        JsonObject params = new JsonObject();
        params.addProperty(key1, value1);
        params.add(key2, value2);
        return params;
    }

    static JsonObject of(String key1, int value1, String key2, JsonElement value2) {
        JsonObject params = new JsonObject();
        params.addProperty(key1, value1);
        params.add(key2, value2);
        return params;
    }
}
