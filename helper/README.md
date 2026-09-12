# 独立 HMCL helper（JSON Lines + HMCL Core）

这是 MCMCL 的独立 helper-side 启动后端。helper 作为单独 JVM 运行，和模组通过 stdin/stdout 的 JSON Lines 通信；对于已经准备好的仓库，目标实例的发现、manifest 继承解析、classpath/JVM 参数生成、原生库处理和进程生命周期均交给 HMCL Core。缺失文件的下载、校验和 Java 安装仍需由 HMCL 或其他安装流程准备。

当前状态：

- JSON Lines 协议、请求校验、异步事件转发、停止和关闭生命周期已实现。
- `list` 会读取官方/HMCL 仓库的 `versions/<instanceId>/` 目录，并支持 `<instanceId>.json` 以及“目录中唯一 JSON 文件”两种 manifest 位置。
- `remoteVersions` 从 Mojang manifest（或 BMCLAPI 兼容镜像）获取可安装的原版版本列表；`install` 下载并安装新的原版实例；`repair` 为已有实例补齐缺失的客户端 jar、库和资源文件。安装通过 `DefaultGameBuilder`/`checkGameCompletionAsync` 走 HMCL Core 的官方下载管线。
- `launch` 支持可选的 `javaPath`（通过运行 `<java> -version` 探测版本）和 `maxMemory`（MB）。
- `src/hmcl/java/` 已有真实 HMCL Core adapter：headless repository/instance subclass、refresh/list、`AuthInfo`、`LaunchOptions`、`DefaultLauncher`、`ProcessListener` 和 `ManagedProcess` 的桥接，以及上述安装能力。adapter 会初始化 JavaFX 工具包（HMCL Core 的任务进度与快照发布依赖它）和全局 `CacheRepository`（ETag 下载缓存，位于仓库根目录的 `cache/`）。
- 默认构建不编译 `src/hmcl/java/`，`Main` 通过 provider/factory 得到 `UnavailableHmclCoreAdapter`；它会返回 `HMCL_CORE_UNAVAILABLE`，不会读取或执行 `launch.json`。
- 传入 `-PhmclCheckout=<固定 checkout>` 或 `-PhmclVersion=<本机发布版本>` 后，Gradle 才把 HMCL 源码 profile 加入编译并让 `Main` 加载真实 provider。profile 构建会把 HMCL Core、传递依赖和当前平台 JavaFX 打进一个可由 `java -jar` 直接启动的 JAR。

## 运行

在工作区根目录执行：

```powershell
.\gradlew.bat -p helper test
.\gradlew.bat -p helper jar
java -jar helper\build\libs\mcmcl-hmcl-helper-0.1.0.jar --repository C:\path\to\.minecraft
```

上面的默认 JAR 只用于协议测试和仓库发现；未接入 HMCL Core 时，`launch` 会明确返回 `HMCL_CORE_UNAVAILABLE`。要构建可启动 Minecraft 的 profile JAR，需要固定一个 HMCL checkout：

```powershell
.\gradlew.bat -p helper `
  "-PhmclCheckout=C:\src\HMCL" `
  clean jar installDist
java -jar helper\build-hmcl\libs\mcmcl-hmcl-helper-0.1.0.jar --repository C:\path\to\.minecraft
```

profile JAR 会从指定 checkout 复制 `LICENSE` 到
`META-INF/licenses/HMCL-LICENSE.txt`。发布该 JAR 时仍须保留构建所用的
HMCL 固定提交，并按 GPL-3.0 提供对应源码或有效的源码获取方式；默认
协议 JAR 不包含 HMCL Core，也不需要这份上游许可证。

当前审查提交记录在 `helper/gradle.properties` 的 `hmclPinnedCommit` 中。若
`hmclCheckout` 是 Git worktree，构建会校验其 HEAD；若使用不含 `.git` 的源码
压缩包，构建会把该提交写入 JAR 元数据和握手响应，但发布者仍须自行确认压缩包
确实来自该提交。可用 `-PhmclCommit=<commit>` 显式覆盖记录值。
Git checkout 若有已跟踪文件改动，构建会拒绝继续；仅本地调试时可显式传入
`-PhmclAllowDirty=true`，但这种产物不应发布。

构建并安装到默认 NeoForge 开发运行目录可合并为一个命令：

```powershell
.\gradlew.bat -p helper `
  "-PhmclCheckout=C:\src\HMCL" `
  clean test installHelper
```

`installHelper` 写入 `../run/mcmcl/hmcl-helper.jar`，可用
`-PhelperInstallDirectory=<目录>` 覆盖。profile JAR 包含当前平台的 JavaFX
原生库，正式二进制应按操作系统和 CPU 架构分别构建、标记和发布。

