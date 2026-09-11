# MCMCL

MCMCL（Minecraft Minecraft Launcher）是一个运行在 Minecraft 内的 NeoForge 模组。它在游戏内扫描实例目录，并用独立 JVM 子进程启动另一个 Minecraft 实例，因此可以做到“用 Minecraft 启动 Minecraft”。

当前版本是一个可工作的 MVP：它负责实例发现、游戏内界面、命令构造、子进程输出和停止操作；下载器、版本继承解析和账号登录暂时通过实例文件或外部启动器准备。

## 使用

构建并运行开发客户端：

```powershell
./gradlew.bat build
./gradlew.bat runClient
```

进入标题界面或暂停菜单，点击“打开 MCMCL”，也可以在没有打开界面时按 `M`。默认实例目录是：

```text
<Minecraft 游戏目录>/mcmcl/instances/
```

在这个目录中为每个实例创建一个子目录，并放入 `launch.json`。例如：

```text
mcmcl/instances/vanilla-26-2/launch.json
```

最小配置示例：

```json
{
  "name": "Vanilla 26.2",
  "version": "26.2",
  "java": "${java}",
  "mainClass": "net.minecraft.client.main.Main",
  "classpath": [
    "${minecraftGameDir}/versions/26.2/26.2.jar",
    "${minecraftGameDir}/libraries/example-library.jar"
  ],
  "jvmArgs": ["-Xmx4G"]
}
```

`classpath` 可以写绝对路径，也可以写相对于实例目录或游戏目录的路径；`libraries/`、`versions/`、`assets/` 开头的相对路径会优先按游戏目录解析。省略 `gameArgs` 时，MCMCL 会自动生成标准的版本、游戏目录、资源目录和当前用户参数。

可用变量包括：

| 变量 | 含义 |
| --- | --- |
| `${java}` | 当前运行 MCMCL 的 Java 可执行文件 |
| `${minecraftGameDir}` | 当前 Minecraft 的游戏目录 |
| `${gameDir}` / `${instanceDir}` | 目标实例目录 |
| `${assetsDir}` / `${assetIndex}` | 目标资源目录和资源索引 |
| `${version}` / `${mainClass}` | 实例版本和主类 |
| `${username}` / `${uuid}` | 当前 Minecraft 用户 |
| `${accessToken}` / `${sessionId}` | 当前会话凭据 |
| `${userType}` / `${versionType}` | 启动参数中的用户类型和版本类型 |

如果需要完全控制参数，可以在 `launch.json` 中提供 `gameArgs` 数组。参数是数组元素，不会经过 shell 拼接；这能避免路径中有空格时的常见问题。`jar` 和 `classpath` 二选一：`jar` 使用 `java -jar`，`classpath` 使用 `java -cp <...> <mainClass>`。

## HMCL Core 是否有帮助？

有帮助，但更适合作为“启动后端”，而不是直接塞进 GUI。真正完整的 Minecraft 启动器还需要处理：

- `version.json` 的继承和依赖库规则；
- 客户端、资源、原生库的下载和校验；
- Forge/NeoForge/Fabric 等加载器的安装与启动参数；
- Microsoft 账号登录、刷新令牌和离线模式；
- Java 版本选择、内存参数和崩溃日志。

这些正是 HMCL Core 或 HMCL 中相关模块能节省大量工作的地方。建议后续把现在的 `InstanceLauncher` 抽象成 `LaunchBackend`/`LaunchPlan`，先保留当前 `launch.json` 后端，再增加 HMCL Core 后端：

```text
游戏内 GUI → LaunchBackend → LaunchPlan → ProcessBuilder
                         ├─ ManifestBackend（当前 MVP）
                         └─ HmclBackend（下载、解析、认证）
```

不过不能假设 HMCL Core 已经直接兼容 NeoForge 26.2：需要按具体仓库提交和 artifact 的 API、Java 版本、许可证逐项确认，尤其是 Minecraft 26.x 的 Java 25 和非重映射环境。HMCL 相关项目通常带有 GPL 系列许可证，正式发布前也要核对你分发模组时的许可证义务。当前代码没有强行加入 HMCL 依赖，避免先被不稳定的 API 或许可证边界锁死。

## 设计边界

- 启动只发生在物理客户端；服务器端不会加载客户端 UI 和进程代码。
- 子进程通过参数列表启动，不调用命令行 shell。
- 实例启动在独立进程中，游戏主线程不会等待它退出。
- 当前 Minecraft 的用户名、UUID 和 access token 会按变量传给子实例；这不等同于实现了账号登录服务。
- 配置文件由用户准备，MCMCL 当前不自动下载版本、库或资源。

## 配置

可在 NeoForge 的模组配置界面中调整：

- `instancesDirectory`：实例根目录，默认 `mcmcl/instances`；
- `allowCustomJava`：是否允许实例选择不同的 Java 可执行文件；
- `maxInstances`：扫描的实例目录数量上限。
