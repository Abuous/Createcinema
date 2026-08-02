package com.yfy.createcinema.film;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public record FilmMetadata(int formatVersion, String id, String title, int fps, int width, int height,
                           int frameCount, String hash, String quality, boolean hasAudio) {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public double durationSeconds() {
        return fps <= 0 ? 0.0 : frameCount / (double) fps;
    }

    public FilmQuality qualityProfile() {
        return FilmQuality.fromId(quality);
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static FilmMetadata fromJson(String json) {
        return GSON.fromJson(json, FilmMetadata.class);
    }
}
