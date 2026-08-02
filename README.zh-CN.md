# Create Cinema

[English README](README.md)

Create Cinema 是一个面向 Minecraft 1.21.1、NeoForge 和 Create 的电影放映附属模组。它提供 Create 动力投影仪、本地视频烧录、网络视频投影、空间音箱、红石音量控制，以及适合屏幕表面的投影渲染效果。

## 环境要求

- Minecraft 1.21.1
- NeoForge 21.1.233，或 21.1 系列内的更新版本
- Create 6.0.10-280
- Java 21
- Gradle 8.14.5，或兼容的 Gradle 环境

项目内嵌 JavaCV/FFmpeg 运行依赖，用于本地视频烧录和网络流解码。

## 主要功能

- 电影烧录机：把本地视频文件转换为游戏内胶卷。
- 动力投影仪：使用 Create 旋转动力播放烧录好的胶卷。
- 网络投影仪：解析并播放受支持的在线视频 URL。
- 白色投影幕和黑色投影幕：用于搭建投影画面表面。
- 暗室方块：用它封闭的房间会把环境曝光压到最低，让投影保持清晰。
- 音箱和线缆：把投影仪连接到空间音频音箱。
- 红石音量：音箱音量由 0 到 15 的红石信号线性控制。

## 播放速度

播放速度基准为：

```text
64 rpm = 1 倍速
```

转速为 0 或动力过载时，投影仪会停止播放。音频音调和视频时间轴使用同一个速度基准。

## 音频行为

音箱使用红石信号强度作为音量：

```text
0  = 静音
15 = 最大音量
```

当红石信号为 0 时，音频流会继续在静音状态下推进，而不是被关闭。这样可以避免关闭红石音量再恢复时重新开流导致音画不同步。只有音箱断开、音箱被拆除、投影仪停止、URL 改变，或世界/客户端会话被清理时，音频才会被完全停止。

## 网络视频支持

网络投影仪支持直接媒体 URL，以及能在页面中暴露可播放媒体地址的网站：

- 直接视频/音频地址，例如 `mp4`、`webm`、`m4v`、`m3u8`、`mpd`
- 普通 HTML 页面中暴露的媒体地址，包括 `video`、`source`、元数据、脚本、属性，或一层 iframe 中的媒体地址
- 通过 Bilibili DASH API 解析的 Bilibili BV 页面
- 在可获取 HLS 播放列表时，通过 Tencent `getinfo` 响应解析的腾讯视频页面
- HLS 点播流，带分片缓存和预取

已知平台限制：

- 爱奇艺页面通常需要网页播放器、登录状态、签名 VRS 请求、VIP 权限或 DRM。模组会在投影画面上显示对应原因，而不是静默失败。
- 优酷 VIP/DRM 视频不能由 FFmpeg 直接播放。模组会显示需要网页播放器/VIP/DRM 的提示。
- 依赖浏览器广告流程、加密 JavaScript 签名、DRM/CDM 或登录 Cookie 的网站，需要真正的浏览器播放模式。目前还没有实现该模式。

## 方块和物品

- `createcinema:burner` - 电影烧录机
- `createcinema:film` - 胶卷
- `createcinema:screen` - 白色投影幕
- `createcinema:black_screen` - 黑色投影幕
- `createcinema:projector` - 胶卷动力投影仪
- `createcinema:network_projector` - URL 网络投影仪
- `createcinema:speaker` - 空间音频音箱
- `createcinema:cable` - 音箱网络线缆
- `createcinema:darkroom_block` - 用于保持投影清晰度的暗室墙体方块

## 构建

在项目目录中执行：

```bash
gradle build
```

当前开发环境中，Windows 客户端运行文件使用 Windows Gradle 和 Java 21 生成：

```bash
WSLENV=JAVA_HOME/p JAVA_HOME="/mnt/d/jdk21" cmd.exe /d /c 'C:\Users\28271\.gradle\wrapper\dists\gradle-8.14.5-bin\3w1tvbe412g1z3jsd16ketrw6\gradle-8.14.5\bin\gradle.bat prepareClientRun --console plain --no-configuration-cache'
```

如果需要从 IDE 启动客户端，不要在启动前对 D 盘项目执行会删除生成文件的 clean 任务，否则可能移除 `build/moddev/clientRunVmArgs.txt` 等 NeoForge 运行文件。

## 输出文件

构建后的模组 jar 位于：

```text
build/libs/createcinema-0.1.0-1.21.1.jar
```

## 开发说明

- 解析入口：`BilibiliResolver`
- 平台解析器：`BilibiliVideoResolver`、`TencentVideoResolver`、`IqiyiVideoResolver`、`YoukuVideoResolver`
- 通用 HTML 媒体发现：`GenericVideoResolver`
- HLS 缓存和分片预取：`HlsStreamCache`
- 网络播放和缓冲：`ClientNetworkProjectorStreams`
- 投影渲染和状态图片覆盖层：`ProjectorRenderer`、`ClientStatusMessageTextures`

## 许可证

MIT，以 `gradle.properties` 中声明为准。
