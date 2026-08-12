package com.yfy.createcinema.client.network;

import com.yfy.createcinema.client.video.VideoResolverHttp;
import com.yfy.createcinema.client.render.NetworkProjectionFrame;
import com.yfy.createcinema.client.video.IqiyiVideoResolver;
import com.yfy.createcinema.client.film.HlsStreamCache;
import com.yfy.createcinema.client.douyin.DouyinVideoResolver;
import com.yfy.createcinema.client.douyin.DouyinLiveResolver;
import com.yfy.createcinema.client.douyin.DouyinBrowserBridge;
import com.yfy.createcinema.client.render.ClientVideoBurner;
import com.yfy.createcinema.client.audio.ClientNetworkProjectorAudio;
import com.yfy.createcinema.client.video.CctvLiveResolver;
import com.yfy.createcinema.client.browser.BrowserProvider;
import com.yfy.createcinema.client.bilibili.BilibiliVideoResolver;
import com.yfy.createcinema.client.bilibili.BilibiliSession;
import com.yfy.createcinema.client.bilibili.BilibiliResolver;
import com.yfy.createcinema.client.bilibili.BilibiliPlaybackProfile;
import com.yfy.createcinema.client.bilibili.BilibiliBangumiResolver;
import com.mojang.blaze3d.platform.NativeImage;
import com.yfy.createcinema.CreateCinema;
import com.yfy.createcinema.NetworkVideoQuality;
import com.yfy.createcinema.blockentity.NetworkProjectorBlockEntity;
import com.yfy.createcinema.packet.C2SNetworkProjectorDouyinContentPacket;
import com.yfy.createcinema.packet.C2SNetworkProjectorMediaInfoPacket;
import com.yfy.createcinema.packet.C2SNetworkProjectorPlaybackHealthPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.world.level.Level;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;

public final class ClientNetworkProjectorStreams {
    private static final int MAX_BUFFERED_FRAMES = 64;
    private static final int MAX_LIVE_BUFFERED_FRAMES = 32;
    private static final int LIVE_FRAME_DUMP_LIMIT = 3;
    private static final long SNAPSHOT_REFRESH_MILLIS = 40L;
    private static final double STARTUP_BUFFER_SECONDS = 1.0;
    private static final double LIVE_PLAYBACK_DELAY_SECONDS = 0.75;
    private static final double MAX_BUFFER_SECONDS = 1.50;
    private static final double MAX_LIVE_BUFFER_SECONDS = 0.90;
    private static final double HARD_RESYNC_SECONDS = 3.0;
    private static final double DISPLAY_LEAD_SECONDS = 0.035;
    private static final double PLAYOUT_DELAY_SECONDS = 1.0;
    private static final long VIDEO_UNDERRUN_GRACE_MILLIS = 250L;
    private static final long LIVE_STOP_GRACE_MILLIS = 1_000L;
    private static final long DECODE_RESUBMIT_MILLIS = 2_000L;
    private static final long DECODE_STALL_MILLIS = 15_000L;
    private static final long STALL_RESUME_TTL_MILLIS = 300_000L;
    private static final String NETWORK_RW_TIMEOUT_MICROS = "5000000";
    private static final Map<String, Session> SESSIONS = new HashMap<>();
    private static final Map<String, StallResume> STALL_RESUMES = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> RANGE_SUPPORT = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_MEDIA_REVISION = new AtomicInteger();
    private static final ExecutorService STREAM_EXECUTOR = new ThreadPoolExecutor(4, 4, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), runnable -> {
        Thread thread = new Thread(runnable, "CreateCinema Network Stream");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());

    private ClientNetworkProjectorStreams() {
    }

    public static NetworkProjectionFrame frame(NetworkProjectorBlockEntity projector, double playTime) {
        if (projector.getLevel() == null || projector.getUrl().isBlank()) return null;
        String key = sessionKey(projector);
        Session session = SESSIONS.get(key);
        if (session != null && session.failed && System.currentTimeMillis() >= session.retryAt) {
            session.close("retry");
            SESSIONS.remove(key);
            session = null;
        }
        boolean continuousEnabled = projector.hasContinuousPlayUpgrade();
        NetworkVideoQuality quality = projector.getQuality();
        if (session == null || !session.url.equals(projector.getUrl())
                || session.continuousEnabled != continuousEnabled || session.quality != quality
                || !Objects.equals(session.mediaOwner, projector.getMediaOwner())) {
            if (session != null) {
                ClientNetworkProjectorAudio.stop(projector);
                if (DouyinLiveResolver.canResolve(session.url) && !session.url.equals(projector.getUrl())) {
                    DouyinBrowserBridge.cancelPendingCapture();
                }
                session.close("projector settings changed");
            }
            StallResume resume = stallResume(key, projector.getUrl());
            double anchor = validServerAnchor(projector) ? projector.getCurrentMediaTimeSeconds() : Double.NaN;
            double itemStartGlobalTime = !Double.isNaN(anchor) ? playTime - anchor
                    : resume != null ? playTime - resume.seconds() : PLAYOUT_DELAY_SECONDS;
            if (!Double.isNaN(anchor)) {
                CreateCinema.LOGGER.info("Starting network stream {} from server anchor {}", projector.getUrl(),
                        formatSeconds(anchor));
            } else if (resume != null) {
                CreateCinema.LOGGER.info("Resuming network stream {} at breakpoint {}", projector.getUrl(),
                        formatSeconds(resume.seconds()));
            } else {
                CreateCinema.LOGGER.debug("Starting network stream {} from {}", projector.getUrl(),
                        formatSeconds(Math.max(0.0, playTime)));
            }
            session = new Session(key, projector, projector.getUrl(), continuousEnabled, quality, playTime,
                    itemStartGlobalTime,
                    projector.getNavigationRevision(), projector.getNavigationOffset(), -1, 0);
            session.stallResume = Double.isNaN(anchor) && resume != null;
            session.resumeSeconds = Double.isNaN(anchor) && resume != null ? resume.seconds() : Double.NaN;
            SESSIONS.put(key, session);
        } else if (session.navigationRevision != projector.getNavigationRevision()
                && (session.continuousPlaylist() || session.sharedDouyinSource)) {
            int navigationDelta = projector.getNavigationOffset() - session.navigationOffset;
            int requestedIndex = session.playlist == null ? -1 : session.playlistIndex + navigationDelta;
            BilibiliResolver.ResolvedPlaylist retainedPlaylist = session.sharedDouyinSource
                    && session.isLocalMediaOwner() ? session.playlist : null;
            int startOffset = retainedPlaylist == null ? projector.getNavigationOffset() : 0;
            ClientNetworkProjectorAudio.stop(projector);
            session.close("playlist navigation");
            session = new Session(key, projector, projector.getUrl(), continuousEnabled, quality, playTime,
                    playTime + PLAYOUT_DELAY_SECONDS,
                    projector.getNavigationRevision(), projector.getNavigationOffset(), requestedIndex, startOffset,
                    retainedPlaylist);
            SESSIONS.put(key, session);
        } else if (session.sharedDouyinSelectionChanged()) {
            ClientNetworkProjectorAudio.stop(projector);
            session.close("shared Douyin selection changed");
            session = new Session(key, projector, projector.getUrl(), continuousEnabled, quality, playTime,
                    playTime + PLAYOUT_DELAY_SECONDS,
                    projector.getNavigationRevision(), projector.getNavigationOffset(), -1, 0);
            SESSIONS.put(key, session);
        }
        session.startDecodeIfReady();
        session.setGlobalPaused(projector.isGloballyPaused());
        session.resume();
        session.updateTarget(playTime);
        session.applyServerAnchor(projector);
        session.lastTouched = System.currentTimeMillis();
        NetworkProjectionFrame frame = session.uploadReady();
        session.publishDisplayInfo();
        return frame;
    }

    public static BilibiliResolver.ResolvedMedia source(NetworkProjectorBlockEntity projector) {
        AudioSource source = audioSource(projector);
        return source == null ? null : source.media();
    }

    public static AudioSource audioSource(NetworkProjectorBlockEntity projector) {
        if (projector.getLevel() == null) return null;
        Session session = SESSIONS.get(sessionKey(projector));
        if (session == null || session.closed || session.failed || !session.videoReadyForAudio
                || !session.url.equals(projector.getUrl()) || session.source == null) {
            return null;
        }
        String key = session.key + "/url/" + session.url + "/" + session.mediaRevision;
        return new AudioSource(key, session.source);
    }