`--repository` 是包含 `versions/`、`libraries/` 和 `assets/` 的游戏仓库根目录；路径必须存在且为目录。可选的 `--download-provider` 选择安装源：`mojang`（默认，Mojang 官方）、`bmclapi`（_bmclapi2.bangbang93.com 镜像）或任意 BMCLAPI 兼容的 `http(s)://` 根 URL。也可以直接使用 Gradle 的 `run` 任务。

真实 profile 使用 helper 启动 JVM 自己的 `JavaRuntime.getDefault()` 作为 Minecraft Java。协议代码和 HMCL Core ABI 以 Java 17 为基线；默认无依赖构建可用 JDK 17。当前 HMCL checkout 的 JavaFX 25 profile 需要 JDK 25 构建、测试和运行，这也与 Minecraft 26.2 一致。profile 构建输出放在 `helper/build-hmcl/`，默认无依赖构建仍放在 `helper/build/`。构建脚本默认选择 Windows x64 的 JavaFX 25；可用 `-PhmclJavafxVersion` 和 `-PhmclJavafxClassifier` 覆盖版本/平台。

stdout 保证只输出协议 JSON；启动参数错误写入 stderr，helper 进程退出码为 2。正常 EOF 或 `shutdown` 的退出码为 0；游戏本身的退出码只通过 `exit` 事件传递。

## JSON Lines 协议 v1

每行一个 JSON 值。请求必须是对象，并包含 JSON 标量 `id` 和 `command`。响应和异步事件都不会跨行，也不会把 access token 写入日志或响应。

### 请求

```json
{"id":"h1","command":"hello"}
{"id":"l1","command":"list"}
{"id":"rv1","command":"remoteVersions"}
{"id":"i1","command":"install","instanceId":"1.21.1","gameVersion":"1.21.1"}
{"id":"r1","command":"repair","instanceId":"26.2"}
{"id":"l2","command":"launch","instanceId":"26.2","username":"Player","uuid":"00000000-0000-0000-0000-000000000000","accessToken":"...","userType":"msa","xuid":"...","clientId":"...","javaPath":"C:\\path\\bin\\java.exe","maxMemory":4096}
{"id":"s1","command":"stop","instanceId":"26.2"}
{"id":"q1","command":"shutdown"}
```

`launch` 的必填字段是 `instanceId`、`username`、`uuid`、`accessToken`、`userType`；`xuid`、`clientId`、`javaPath` 和 `maxMemory` 可选。`javaPath` 指向目标游戏使用的 Java 可执行文件（helper 会运行 `-version` 探测版本）；`maxMemory` 是最大堆（MB）。离线账号由模组侧构造：`uuid` 使用 `OfflinePlayer:<用户名>` 的 nameUUID，`accessToken` 使用随机 UUID，`userType` 仍为 `msa`。当前协议不携带 `userProperties`，真正的 HMCL 适配器会在内部按账号类型构造对应的属性 JSON。

`install` 创建新的原版实例（`instanceId` 必须不存在，`gameVersion` 为要安装的版本 id）；`repair` 为已有实例补齐缺失的客户端 jar、库与资源文件。两者都是异步操作，复用 `launch` 的事件模型但没有 `started` 事件：进度通过 `log` 事件返回，成功以 `exit`(code 0) 结束，失败以 `error` 事件结束。安装串行执行（HMCL 仓库一次只允许一个独占 draft），重复请求返回 `ALREADY_RUNNING`；安装进行中的 `launch` 会直接失败。`stop` 现在同时作用于启动槽与安装/修复槽；安装取消是尽力而为（中断 + `TaskExecutor.cancel()`），关闭 helper 始终可靠终止。

模组在其他请求前自动发送 `hello`。它返回协议版本、helper 版本、后端名称、是否具备真实启动/安装能力以及构建所对应的 HMCL 提交，例如：

```json
{"type":"response","id":"h1","ok":true,"protocolVersion":1,"helperVersion":"0.1.0","backend":"hmcl-core","launchAvailable":true,"installAvailable":true,"hmclProfile":true,"hmclCommit":"df52bc6e81e2e1116c131483dfb9996fdb7b2b10"}
```

协议版本不兼容时，模组会关闭该 helper 并显示明确错误；默认无 HMCL profile 的
JAR 会报告 `backend:"unavailable"`、`launchAvailable:false` 和 `installAvailable:false`。

### remoteVersions 响应

```json
{"type":"response","id":"rv1","ok":true,"versions":[{"id":"26.2","type":"release","releaseTime":"2026-06-09T12:05:32+00:00"}]}
```

`versions` 按发布时间从新到旧排序，覆盖全部类型（release/snapshot/old 等），由调用方自行过滤。未接入 HMCL Core 时返回 `HMCL_CORE_UNAVAILABLE`。

