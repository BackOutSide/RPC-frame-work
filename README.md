# RivetRPC

<!-- markdownlint-disable MD013 -->

RivetRPC 是面向 Java 服务的轻量级 RPC 框架，围绕可解释的调用失败、可观测的服务链路和可复现的性能验证持续迭代。当前实现覆盖服务注册与发现、客户端代理、连接复用、自定义协议、序列化与压缩、服务端调用，以及请求与响应关联。

项目从 [JavaGuide 的 guide-rpc-framework](https://github.com/Snailclimb/guide-rpc-framework) 学习实现发展而来，保留原有代码中的作者信息，详见[来源说明](NOTICE.md)。RivetRPC 的后续设计聚焦故障治理、trace、审计与基准测试；这些能力以实际实现和验证结果为准。当前版本尚未完成生产级治理。

## 点评业务一期

点评应用走通店铺详情、优惠券浏览、抢券受理和订单查询。Spring Boot Web 服务通过本仓库的 Netty RPC 调用业务 Provider，使用 ZooKeeper 发现实例。详见[业务边界和本地运行步骤](examples/dianping/README.md)。

## 历史资料

[早期实现讲解](docs/legacy/RPC_INTERVIEW_GUIDE.md) 固定于旧提交 `727416b`，用于核对项目演进，不代表当前源码与功能状态。

[RivetRPC 命名迁移说明](docs/MIGRATION_RIVETRPC.md)列出 Maven、包名和示例目录的变更，以及新旧节点混部的边界。

## 核心能力

- 自定义 16 字节协议头，使用 `LengthFieldBasedFrameDecoder` 处理 TCP 粘包和半包。
- Netty NIO 通信，客户端缓存并复用 Channel，服务端将业务处理从 IO EventLoop 中隔离。
- 使用 `CompletableFuture` 和请求体 UUID 关联并发请求与响应。
- 基于 ZooKeeper/Curator 完成服务注册、发现、本地地址缓存、节点监听和会话恢复后的重新注册。
- 自定义 SPI 扩展序列化、压缩、注册发现、传输和负载均衡实现。
- 支持 Hessian、Kryo、Protostuff 序列化实现和 Gzip 压缩；当前示例链路默认使用 Hessian + Gzip。
- 提供随机、轮询、最少活跃和一致性哈希实现。默认使用随机策略，可通过 `rpc.client.load-balance` 选择。
- 通过 `@RpcScan`、`@RpcService`、`@RpcReference` 完成服务发布和客户端代理注入。

## 模块结构

```text
modules/                   框架模块
  rivet-rpc-common/
  rivet-rpc-core/
examples/                  独立调用和业务示例
  hello/
    api/  client/  server/
  dianping/
    api/  provider/  web/
    README.md  compose.yaml  smoke.ps1
docs/                      架构图、迁移说明与历史资料
```

| 模块 | 职责 |
| --- | --- |
| `modules/rivet-rpc-common` | 枚举、异常、SPI 加载器、单例工厂、线程池与通用工具 |
| `modules/rivet-rpc-core` | 协议、编解码、Netty/Socket 传输、注册发现、负载均衡、序列化压缩和 Spring 接入 |
| `examples/hello/api` | Hello 示例两端共享的接口和 DTO |
| `examples/hello/server` | Hello Provider 示例，发布 `HelloService` 并启动 Netty 服务端 |
| `examples/hello/client` | Hello Consumer 示例，通过 `@RpcReference` 发起远程调用 |
| `examples/dianping/api` | 点评业务的 RPC 接口和 DTO |
| `examples/dianping/provider` | 店铺查询、优惠券秒杀、异步建单 |
| `examples/dianping/web` | 点评 HTTP 入口、会话和 RPC 消费端 |

## 两台主机之间的一次 RPC 调用

下图将控制面与数据面放在一起：ZooKeeper 负责注册与发现，主机 A 和主机 B 之间通过 Netty/TCP 传输 RPC 请求、响应和心跳。

[查看双主机 RPC 调用链（Mermaid）](docs/architecture/two-host-rpc-flow.md)，或查看[源文件](docs/architecture/two-host-rpc-flow.mmd)。

完整链路可以压缩成以下十步：

1. 服务端 Spring 容器扫描 `@RpcService`，将实现对象写入本地服务表。Netty 监听就绪后，向 ZooKeeper 注册临时节点。
2. 客户端 Spring 容器为 `@RpcReference` 字段注入 JDK 动态代理。
3. 业务方法调用进入 `RpcClientProxy`，代理构造包含 UUID、接口、方法、参数、group 和 version 的 `RpcRequest`。
4. `ZkServiceDiscoveryImpl` 从本地缓存或 ZooKeeper 读取地址列表，再根据负载均衡策略选择节点。
5. `NettyRpcClient` 从 `ChannelProvider` 复用活动连接；缓存未命中时才建立 TCP Channel。
6. 客户端先保存 `UUID -> CompletableFuture`，再将请求封装为 `RpcMessage`。
7. `RpcMessageCodec` 对请求进行 Hessian 序列化和 Gzip 压缩，写入协议头后由 Netty 发送。
8. 服务端拆帧、校验、解压和反序列化，在独立业务线程组中查找本地服务并反射调用目标方法。
9. 服务端把原请求 UUID 写入 `RpcResponse`，经过相同协议链路返回客户端。
10. 客户端根据响应 UUID 完成对应 Future，唤醒等待线程，代理校验响应后把业务结果返回给调用方。

## 自定义协议

协议头固定为 16 字节，消息体长度为 `fullLength - 16`。

| 字节偏移 | 长度 | 字段 | 作用 |
| --- | ---: | --- | --- |
| `0-3` | 4 B | `magic` | 当前协议版本固定为 `grpc` 字节，仅用于拒绝非法帧；不表示兼容 gRPC |
| `4` | 1 B | `version` | 当前协议版本为 `1` |
| `5-8` | 4 B | `fullLength` | 整个帧的长度，包含协议头和消息体 |
| `9` | 1 B | `messageType` | 请求、响应、PING 或 PONG |
| `10` | 1 B | `codec` | 序列化算法编号 |
| `11` | 1 B | `compress` | 压缩算法编号 |
| `12-15` | 4 B | `frameRequestId` | 当前实现中的帧序号 |
| `16...` | 可变 | `body` | 压缩后的 `RpcRequest` 或 `RpcResponse` |

帧解码参数为：

```text
maxFrameLength    = 8 MiB
lengthFieldOffset = 5
lengthFieldLength = 4
lengthAdjustment  = -9
initialBytesStrip = 0
```

`fullLength` 从整帧起点计算，而 Netty 在读取完长度字段时已经越过前 9 个字节，因此 `lengthAdjustment = -9`。

> 当前真正用于 `CompletableFuture` 关联的是请求体中的字符串 UUID。协议头的整型 `frameRequestId` 尚未回显参与关联，后续可将两套 ID 统一。

## Netty 线程与连接模型

- 客户端使用 `NioEventLoopGroup` 处理连接、编解码和响应事件。
- 服务端使用 1 个 boss 线程接受连接，worker EventLoop 处理网络读写。
- `NettyRpcServerHandler` 被放入 `DefaultEventExecutorGroup(cpus * 2)`，反射调用和业务代码不会阻塞 worker EventLoop。
- `ChannelProvider` 按服务地址缓存活动 Channel；失效 Channel 会在下一次获取时剔除并重新连接。
- 客户端连续 5 秒写空闲时发送 PING，服务端回复 PONG；服务端连续 30 秒读空闲时关闭连接。

## 服务注册与发现

服务键由 `interfaceName + group + version` 组成，ZooKeeper 节点结构如下：

```text
/my-rpc
└── io.github.backoutside.rivet.HelloServicetest1version1
    └── 192.168.x.x:9998          # EPHEMERAL
```

- Provider 使用临时节点注册实例，并在 JVM ShutdownHook 中主动清理节点和线程池。
- Consumer 首次发现服务时读取子节点并缓存地址列表，同时注册 `PathChildrenCache`。
- 节点发生变化时，当前实现重新拉取全部子节点并替换本地缓存。
- ZooKeeper session 丢失后记录重注册状态，重新连接成功时恢复临时节点。

## 运行入口

环境要求：JDK 17、Maven 3.6+、ZooKeeper（RPC 示例默认 `127.0.0.1:2181`）。

启动顺序：

1. 启动 ZooKeeper。
2. 运行 `examples/hello/server/src/main/java/NettyServerMain.java`，监听 `9998`。
3. 运行 `examples/hello/client/src/main/java/io/github/backoutside/rivet/NettyClientMain.java`。
4. 客户端第一次调用后会等待 12 秒，以便观察写空闲触发的心跳，随后再调用 10 次。

## 核心源码导航

| 主题 | 入口 |
| --- | --- |
| Spring 注解扫描与代理注入 | [`CustomScannerRegistrar`](modules/rivet-rpc-core/src/main/java/io/github/backoutside/rivet/spring/CustomScannerRegistrar.java)、[`SpringBeanPostProcessor`](modules/rivet-rpc-core/src/main/java/io/github/backoutside/rivet/spring/SpringBeanPostProcessor.java) |
| 客户端动态代理 | [`RpcClientProxy`](modules/rivet-rpc-core/src/main/java/io/github/backoutside/rivet/proxy/RpcClientProxy.java) |
| 服务发现与负载均衡 | [`ZkServiceDiscoveryImpl`](modules/rivet-rpc-core/src/main/java/io/github/backoutside/rivet/registry/zk/ZkServiceDiscoveryImpl.java) |
| Netty 客户端与连接复用 | [`NettyRpcClient`](modules/rivet-rpc-core/src/main/java/io/github/backoutside/rivet/remoting/transport/netty/client/NettyRpcClient.java)、[`ChannelProvider`](modules/rivet-rpc-core/src/main/java/io/github/backoutside/rivet/remoting/transport/netty/client/ChannelProvider.java) |
| 请求响应关联 | [`UnprocessedRequests`](modules/rivet-rpc-core/src/main/java/io/github/backoutside/rivet/remoting/transport/netty/client/UnprocessedRequests.java) |
| 协议编解码 | [`RpcMessageFrameDecoder`](modules/rivet-rpc-core/src/main/java/io/github/backoutside/rivet/remoting/transport/netty/codec/RpcMessageFrameDecoder.java)、[`RpcMessageCodec`](modules/rivet-rpc-core/src/main/java/io/github/backoutside/rivet/remoting/transport/netty/codec/RpcMessageCodec.java) |
| Netty 服务端 | [`NettyRpcServer`](modules/rivet-rpc-core/src/main/java/io/github/backoutside/rivet/remoting/transport/netty/server/NettyRpcServer.java)、[`NettyRpcServerHandler`](modules/rivet-rpc-core/src/main/java/io/github/backoutside/rivet/remoting/transport/netty/server/NettyRpcServerHandler.java) |
| 服务注册与本地服务表 | [`ZkServiceProviderImpl`](modules/rivet-rpc-core/src/main/java/io/github/backoutside/rivet/provider/impl/ZkServiceProviderImpl.java)、[`CuratorUtils`](modules/rivet-rpc-core/src/main/java/io/github/backoutside/rivet/registry/zk/util/CuratorUtils.java) |
| SPI 扩展加载 | [`ExtensionLoader`](modules/rivet-rpc-common/src/main/java/io/github/backoutside/rivet/extension/ExtensionLoader.java) |

## 当前实现边界

- 网络收发与响应回填由 Netty 异步处理。业务调用同步等待结果，并受 RPC 超时控制。
- RPC 层覆盖连接失败和请求超时。点评秒杀的幂等由业务键与数据库唯一约束实现。
- 自动重试、限流、熔断、传输鉴权和链路追踪属于后续框架迭代。
- 注册中心监听是“事件触发后全量刷新”，不是逐条增量更新。
- 当前版本已具备完整的基础调用链；容错、治理、安全和可观测性仍需实现和验证。
