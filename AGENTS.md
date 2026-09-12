# AGENTS.md — MCMCL 工作区指南

## 仓库简介

MCMCL（Minecraft Minecraft Launcher）是一个运行在 Minecraft 内的 NeoForge 模组（目标 Minecraft 26.2），通过独立的 helper JVM 启动其他 Minecraft 实例：

```
Minecraft 内 GUI → JSON Lines（stdin/stdout）→ mcmcl-hmcl-helper.jar → HMCL Core → 目标 Minecraft
```

仓库包含 **两个相互独立的 Gradle 工程**：

| 工程 | 位置 | 作用 |
|---|---|---|
| NeoForge 模组 | 仓库根目录，`src/main/java/top/fish1000/mcmcl/` | 游戏内启动器 UI + helper 进程客户端 |
| Helper | `helper/`（独立 `settings.gradle`，用 `-p helper` 构建） | 封装 HMCL Core 的独立 JVM 后端 |

主要文档（中文，协议与设计细节请先阅读）：`README.md`、`helper/README.md`。

## 常用命令

开发环境为 Windows，使用 `gradlew.bat`（CI 在 Linux 上用 `./gradlew`）。以下命令均从仓库根目录执行。

```powershell
# 模组（Java 25，NeoForge 26.2）
./gradlew.bat build
./gradlew.bat runClient

# Helper（默认无依赖的协议构建，基线 JDK 17）
./gradlew.bat -p helper test
./gradlew.bat -p helper jar

# Helper 真实 HMCL profile 构建，并安装到开发运行目录
./gradlew.bat -p helper "-PhmclCheckout=C:\src\HMCL" clean test installHelper
```

- `installHelper` 会把 JAR 复制到 `run/mcmcl/hmcl-helper.jar`（可用 `-PhelperInstallDirectory=<目录>` 覆盖）。
- 模组构建（`prepareBundledHelper` 任务）会把 `helper/build-hmcl/libs` 下最新的 profile JAR 打包进模组（资源 `helper/hmcl-helper.jar`），运行时由 `HelperBundle` 自动解压到配置的 helper 位置；没有 profile 产物时模组构建仅告警跳过，`-PrequireHelperEmbed=true` 可强制失败。
- **测试不是 JUnit**：`helper` 的测试是普通 `main()` 入口套件（`ProtocolTest`），通过 `protocolTest` 任务运行；`test` 依赖该任务。
- 根工程启用了 Gradle configuration-cache（注意：`build.gradle` 改动后若任务行为异常，用 `--no-configuration-cache` 排查）。

## 架构边界

**模组侧**（`src/main/java/top/fish1000/mcmcl/`）：
- `MinecraftMinecraftLauncher` — 公共 `@Mod` 入口；保持不含任何客户端类，确保专用服务器不会加载启动器代码。
- `MinecraftMinecraftLauncherClient` — `@Mod(dist = Dist.CLIENT)` 客户端入口；注册快捷键 `M`，在标题/暂停界面添加按钮。
- `HmclHelperClient` — 启动并管理 helper JVM 进程，负责协议通信；`LauncherScreen` 与 `InstanceManager` 是界面和实例列表；`Config` 是 NeoForge 客户端配置（`instancesDirectory`、`hmclHelperJar`、`maxInstances`）。

**Helper 侧**（`helper/src/`）：
- `Main`/`HelperServer` — JSON Lines 服务循环与请求分发。
- `protocol/` — 手写、零第三方依赖的 JSON 实现。保持无依赖状态。
- `repository/` — 对 `versions/<id>/<id>.json` manifest 的只读发现。
- `hmcl/` — **唯一**的 HMCL Core 适配边界。默认构建不编译 `src/hmcl/java/`，仅在传入 `-PhmclCheckout` 或 `-PhmclVersion` 时才会编译；这些是唯一允许 import `org.jackhuang.hmcl.*` 的文件。没有 profile 时，`Main` 使用 `UnavailableHmclCoreAdapter`，返回错误码 `HMCL_CORE_UNAVAILABLE`。

## 协议规则（JSON Lines v1）

- `stdout` **只**输出协议 JSON；所有诊断信息写到 `stderr`。helper 退出码：0 = 正常 EOF/`shutdown`，2 = 启动参数错误。
- 模组总是先发送 `hello`；响应与日志绝不能记录或回显 `accessToken`。
- `launch` 是异步操作：`ok:true` 只表示请求已接受；结果通过 `started` / `log` / `exit` / `error` 事件返回。
- 错误码：`INVALID_REQUEST`、`UNKNOWN_COMMAND`、`HMCL_CORE_UNAVAILABLE`、`ALREADY_RUNNING`、`NOT_RUNNING`、`CANCELLED`、`INTERNAL_ERROR`（事件中还有 `HMCL_CORE_ERROR`）。
- `launch.json` 是**已废弃**的后端 — 不要重新引入，也不要作为回退路径。
- 任何协议变更必须同步修改模组的 `HmclHelperClient`、helper 的 `HelperServer`，以及 `helper/README.md`。

## 构建注意事项

- `helper/gradle.properties` 记录了 `hmclPinnedCommit`；profile 构建会校验 HMCL checkout 的 HEAD 与之一致，且拒绝带脏改动的 checkout（仅限本地调试的豁免：`-PhmclAllowDirty=true`）。
- profile 构建输出在 `helper/build-hmcl/`（刻意与 `helper/build/` 分离 — 避免旧适配器类混入无依赖构建）。
- profile JAR 不打包 JavaFX：helper 首次启动时按当前平台从 Maven 仓库拉取（`JavaFxBootstrap`，缓存于 `<仓库>/javafx/<版本>-<平台>/`，可用 `--javafx-version/--javafx-dir/--javafx-repo` 覆盖），因此一个 profile JAR 跨平台分发。构建/测试期的 JavaFX 依赖默认取宿主平台的 classifier，可用 `-PhmclJavafxClassifier` 覆盖。
- 模组元数据（`neoforge.mods.toml`）由 `src/main/templates/` 以根 `gradle.properties` 的属性展开生成（`generateModMetadata` 任务）。
- HMCL Core 为 GPL-3.0：profile JAR 必须附带 `META-INF/licenses/HMCL-LICENSE.txt` 及对应源码/源码获取方式。模组本身为"All Rights Reserved"。

## 仓库杂项

- 根目录下的 `net/` 与 `META-INF/` 是空的残留目录，不是源码。
- `run/` 是被 gitignore 的开发运行目录（开发客户端的游戏目录），包含 `mcmcl/hmcl-helper.jar` 和 `mcmcl/hmcl/` HMCL 仓库。
- 仓库根目录的 `stderr.log` 是未跟踪的临时文件。
- 目标版本：Minecraft 26.2、NeoForge 26.2.0.86、modid `minecraftminecraftlauncher`；模组 Java 25 / helper 基线 Java 17（profile 构建运行在 JDK 25 上）。
