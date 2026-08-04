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

See [Third-Party Notices](THIRD_PARTY_NOTICES.md) for the required mods, embedded libraries, and upstream license notes.

## Dependencies and Credits

Create Cinema is built on top of Minecraft, NeoForge, and Create. It also uses libraries from the Create/NeoForge ecosystem and media-processing libraries for video playback.

- Minecraft: target game platform, owned by Mojang/Microsoft.
- NeoForge: mod loader and modding API.
- Create: required runtime mod and kinetic system integration.
- Ponder: Create ecosystem UI/tutorial dependency.
- Flywheel: Create rendering/runtime dependency.
- Registrate: registration helper library.
- JavaCV and JavaCPP: FFmpeg-backed video/audio bindings and native runtime.
- FFmpeg binaries from Bytedeco presets: video/audio decoding and encoding.
- jsoup: HTML parsing for network video URL discovery.
- Parchment mappings: development mappings used at build time.

These dependencies are not relicensed by Create Cinema. Their names, code, assets, and binaries remain under their own upstream licenses and terms.

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
- Bilibili BV pages through the Bilibili DASH API, including multipart playlist discovery
- Bilibili Live rooms through the anonymous live API, with FLV reconnect support
- Tencent Video pages through Tencent's public page and `getinfo` APIs, including episode catalog discovery
- Public Douyin videos, plus browser-authorized recommendations and Douyin Live rooms
- HLS VOD streams with segment caching and prefetching

Install a Continuous Play Upgrade in the Network Projector's upgrade slot to play a detected multipart or episode list from the selected entry to the end. Each upgrade slot accepts one card. Playback stops after the final entry. Without the upgrade, only the selected video or part is played.

The Network Projector stores a selectable quality level: 480p/24fps, 720p/30fps (default), or 1080p/30fps. Platform requests and client frame limits both follow this setting.

Known platform limits:

- iQiyi pages commonly require the browser player, login state, signed VRS requests, VIP access, or DRM. The mod shows this reason on the screen instead of failing silently.
- Youku VIP/DRM videos are not directly playable by FFmpeg. The mod shows a web-player/VIP/DRM message on the screen.
- Douyin recommendations and Live use a Windows-only embedded Microsoft Edge WebView2 profile. Right-click a Configuration Manager and open authorization; the window is shown only while signing in or completing verification and stays hidden during playback.
- The WebView2 profile remains under the local game directory and preserves the signed-in session. The embedded view is closed when authorization is disabled or Minecraft exits; the mod does not read or store raw Cookies in its configuration.
- DRM/CDM and account/content restrictions are not bypassed.

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
- `createcinema:continuous_play_upgrade` - Enables sequential multipart and episode playback
- `createcinema:remote_control_upgrade` - Uses two Create wireless frequency pairs for previous/next navigation
- `createcinema:config_manager` - Opens local browser authorization settings

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

Create Cinema source code is licensed under the [MIT License](LICENSE), as declared in `gradle.properties` and the NeoForge mod metadata.

Minecraft, NeoForge, Create, Ponder, Flywheel, Registrate, JavaCV, JavaCPP, FFmpeg, jsoup, and online video services remain under their own licenses and terms. See [Third-Party Notices](THIRD_PARTY_NOTICES.md).
