package com.yfy.createcinema;

/** Per-client target used when a network projector enables member quality. */
public enum BilibiliMemberQuality {
    FHD60(1, 1920, 1080, 60.0, "gui.createcinema.bilibili_member_quality.fhd60"),
    QHD60(3, 2560, 1440, 60.0, "gui.createcinema.bilibili_member_quality.qhd60"),
    UHD60(2, 3840, 2160, 60.0, "gui.createcinema.bilibili_member_quality.uhd60");

    private final int configId;
    private final int maxWidth;
    private final int maxHeight;
    private final double maxFps;
    private final String translationKey;

    BilibiliMemberQuality(int configId, int maxWidth, int maxHeight, double maxFps, String translationKey) {
        this.configId = configId;
        this.maxWidth = maxWidth;
        this.maxHeight = maxHeight;
        this.maxFps = maxFps;
        this.translationKey = translationKey;
    }

    public int configId() { return configId; }
    public int maxWidth() { return maxWidth; }
    public int maxHeight() { return maxHeight; }
    public double maxFps() { return maxFps; }
    public String translationKey() { return translationKey; }

    public BilibiliMemberQuality next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /** Legacy values 0/1/2 remain compatible with the previous default, 1080P60, and 4K setting. */
    public static BilibiliMemberQuality byConfigId(int id) {
        return switch (id) {
            case 2 -> UHD60;
            case 3 -> QHD60;
            default -> FHD60;
        };
    }
}
