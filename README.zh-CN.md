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

必需模组、内嵌库和上游许可证说明见 [第三方组件说明](THIRD_PARTY_NOTICES.zh-CN.md)。

## 依赖和鸣谢

Create Cinema 构建在 Minecraft、NeoForge 和 Create 之上，并使用了 Create/NeoForge 生态中的库以及用于视频播放的媒体处理库。

- Minecraft：目标游戏平台，属于 Mojang/Microsoft。
- NeoForge：模组加载器和模组开发 API。
- Create：必需运行模组，提供 Create 动力系统集成。
- Ponder：Create 生态的 UI/教程依赖。
- Flywheel：Create 渲染/运行时依赖。
- Registrate：注册辅助库。
- JavaCV 和 JavaCPP：基于 FFmpeg 的视频/音频绑定和原生运行时。
- Bytedeco FFmpeg binaries：视频/音频解码和编码。
- jsoup：用于网络视频 URL 发现的 HTML 解析库。
- Parchment mappings：构建时使用的开发映射。

这些依赖不会因为 Create Cinema 的 MIT 许可证而被重新授权。它们的名称、代码、资源和二进制文件仍然遵循各自上游项目的许可证和使用条款。

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
- 通过 Bilibili DASH API 解析的 Bilibili BV 页面，支持检测分 P 目录
- 通过匿名接口解析的 Bilibili 直播间，支持 FLV 断线重连
- 通过腾讯公开视频页面与 `getinfo` API 解析的腾讯视频页面，支持检测剧集目录
- 抖音公开视频，以及通过 WebView2 授权的推荐流、相关推荐和直播间
- HLS 点播流，带分片缓存和预取

在网络放映机的升级槽装入连播升级后，会从当前分 P 或剧集开始依次播放检测到的目录，并在最后一项结束后停止。每个升级槽仅可安装一张升级卡；未装升级时只播放当前视频或分 P。

网络放映机可保存清晰度设置：480p/24fps、默认 720p/30fps 或 1080p/30fps。平台媒体请求和客户端帧率/尺寸上限都会使用该设置。

已知平台限制：

- 爱奇艺页面通常需要网页播放器、登录状态、签名 VRS 请求、VIP 权限或 DRM。模组会在投影画面上显示对应原因，而不是静默失败。
- 优酷 VIP/DRM 视频不能由 FFmpeg 直接播放。模组会显示需要网页播放器/VIP/DRM 的提示。
- 抖音推荐流与直播使用仅支持 Windows 的内嵌 Microsoft Edge WebView2 profile。右键配置管理器打开授权；窗口只在登录或完成验证时显示，播放期间保持隐藏。
- WebView2 profile 保存在本地游戏目录并持久化登录状态。关闭授权或退出 Minecraft 时会关闭内嵌视图；模组不会把原始 Cookie 读取或写入配置文件。
- DRM/CDM、账号权限与内容权限限制不会被绕过。

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
- `createcinema:continuous_play_upgrade` - 启用分 P 与剧集顺序连播

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

Create Cinema 源代码采用 [MIT License](LICENSE)，并已在 `gradle.properties` 和 NeoForge 模组元数据中声明。

Minecraft、NeoForge、Create、Ponder、Flywheel、Registrate、JavaCV、JavaCPP、FFmpeg、jsoup 以及在线视频服务仍遵循各自的许可证和使用条款。详见 [第三方组件说明](THIRD_PARTY_NOTICES.zh-CN.md)。