    public static double mediaTime(NetworkProjectorBlockEntity projector) {
        if (projector.getLevel() == null) return projector.getPlayTime();
        Session session = SESSIONS.get(sessionKey(projector));
        return session == null ? projector.getPlayTime() : session.mediaTime();
    }

    public static int mediaRevision(NetworkProjectorBlockEntity projector) {
        if (projector.getLevel() == null) return 0;
        Session session = SESSIONS.get(sessionKey(projector));
        return session == null ? 0 : session.mediaRevision;
    }

    public static Status status(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return Status.IDLE;
        Session session = SESSIONS.get(sessionKey(minecraft.level, pos));
        return status(session);
    }

    public static Status status(NetworkProjectorBlockEntity projector) {
        return status(projector.getLevel() == null ? null : SESSIONS.get(sessionKey(projector)));
    }

    private static Status status(Session session) {
        if (session == null) return Status.IDLE;
        if (session.failed) return Status.ERROR;
        if (session.ended) return Status.ENDED;
        if (session.source == null || session.textureLocation == null || !session.bufferReady) {
            return Status.LOADING;
        }
        return Status.PLAYING;
    }

    public static float progress(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return 0.0f;
        Session session = SESSIONS.get(sessionKey(minecraft.level, pos));
        return progress(session);
    }

    public static float progress(NetworkProjectorBlockEntity projector) {
        return progress(projector.getLevel() == null ? null : SESSIONS.get(sessionKey(projector)));
    }

    private static float progress(Session session) {
        if (session == null || session.failed) return 0.0f;
        return Math.max(0.0f, Math.min(1.0f, session.progress));
    }

    public static Component message(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return Component.empty();
        Session session = SESSIONS.get(sessionKey(minecraft.level, pos));
        return message(session);
    }

    public static Component message(NetworkProjectorBlockEntity projector) {
        return message(projector.getLevel() == null ? null : SESSIONS.get(sessionKey(projector)));
    }

    private static Component message(Session session) {
        if (session == null) return Component.empty();
        if (session.failed) return session.errorMessage == null
                ? Component.translatable("gui.createcinema.stream.error_short")
                : session.errorMessage;
        if (session.ended) return Component.translatable("gui.createcinema.stream.ended");
        if (session.stallResume && !session.bufferReady) {
            return Component.translatable("gui.createcinema.stream.recovering",
                    formatSeconds(session.resumeSeconds));
        }
        return Component.translatable("gui.createcinema.stream.loading_progress", Math.round(session.progress * 100.0f));
    }

    private static StallResume stallResume(String key, String url) {
        StallResume resume = STALL_RESUMES.get(key);
        if (resume == null || !resume.url().equals(url)) return null;
        return resume;
    }

    private static boolean validServerAnchor(NetworkProjectorBlockEntity projector) {
        return projector.getMediaRevision() > 0 && !projector.isMediaLive()
                && projector.getMediaStatus() != NetworkProjectorBlockEntity.MediaStatus.ENDED
                && projector.getMediaTimeSeconds() >= 0.0;
    }

    private static boolean supportsRangeSeek(String mediaUrl, String referer, Map<String, String> headers) {
        Boolean known = RANGE_SUPPORT.get(mediaUrl);
        if (known != null) return known;
        boolean supported = VideoResolverHttp.supportsRange(mediaUrl, referer, headers);
        RANGE_SUPPORT.put(mediaUrl, supported);
        return supported;
    }

    private static String formatSeconds(double seconds) {
        if (Double.isNaN(seconds) || seconds < 0.0) seconds = 0.0;
        int total = (int) Math.floor(seconds);
        return String.format(java.util.Locale.ROOT, "%d:%02d", total / 60, total % 60);
    }

    public static Component playlistMessage(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return Component.empty();
        Session session = SESSIONS.get(sessionKey(minecraft.level, pos));
        if (session == null || session.playlist == null) return Component.empty();
        int count = session.displayPlaylistCount();
        if (count <= 1) return Component.translatable("gui.createcinema.playlist.single");
        return Component.translatable("gui.createcinema.playlist.detected", count,
                session.displayPlaylistIndex() + 1, count);
    }

    public static void requestRetry(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;
        Session session = SESSIONS.get(minecraft.level.dimension().location() + "/" + pos.asLong());
        if (session != null) session.retryAt = 0L;
    }

    public static void requestDouyinRetry() {
        requestBrowserRetry(BrowserProvider.DOUYIN);
    }

    /** Reopens only local Bilibili member-mode streams after this viewer changes their target profile. */
    public static void refreshBilibiliMemberSources() {
        Minecraft.getInstance().execute(ClientNetworkProjectorStreams::refreshBilibiliMemberSourcesNow);
    }

    private static void refreshBilibiliMemberSourcesNow() {
        SESSIONS.entrySet().removeIf(entry -> {
            Session session = entry.getValue();
            if (!session.quality.isMemberQuality()
                    || !(BilibiliVideoResolver.canResolve(session.url) || BilibiliBangumiResolver.canResolve(session.url))) {
                return false;
            }
            ClientNetworkProjectorAudio.stop(session.projector);
            session.close("local Bilibili member quality changed");
            return true;
        });
    }

    public static void requestBrowserRetry(BrowserProvider provider) {
        SESSIONS.entrySet().removeIf(entry -> {
            Session session = entry.getValue();
            boolean matches = switch (provider) {
                case DOUYIN -> DouyinVideoResolver.canResolve(session.url) || DouyinLiveResolver.canResolve(session.url);
                case IQIYI -> IqiyiVideoResolver.canResolve(session.url);
            };
            if (!matches) return false;
            session.close(provider.id() + " authorization changed");
            return true;
        });
    }

    public static void stop(NetworkProjectorBlockEntity projector) {
        if (projector.getLevel() == null) return;
        String key = sessionKey(projector);
        STALL_RESUMES.remove(key);
        Session session = SESSIONS.get(key);
        if (session != null && session.retainWhileUnpowered(projector)) return;
        session = SESSIONS.remove(key);
        if (session != null) session.close("projector stopped");
    }

    public static void tick() {
        long now = System.currentTimeMillis();
        long cutoff = now - 60_000L;
        STALL_RESUMES.entrySet().removeIf(entry -> now - entry.getValue().recordedAt() > STALL_RESUME_TTL_MILLIS);
        SESSIONS.entrySet().removeIf(entry -> {
            Session session = entry.getValue();
            session.reportPlaybackHealth(now, false);
            if (session.projectorInvalid()) {
                session.close("projector state changed");
                return true;
            }
            if (session.liveStopStartedAt > 0L
                    && now - session.liveStopStartedAt >= LIVE_STOP_GRACE_MILLIS) {
                session.close("live projector stop grace expired");
                return true;
            }
            if (session.stalled(now)) {
                return true;
            }
            if (session.lastTouched >= cutoff) return false;
            session.close("not rendered for 60 seconds");
            return true;
        });
    }

    private static String sessionKey(NetworkProjectorBlockEntity projector) {
        return projector.getLevel() == null ? "" : sessionKey(projector.getLevel(), projector.getBlockPos());
    }

    private static String sessionKey(Level level, BlockPos pos) {
        return Integer.toUnsignedString(System.identityHashCode(level)) + "/"
                + level.dimension().location() + "/" + pos.asLong();
    }

    private static boolean isSharedDouyinSource(String url) {
        return DouyinVideoResolver.canResolve(url) && !DouyinLiveResolver.canResolve(url)
                && !DouyinVideoResolver.isLongVideo(url);
    }

    public static void closeAll() {
        boolean hadBrowserSession = SESSIONS.values().stream().anyMatch(session ->
                DouyinLiveResolver.canResolve(session.url) || DouyinVideoResolver.canResolve(session.url)
                        || IqiyiVideoResolver.canResolve(session.url));
        SESSIONS.values().forEach(session -> session.close("client level cleared"));
        SESSIONS.clear();
        STALL_RESUMES.clear();
        HlsStreamCache.clear();
        RANGE_SUPPORT.clear();
        if (hadBrowserSession) DouyinBrowserBridge.cancelPendingCapture();
    }

