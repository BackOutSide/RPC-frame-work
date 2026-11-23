# Netty RPC tracing 快速验证

本示例只观察 RivetRPC 的 Netty 方法调用。Java agent 提供 OpenTelemetry SDK 和控制台导出；自定义 RPC 协议中的 span 与上下文传播由框架埋点负责。脚本关闭 agent 的其他自动埋点及 metrics/logs 导出，便于直接对照客户端和服务端的 RPC span。`console` 是 OpenTelemetry 官方支持的 trace exporter；手工 API 埋点可在关闭默认自动埋点后继续工作。[Java 配置](https://opentelemetry.io/docs/languages/java/configuration/)；[agent 埋点开关](https://opentelemetry.io/docs/zero-code/java/agent/disable/)

## 运行

需要 JDK 17、Maven、PowerShell，以及运行在 `127.0.0.1:2181` 的 ZooKeeper。下载 `opentelemetry-javaagent.jar` 到本机任意目录，并把下面的 `-AgentJar` 改为实际路径。以下命令均从仓库根目录执行。[OpenTelemetry Java agent 官方下载](https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/latest/download/opentelemetry-javaagent.jar)

第一个 PowerShell 窗口启动 Provider；`-Build` 会安装 hello 示例及其 Reactor 依赖：

```powershell
.\examples\hello\observe-netty.ps1 -Role server -AgentJar "$env:USERPROFILE\Downloads\opentelemetry-javaagent.jar" -Build
```

待 Provider 注册到 ZooKeeper 后，在第二个窗口启动 Consumer：

```powershell
.\examples\hello\observe-netty.ps1 -Role client -AgentJar "$env:USERPROFILE\Downloads\opentelemetry-javaagent.jar"
```

若 Maven 使用的镜像暂时不可用，可通过脚本的 `-MavenSettings <settings.xml>` 传入可用仓库配置，两端使用同一配置。

Consumer 先调用一次 `HelloService.hello`，等待 12 秒，再连续调用 10 次。等待控制台输出 span 后，按 `Ctrl+C` 停止两个进程。若在 IDE 中运行相同的两个 `main` 类，把脚本中的 `-javaagent` 和 `-Dotel.*` 参数放到两端的 VM options 中即可。

## 核对输出

- 每次成功的 Netty RPC 应有一个 `CLIENT` span 和一个 `SERVER` span，二者的 `traceId` 相同、`spanId` 不同；`SERVER` 的父 span 是该次调用的 `CLIENT` span。链路层对外使用的全局 RequestID 是这个 `traceId`，RPC 日志 MDC 的键也叫 `traceId`。已有协议字段 `RpcRequest.requestId` 是每次物理请求的 UUID，用于匹配响应；它和全局链路 ID 用途不同。两端均须加载 agent 或另行配置 OpenTelemetry SDK；只有 API 依赖时默认不会导出 span。[OpenTelemetry 库规范](https://opentelemetry.io/docs/specs/otel/library-guidelines/)
- 该 hello 客户端没有共享的上游父 span，因此 11 次顶层调用通常是 11 条独立 trace。若多个方法调用位于同一父上下文，顺序调用或并发扇出应共享 `traceId`、形成不同的兄弟 `CLIENT` span；并发执行时须传递上下文。
- 一个逻辑客户端调用只生成一个 `CLIENT` span，覆盖发现、连接、发送、等待及可能的重试；每次真正到达 Provider 的尝试产生各自的 `SERVER` span。当前单 Provider hello 示例默认最多一次尝试，不能演示多次重试；失败于发现或连接的尝试没有 `SERVER` span。[RPC span 约定](https://opentelemetry.io/docs/specs/semconv/rpc/rpc-spans/)
- 12 秒等待期间会发生 PING/PONG 心跳，但它们不是方法调用，不应出现对应的 RPC span。无法解码成 `RpcRequest` 的帧也没有可提取的 RPC 上下文，不能以此生成方法级 span；可在协议错误日志中观察这类故障。

控制台 exporter 用于本地验证，不需要 Collector。要在 Jaeger 等后端检索链路，可在运行应用中将 `otel.traces.exporter` 改为 `otlp` 并配置 OTLP endpoint；框架库不负责选择导出后端。[OpenTelemetry Java SDK 与库的职责](https://opentelemetry.io/docs/specs/otel/library-guidelines/)

如需直接在 RPC 日志里搜索全局 RequestID，可给日志格式加入 `traceId=%X{traceId}`。默认日志格式不一定显示 MDC，因此控制台 span 输出仍是本示例的主要核对依据。
