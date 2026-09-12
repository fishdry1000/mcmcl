# 独立 HMCL helper（JSON Lines + HMCL Core）

helper 是 MCMCL 的独立启动后端：单独 JVM 进程，与模组通过 stdin/stdout 的 JSON Lines 通信。实例发现、manifest 继承解析、classpath/JVM 参数生成、原生库处理、下载安装与进程生命周期全部交给 HMCL Core；helper 本身只包含协议、仓库发现和 HMCL Core 适配层。

## 实现

- **协议**（`HelperServer`，无第三方依赖）：请求校验、异步事件转发、停止/关闭生命周期。
- **仓库发现**：`list` 读取 `versions/<instanceId>/`，支持 `<instanceId>.json` 与“目录中唯一 JSON 文件”两种 manifest 位置。
- **安装**：`remoteVersions` 列出可安装的游戏版本或加载器版本；`install` 经 `DefaultGameBuilder` 走 HMCL Core 官方下载管线，安装原版实例或叠加加载器组件（fabric/forge/neoforge/quilt/optifine）；`repair` 经 `checkGameCompletionAsync` 为已有实例补齐缺失文件。
- **启动**：`AuthInfo`、`LaunchOptions`（支持 `javaPath`——运行 `-version` 探测版本，及 `maxMemory`）、`DefaultLauncher`、`ProcessListener` 桥接。
- **HMCL Core 适配边界**（`src/hmcl/java/`，仅 profile 构建编译）：headless repository/instance subclass 与上述能力的桥接；adapter 还会初始化 JavaFX 工具包（HMCL Core 的任务进度与快照发布依赖它）和全局 `CacheRepository`（ETag 下载缓存，位于 `<仓库>/cache/`）。
- 未接 HMCL Core 时使用 `UnavailableHmclCoreAdapter`：`launch`/`install` 明确返回 `HMCL_CORE_UNAVAILABLE`，不存在 `launch.json` 回退。

## 构建

协议代码和 HMCL Core ABI 以 Java 17 为基线；HMCL checkout 的 JavaFX 25 profile 要求用 JDK 25 构建、测试和运行（与 Minecraft 26.2 一致）。

```powershell
# 无依赖协议构建（JDK 17 即可）
.\gradlew.bat -p helper test
.\gradlew.bat -p helper jar
java -jar helper\build\libs\mcmcl-hmcl-helper-0.1.0.jar --repository C:\path\to\.minecraft
```

上面的默认 JAR 只用于协议测试和仓库发现；未接入 HMCL Core 时 `launch`/`install` 返回 `HMCL_CORE_UNAVAILABLE`。可启动 Minecraft 的 profile JAR 需要固定 HMCL checkout：

```powershell
.\gradlew.bat -p helper `
  "-PhmclCheckout=C:\src\HMCL" `
  clean test installHelper
java -jar helper\build-hmcl\libs\mcmcl-hmcl-helper-0.1.0.jar --repository C:\path\to\.minecraft
```

- profile 构建输出在 `helper/build-hmcl/`（刻意与 `helper/build/` 分离，避免旧适配器类混入无依赖构建）。
- profile JAR 包含 HMCL Core 和传递依赖，**不含 JavaFX**（运行时按平台拉取，见下文），因此平台无关、单文件分发。
- profile JAR 会从 checkout 复制 `LICENSE` 到 `META-INF/licenses/HMCL-LICENSE.txt`；按 GPL-3.0 发布时仍须提供对应源码或有效的源码获取方式。
- `installHelper` 写入 `../run/mcmcl/hmcl-helper.jar`，可用 `-PhelperInstallDirectory=<目录>` 覆盖；也可以直接使用 Gradle 的 `run` 任务。
- 固定提交记录在 `helper/gradle.properties` 的 `hmclPinnedCommit`。Git checkout 会校验 HEAD 与之一致，且拒绝带脏改动（本地调试豁免 `-PhmclAllowDirty=true`，该产物不应发布）；不含 `.git` 的源码压缩包无法自动校验，构建只把提交写入 JAR 元数据，发布者须自行确认来源。可用 `-PhmclCommit=<commit>` 覆盖记录值。
- 构建期的 JavaFX 编译依赖默认取宿主平台 classifier、版本 25；可用 `-PhmclJavafxVersion` 和 `-PhmclJavafxClassifier` 覆盖。