### 响应

所有响应都具有以下公共形状：

```json
{"type":"response","id":"l1","ok":true}
{"type":"response","id":"bad","ok":false,"code":"INVALID_REQUEST","message":"..."}
```

`list` 成功时增加 `instances` 数组。当前骨架的实例描述为：

```json
{
  "instanceId":"1.26.2",
  "name":"1.26.2",
  "root":"C:\\path\\to\\.minecraft\\versions\\1.26.2",
  "manifest":"C:\\path\\to\\.minecraft\\versions\\1.26.2\\1.26.2.json"
}
```

`launch` 是异步操作：`ok:true` 只表示请求已接受，并且保证先于该请求产生的异步事件写出；启动失败或在进程创建前取消通过 `error` 事件报告。未知实例、HMCL Core 未接入或重复启动会直接返回 `ok:false`。可观察的错误代码包括 `INVALID_REQUEST`、`UNKNOWN_COMMAND`、`HMCL_CORE_UNAVAILABLE`、`ALREADY_RUNNING`、`NOT_RUNNING`、`CANCELLED` 和 `INTERNAL_ERROR`。

### 异步事件

```json
{"type":"event","instanceId":"26.2","event":"started"}
{"type":"event","instanceId":"26.2","event":"log","line":"[main/INFO]: ..."}
{"type":"event","instanceId":"26.2","event":"exit","code":0}
{"type":"event","instanceId":"26.2","event":"error","code":"HMCL_CORE_ERROR","message":"..."}
```

`started`、`log`、`exit`、`error` 是唯一的事件名。helper 会用锁保护 stdout，因此 HMCL 线程产生的日志不会和响应交错。空白输入行会忽略；无法解析的行会返回 `id:null` 的 `INVALID_REQUEST` 响应并继续服务。

## HMCL Core 接入评估

本次调研针对 `HMCL-dev/HMCL` 的 `main` 分支（调研时看到的提交为 `df52bc6e81e2e1116c131483dfb9996fdb7b2b10`），结论是“可以源码接入，但应把 HMCL Core 限制在独立 helper 进程内，不应让 NeoForge 模组直接依赖它”。

已确认的结构：

1. 仓库根 `settings.gradle.kts` 包含 `HMCL`、`HMCLCore`、`HMCLBoot` 等多个 Gradle 子项目；`HMCLCore/build.gradle.kts` 将 Java 编译目标设为 17，并通过 version catalog 引入 Gson、JavaFX 相关库、压缩库、JNA、HTTP/HTML 等依赖。
2. `HMCLCore` 提供 `GameRepository`、`GameInstanceManifest`、`LaunchOptions`、`AuthInfo`、`DefaultLauncher` 和 `ProcessListener` 等核心类型，但 `DefaultGameRepository` 是抽象类。
3. 完整 HMCL 的 `HMCLGameRepository` 在 `HMCL` 主模块而不是 `HMCLCore`；它依赖 `GameDirectory`、设置管理和 JavaFX 属性。只拿 Core jar 不能直接得到完整 HMCL 的仓库实现。
4. `DefaultLauncher` 的调用链仍需要一个已解析的 `GameInstance`/manifest、`AuthInfo`、`LaunchOptions` 和 `ProcessListener`。它不是接收一个实例目录就能启动的静态函数。
5. 当前仓库没有可直接依赖的稳定公开 `HMCLCore` Maven 坐标。HMCL 根构建配置了 `mavenLocal()` 发布，版本默认来自根项目（当前源码为 `3.0`），但这不是稳定的远程发布契约。HMCL 代码为 GPL-3.0；最终分发还需要和当前模组的许可证策略一起审查。

### 可执行的源码/构建方案

方案 A：固定 HMCL 源码 checkout，使用 Gradle composite build。helper 的 `settings.gradle` 已预留 `hmclCheckout`：

```powershell
git clone https://github.com/HMCL-dev/HMCL.git C:\src\HMCL
git -C C:\src\HMCL checkout <经过审查的固定提交>
.\gradlew -p helper "-PhmclCheckout=C:\src\HMCL" classes
.\gradlew -p helper "-PhmclCheckout=C:\src\HMCL" installDist
```

该参数会把 `org.jackhuang:HMCLCore` 替换为 checkout 中的 `:HMCLCore` 项目，并编译 `src/hmcl/java`。需要注意：这是完整 HMCL Gradle 构建，不是下载一个轻量 jar；首次构建会解析 HMCL 的构建逻辑和 Core 依赖。profile 的 `jar` 已包含 HMCL Core、传递依赖和当前平台 JavaFX；`installDist` 仍可用于检查/分发展开后的依赖目录。正式发布前应把 checkout 固定到已审查的提交，而不是跟随 `main`。