    private static class Session {
        private final String key;
        private final NetworkProjectorBlockEntity projector;
        private final String url;
        private final boolean continuousEnabled;
        private final NetworkVideoQuality quality;
        private volatile BilibiliPlaybackProfile playbackProfile =
                BilibiliPlaybackProfile.standard(NetworkVideoQuality.HIGH);
        private final ArrayDeque<DecodedFrame> bufferedFrames = new ArrayDeque<>();
        private volatile BilibiliResolver.ResolvedPlaylist playlist;
        private volatile BilibiliResolver.ResolvedMedia source;
        private volatile double targetTime;
        private volatile double itemStartTime;
        private volatile double duration;
        private volatile long lastTouched = System.currentTimeMillis();
        private volatile long liveClockStartedAt;
        private volatile long liveStopStartedAt;
        private volatile boolean globalPaused;
        private volatile double pausedLiveTime;
        private volatile boolean videoReadyForAudio;
        private volatile long decodedFrameCount;
        private long uploadedFrameCount;
        private long lastVideoDiagnosticAt;
        private volatile boolean closed;
        private volatile boolean failed;
        private volatile boolean ended;
        private volatile long retryAt;
        private volatile boolean decodeResubmitPending;
        private volatile long lastDecodeHeartbeat = System.currentTimeMillis();
        private volatile float progress = 0.03f;
        private volatile boolean stallResume;
        private volatile double resumeSeconds = Double.NaN;
        private volatile Component errorMessage;
        private volatile boolean bufferReady;
        private volatile boolean rebuffering;
        private volatile long lastFrameUploadedAt = System.currentTimeMillis();
        private volatile double lastDisplayedTimestamp = Double.NaN;
        private volatile NetworkProjectorBlockEntity.PlaybackHealth playbackHealth =
                NetworkProjectorBlockEntity.PlaybackHealth.BUFFERING;
        private volatile long lastPlaybackHealthReportAt;
        private DynamicTexture texture;
        private ResourceLocation textureLocation;
        private int width;
        private int height;
        private int liveFramesDumped;
        private volatile int playlistIndex;
        private volatile int mediaRevision;
        private int publishedMediaRevision = -1;
        private double publishedDuration = Double.NaN;
        private boolean publishedLive;
        private NetworkProjectorBlockEntity.MediaStatus publishedStatus;
        private long nextMediaInfoPublish;
        private final int navigationRevision;
        private final int navigationOffset;
        private final int requestedPlaylistIndex;
        private final int playlistStartOffset;
        private final boolean sharedDouyinSource;
        private final int sharedDouyinContentRevision;
        private final UUID mediaOwner;
        private volatile boolean initialDecodeSubmitted;
        private volatile int sharedPlaylistIndex;
        private volatile int sharedPlaylistCount;
        private String publishedDouyinContentId = "";
        private volatile long lastAnchorAdjustAt;

        private Session(String key, NetworkProjectorBlockEntity projector, String url, boolean continuousEnabled, NetworkVideoQuality quality,
                        double globalPlayTime, double itemStartGlobalTime, int navigationRevision, int navigationOffset,
                        int requestedPlaylistIndex, int playlistStartOffset) {
            this(key, projector, url, continuousEnabled, quality, globalPlayTime, itemStartGlobalTime,
                    navigationRevision, navigationOffset,
                    requestedPlaylistIndex, playlistStartOffset, null);
        }

        private Session(String key, NetworkProjectorBlockEntity projector, String url, boolean continuousEnabled,
                        NetworkVideoQuality quality, double globalPlayTime, double itemStartGlobalTime,
                        int navigationRevision, int navigationOffset,
                        int requestedPlaylistIndex, int playlistStartOffset,
                        BilibiliResolver.ResolvedPlaylist retainedPlaylist) {
            this.key = key;
            this.projector = projector;
            this.url = url;
            this.continuousEnabled = continuousEnabled;
            this.quality = quality;
            this.navigationRevision = navigationRevision;
            this.navigationOffset = navigationOffset;
            this.requestedPlaylistIndex = requestedPlaylistIndex;
            this.playlistStartOffset = playlistStartOffset;
            mediaOwner = projector.getMediaOwner();
            sharedDouyinSource = isSharedDouyinSource(url);
            sharedDouyinContentRevision = matchingSharedDouyinContent()
                    ? projector.getDouyinContentRevision() : 0;
            sharedPlaylistIndex = matchingSharedDouyinContent() ? projector.getDouyinPlaylistIndex() : 0;
            sharedPlaylistCount = matchingSharedDouyinContent() ? projector.getDouyinPlaylistCount() : 0;
            targetTime = globalPlayTime;
            itemStartTime = itemStartGlobalTime;
            if (retainedPlaylist != null) {
                playlist = retainedPlaylist;
                playlistIndex = Math.max(0, Math.min(requestedPlaylistIndex, playlist.entries().size() - 1));
                sharedPlaylistIndex = playlistIndex;
                sharedPlaylistCount = playlist.entries().size();
            }
            mediaRevision = NEXT_MEDIA_REVISION.incrementAndGet();
            CreateCinema.LOGGER.debug("Created network stream session {} (continuous={}, quality={}, revision={})",
                    url, continuousEnabled, quality, mediaRevision);
            startDecodeIfReady();
        }

        private synchronized void startDecodeIfReady() {
            if (closed || failed || !canStartDecode()) return;
            if (decodeResubmitPending && System.currentTimeMillis() >= retryAt) {
                decodeResubmitPending = false;
                submitDecode();
                return;
            }
            if (initialDecodeSubmitted) return;
            initialDecodeSubmitted = true;
            submitDecode();
        }

        private boolean canStartDecode() {
            if (!sharedDouyinSource || isLocalMediaOwner()) return true;
            return matchingSharedDouyinContent();
        }

        private boolean sharedDouyinSelectionChanged() {
            return sharedDouyinSource && !isLocalMediaOwner() && matchingSharedDouyinContent()
                    && projector.getDouyinContentRevision() != sharedDouyinContentRevision;
        }

        private boolean matchingSharedDouyinContent() {
            return projector.getDouyinContentNavigationRevision() == navigationRevision
                    && !projector.getDouyinContentId().isBlank();
        }

        private boolean isLocalMediaOwner() {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft.player != null && minecraft.player.getUUID().equals(mediaOwner);
        }

        private void updateTarget(double playTime) {
            if (ended) return;
            targetTime = Math.max(targetTime, playTime);
        }

        private void resume() {
            liveStopStartedAt = 0L;
        }

        private void setGlobalPaused(boolean paused) {
            if (globalPaused == paused) return;
            if (paused && source != null && source.live()) {
                pausedLiveTime = playbackTime();
            } else if (paused && validServerAnchor(projector)) {
                double anchor = projector.getCurrentMediaTimeSeconds();
                itemStartTime += mediaDelta(mediaTime(), anchor, duration);
            }
            if (!paused && source != null && source.live() && liveClockStartedAt != 0L) {
                liveClockStartedAt = System.nanoTime()
                        - (long) ((pausedLiveTime + LIVE_PLAYBACK_DELAY_SECONDS) * 1_000_000_000.0);
            }
            globalPaused = paused;
        }

        private boolean retainWhileUnpowered(NetworkProjectorBlockEntity projector) {
            if (source == null || !source.live() || projector.isRemoved() || !url.equals(projector.getUrl())
                    || projector.isOverStressed()
                    || Math.abs(projector.getSpeed()) > 0.0f) {
                return false;
            }
            long now = System.currentTimeMillis();
            if (liveStopStartedAt == 0L) liveStopStartedAt = now;
            lastTouched = now;
            return now - liveStopStartedAt < LIVE_STOP_GRACE_MILLIS;
        }

        private boolean projectorInvalid() {
            Level level = projector.getLevel();
            if (projector.isRemoved() || level == null || level.getBlockEntity(projector.getBlockPos()) != projector) {
                ClientNetworkProjectorAudio.stop(projector);
                return true;
            }
            if (!url.equals(projector.getUrl())) {
                ClientNetworkProjectorAudio.stop(projector);
                return true;
            }
            if (projector.canProject()) {
                resume();
                return false;
            }
            ClientNetworkProjectorAudio.stop(projector);
            return !retainWhileUnpowered(projector);
        }

