# MCMCL

> 你的下一款启动器，何必是启动器。

**！！⚠赤石警告⚠！！**

Minecraft Minecraft Launcher 是一款运行在 Minecraft 26.2 里的启动器 NeoForge 模组。

由 [HMCL](https://github.com/HMCL-dev/HMCL) 核心强力驱动。

## 安装

1. 从 [Releases](../../releases) 下载模组 JAR，放入 `mods` 文件夹；
2. 使用 Minecraft 26.2 + NeoForge 启动游戏。

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

需要 JDK 25。Helper 依赖项目固定版本的 HMCL 源码，构建前请先拉取并切换到对应提交。

### PowerShell（Windows）

```powershell
git clone https://github.com/HMCL-dev/HMCL.git ..\HMCL
git -C ..\HMCL checkout df52bc6e81e2e1116c131483dfb9996fdb7b2b10

.\gradlew.bat -p helper "-PhmclCheckout=$((Resolve-Path '..\HMCL').Path)" clean test installHelper
.\gradlew.bat build
```

### Bash（Linux/macOS）

```bash
git clone https://github.com/HMCL-dev/HMCL.git ../HMCL
git -C ../HMCL checkout df52bc6e81e2e1116c131483dfb9996fdb7b2b10

./gradlew -p helper "-PhmclCheckout=$(cd ../HMCL && pwd)" clean test installHelper
./gradlew build
```

## 许可证

本项目以 [GPL-3.0](LICENSE) 许可证发布。

[HMCL](https://github.com/HMCL-dev/HMCL) 同样遵循 [GPL-3.0](LICENSE) 。
