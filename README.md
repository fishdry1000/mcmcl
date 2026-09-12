# MCMCL

MCMCL（Minecraft Minecraft Launcher）是一个运行在 Minecraft 内的 NeoForge 模组。它通过独立的 HMCL helper JVM 管理并启动其他 Minecraft 实例。

当前启动链路为：

```text
Minecraft 内 GUI → JSON Lines → mcmcl-hmcl-helper.jar → HMCL Core → 目标 Minecraft
```

模组本身不再解析 `launch.json`，也不再直接拼接 Minecraft 启动命令。对于已经准备好的 HMCL 仓库，实例 manifest 的读取/继承、启动参数生成、原生库处理和目标进程管理均由 helper/HMCL Core 负责；缺失文件的下载、校验和 Java 安装仍需由 HMCL 或其他安装流程准备。

## 构建模组

```powershell
./gradlew.bat build
./gradlew.bat runClient
```

项目目标为 NeoForge 26.2、Java 25。进入标题界面或暂停菜单点击“打开 MCMCL”，也可以按 `M`。

## 构建 HMCL helper

helper 的协议代码和 HMCL Core ABI 以 Java 17 为基线；默认协议构建可用 JDK 17。当前 HMCL checkout 的 JavaFX 25 profile 则要求使用 JDK 25 构建、测试和运行，这也与 Minecraft 26.2 一致：

```powershell
./gradlew.bat -p helper test
./gradlew.bat -p helper jar
```

HMCL Core 当前没有稳定的公开 Maven 坐标。真实 HMCL Core adapter 需要使用经过审查的 HMCL 源码 checkout，或先将同一 checkout 的 `HMCLCore` 发布到本机 Maven 仓库。具体流程见 [helper/README.md](helper/README.md)。

开发环境可用一个命令构建真实 profile 并安装到默认运行目录：

```powershell
./gradlew.bat -p helper `
  "-PhmclCheckout=C:\src\HMCL" `
  clean test installHelper
```

`installHelper` 默认写入 `run/mcmcl/hmcl-helper.jar`；可用
`-PhelperInstallDirectory=<目录>` 覆盖。正式安装时将同一个 profile JAR 放到
当前 Minecraft 游戏目录的 `mcmcl/hmcl-helper.jar`，或者在 NeoForge 配置中修改
`hmclHelperJar`。HMCL 游戏仓库默认目录为：

```text
<Minecraft 游戏目录>/mcmcl/hmcl/
```

这是包含 `versions/`、`libraries/` 和 `assets/` 的 HMCL/官方布局根目录。例如实例 manifest 位于：

```text
mcmcl/hmcl/versions/1.26.2/1.26.2.json
```

helper 启动参数为：

```text
java -jar mcmcl-hmcl-helper.jar --repository <HMCL 游戏仓库根目录>
```

## helper 协议

模组与 helper 通过 stdin/stdout 的 UTF-8 JSON Lines 通信。stdout 只包含协议消息，stderr 用于 helper 自身诊断。

请求示例：

```json
{"id":"0","command":"hello"}
{"id":"1","command":"list"}
{"id":"2","command":"launch","instanceId":"26.2","username":"Player","uuid":"00000000-0000-0000-0000-000000000000","accessToken":"...","userType":"msa"}
{"id":"3","command":"stop","instanceId":"26.2"}
```

模组启动 helper 后会先用 `hello` 校验协议版本，并读取 helper 版本、HMCL 固定
提交和启动能力。启动请求的响应只表示请求已接受；`started`、`log`、`exit` 和
`error` 通过异步事件返回。协议的完整定义见 [helper/README.md](helper/README.md)。

## 设计边界

- 只有物理客户端启动 helper；专用服务器不会加载客户端启动代码。
- 目标游戏始终运行在独立 JVM 中，不阻塞 Minecraft 主线程。
- 当前 Minecraft 的用户名、UUID、access token、XUID 和 client ID 会传给 helper；MCMCL 不实现账号登录或刷新令牌。
- `launch.json` 已废弃，不再作为启动后端或兼容回退路径。
- helper 运行时必须遵循 HMCL Core 的 GPL-3.0 条款；发布模组和 helper 前需要一并提供相应许可证与源码/对应源码材料。

默认 helper JAR 只包含协议和仓库发现代码，不能启动游戏；发布或实际使用时必须使用固定 HMCL checkout 构建的 profile JAR。profile JAR 构建会把 checkout 中的 `LICENSE` 放入 `META-INF/licenses/HMCL-LICENSE.txt`，但这不替代对应的 HMCL 源码/对应源码材料。

helper profile 包含当前操作系统/架构的 JavaFX 原生库，因此正式分发应按平台
分别构建，并把 helper、锁定的 HMCL 提交及对应源码材料作为一组发布。

## 配置

NeoForge 客户端配置项：

- `instancesDirectory`：HMCL 仓库根目录，默认 `mcmcl/hmcl`；
- `hmclHelperJar`：独立 helper JAR 路径，默认 `mcmcl/hmcl-helper.jar`；
- `maxInstances`：界面最多显示的实例数量。