        private void decode() {
            boolean advance = false;
            boolean reconnect = false;
            try {
                progress = 0.08f;
                ClientVideoBurner.awaitFfmpeg();
                if (closed) return;
                progress = 0.18f;
                if (playlist == null) {
                    if (sharedDouyinSource && !isLocalMediaOwner()) {
                        String contentId = matchingSharedDouyinContent()
                                ? projector.getDouyinContentId() : DouyinVideoResolver.contentId(url);
                        if (contentId == null) return;
                        playlist = BilibiliResolver.ResolvedPlaylist.single(DouyinVideoResolver.contentUrl(contentId));
                        playlistIndex = 0;
                        if (matchingSharedDouyinContent()) {
                            itemStartTime = projector.getDouyinContentStartTime();
                            sharedPlaylistIndex = projector.getDouyinPlaylistIndex();
                            sharedPlaylistCount = projector.getDouyinPlaylistCount();
                        } else {
                            sharedPlaylistIndex = 0;
                            sharedPlaylistCount = 1;
                        }
                    } else {
                        playlist = BilibiliResolver.discoverPlaylist(url);
                        int initialIndex = requestedPlaylistIndex >= 0
                                ? requestedPlaylistIndex : playlist.startIndex() + playlistStartOffset;
                        playlistIndex = Math.max(0, Math.min(initialIndex, playlist.entries().size() - 1));
                        sharedPlaylistIndex = playlistIndex;
                        sharedPlaylistCount = playlist.entries().size();
                        CreateCinema.LOGGER.debug("Detected network playlist {} with {} entries, starting at {}", url,
                                playlist.entries().size(), playlistIndex + 1);
                    }
                }
                String mediaUrl = sharedDouyinSource || continuousPlaylist()
                        ? playlist.entries().get(playlistIndex).url() : url;
                playbackProfile = BilibiliPlaybackProfile.standard(
                        quality.isMemberQuality() ? NetworkVideoQuality.ULTRA : quality);
                if (sharedDouyinSource) {
                    String contentId = isLocalMediaOwner()
                            ? DouyinVideoResolver.contentId(mediaUrl)
                            : matchingSharedDouyinContent() ? projector.getDouyinContentId()
                            : DouyinVideoResolver.contentId(mediaUrl);
                    if (contentId == null) throw new IOException("Selected Douyin video has no content id");
                    if (isLocalMediaOwner()) publishDouyinContent(contentId);
                    source = DouyinVideoResolver.resolveAuthenticatedContent(contentId, quality);
                } else {
                    NetworkVideoQuality resolverQuality = quality;
                    boolean memberBilibiliSource = BilibiliVideoResolver.canResolve(mediaUrl)
                            || BilibiliBangumiResolver.canResolve(mediaUrl);
                    if (quality.isMemberQuality() && !memberBilibiliSource) {
                        resolverQuality = NetworkVideoQuality.ULTRA;
                    }
                    source = BilibiliResolver.resolve(mediaUrl, resolverQuality);
                    playbackProfile = memberBilibiliSource
                            ? BilibiliSession.playbackProfile(resolverQuality)
                            : BilibiliPlaybackProfile.standard(resolverQuality);
                }
                if (closed) return;
                videoReadyForAudio = false;
                if (source.live()) liveClockStartedAt = 0L;
                if (source.live() && source.snapshotUrl() != null) {
                    decodeSnapshot(source.snapshotUrl());
                    return;
                }
                progress = 0.38f;
                boolean hlsUrl = !source.live() && HlsStreamCache.isHls(source.videoUrl());
                if (hlsUrl && !HlsStreamCache.isPrepared(source.videoUrl())) {
                    HlsStreamCache.prepare(source.videoUrl(), source.referer(), source.headers());
                }
                boolean hls = hlsUrl && HlsStreamCache.isPrepared(source.videoUrl());
                double knownDuration = source.live() ? 0.0 : source.durationSeconds() > 0.0 ? source.durationSeconds()
                        : hls ? HlsStreamCache.duration(source.videoUrl()) : 0.0;
                double requestedStart = seekTime(playbackTime(), knownDuration);
                CreateCinema.LOGGER.info("Opening network video {} at {}s (hls={}, duration={}s)", url,
                        String.format(java.util.Locale.ROOT, "%.3f", requestedStart), hls,
                        String.format(java.util.Locale.ROOT, "%.3f", knownDuration));
                double streamStart = hls ? HlsStreamCache.segmentStart(source.videoUrl(), requestedStart) : 0.0;
                InputStream hlsInput = hls ? HlsStreamCache.open(source.videoUrl(), source.referer(),
                        source.headers(), requestedStart) : null;
                if (hls) HlsStreamCache.prefetch(source.videoUrl(), requestedStart, 3);
                try (InputStream input = hlsInput;
                     NativeFrameScaler scaler = new NativeFrameScaler();
                     FFmpegFrameGrabber grabber = hls ? new FFmpegFrameGrabber(input, 0)
                             : new FFmpegFrameGrabber(source.videoUrl())) {
                    configure(grabber, source.referer(), source.headers());
                    if (hls) grabber.setFormat("mpegts");
                    if (source.live() && source.videoUrl().contains(".flv")) {
                        grabber.setFormat("flv");
                        grabber.setOption("analyzeduration", "5000000");
                        grabber.setOption("probesize", "5000000");
                    }
                    if (source.live()) {
                        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
                        grabber.setPixelFormat(AV_PIX_FMT_RGBA);
                        if (CctvLiveResolver.canResolve(url)) grabber.setVideoCodecName("h264");
                    } else {
                        grabber.setImageMode(FrameGrabber.ImageMode.RAW);
                    }
                    grabber.start();
                    if (closed) return;
                    if (source.live()) {
                        CreateCinema.LOGGER.info(
                                "Opened live video stream: format={} videoCodec={} {}x{} audioCodec={}",
                                grabber.getFormat(), grabber.getVideoCodecName(),
                                grabber.getImageWidth(), grabber.getImageHeight(), grabber.getAudioCodecName());
                    }
                    progress = 0.60f;
                    duration = source.live() ? 0.0 : source.durationSeconds() > 0.0 ? source.durationSeconds()
                            : hls ? HlsStreamCache.duration(source.videoUrl())
                            : grabber.getLengthInTime() / 1_000_000.0;
                    double startTime = seekTime(playbackTime(), duration);
                    boolean directSeek = false;
                    if (!source.live() && !hls) {
                        if (startTime > 0.25) {
                            boolean rangeOk = supportsRangeSeek(source.videoUrl(), source.referer(), source.headers());
                            CreateCinema.LOGGER.info("Seek decision for {} to {}s: range={}",
                                    source.videoUrl(), String.format(java.util.Locale.ROOT, "%.3f", startTime), rangeOk);
                            if (rangeOk) {
                                try {
                                    grabber.setTimestamp((long) (startTime * 1_000_000.0));
                                    directSeek = true;
                                } catch (Exception error) {
                                    CreateCinema.LOGGER.debug("Stream seek failed for {}, decoding from start", url, error);
                                }
                            } else {
                                CreateCinema.LOGGER.info("Source does not support Range, will fast-forward from stream start");
                            }
                        }
                    }
                    long containerStartMicros = grabber.getFormatContext().start_time();
                    double timestampOrigin = source.live() || hls || containerStartMicros == AV_NOPTS_VALUE ? Double.NaN
                            : containerStartMicros / 1_000_000.0;
                    double timestampTarget = directSeek ? startTime : 0.0;
                    progress = 0.72f;
                    double nextPublishTimestamp = -1.0;
                    double lastTimelineTimestamp = Double.NaN;
                    decodeLoop:
                    while (!closed) {
                        Frame frame = grabber.grabImage();
                        if (frame == null) {
                            if (source.live()) {
                                markBufferReady();
                                reconnect = true;
                                break;
                            }
                            if (continuousPlaylist()) {
                                markBufferReady();
                                awaitPlaybackEnd();
                                if (playlistIndex + 1 < playlist.entries().size()) advance = true;
                                else finishPlaylist();
                                break;
                            }
                            if (hls || duration <= 0.0) {
                                markBufferReady();
                                break;
                            }
                            grabber.setTimestamp(0L);
                            nextPublishTimestamp = -1.0;
                            continue;
                        }
                        lastDecodeHeartbeat = System.currentTimeMillis();
                        if (source.live() && liveClockStartedAt == 0L) {
                            liveClockStartedAt = System.nanoTime();
                            if (globalPaused) pausedLiveTime = 0.0;
                        }
                        double rawTimestamp = frame.timestamp / 1_000_000.0;
                        if (Double.isNaN(timestampOrigin)) {
                            timestampOrigin = hls ? rawTimestamp - streamStart : rawTimestamp - timestampTarget;
                            if (!hls && duration > 0.0) {
                                CreateCinema.LOGGER.debug("Timeline origin for {} = first raw PTS {}s", url,
                                        String.format(java.util.Locale.ROOT, "%.3f", rawTimestamp));
                            }
                        }
                        double timestamp = rawTimestamp - timestampOrigin;
                        if (duration > 0.0) timestamp = wrap(timestamp, duration);
                        if (duration <= 0.0 && !Double.isNaN(lastTimelineTimestamp)
                                && (timestamp < lastTimelineTimestamp - 1.0 || timestamp > lastTimelineTimestamp + 10.0)) {
                            timestampOrigin = rawTimestamp - lastTimelineTimestamp - 1.0 / Math.max(1.0, grabber.getFrameRate());
                            timestamp = rawTimestamp - timestampOrigin;
                            nextPublishTimestamp = -1.0;
                        }
                        lastTimelineTimestamp = timestamp;
                        while (!closed) {
                            double masterTime = wrap(playbackTime(), duration);
                            double drift = mediaDelta(timestamp, masterTime, duration);
                            if (source.live() && drift < -HARD_RESYNC_SECONDS) {
                                clearBufferedFrames(true);
                                liveClockStartedAt = System.nanoTime()
                                        - (long) ((timestamp + LIVE_PLAYBACK_DELAY_SECONDS) * 1_000_000_000.0);
                                nextPublishTimestamp = -1.0;
                                break;
                            }
                            if (directSeek && duration > 0.0 && drift < -HARD_RESYNC_SECONDS) {
                                clearBufferedFrames(true);
                                grabber.setTimestamp((long) (masterTime * 1_000_000.0));
                                timestampOrigin = Double.NaN;
                                timestampTarget = masterTime;
                                lastTimelineTimestamp = Double.NaN;
                                nextPublishTimestamp = -1.0;
                                continue decodeLoop;
                            }
                            double maxBufferSeconds = source.live() ? MAX_LIVE_BUFFER_SECONDS : MAX_BUFFER_SECONDS;
                            if (drift <= maxBufferSeconds) break;
                            lastDecodeHeartbeat = System.currentTimeMillis();
                            Thread.sleep(4L);
                        }
                        if (mediaDelta(timestamp, wrap(playbackTime(), duration), duration) < -0.12) continue;
                        if (nextPublishTimestamp < 0.0 || timestamp - nextPublishTimestamp > 0.5) {
                            nextPublishTimestamp = timestamp;
                        }
                        if (timestamp + 0.001 < nextPublishTimestamp) continue;
                        DecodedFrame next = scaler.decode(frame, playbackProfile.maxWidth(), playbackProfile.maxHeight());
                        if (next != null) {
                            if (closed) {
                                next.image.close();
                                break;
                            }
                            if (source.live() && liveFramesDumped < LIVE_FRAME_DUMP_LIMIT) {
                                dumpLiveFrame(next, liveFramesDumped++);
                            }
                            enqueue(new DecodedFrame(next.width, next.height, next.image, timestamp));
                            nextPublishTimestamp += 1.0 / playbackProfile.maxFps();
                        }
                    }
                    grabber.stop();
                }
            } catch (Throwable error) {
                if (!closed) {
                    recordStallResume();
                    progress = 0.0f;
                    failed = true;
                    videoReadyForAudio = false;
                    errorMessage = visibleError(url, error);
                    String details = nestedMessage(error).toLowerCase(java.util.Locale.ROOT);
                    boolean configurationFailure = details.contains("douyin cookie is not configured")
                            || details.contains("douyin cookie is incomplete")
                            || details.contains("douyin browser authorization")
                            || details.contains("douyin recommendation authorization")
                            || details.contains("douyin authenticated")
                            || details.contains("requires verification")
                            || ClientVideoBurner.ffmpegLoadFailed();
                    playbackHealth = configurationFailure || !networkFailure(details)
                            ? NetworkProjectorBlockEntity.PlaybackHealth.LOCAL_FAILURE
                            : NetworkProjectorBlockEntity.PlaybackHealth.SOURCE_UNREACHABLE;
                    reportPlaybackHealth(System.currentTimeMillis(), true);
                    retryAt = configurationFailure ? Long.MAX_VALUE : System.currentTimeMillis() + 5_000L;
                    if (configurationFailure) {
                        CreateCinema.LOGGER.warn("Network projector requires updated Douyin client authorization for {}", url);
                    } else {
                        CreateCinema.LOGGER.warn("Network projector failed to stream {}", url, error);
                    }
                }
            }
            if (advance && !closed) advancePlaylist();
            else if (reconnect && !closed) reconnectLiveStream();
        }