方案 B：先在固定 checkout 中发布到本机 Maven 仓库：

```powershell
cd C:\src\HMCL
.\gradlew :HMCLCore:publishToMavenLocal
cd F:\Porj\Minecraft\mcmcl-0.1.0+mc26.2
.\gradlew.bat -p helper "-PhmclGroup=HMCL3" "-PhmclVersion=unspecified" classes
.\gradlew.bat -p helper "-PhmclGroup=HMCL3" "-PhmclVersion=unspecified" installDist
```

helper 的 `build.gradle` 只在传入 `hmclVersion` 或 `hmclCheckout` 时启用该依赖；不传参数时协议骨架完全无外部运行时依赖。方案 B 的 group/version 必须与固定 checkout 实际发布的 POM 一致；当前 HMCL checkout 的本机发布坐标是 `HMCL3:HMCLCore:unspecified`。方案 B 适合本地验证，不适合作为可复现发布机制，除非同时保存源码提交、生成的 POM/依赖锁定和许可证材料。

当前工作区的验证结果是：默认 `test`/`build` 成功；使用上述 HMCL checkout 的真实 profile `compileJava`、`test`、自包含 `jar`、`installDist` 和 `list`/`shutdown` 协议冒烟均成功。profile 测试还用动态 fixture 验证了 `DefaultLauncher` 的进程启动、stdout/stderr 转发和退出事件。没有 checkout 时，`hmclVersion` 方案仍只适合本地验证：需要先在同一 checkout 中执行 `:HMCLCore:publishToMavenLocal`，并同时锁定生成物与许可证材料。

### 真实适配器的当前实现

`RealHmclCoreAdapter` 已实现下面这条 profile-only 路径；如果 HMCL API 在未来 main 分支发生变化，编译失败会集中在 `src/hmcl/java`，默认协议 profile 不受影响：

1. 创建或复用 headless 的 HMCL repository，调用 refresh 并解析 `GameInstanceID`；
2. 用 `HmclLaunchRequest` 构造 HMCL `AuthInfo`；当前 Core 的 `AuthInfo` API 不接收 `xuid`/`clientId`，这两个字段仅保留在协议中供未来适配；
3. 组装 `LaunchOptions`，选择实例运行目录、Java runtime（`javaPath` 存在时探测其版本，否则用 helper 自身 JVM）和 `maxMemory`；
4. 创建 `DefaultLauncher`，把 `ProcessListener.onLog`、`onExit` 映射为 `HmclLaunchEventSink`；
5. 保存 HMCL 的 `ManagedProcess`/任务句柄，在 `stop` 和 `shutdown` 中终止游戏进程；
6. `install`/`repair` 通过 `DefaultDependencyManager` 的 `newGameBuilder`/`checkGameCompletionAsync` 执行，任务用 `TaskExecutor` 启动并等待（`whenComplete` 组合子依赖 executor 维护的状态，不能用裸 `Task.run()`）；取消通过中断 + `TaskExecutor.cancel()`；
7. 给 helper 分发 HMCL Core 所需的 JavaFX runtime modules 和其传递依赖，并在发布前完成 GPL-3.0 合规审查。

当前已验证真实 profile 能启动 headless helper、完成 `hello`/`list`/`shutdown`，
并通过动态 JVM fixture 验证 `DefaultLauncher` 的实际进程启动、stdout/stderr
转发和退出事件；也已用完整 Minecraft 26.2 文件集创建目标 Minecraft 进程、
转发真实日志并通过 `stop` 结束。安装路径由本地 BMCLAPI 兼容 fixture 服务器
验收：`remoteVersions` 列表、全新 `install`（版本 JSON、客户端 jar、资源索引
均带 SHA-1 校验地下载落盘）、删除客户端 jar 后的 `repair` 补齐，以及"安装后
启动该实例"的全链路。对真实 Mojang/BMCLAPI 服务器的下载验收需要联网环境，
模组加载器实例（Forge/Fabric/NeoForge）仍需单独验收。

## 目录

```text
helper/
├─ README.md
├─ settings.gradle
├─ build.gradle
└─ src/
   ├─ main/java/top/fish1000/mcmcl/helper/
   │  ├─ Main.java
   │  ├─ HelperServer.java
   │  ├─ HmclCoreAdapterFactory.java
   │  ├─ hmcl/       # HMCL Core 唯一适配边界
   │  ├─ protocol/   # 无第三方依赖的 JSON Lines 实现
   │  └─ repository/ # 标准 versions/ 目录的只读发现
   ├─ hmcl/java/top/fish1000/mcmcl/helper/hmcl/
   │  ├─ RealHmclCoreAdapterProvider.java
   │  └─ RealHmclCoreAdapter.java
   └─ test/java/.../ProtocolTest.java
```
