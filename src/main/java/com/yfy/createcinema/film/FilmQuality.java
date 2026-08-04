package com.yfy.createcinema.film;

public enum FilmQuality {
    SMOOTH("smooth", 320, 180, 12, 0.58f, 96_000),
    STANDARD("standard", 640, 360, 15, 0.72f, 128_000),
    HIGH("high", 854, 480, 20, 0.85f, 160_000);

    private final String id;
    private final int maxWidth;
    private final int maxHeight;
    private final int fps;
    private final float jpegQuality;
    private final int audioBitrate;

    FilmQuality(String id, int maxWidth, int maxHeight, int fps, float jpegQuality, int audioBitrate) {
        this.id = id;
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.fps = fps;
        this.jpegQuality = jpegQuality;
        this.audioBitrate = audioBitrate;
    }

    public String id() { return id; }
    public int maxWidth() { return maxWidth; }
    public int maxHeight() { return maxHeight; }
    public int fps() { return fps; }
    public float jpegQuality() { return jpegQuality; }
    public int audioBitrate() { return audioBitrate; }

    public static FilmQuality fromId(String id) {
        if (id != null) {
            for (FilmQuality quality : values()) {
                if (quality.id.equals(id)) return quality;
            }
        }
        return STANDARD;
    }
}