        private boolean continuousPlaylist() {
            return continuousEnabled && playlist != null && playlist.entries().size() > 1;
        }

        private int displayPlaylistIndex() {
            return sharedDouyinSource && !isLocalMediaOwner() ? sharedPlaylistIndex : playlistIndex;
        }

        private int displayPlaylistCount() {
            if (sharedDouyinSource && !isLocalMediaOwner()) return sharedPlaylistCount;
            return playlist == null ? 0 : playlist.entries().size();
        }

        private void publishDouyinContent(String contentId) {
            if (contentId.equals(publishedDouyinContentId)) return;
            publishedDouyinContentId = contentId;
            int selectedIndex = playlist == null ? 0 : playlistIndex;
            int selectedCount = playlist == null ? 1 : playlist.entries().size();
            double selectedStartTime = itemStartTime;
            Minecraft.getInstance().execute(() -> {
                if (closed || !isLocalMediaOwner() || !url.equals(projector.getUrl())) return;
                new C2SNetworkProjectorDouyinContentPacket(projector.getBlockPos(), url, navigationRevision,
                        contentId, selectedIndex, selectedCount, selectedStartTime).send();
            });
        }

        private void awaitPlaybackEnd() throws InterruptedException {
            while (!closed && duration > 0.0 && playbackTime() < duration - DISPLAY_LEAD_SECONDS) {
                lastDecodeHeartbeat = System.currentTimeMillis();
                Thread.sleep(4L);
            }
        }

        private void advancePlaylist() {
            playlistIndex++;
            mediaRevision = NEXT_MEDIA_REVISION.incrementAndGet();
            itemStartTime = targetTime;
            duration = 0.0;
            source = null;
            videoReadyForAudio = false;
            clearBufferedFrames(true);
            progress = 0.18f;
            CreateCinema.LOGGER.debug("Advancing network playlist {} to {}/{}", url,
                    playlistIndex + 1, playlist.entries().size());
            submitDecode();
        }

        private void finishPlaylist() {
            ended = true;
            mediaRevision = NEXT_MEDIA_REVISION.incrementAndGet();
            source = null;
            videoReadyForAudio = false;
            clearBufferedFrames(false);
            progress = 1.0f;
        }

        private void reconnectLiveStream() {
            mediaRevision = NEXT_MEDIA_REVISION.incrementAndGet();
            source = null;
            videoReadyForAudio = false;
            duration = 0.0;
            clearBufferedFrames(true);
            progress = 0.18f;
            try {
                Thread.sleep(1_000L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!closed) submitDecode();
        }

        private boolean stalled(long now) {
            if (closed || failed || ended || lastDecodeHeartbeat >= now - DECODE_STALL_MILLIS) return false;
            playbackHealth = NetworkProjectorBlockEntity.PlaybackHealth.SOURCE_UNREACHABLE;
            reportPlaybackHealth(now, true);
            recordStallResume();
            close("decode stalled");
            CreateCinema.LOGGER.warn("Network stream session {} stalled for {} ms, will resume at breakpoint", url,
                    now - lastDecodeHeartbeat);
            return true;
        }

        private void recordStallResume() {
            if (source == null || source.live()) return;
            boolean hasDisplayedFrame = !Double.isNaN(lastDisplayedTimestamp);
            double breakpoint = hasDisplayedFrame ? lastDisplayedTimestamp : playbackTime();
            if (!hasDisplayedFrame) {
                StallResume existing = STALL_RESUMES.get(key);
                if (existing != null && existing.url().equals(url)) return;
            }
            breakpoint = seekTime(breakpoint, duration);
            STALL_RESUMES.put(key, new StallResume(url, breakpoint, System.currentTimeMillis()));
            notifyPlayer("gui.createcinema.stream.network_dropped", breakpoint);
        }

        private void notifyPlayer(String translationKey, double seconds) {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.execute(() -> {
                if (minecraft.player != null) {
                    minecraft.player.displayClientMessage(
                            Component.translatable(translationKey, formatSeconds(seconds)), false);
                }
            });
        }

        private void reportPlaybackHealth(long now, boolean force) {
            if (!force && now - lastPlaybackHealthReportAt < 1_000L) return;
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.getConnection() == null) return;
            NetworkProjectorBlockEntity.PlaybackHealth health = playbackHealth;
            if (!failed && rebuffering && now - lastFrameUploadedAt >= 3_000L) {
                health = NetworkProjectorBlockEntity.PlaybackHealth.SOURCE_UNREACHABLE;
            } else if (!failed && !bufferReady) {
                health = NetworkProjectorBlockEntity.PlaybackHealth.BUFFERING;
            } else if (!failed) {
                health = NetworkProjectorBlockEntity.PlaybackHealth.HEALTHY;
            }
            lastPlaybackHealthReportAt = now;
            NetworkProjectorBlockEntity.PlaybackHealth reportedHealth = health;
            minecraft.execute(() -> {
                if (minecraft.getConnection() != null) {
                    new C2SNetworkProjectorPlaybackHealthPacket(projector.getBlockPos(), url, navigationRevision,
                            reportedHealth).send();
                }
            });
        }

