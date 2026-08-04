package com.yfy.createcinema;

public final class PlaybackSpeeds {
    public static final double BASE_RPM = 64.0;

    private PlaybackSpeeds() {
    }

    public static double secondsPerTick(float speed) {
        return rate(speed) / 20.0;
    }

    public static float rate(float speed) {
        return Math.max(0.5f, Math.min(2.0f, (float) (Math.abs(speed) / BASE_RPM)));
    }
}
