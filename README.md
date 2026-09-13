# MCMCL

运行在 Minecraft 里的启动器模组。不用退出游戏，就能安装、启动和管理其他 Minecraft 实例。

本体是一个 [NeoForge](https://neoforged.net/) 模组，实例的下载安装和启动由内置的 HMCL 启动后端完成，不需要单独安装其他软件。

## 功能

- 安装新实例：原版，以及 Fabric / Forge / NeoForge / Quilt / OptiFine
- 一键修复实例缺失的文件
- 启动、停止实例，实时查看日志
- 使用离线账号，或沿用当前登录的微软账号
- 可以为实例指定 Java 路径和内存大小

## 安装

1. 从 [Releases](../../releases) 下载模组 JAR，放入 `mods` 文件夹；
2. 使用 NeoForge 26.2 启动游戏。

后端所需的文件已全部内置，首次打开时自动就位；安装实例时会联网下载游戏文件。

## 使用

在标题界面或暂停菜单点击「打开 MCMCL」，也可以按 `M`。

- **安装**：点「安装」，选好游戏版本后点「下一步」（选了加载器会再选加载器版本），等待下载完成；
- **启动**：选中实例后点「启动」，日志会实时显示在下方，「停止」可以结束游戏；双击实例或回车也可以直接启动；
- **管理**：「编辑」修改该实例的启动设置（Java 路径、最大内存，留空用全局配置），「Mod 目录」直达该实例的 mods 文件夹（仅模组实例可用）。
- **修复**：实例缺文件打不开时，删除坏掉的文件后再启动，缺什么补什么。

首次使用建议先到 NeoForge 配置里看一眼账号设置：默认沿用当前登录的微软账号；想用离线账号就打开 `offlineMode` 并填好用户名（离线账号只能进离线服务器）。

## 配置

| 配置项 | 说明 | 默认值 |
|---|---|---|
| `instancesDirectory` | 实例仓库目录 | `mcmcl/hmcl` |
| `hmclHelperJar` | 启动后端 JAR 位置（一般不用改） | `mcmcl/hmcl-helper.jar` |
| `offlineMode` | 使用离线账号 | 关 |
| `offlineUsername` | 离线账号用户名 | 当前账号名 |
| `javaPath` | 启动实例用的 Java，留空用游戏自带 | 空 |
| `maxMemory` | 实例最大内存（MB），0 为自动 | 0 |
| `downloadProvider` | 下载源：`mojang` 或 `bmclapi` | `mojang` |
| `maxInstances` | 列表最多显示的实例数 | 32 |

## 常见问题

**下载慢或者失败？** 把 `downloadProvider` 改成 `bmclapi`（国内镜像）再试。

**提示 Java 版本不合适？** 在 `javaPath` 里指定一个合适的 Java；不确定就留空，默认使用与游戏相同的 Java。

**能登录微软账号吗？** MCMCL 不做账号登录，离线模式之外都是沿用你启动 Minecraft 时登录的账号。

## 从源码构建

需要 JDK 25：

```powershell
gradlew.bat -p helper "-PhmclCheckout=<HMCL 源码目录>" clean test installHelper
gradlew.bat build
```

构建细节、启动后端协议与开发说明见 [helper/README.md](helper/README.md)。

## 许可证

本项目以 [GPL-3.0](LICENSE) 许可证发布，与内置启动后端所依赖的 [HMCL](https://github.com/HMCL-dev/HMCL) 保持一致。安装界面的版本图标取自 HMCL（`assets/minecraftminecraftlauncher/textures/gui/version/`），同样遵循 GPL-3.0。