        private boolean networkFailure(String details) {
            if (details.contains("timed out") || details.contains("timeout")
                    || details.contains("connection reset") || details.contains("connection refused")
                    || details.contains("connectexception") || details.contains("unresolvedaddress")
                    || details.contains("unknown host") || details.contains("unknownhost")
                    || details.contains("failed to download hls segment")
                    || details.contains("http 429") || details.matches("(?s).*http 5\\d\\d.*")) {
                return true;
            }
            return !Double.isNaN(lastDisplayedTimestamp)
                    && (details.contains("did not find a video or audio stream")
                    || details.contains("decoder ended") || details.contains("end of file"));
        }

        private void submitDecode() {
            try {
                STREAM_EXECUTOR.execute(this::decode);
                decodeResubmitPending = false;
            } catch (RejectedExecutionException error) {
                decodeResubmitPending = true;
                retryAt = System.currentTimeMillis() + DECODE_RESUBMIT_MILLIS;
                CreateCinema.LOGGER.debug("Network stream decode queue is full, will retry {} in {} ms", url,
                        DECODE_RESUBMIT_MILLIS);
            }
        }

        private void dumpLiveFrame(DecodedFrame frame, int index) {
            try {
                Path dir = Path.of("logs", "liveframes");
                Files.createDirectories(dir);
                Path file = dir.resolve("frame_" + mediaRevision + "_" + index + ".png");
                frame.image.writeToFile(file);
                CreateCinema.LOGGER.info("Dumped live frame {}: {}x{} to {}", index, frame.width, frame.height,
                        file.toAbsolutePath());
            } catch (Throwable error) {
                CreateCinema.LOGGER.warn("Could not dump live frame {}: {}", index, error.toString());
            }
        }

        private void decodeSnapshot(String snapshotUrl) {
            if (closed) return;
            progress = 0.50f;
            liveClockStartedAt = System.nanoTime();
            CreateCinema.LOGGER.info("CCTV live stream is DRM-protected; displaying live snapshot from {}", snapshotUrl);
            byte[] lastBytes = null;
            String etag = null;
            String lastModified = null;
            while (!closed) {
                VideoResolverHttp.SnapshotProbe probe;
                try {
                    probe = VideoResolverHttp.probe(snapshotUrl, url);
                } catch (IOException | InterruptedException error) {
                    if (Thread.currentThread().isInterrupted()) return;
                    CreateCinema.LOGGER.warn("Could not probe CCTV live snapshot {}: {}", snapshotUrl, error.toString());
                    sleepQuietly(250L);
                    continue;
                }
                if (probe != null && !probe.changedFrom(etag, lastModified)) {
                    sleepQuietly(SNAPSHOT_REFRESH_MILLIS);
                    continue;
                }
                byte[] bytes;
                try {
                    bytes = VideoResolverHttp.getBytes(snapshotUrl, url);
                } catch (IOException | InterruptedException error) {
                    if (Thread.currentThread().isInterrupted()) return;
                    CreateCinema.LOGGER.warn("Could not fetch CCTV live snapshot {}: {}", snapshotUrl, error.toString());
                    sleepQuietly(250L);
                    continue;
                }
                if (probe != null) {
                    etag = probe.etag();
                    lastModified = probe.lastModified();
                }
                if (bytes == null || bytes.length == 0) {
                    sleepQuietly(250L);
                    continue;
                }
                if (lastBytes != null && Arrays.equals(lastBytes, bytes)) {
                    sleepQuietly(SNAPSHOT_REFRESH_MILLIS);
                    continue;
                }
                lastBytes = bytes;
                if (closed) return;
                NativeImage image = readJpegImage(bytes);
                if (image == null) {
                    sleepQuietly(250L);
                    continue;
                }
                double timestamp = (System.nanoTime() - liveClockStartedAt) / 1_000_000_000.0;
                try {
                    enqueue(new DecodedFrame(image.getWidth(), image.getHeight(), image, timestamp));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
                markBufferReady();
                sleepQuietly(SNAPSHOT_REFRESH_MILLIS);
            }
        }

        private static void sleepQuietly(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }

        private static NativeImage readJpegImage(byte[] bytes) {
            try {
                BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(bytes));
                if (buffered == null) throw new IOException("Unsupported or corrupt snapshot image");
                NativeImage image = new NativeImage(buffered.getWidth(), buffered.getHeight(), false);
                for (int y = 0; y < buffered.getHeight(); y++) {
                    for (int x = 0; x < buffered.getWidth(); x++) {
                        int argb = buffered.getRGB(x, y);
                        image.setPixelRGBA(x, y, FastColor.ABGR32.color(
                                argb >>> 24, argb & 0xFF, argb >> 8 & 0xFF, argb >> 16 & 0xFF));
                    }
                }
                return image;
            } catch (Throwable error) {
                CreateCinema.LOGGER.warn("Could not decode CCTV live snapshot: {}", error.toString());
                return null;
            }
        }

        private static Component visibleError(String url, Throwable error) {
            String message = nestedMessage(error).toLowerCase(java.util.Locale.ROOT);
            if (message.contains("douyin cookie is not configured")) {
                return Component.translatable("gui.createcinema.stream.error.douyin_cookie_required");
            }
            if (message.contains("douyin browser authorization")) {
                return Component.translatable("gui.createcinema.stream.error.douyin_browser_required");
            }
            if (message.contains("douyin cookie is incomplete")) {
                return Component.translatable("gui.createcinema.stream.error.douyin_cookie_incomplete");
            }
            if (message.contains("douyin recommendation authorization")
                    || message.contains("douyin authenticated") || message.contains("requires verification")) {
                return Component.translatable("gui.createcinema.stream.error.douyin_cookie_invalid");
            }
            if (DouyinLiveResolver.canResolve(url) && message.contains("capture timed out")) {
                return Component.translatable("gui.createcinema.stream.error_short");
            }
            if (DouyinLiveResolver.canResolve(url)) {
                return Component.translatable("gui.createcinema.stream.error.douyin_live_auth");
            }
            if (DouyinVideoResolver.isHomepageFeed(url)) {
                return Component.translatable("gui.createcinema.stream.error.douyin_feed_login");
            }
            if (DouyinVideoResolver.isLongVideo(url)) {
                return Component.translatable("gui.createcinema.stream.error.douyin_long_video_restricted");
            }
            if (DouyinVideoResolver.canResolve(url)) {
                return Component.translatable("gui.createcinema.stream.error.douyin_public_only");
            }
            String host = host(url);
            if (host.endsWith("iqiyi.com") || host.endsWith("pps.tv")) {
                return Component.translatable(message.contains("drm")
                        ? "gui.createcinema.stream.error.iqiyi_drm"
                        : message.contains("browser") || message.contains("log in")
                        ? "gui.createcinema.stream.error.iqiyi_browser_required"
                        : "gui.createcinema.stream.error.iqiyi_unavailable");
            }
            if (host.endsWith("youku.com")) {
                return Component.translatable("gui.createcinema.stream.error.youku_drm");
            }
            if (host.endsWith("cctv.com")) {
                if (message.contains("no channel") || message.contains("exposed no playable channel")) {
                    return Component.translatable("gui.createcinema.stream.error.cctv_live_channel_required");
                }
                return Component.translatable(message.contains("drm") || message.contains("protected")
                        ? "gui.createcinema.stream.error.cctv_drm"
                        : CctvLiveResolver.canResolve(url)
                        ? "gui.createcinema.stream.error.cctv_live_unavailable"
                        : "gui.createcinema.stream.error.cctv_video_unavailable");
            }
            if (message.contains("drm") || message.contains("login") || message.contains("vip")
                    || message.contains("directly playable") || message.contains("browser")) {
                return Component.translatable("gui.createcinema.stream.error.unsupported_web");
            }
            return Component.translatable("gui.createcinema.stream.error_short");
        }

        private static String host(String url) {
            try {
                String host = URI.create(url).getHost();
                return host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
            } catch (IllegalArgumentException e) {
                return "";
            }
        }

