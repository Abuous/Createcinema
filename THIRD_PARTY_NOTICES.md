# Third-Party Notices

[中文说明](THIRD_PARTY_NOTICES.zh-CN.md)

Create Cinema is licensed under the MIT License. Third-party games, mods, libraries, mappings, tools, and media services used with or referenced by this project remain under their own licenses and terms.

This file is a practical dependency notice for the current project version. If dependency versions change, review the upstream license files and update this notice.

## Minecraft and Mod Loader

| Name | Purpose | Notice |
| --- | --- | --- |
| Minecraft | Target game platform | Owned by Mojang/Microsoft and governed by their EULA and usage guidelines. Not licensed by this project. |
| NeoForge | Mod loader and Minecraft modding API | Third-party modding platform. See the NeoForge project for its license and notices. |
| Parchment mappings | Development mappings | Used at build time for readable Minecraft names. See the Parchment project for its license and notices. |

## Required Mods and Mod Libraries

| Name | Purpose | Notice |
| --- | --- | --- |
| Create | Required runtime mod and kinetic system integration | Create is a separate mod and is not licensed by this project. Install a compatible Create version separately. |
| Ponder | Create ecosystem UI/tutorial dependency | Used through the Create/NeoForge dependency stack. See upstream license and notices. |
| Flywheel | Create rendering dependency | Used as a Create runtime/rendering dependency. See upstream license and notices. |
| Registrate | Registration helper library | Used for block, item, menu, and block entity registration. See upstream license and notices. |

## Embedded and Runtime Libraries

| Name | Purpose | Notice |
| --- | --- | --- |
| JavaCV | Java bindings used for FFmpeg-backed video/audio handling | Embedded or included at runtime. See the JavaCV project for its Apache License 2.0 notices. |
| JavaCPP | Native binding runtime used by JavaCV/FFmpeg | Embedded or included at runtime. See the JavaCPP project for its Apache License 2.0 notices. |
| FFmpeg binaries from Bytedeco presets | Video/audio decoding and encoding | Embedded or included at runtime through Bytedeco artifacts. FFmpeg components remain under their upstream LGPL/GPL license terms depending on build configuration. |
| jsoup | HTML parsing for network video URL discovery | Embedded through Jar-in-Jar. See the jsoup project for its MIT License notices. |

## Online Video Services

Network projectors can resolve publicly exposed media URLs from supported pages. This project does not grant rights to third-party video content or bypass provider terms. Users are responsible for complying with each website's terms, account requirements, copyright rules, and regional restrictions.
