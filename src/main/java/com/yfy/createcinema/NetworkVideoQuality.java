package com.yfy.createcinema;

public enum NetworkVideoQuality {
    SMOOTH(0, 854, 480, 24.0, 32, "hd", "gui.createcinema.network_quality.smooth"),
    HIGH(1, 1280, 720, 30.0, 64, "shd", "gui.createcinema.network_quality.high"),
    ULTRA(2, 1920, 1080, 30.0, 80, "fhd", "gui.createcinema.network_quality.ultra");

    private final int id;
    private final int maxWidth;
    private final int maxHeight;
    private final double maxFps;
    private final int bilibiliQn;
    private final String tencentDefinition;
    private final String translationKey;

    NetworkVideoQuality(int id, int maxWidth, int maxHeight, double maxFps, int bilibiliQn,
                        String tencentDefinition, String translationKey) {
        this.id = id;
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.maxFps = maxFps;
        this.bilibiliQn = bilibiliQn;
        this.tencentDefinition = tencentDefinition;
        this.translationKey = translationKey;
    }

    public int id() { return id; }
    public int maxWidth() { return maxWidth; }
    public int maxHeight() { return maxHeight; }
    public double maxFps() { return maxFps; }
    public int bilibiliQn() { return bilibiliQn; }
    public String tencentDefinition() { return tencentDefinition; }
    public String translationKey() { return translationKey; }

    public NetworkVideoQuality next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public static NetworkVideoQuality byId(int id) {
        for (NetworkVideoQuality quality : values()) if (quality.id == id) return quality;
        return HIGH;
    }
}