        private static String nestedMessage(Throwable error) {
            StringBuilder builder = new StringBuilder();
            for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) {
                if (cursor.getMessage() != null) builder.append(cursor.getMessage()).append('\n');
            }
            return builder.toString();
        }

        private NetworkProjectionFrame uploadReady() {
            DecodedFrame frame = null;
            boolean enteredRebuffer = false;
            if (bufferReady) {
                double masterTime = wrap(playbackTime(), duration);
                synchronized (bufferedFrames) {
                    while (!bufferedFrames.isEmpty()
                            && mediaDelta(bufferedFrames.getFirst().timestamp, masterTime, duration) <= DISPLAY_LEAD_SECONDS) {
                        if (frame != null) frame.image.close();
                        frame = bufferedFrames.removeFirst();
                    }
                    if (frame == null && bufferedFrames.isEmpty() && textureLocation != null
                            && source != null && !source.live()
                            && System.currentTimeMillis() - lastFrameUploadedAt > VIDEO_UNDERRUN_GRACE_MILLIS) {
                        bufferReady = false;
                        rebuffering = true;
                        playbackHealth = NetworkProjectorBlockEntity.PlaybackHealth.BUFFERING;
                        videoReadyForAudio = false;
                        progress = 0.72f;
                        enteredRebuffer = true;
                        CreateCinema.LOGGER.debug("Network video {} entered rebuffer at {}s", url,
                                String.format(java.util.Locale.ROOT, "%.3f", masterTime));
                    }
                }
            }
            if (enteredRebuffer) ClientNetworkProjectorAudio.stop(projector);
            if (frame != null) {
                if (texture == null || width != frame.width || height != frame.height) {
                    if (textureLocation != null) Minecraft.getInstance().getTextureManager().release(textureLocation);
                    textureLocation = ResourceLocation.fromNamespaceAndPath(CreateCinema.MODID,
                            "network_stream/" + Integer.toUnsignedString(key.hashCode()));
                    texture = new DynamicTexture(frame.image);
                    Minecraft.getInstance().getTextureManager().register(textureLocation, texture);
                } else {
                    texture.setPixels(frame.image);
                    texture.upload();
                }
                width = frame.width;
                height = frame.height;
                uploadedFrameCount++;
                lastFrameUploadedAt = System.currentTimeMillis();
                lastDisplayedTimestamp = frame.timestamp;
                progress = 1.0f;
                if (stallResume && frame.timestamp >= resumeSeconds - 1.0) {
                    double resumedAt = resumeSeconds;
                    stallResume = false;
                    resumeSeconds = Double.NaN;
                    STALL_RESUMES.remove(key);
                    notifyPlayer("gui.createcinema.stream.resumed", resumedAt);
                }
            }
            logVideoState();
            return textureLocation == null ? null : new NetworkProjectionFrame(textureLocation, width, height);
        }

        private void enqueue(DecodedFrame frame) throws InterruptedException {
            while (!closed) {
                synchronized (bufferedFrames) {
                    if (closed) break;
                    int maxFrames = source != null && source.live() ? MAX_LIVE_BUFFERED_FRAMES
                            : playbackProfile.maxBufferedFrames();
                    if (bufferedFrames.size() < maxFrames) {
                        bufferedFrames.addLast(frame);
                        decodedFrameCount++;
                        lastDecodeHeartbeat = System.currentTimeMillis();
                        if (!bufferReady) {
                            double buffered = bufferedSecondsLocked();
                            progress = (float) Math.min(0.95, 0.72 + 0.23 * buffered / STARTUP_BUFFER_SECONDS);
                            if (buffered >= requiredBufferSeconds()) markBufferReady();
                        }
                        return;
                    }
                }
                Thread.sleep(4L);
            }
            frame.image.close();
        }

        private void markBufferReady() {
            synchronized (bufferedFrames) {
                if (!bufferedFrames.isEmpty()) {
                    bufferReady = true;
                    rebuffering = false;
                    playbackHealth = NetworkProjectorBlockEntity.PlaybackHealth.HEALTHY;
                    videoReadyForAudio = true;
                    progress = 0.95f;
                    CreateCinema.LOGGER.debug("Network video {} buffered {}s and is ready", url,
                            String.format(java.util.Locale.ROOT, "%.3f", bufferedSecondsLocked()));
                }
            }
        }

        private double bufferedSecondsLocked() {
            if (bufferedFrames.size() < 2) return 0.0;
            double buffered = bufferedFrames.getLast().timestamp - bufferedFrames.getFirst().timestamp;
            if (duration > 0.0 && buffered < 0.0) buffered += duration;
            return Math.max(0.0, buffered);
        }

        private double requiredBufferSeconds() {
            if (duration <= 0.0) return STARTUP_BUFFER_SECONDS;
            double capacitySeconds = Math.max(0.05, (playbackProfile.maxBufferedFrames() - 1)
                    / Math.max(1.0, playbackProfile.maxFps()));
            return Math.min(Math.min(STARTUP_BUFFER_SECONDS, capacitySeconds * 0.75),
                    Math.max(0.05, duration * 0.40));
        }

        private void clearBufferedFrames(boolean resetReady) {
            synchronized (bufferedFrames) {
                DecodedFrame frame;
                while ((frame = bufferedFrames.pollFirst()) != null) frame.image.close();
                if (resetReady) {
                    bufferReady = false;
                    rebuffering = true;
                    playbackHealth = NetworkProjectorBlockEntity.PlaybackHealth.BUFFERING;
                    videoReadyForAudio = false;
                    progress = 0.72f;
                }
            }
        }

        private void applyServerAnchor(NetworkProjectorBlockEntity projector) {
            if (closed || failed || ended || source == null || source.live() || isLocalMediaOwner()
                    || projector.getMediaRevision() <= 0 || projector.isMediaLive()) return;
            double anchor = projector.getCurrentMediaTimeSeconds();
            double current = mediaTime();
            double diff = mediaDelta(current, anchor, duration);
            if (Math.abs(diff) <= 0.15) return;
            long now = System.currentTimeMillis();
            if (lastAnchorAdjustAt == 0L) {
                lastAnchorAdjustAt = now;
                return;
            }
            double elapsed = Math.min(1.0, (now - lastAnchorAdjustAt) / 1_000.0);
            lastAnchorAdjustAt = now;
            if (Math.abs(diff) >= HARD_RESYNC_SECONDS) {
                clearBufferedFrames(true);
                itemStartTime += diff;
                lastDecodeHeartbeat = now;
                CreateCinema.LOGGER.debug("Hard-resynced network stream {} to server anchor {}s (was {}s, delta {}s)",
                        url, String.format(java.util.Locale.ROOT, "%.3f", anchor),
                        String.format(java.util.Locale.ROOT, "%.3f", current),
                        String.format(java.util.Locale.ROOT, "%+.3f", diff));
                return;
            }
            double correction = Math.copySign(Math.min(Math.abs(diff), Math.max(0.002, elapsed * 0.05)), diff);
            itemStartTime += correction;
        }

        private double playbackTime() {
            if (source != null && source.live()) {
                if (globalPaused) return pausedLiveTime;
                if (liveClockStartedAt == 0L) return 0.0;
                return Math.max(0.0, (System.nanoTime() - liveClockStartedAt) / 1_000_000_000.0
                        - LIVE_PLAYBACK_DELAY_SECONDS);
            }
            return Math.max(0.0, targetTime - itemStartTime);
        }

        private double mediaTime() {
            return wrap(playbackTime(), duration);
        }

        private void publishDisplayInfo() {
            Level level = projector.getLevel();
            if (level == null || !level.isClientSide()
                    || level.getClass().getName().startsWith("net.createmod.ponder.")
                    || !isLocalMediaOwner()) return;

            boolean live = source != null && source.live();
            double currentDuration = live ? 0.0 : Math.max(0.0, duration);
            double currentTime = Math.max(0.0, mediaTime());
            NetworkProjectorBlockEntity.MediaStatus currentStatus = switch (status(this)) {
                case IDLE -> NetworkProjectorBlockEntity.MediaStatus.IDLE;
                case LOADING -> NetworkProjectorBlockEntity.MediaStatus.LOADING;
                case PLAYING -> NetworkProjectorBlockEntity.MediaStatus.PLAYING;
                case ENDED -> NetworkProjectorBlockEntity.MediaStatus.ENDED;
                case ERROR -> NetworkProjectorBlockEntity.MediaStatus.ERROR;
            };
            long gameTime = level.getGameTime();
            boolean changed = mediaRevision != publishedMediaRevision
                    || Math.abs(currentDuration - publishedDuration) > 0.05
                    || live != publishedLive || currentStatus != publishedStatus;
            if (!changed && gameTime < nextMediaInfoPublish) return;

            new C2SNetworkProjectorMediaInfoPacket(projector.getBlockPos(), url, mediaRevision, currentDuration,
                    currentTime, live, currentStatus).send();
            publishedMediaRevision = mediaRevision;
            publishedDuration = currentDuration;
            publishedLive = live;
            publishedStatus = currentStatus;
            nextMediaInfoPublish = gameTime + 20L;
        }

