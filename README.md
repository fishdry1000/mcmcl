# MCMCL

**！！⚠赤石警告⚠！！**

> 你的下一款启动器，何必是启动器。

Minecraft Minecraft Launcher 是一款运行在 Minecraft 26.2 里的启动器 NeoForge 模组。

由 [HMCL](https://github.com/HMCL-dev/HMCL) 核心强力驱动。

## 安装

1. 从 [Releases](../../releases) 下载模组 JAR，放入 `mods` 文件夹；
2. 使用 NeoForge 26.2 启动游戏。

## 使用

在标题界面或暂停菜单点击「打开 MCMCL」，也可以按 `M`。

## 配置

| 配置项               | 说明                                        | 默认值                  |
| -------------------- | ------------------------------------------- | ----------------------- |
| `instancesDirectory` | 实例仓库目录                                | `mcmcl/hmcl`            |
| `hmclHelperJar`      | 启动后端 JAR 位置（一般不用改）             | `mcmcl/hmcl-helper.jar` |
| `offlineMode`        | 使用离线账号                                | 关                      |
| `offlineUsername`    | 离线账号用户名                              | 当前账号名              |
| `javaPath`           | 启动实例用的 Java，留空用游戏自带           | 空                      |
| `maxMemory`          | 实例最大内存（MB），0 为自动                | 0                       |
| `versionIsolation`   | 版本隔离策略：`ALWAYS`、`MODDED` 或 `NEVER` | `MODDED`                |
| `downloadProvider`   | 下载源：`mojang` 或 `bmclapi`               | `mojang`                |
| `maxInstances`       | 列表最多显示的实例数                        | 32                      |

## 从源码构建

### 拉取 HMCL 源代码

```bash
git clone https://github.com/HMCL-dev/HMCL
```

### 构建项目

需要 JDK 25：

```bash
gradlew -p helper "-PhmclCheckout=<HMCL 源码目录>" clean test installHelper
gradlew build
```

## 许可证

本项目以 [GPL-3.0](LICENSE) 许可证发布。

[HMCL](https://github.com/HMCL-dev/HMCL) 同样遵循 [GPL-3.0](LICENSE) 。