HMCL Core 的获取方式（源码 composite build 或发布到本机 Maven 仓库）见下文[背景调研](#背景调研hmcl-core-接入)。

## 运行

```text
java -jar mcmcl-hmcl-helper.jar --repository <HMCL 游戏仓库根目录> [选项]
```

- `--repository`：包含 `versions/`、`libraries/`、`assets/` 的游戏仓库根目录，必须存在且为目录。
- `--download-provider <mojang|bmclapi|url>`：安装源，默认 `mojang`（官方）；`bmclapi`（bmclapi2.bangbang93.com 镜像）或任意 BMCLAPI 兼容 `http(s)://` 根 URL。
- `--javafx-version <v>` / `--javafx-dir <dir>` / `--javafx-repo <url>`：JavaFX 运行时的版本、缓存目录与镜像，见下文。
- `--help`：用法说明。

进程行为：stdout **只**输出协议 JSON，所有诊断写 stderr；启动参数错误退出码为 2，正常 EOF 或 `shutdown` 为 0；目标游戏自身的退出码只通过 `exit` 事件传递。

## JavaFX 运行时

HMCL Core 的任务进度与快照发布依赖 JavaFX。为了让 profile JAR 跨平台，JavaFX 不打包进 JAR：首次启动时 helper 检测 classpath 上没有 JavaFX，就按当前平台（Windows/macOS/Linux × x64/AArch64）从 Maven 仓库下载 `javafx-base`/`javafx-graphics`/`javafx-controls` 三个模块到 `<仓库>/javafx/<版本>-<平台>/`，校验 SHA-1 后带着扩展 classpath 重启自身；此后启动直接复用缓存，不再联网。也可以手动预置缓存目录（离线机器）：放入平台匹配的 `javafx-base.jar`、`javafx-graphics.jar`、`javafx-controls.jar`，helper 会直接使用，不做任何网络请求。

- `--javafx-version <v>`：JavaFX 版本，默认 `25`；
- `--javafx-dir <dir>`：缓存目录，默认 `<仓库>/javafx`；
- `--javafx-repo <url>`：Maven 仓库根，默认 Maven Central（`https://repo1.maven.org/maven2`），可指向任意镜像（如 `https://maven.aliyun.com/repository/public`）。

注意：Linux 无显示器的服务器环境无法启动 JavaFX 工具包（HMCL 本身同理），桌面环境不受影响。

## JSON Lines 协议 v1

每行一个 JSON 值。请求必须是对象，并包含 JSON 标量 `id` 和 `command`。响应和异步事件都不会跨行，也不会把 access token 写入日志或响应。

### 请求

```json
{"id":"h1","command":"hello"}
{"id":"l1","command":"list"}
{"id":"rv1","command":"remoteVersions"}
{"id":"i1","command":"install","instanceId":"1.21.1-fabric","gameVersion":"1.21.1","loaders":[{"type":"fabric","version":"0.16.9"}]}
{"id":"r1","command":"repair","instanceId":"26.2"}
{"id":"l2","command":"launch","instanceId":"26.2","username":"Player","uuid":"00000000-0000-0000-0000-000000000000","accessToken":"...","userType":"msa","xuid":"...","clientId":"...","javaPath":"C:\\path\\bin\\java.exe","maxMemory":4096}
{"id":"s1","command":"stop","instanceId":"26.2"}
{"id":"q1","command":"shutdown"}
```

`launch` 的必填字段是 `instanceId`、`username`、`uuid`、`accessToken`、`userType`；`xuid`、`clientId`、`javaPath` 和 `maxMemory` 可选。`javaPath` 指向目标游戏使用的 Java 可执行文件（helper 会运行 `-version` 探测版本）；`maxMemory` 是最大堆（MB）。离线账号由模组侧构造：`uuid` 使用 `OfflinePlayer:<用户名>` 的 nameUUID，`accessToken` 使用随机 UUID，`userType` 仍为 `msa`。当前协议不携带 `userProperties`，HMCL 适配器在内部按账号类型构造对应的属性 JSON。

`install` 创建新实例（`instanceId` 必须不存在）；`gameVersion` 为要安装的游戏版本，可选 `loaders` 数组指定 HMCL 组件 patch id + 版本（如 `{"type":"fabric","version":"0.16.9"}`），helper 通过 `GameBuilder` 组件链安装 Fabric/Forge/NeoForge/Quilt/OptiFine 等加载器。`repair` 为已有实例补齐缺失的客户端 jar、库与资源文件（不需要 `gameVersion`）。

两者都是异步操作，复用 `launch` 的事件模型但没有 `started` 事件：进度通过 `log` 事件返回，成功以 `exit`（code 0）结束，失败以 `error` 事件结束。安装串行执行（HMCL 仓库一次只允许一个独占 draft），重复请求返回 `ALREADY_RUNNING`；安装进行中的 `launch` 会直接失败。`stop` 同时作用于启动槽与安装/修复槽；安装取消是尽力而为（中断 + `TaskExecutor.cancel()`），关闭 helper 始终可靠终止。

模组在其他请求前自动发送 `hello`，返回协议版本、helper 版本、后端名称、能力位与 HMCL 提交：

```json
{"type":"response","id":"h1","ok":true,"protocolVersion":1,"helperVersion":"0.1.0","backend":"hmcl-core","launchAvailable":true,"installAvailable":true,"hmclProfile":true,"hmclCommit":"df52bc6e81e2e1116c131483dfb9996fdb7b2b10"}
```

协议版本不兼容时，模组会关闭该 helper 并显示明确错误；默认无 HMCL profile 的 JAR 报告 `backend:"unavailable"`、`launchAvailable:false`、`installAvailable:false`。

### 响应

所有响应都具有以下公共形状：

```json
{"type":"response","id":"l1","ok":true}
{"type":"response","id":"bad","ok":false,"code":"INVALID_REQUEST","message":"..."}
```

`list` 成功时增加 `instances` 数组：

```json
{
  "instanceId":"1.26.2",
  "name":"1.26.2",
  "root":"C:\\path\\to\\.minecraft\\versions\\1.26.2",
  "manifest":"C:\\path\\to\\.minecraft\\versions\\1.26.2\\1.26.2.json"
}
```

`remoteVersions` 成功时增加 `versions` 数组（`id`/`type`/`releaseTime`）：

```json
{"type":"response","id":"rv1","ok":true,"versions":[{"id":"26.2","type":"release","releaseTime":"2026-06-09T12:05:32+00:00"}]}
```

不带参数时返回全部游戏版本（按发布时间从新到旧）；携带可选 `component`（HMCL patch id，如 `fabric`/`forge`/`neoforge`/`quilt`/`optifine`）和 `gameVersion` 时返回该游戏版本可用的加载器版本（`id` 为组件版本号）。`component` 必须与 `gameVersion` 同时提供，否则返回 `INVALID_REQUEST`；未接入 HMCL Core 时返回 `HMCL_CORE_UNAVAILABLE`。

`launch`/`install`/`repair` 的 `ok:true` 只表示请求已接受，并且保证先于该请求产生的异步事件写出；准备阶段失败（未知实例、HMCL Core 未接入、重复请求等）直接返回 `ok:false`。可观察的错误代码包括 `INVALID_REQUEST`、`UNKNOWN_COMMAND`、`HMCL_CORE_UNAVAILABLE`、`ALREADY_RUNNING`、`NOT_RUNNING`、`CANCELLED` 和 `INTERNAL_ERROR`。

### 异步事件

```json
{"type":"event","instanceId":"26.2","event":"started"}
{"type":"event","instanceId":"26.2","event":"log","line":"[main/INFO]: ..."}
{"type":"event","instanceId":"26.2","event":"exit","code":0}
{"type":"event","instanceId":"26.2","event":"error","code":"HMCL_CORE_ERROR","message":"..."}
```

`started`、`log`、`exit`、`error` 是唯一的事件名。helper 会用锁保护 stdout，因此 HMCL 线程产生的日志不会和响应交错。空白输入行会忽略；无法解析的行会返回 `id:null` 的 `INVALID_REQUEST` 响应并继续服务。

## 背景调研：HMCL Core 接入

以下为接入前的调研记录（针对 `HMCL-dev/HMCL` 的 `main` 分支，提交 `df52bc6e81e2e1116c131483dfb9996fdb7b2b10`），结论是“可以源码接入，但应把 HMCL Core 限制在独立 helper 进程内，不应让 NeoForge 模组直接依赖它”。

1. 仓库根 `settings.gradle.kts` 包含 `HMCL`、`HMCLCore`、`HMCLBoot` 等多个 Gradle 子项目；`HMCLCore/build.gradle.kts` 将 Java 编译目标设为 17，并通过 version catalog 引入 Gson、JavaFX 相关库、压缩库、JNA、HTTP/HTML 等依赖。
2. `HMCLCore` 提供 `GameRepository`、`GameInstanceManifest`、`LaunchOptions`、`AuthInfo`、`DefaultLauncher` 和 `ProcessListener` 等核心类型，但 `DefaultGameRepository` 是抽象类。
3. 完整 HMCL 的 `HMCLGameRepository` 在 `HMCL` 主模块而不是 `HMCLCore`；它依赖 `GameDirectory`、设置管理和 JavaFX 属性。只拿 Core jar 不能直接得到完整 HMCL 的仓库实现。
4. `DefaultLauncher` 的调用链需要一个已解析的 `GameInstance`/manifest、`AuthInfo`、`LaunchOptions` 和 `ProcessListener`，不是接收实例目录就能启动的静态函数。
5. 没有稳定的公开 `HMCLCore` Maven 坐标（根构建配置 `mavenLocal()` 发布，坐标 `HMCL3:HMCLCore:unspecified`，不是稳定契约）。

### 获取 HMCL Core 的两种方式

方案 A（推荐）：固定 HMCL 源码 checkout，Gradle composite build：

```powershell
git clone https://github.com/HMCL-dev/HMCL.git C:\src\HMCL
git -C C:\src\HMCL checkout <经过审查的固定提交>
.\gradlew -p helper "-PhmclCheckout=C:\src\HMCL" clean test installHelper
```

该参数会把 `org.jackhuang:HMCLCore` 替换为 checkout 中的 `:HMCLCore` 项目并编译 `src/hmcl/java`。这是完整 HMCL Gradle 构建，首次构建会解析 HMCL 的构建逻辑和 Core 依赖。正式发布前应把 checkout 固定到已审查的提交。

方案 B：先在固定 checkout 中发布到本机 Maven 仓库：

```powershell
cd C:\src\HMCL
.\gradlew :HMCLCore:publishToMavenLocal
cd F:\Porj\Minecraft\mcmcl-0.1.0+mc26.2
.\gradlew.bat -p helper "-PhmclGroup=HMCL3" "-PhmclVersion=unspecified" test
```

方案 B 的 group/version 必须与固定 checkout 实际发布的 POM 一致，适合本地验证；作为可复现发布机制时必须同时保存源码提交、生成的 POM/依赖锁定和许可证材料。

## 真实适配器的当前实现

`RealHmclCoreAdapter` 的 profile-only 路径（HMCL API 变化时编译失败会集中在 `src/hmcl/java`，默认协议 profile 不受影响）：

1. 创建或复用 headless 的 HMCL repository，`refresh` 后按 `GameInstanceID` 解析实例；
2. 用 `HmclLaunchRequest` 构造 HMCL `AuthInfo`（Core 的 `AuthInfo` 不接收 `xuid`/`clientId`，两个字段仅保留在协议中）；
3. 组装 `LaunchOptions`：实例运行目录、Java runtime（`javaPath` 存在时探测版本，否则 helper 自身 JVM）、`maxMemory`；
4. `DefaultLauncher` + `ProcessListener.onLog/onExit` 映射为事件；`stop`/`shutdown` 终止游戏进程；
5. `install`/`repair` 经 `DefaultDependencyManager.newGameBuilder`/`checkGameCompletionAsync`，任务用 `TaskExecutor` 启动并等待（`whenComplete` 组合子依赖 executor 维护的状态，不能用裸 `Task.run()`）；取消为中断 + `TaskExecutor.cancel()`；
6. 发布前完成 GPL-3.0 合规审查；JavaFX 不随 JAR 分发。

## 验收记录

- 真实 profile：`hello`/`list`/`shutdown` 协议冒烟；动态 JVM fixture 验证 `DefaultLauncher` 的进程启动、stdout/stderr 转发、退出事件；用完整 Minecraft 26.2 文件集创建真实游戏进程、转发日志并 `stop` 结束。
- 安装路径（本地 BMCLAPI 兼容 fixture 服务器）：`remoteVersions`；全新 `install`（版本 JSON、客户端 jar、资源索引带 SHA-1 落盘）；删除客户端 jar 后 `repair` 补齐；**Fabric 加载器实例**（假 fabric-meta 端点提供加载器列表与 launch meta，产物含 fabric 补丁与库文件）及“安装后启动该实例”全链路。
- **Linux**（WSL2 Ubuntu 24.04 + Oracle JDK 25）：bootstrap 识别 linux classifier、从 Maven Central 下载 JavaFX linux 模块并缓存复用、JavaFX 工具包启动；`install` 从 Mojang 真实下载 264MB 原版实例（1.7.10），删除客户端 jar 后 `repair` 补齐。
- 待验收：Forge/NeoForge/Quilt 安装器链对真实镜像服务器的下载。

## 目录

```text
helper/
├─ README.md
├─ settings.gradle
├─ build.gradle
└─ src/
   ├─ main/java/top/fish1000/mcmcl/helper/
   │  ├─ Main.java              # 入口 + CLI 参数 + JavaFX bootstrap
   │  ├─ JavaFxBootstrap.java   # JavaFX 检测/下载/校验/重启
   │  ├─ HelperServer.java      # JSON Lines 命令循环
   │  ├─ HmclCoreAdapterFactory.java
   │  ├─ hmcl/                  # HMCL Core 唯一适配边界（协议类型）
   │  ├─ protocol/              # 无第三方依赖的 JSON 实现
   │  └─ repository/            # 标准 versions/ 目录的只读发现
   ├─ hmcl/java/top/fish1000/mcmcl/helper/hmcl/
   │  ├─ RealHmclCoreAdapterProvider.java
   │  └─ RealHmclCoreAdapter.java
   └─ test/java/top/fish1000/mcmcl/helper/
      ├─ ProtocolTest.java      # 普通 main() 套件，经 protocolTest 任务运行
      └─ LaunchFixtureMain.java
```
