# 第三方组件说明

[English Notices](THIRD_PARTY_NOTICES.md)

Create Cinema 采用 MIT License 开源。项目使用或引用到的第三方游戏、模组、库、映射、工具和在线视频服务仍然遵循它们各自的许可证和使用条款。

本文件是当前项目版本的依赖说明。后续如果依赖版本发生变化，应重新检查上游许可证文件并更新这里的内容。

## Minecraft 和模组加载器

| 名称 | 用途 | 说明 |
| --- | --- | --- |
| Minecraft | 目标游戏平台 | 属于 Mojang/Microsoft，遵循其 EULA 和使用规范。本项目不对 Minecraft 授权。 |
| NeoForge | 模组加载器和 Minecraft 模组开发 API | 第三方模组平台。许可证和声明以 NeoForge 上游项目为准。 |
| Parchment mappings | 开发期映射 | 用于构建时提供可读的 Minecraft 名称。许可证和声明以 Parchment 上游项目为准。 |

## 必需模组和模组库

| 名称 | 用途 | 说明 |
| --- | --- | --- |
| Create | 必需运行模组，提供动力系统集成 | Create 是独立模组，不由本项目授权。运行时需要安装兼容版本的 Create。 |
| Ponder | Create 生态的 UI/教程依赖 | 通过 Create/NeoForge 依赖栈使用。许可证和声明以上游项目为准。 |
| Flywheel | Create 渲染依赖 | 作为 Create 运行时/渲染依赖使用。许可证和声明以上游项目为准。 |
| Registrate | 注册辅助库 | 用于方块、物品、菜单和方块实体注册。许可证和声明以上游项目为准。 |

## 内嵌和运行时库

| 名称 | 用途 | 说明 |
| --- | --- | --- |
| JavaCV | 用于 FFmpeg 视频/音频处理的 Java 绑定 | 作为运行时依赖或内嵌依赖使用。许可证说明以 JavaCV 上游项目为准。 |
| JavaCPP | JavaCV/FFmpeg 使用的原生绑定运行时 | 作为运行时依赖或内嵌依赖使用。许可证说明以 JavaCPP 上游项目为准。 |
| Bytedeco FFmpeg binaries | 视频/音频解码和编码 | 通过 Bytedeco 构件作为运行时依赖或内嵌依赖使用。FFmpeg 组件根据构建配置遵循其上游 LGPL/GPL 条款。 |
| jsoup | 网络视频页面 HTML 解析 | 通过 Jar-in-Jar 内嵌。许可证说明以 jsoup 上游项目为准。 |
| Microsoft Edge WebView2 SDK loader | 仅 Windows 的抖音内嵌授权与认证网络响应捕获 | 以静态方式链接进原生桥，遵守 WebView2 SDK 的 BSD 风格许可。Evergreen WebView2 Runtime 由 Microsoft 单独提供并更新。 |

## 在线视频服务

网络投影仪可以解析受支持页面中公开暴露的媒体地址。本项目不授予任何第三方视频内容的使用权，也不用于绕过平台条款。用户需要自行遵守各网站的服务条款、账号要求、版权规则和地区限制。
