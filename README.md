# Create Cinema

[中文说明](README.zh-CN.md)

Create Cinema is a Minecraft 1.21.1 NeoForge addon for Create. It adds kinetic projectors, local film burning, network video projection, speaker audio, redstone volume control, and screen-focused projection effects.

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.233 or newer in the 21.1 line
- Create 6.0.10-280
- Java 21
- Gradle 8.14.5 or a compatible Gradle installation

The project embeds JavaCV/FFmpeg runtime dependencies for local video burning and network stream decoding.

## Main Features

- Film Burner: converts local video files into in-game film packages.
- Kinetic Projector: plays burned films on projection screens using Create rotational power.
- Network Projector: resolves and streams supported online video URLs.
- Projection Screen and Black Projection Screen: build screen surfaces for projection.
- Darkroom Block: sealed rooms built with this block keep projection clarity at the darkest level.
- Speaker and Cable: connect projectors to spatial audio speakers.
- Redstone volume: speaker volume is controlled linearly by redstone strength from 0 to 15.

## Playback Speed

The playback baseline is:

```text
64 rpm = 1x playback speed
```

Zero speed or overstress stops projector playback. Audio pitch and video timing use the same speed baseline.

## Audio Behavior

Speakers use redstone signal strength as volume:

```text
0  = muted
15 = full volume
```

When redstone is 0, the audio stream keeps running silently instead of being closed. This prevents audio desync when redstone volume is turned off and then restored. Audio is fully stopped only when the speaker is disconnected, removed, the projector stops, the URL changes, or the world/session is cleared.

## Network Video Support

The network projector supports direct media URLs and pages that expose playable media:

- Direct video/audio URLs such as `mp4`, `webm`, `m4v`, `m3u8`, and `mpd`
- Generic HTML pages with exposed media URLs in `video`, `source`, metadata, scripts, attributes, or one level of iframe
- Bilibili BV pages through the Bilibili DASH API
- Tencent Video pages through Tencent's public `getinfo` response when an HLS playlist is available
- HLS VOD streams with segment caching and prefetching

Known platform limits:

- iQiyi pages commonly require the browser player, login state, signed VRS requests, VIP access, or DRM. The mod shows this reason on the screen instead of failing silently.
- Youku VIP/DRM videos are not directly playable by FFmpeg. The mod shows a web-player/VIP/DRM message on the screen.
- Sites that only work after browser-based ads, encrypted JavaScript signing, DRM/CDM, or login cookies need a real browser playback mode. That mode is not implemented yet.

## Blocks and Items

- `createcinema:burner` - Film Burner
- `createcinema:film` - Film item
- `createcinema:screen` - White projection screen
- `createcinema:black_screen` - Black projection screen
- `createcinema:projector` - Kinetic projector for burned films
- `createcinema:network_projector` - URL-based network projector
- `createcinema:speaker` - Spatial audio speaker
- `createcinema:cable` - Speaker network cable
- `createcinema:darkroom_block` - Darkroom wall block for full projection clarity

## Building

From this project directory:

```bash
gradle build
```

In the current development environment, Windows client run files are prepared with the Windows Gradle install and Java 21:

```bash
WSLENV=JAVA_HOME/p JAVA_HOME="/mnt/d/jdk21" cmd.exe /d /c 'C:\Users\28271\.gradle\wrapper\dists\gradle-8.14.5-bin\3w1tvbe412g1z3jsd16ketrw6\gradle-8.14.5\bin\gradle.bat prepareClientRun --console plain --no-configuration-cache'
```

Do not run a clean task on the D drive immediately before launching from the IDE if it removes generated NeoForge run files such as `build/moddev/clientRunVmArgs.txt`.

## Output

The built mod jar is written to:

```text
build/libs/createcinema-0.1.0-1.21.1.jar
```

## Development Notes

- Resolver entry point: `BilibiliResolver`
- Platform resolvers: `BilibiliVideoResolver`, `TencentVideoResolver`, `IqiyiVideoResolver`, `YoukuVideoResolver`
- Generic HTML media discovery: `GenericVideoResolver`
- HLS cache and segment prefetch: `HlsStreamCache`
- Network playback and buffering: `ClientNetworkProjectorStreams`
- Projection rendering and status image overlays: `ProjectorRenderer` and `ClientStatusMessageTextures`

## License

MIT, as declared in `gradle.properties`.