        private void logVideoState() {
            long now = System.currentTimeMillis();
            if (now - lastVideoDiagnosticAt < 2_000L) return;
            lastVideoDiagnosticAt = now;
            synchronized (bufferedFrames) {
                String first = bufferedFrames.isEmpty() ? "-"
                        : String.format(java.util.Locale.ROOT, "%.3f", bufferedFrames.getFirst().timestamp);
                String last = bufferedFrames.isEmpty() ? "-"
                        : String.format(java.util.Locale.ROOT, "%.3f", bufferedFrames.getLast().timestamp);
                CreateCinema.LOGGER.debug(
                        "Network video revision {}: media={}s duration={}s ready={} rebuffering={} queued={} [{}..{}] decoded={} uploaded={}",
                        mediaRevision,
                        String.format(java.util.Locale.ROOT, "%.3f", mediaTime()),
                        String.format(java.util.Locale.ROOT, "%.3f", duration),
                        bufferReady, rebuffering, bufferedFrames.size(), first, last, decodedFrameCount,
                        uploadedFrameCount);
            }
        }

        private void close(String reason) {
            if (closed) return;
            closed = true;
            videoReadyForAudio = false;
            CreateCinema.LOGGER.debug("Closed network stream session {} revision {}: {}", url, mediaRevision, reason);
            clearBufferedFrames(false);
            if (textureLocation != null) Minecraft.getInstance().getTextureManager().release(textureLocation);
        }
    }

    public static void configure(FFmpegFrameGrabber grabber, String referer) {
        configure(grabber, referer, Map.of());
    }

    public static void configure(FFmpegFrameGrabber grabber, String referer, Map<String, String> headers) {
        grabber.setOption("user_agent", BilibiliResolver.USER_AGENT);
        if (referer != null && !referer.isBlank()) grabber.setOption("referer", referer);
        StringBuilder rawHeaders = new StringBuilder();
        headers.forEach((name, value) -> {
            if (!name.equalsIgnoreCase("Referer") && !name.equalsIgnoreCase("User-Agent") && !value.isBlank()) {
                rawHeaders.append(name).append(": ").append(value).append("\r\n");
            }
        });
        if (!rawHeaders.isEmpty()) grabber.setOption("headers", rawHeaders.toString());
        grabber.setOption("rw_timeout", NETWORK_RW_TIMEOUT_MICROS);
        grabber.setOption("timeout", NETWORK_RW_TIMEOUT_MICROS);
        grabber.setOption("stimeout", NETWORK_RW_TIMEOUT_MICROS);
        grabber.setOption("reconnect", "1");
        grabber.setOption("reconnect_streamed", "1");
        grabber.setOption("reconnect_on_network_error", "1");
        grabber.setOption("reconnect_delay_max", "2");
        grabber.setOption("reconnect_max_retries", "2");
    }

    private static class NativeFrameScaler implements AutoCloseable {
        private static final Field NATIVE_IMAGE_PIXELS = findNativeImagePixels();
        private SwsContext context;
        private BytePointer output;
        private PointerPointer<BytePointer> outputPlanes;
        private IntPointer outputStrides;
        private int sourceWidth;
        private int sourceHeight;
        private int sourceFormat;
        private int width;
        private int height;

        private DecodedFrame decode(Frame frame, int maxWidth, int maxHeight) throws IOException {
            if (!(frame.opaque instanceof AVFrame raw)) return null;
            if (raw.format() < 0) return null;
            int rawWidth = raw.width();
            int rawHeight = raw.height();
            if (rawWidth <= 0 || rawHeight <= 0) return null;
            double factor = Math.min(1.0, Math.min(maxWidth / (double) rawWidth, maxHeight / (double) rawHeight));
            int targetWidth = Math.max(1, (int) Math.round(rawWidth * factor));
            int targetHeight = Math.max(1, (int) Math.round(rawHeight * factor));
            ensure(rawWidth, rawHeight, raw.format(), targetWidth, targetHeight);
            int rows = sws_scale(context, raw.data(), raw.linesize(), 0, rawHeight, outputPlanes, outputStrides);
            if (rows != targetHeight) throw new IOException("FFmpeg scaled " + rows + " of " + targetHeight + " rows");

            NativeImage image = new NativeImage(targetWidth, targetHeight, false);
            try {
                long byteCount = (long) targetWidth * targetHeight * 4L;
                output.position(0);
                if (NATIVE_IMAGE_PIXELS != null) {
                    MemoryUtil.memCopy(output.address(), NATIVE_IMAGE_PIXELS.getLong(image), byteCount);
                } else {
                    IntBuffer pixels = output.position(0).capacity(byteCount)
                            .asByteBuffer().order(ByteOrder.nativeOrder()).asIntBuffer();
                    for (int y = 0; y < targetHeight; y++) {
                        for (int x = 0; x < targetWidth; x++) image.setPixelRGBA(x, y, pixels.get());
                    }
                }
                return new DecodedFrame(targetWidth, targetHeight, image);
            } catch (Throwable error) {
                image.close();
                if (error instanceof IOException io) throw io;
                throw new IOException("Could not copy a decoded video frame", error);
            }
        }

        private static Field findNativeImagePixels() {
            try {
                Field field = NativeImage.class.getDeclaredField("pixels");
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return null;
            }
        }

        private static String pixFmtName(int format) {
            try {
                BytePointer name = av_get_pix_fmt_name(format);
                return name == null ? String.valueOf(format) : name.getString();
            } catch (Throwable error) {
                return String.valueOf(format);
            }
        }

        private void ensure(int rawWidth, int rawHeight, int rawFormat, int targetWidth, int targetHeight)
                throws IOException {
            if (context != null && sourceWidth == rawWidth && sourceHeight == rawHeight && sourceFormat == rawFormat
                    && width == targetWidth && height == targetHeight) return;
            close();
            CreateCinema.LOGGER.info("Video scaler {}x{} {} -> {}x{}", rawWidth, rawHeight, pixFmtName(rawFormat),
                    targetWidth, targetHeight);
            context = sws_getContext(rawWidth, rawHeight, rawFormat, targetWidth, targetHeight,
                    AV_PIX_FMT_RGBA, SWS_BILINEAR, null, null, (double[]) null);
            if (context == null || context.isNull()) throw new IOException("FFmpeg could not create a video scaler");
            output = new BytePointer((long) targetWidth * targetHeight * 4L);
            outputPlanes = new PointerPointer<>(4);
            outputPlanes.put(0, output);
            outputStrides = new IntPointer(4);
            outputStrides.put(0, targetWidth * 4);
            sourceWidth = rawWidth;
            sourceHeight = rawHeight;
            sourceFormat = rawFormat;
            width = targetWidth;
            height = targetHeight;
        }

        @Override
        public void close() {
            if (context != null) sws_freeContext(context);
            if (output != null) output.close();
            if (outputPlanes != null) outputPlanes.close();
            if (outputStrides != null) outputStrides.close();
            context = null;
            output = null;
            outputPlanes = null;
            outputStrides = null;
        }
    }

    private static double wrap(double value, double duration) {
        if (duration <= 0.0) return value;
        double wrapped = value % duration;
        return wrapped < 0.0 ? wrapped + duration : wrapped;
    }

    private static double seekTime(double value, double duration) {
        return duration <= 0.0 ? Math.max(0.0, value) : wrap(value, duration);
    }

    private static double mediaDelta(double value, double reference, double duration) {
        double delta = value - reference;
        if (duration > 0.0) {
            if (delta > duration / 2.0) delta -= duration;
            else if (delta < -duration / 2.0) delta += duration;
        }
        return delta;
    }

    private record DecodedFrame(int width, int height, NativeImage image, double timestamp) {
        private DecodedFrame(int width, int height, NativeImage image) {
            this(width, height, image, 0.0);
        }
    }

    private record StallResume(String url, double seconds, long recordedAt) {
    }

    public record AudioSource(String key, BilibiliResolver.ResolvedMedia media) {
    }

    public enum Status {
        IDLE,
        LOADING,
        PLAYING,
        ENDED,
        ERROR
    }
}
