# MCMCL

MCMCL（Minecraft Minecraft Launcher）是一个运行在 Minecraft 内的 NeoForge 模组：在游戏里直接管理、安装并启动其他 Minecraft 实例。实例发现、manifest 继承、启动参数生成、原生库处理、下载安装与目标进程管理全部由独立 helper JVM 中的 HMCL Core 完成，模组本身只负责 GUI 与协议通信：

```text
Minecraft 内 GUI → JSON Lines（stdin/stdout）→ mcmcl-hmcl-helper.jar → HMCL Core → 目标 Minecraft
```

## 功能

- **启动/停止** HMCL 仓库中的已有实例，实时查看目标进程日志；
- **安装**新实例：原版，或叠加 Fabric/Forge/NeoForge/Quilt/OptiFine 加载器组件（HMCL `GameBuilder` 组件链，从 Mojang 官方源或 BMCLAPI 镜像下载）；
- **修复**已有实例缺失的客户端 jar、库与资源文件；
- **账号**：沿用当前 Minecraft 会话，或使用离线账号（`OfflinePlayer` UUID，用户名可配置）；
- **启动设置**：目标 Java 路径、最大内存；
- **开箱即用**：模组 JAR 内置 helper，缺失时自动解压，模组更新时自动刷新。

`launch.json` 已废弃：模组不再解析它，也不存在回退路径。

## 构建

| 工程 | 目录 | 说明 |
|---|---|---|
| NeoForge 模组 | 仓库根目录 | 游戏内启动器 UI + helper 进程客户端 |
| Helper | `helper/`（独立 Gradle 工程） | 封装 HMCL Core 的 JSON Lines 后端 |

开发环境为 Windows（`gradlew.bat`），Linux/macOS 用 `./gradlew`。目标为 NeoForge 26.2 / Minecraft 26.2 / Java 25；helper 协议代码以 Java 17 为基线。

### 模组

```powershell
./gradlew.bat build        # 产物在 build/libs/
./gradlew.bat runClient    # 开发客户端
```

模组构建会自动把 `helper/build-hmcl/libs` 下最新的 profile JAR 打包进模组；没有 profile 产物时仅告警跳过，`-PrequireHelperEmbed=true` 可强制失败。

### Helper

```powershell
# 无依赖的协议构建（JDK 17 即可）：仅协议与仓库发现，不能启动游戏
./gradlew.bat -p helper test
./gradlew.bat -p helper jar

# 真实 HMCL profile（需 JDK 25）：可启动游戏、安装/修复实例
./gradlew.bat -p helper "-PhmclCheckout=C:\src\HMCL" clean test installHelper
```

- HMCL Core 没有稳定的公开 Maven 坐标，profile 构建需要固定一份经过审查的 HMCL 源码 checkout，具体流程与固定提交校验见 [helper/README.md](helper/README.md)。
- `installHelper` 把 profile JAR 复制到 `run/mcmcl/hmcl-helper.jar`（开发运行目录），可用 `-PhelperInstallDirectory=<目录>` 覆盖。
- profile JAR **平台无关**：JavaFX 不打包在内，helper 首次启动时按当前平台自动下载并缓存（离线机器可预置缓存），见 [helper/README.md](helper/README.md) 的“JavaFX 运行时”。
- profile JAR 内含 HMCL Core（GPL-3.0），构建会把 checkout 的 `LICENSE` 放入 `META-INF/licenses/HMCL-LICENSE.txt`；发布时必须随附对应源码/源码获取方式。

## 运行

把模组 JAR 放进 `mods/` 即可。进入标题/暂停界面点击“打开 MCMCL”（或按 `M`），模组会自动解压内置 helper 并启动。游戏仓库默认位于：

```text
<Minecraft 游戏目录>/mcmcl/hmcl/
```

这是包含 `versions/`、`libraries/`、`assets/` 的 HMCL/官方布局根目录（实例 manifest 形如 `mcmcl/hmcl/versions/26.2/26.2.json`）。也可以把配置项 `instancesDirectory` 指向任意 HMCL 实例目录，或点“打开实例文件夹”后手动放入。

helper 也可以独立运行（调试用）：

```text
java -jar mcmcl-hmcl-helper.jar --repository <HMCL 游戏仓库根目录>
```

## helper 协议

模组与 helper 通过 stdin/stdout 的 UTF-8 JSON Lines 通信：stdout 只输出协议消息，诊断写 stderr；退出码 0 表示正常结束，2 表示启动参数错误。

模组启动 helper 后先发送 `hello` 校验协议版本，并读取 helper 版本、HMCL 固定提交与能力位（`launchAvailable`/`installAvailable`）。`launch`/`install`/`repair` 是异步操作：响应 `ok:true` 只表示请求已接受，结果通过 `started`/`log`/`exit`/`error` 事件返回。完整协议（命令、字段、错误码、事件）见 [helper/README.md](helper/README.md)。

## 设计边界

- 只有物理客户端加载启动器代码；专用服务器不受影响。
- 目标游戏运行在独立 JVM 中，不阻塞 Minecraft 主线程。
- 当前会话凭据（用户名/UUID/token/XUID/clientId）透传给 helper，离线模式下由模组生成离线凭据；MCMCL 不实现微软登录或令牌刷新。
- Forge/NeoForge/Quilt 安装器链尚未对真实镜像服务器做联网验收（Fabric 已在本地 fixture 验证）。
- helper 运行时必须遵循 HMCL Core 的 GPL-3.0 条款；发布模组和 helper 需一并提供对应源码材料。

## 配置

NeoForge 客户端配置项：

- `instancesDirectory`：HMCL 仓库根目录，默认 `mcmcl/hmcl`；
- `hmclHelperJar`：helper JAR 路径，默认 `mcmcl/hmcl-helper.jar`；缺失时自动解压模组内置的 helper，模组更新时自动刷新，自己放置的 JAR 不会被覆盖；
- `maxInstances`：界面最多显示的实例数量；
- `offlineMode`：使用离线账号而不是当前 Minecraft 会话；
- `offlineUsername`：离线账号用户名，留空时沿用当前会话用户名；
- `javaPath`：目标游戏使用的 Java 可执行文件路径，留空时使用 helper 自身的 Java；
- `maxMemory`：目标游戏最大内存（MB），0 表示由 HMCL 决定；
- `downloadProvider`：安装实例时的下载源，`mojang`（默认）或 `bmclapi`。
