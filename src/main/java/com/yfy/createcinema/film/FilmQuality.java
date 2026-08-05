package com.yfy.createcinema.film;

public enum FilmQuality {
    SMOOTH("smooth", 320, 180, 12, 0.58f, 500_000, 96_000),
    STANDARD("standard", 640, 360, 15, 0.72f, 1_200_000, 128_000),
    HIGH("high", 854, 480, 20, 0.85f, 2_000_000, 160_000),
    HD("hd", 1280, 720, 20, 0.82f, 4_000_000, 192_000),
    ULTRA("ultra", 1920, 1080, 20, 0.78f, 8_000_000, 224_000);

    private final String id;
    private final int maxWidth;
    private final int maxHeight;
    private final int fps;
    private final float jpegQuality;
    private final int videoBitrate;
    private final int audioBitrate;

    FilmQuality(String id, int maxWidth, int maxHeight, int fps, float jpegQuality, int videoBitrate, int audioBitrate) {
        this.id = id;
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.fps = fps;
        this.jpegQuality = jpegQuality;
        this.videoBitrate = videoBitrate;
        this.audioBitrate = audioBitrate;
    }

    public String id() { return id; }
    public int maxWidth() { return maxWidth; }
    public int maxHeight() { return maxHeight; }
    public int fps() { return fps; }
    public float jpegQuality() { return jpegQuality; }
    public int videoBitrate() { return videoBitrate; }
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
