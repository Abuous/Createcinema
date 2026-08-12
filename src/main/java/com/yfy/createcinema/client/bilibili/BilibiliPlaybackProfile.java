package com.yfy.createcinema.client.bilibili;

import com.yfy.createcinema.BilibiliMemberQuality;
import com.yfy.createcinema.NetworkVideoQuality;

/** Local decode limits for a Bilibili stream after membership and source capabilities are considered. */
public record BilibiliPlaybackProfile(int requestedQn, int maxWidth, int maxHeight, double maxFps,
                               int maxBufferedFrames, boolean member) {
    private static final long MEMBER_FRAME_BUDGET_BYTES = 128L * 1024L * 1024L;
    private static final int DEFAULT_FRAME_LIMIT = 64;

    static BilibiliPlaybackProfile forProjector(NetworkVideoQuality quality, boolean vip,
                                                BilibiliMemberQuality memberQuality) {
        if (!quality.isMemberQuality()) return standard(quality);
        if (!vip) return standard(NetworkVideoQuality.ULTRA);
        return member(memberQuality);
    }

    public static BilibiliPlaybackProfile standard(NetworkVideoQuality quality) {
        NetworkVideoQuality effective = quality.isMemberQuality() ? NetworkVideoQuality.ULTRA : quality;
        return new BilibiliPlaybackProfile(effective.bilibiliQn(), effective.maxWidth(), effective.maxHeight(),
                effective.maxFps(), DEFAULT_FRAME_LIMIT, false);
    }

    private static BilibiliPlaybackProfile member(BilibiliMemberQuality quality) {
        long bytesPerFrame = (long) quality.maxWidth() * quality.maxHeight() * 4L;
        int frameLimit = (int) Math.max(2L, Math.min(16L, MEMBER_FRAME_BUDGET_BYTES / bytesPerFrame));
        // Request the full member manifest, then select the best decodable stream within the local target.
        return new BilibiliPlaybackProfile(120, quality.maxWidth(), quality.maxHeight(), quality.maxFps(),
                frameLimit, true);
    }
}
