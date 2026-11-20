# Java RPC 项目简历复习与面试讲解

> **历史快照**：本文描述旧仓库 `RPC-frame-work` 在提交 `727416b` 时的实现。文中的项目名、教学表述、模块路径和固定提交链接保留原貌，供追溯项目演进；当前 RivetRPC 的结构与运行方式请以 [根目录 README](../../README.md) 为准。

<!-- markdownlint-configure-file { "MD013": false, "MD024": { "siblings_only": true } } -->

本文围绕简历中的六条项目亮点，整理项目原理、实际源码链路、启动流程、设计取舍和面试追问。目标是在一天左右把项目讲清楚，而不是背出一套与代码无关的标准答案。

依据为本轮复习对话及仓库源码。核对日期为 2026 年 10 月 5 日，源码基线为 `727416b59f8508bbeb4dbce30837d919be0b1ee3`。源码链接固定到该提交，方便核对；后续实现变化时应同步更新本文。本次只做静态核对和文档整理，没有执行完整运行测试、压测或故障注入。下文的风险是源码分析结果，不代表已通过实验复现。

阅读时始终区分三件事：算法或框架的一般原理、当前项目已经实现的逻辑，以及尚待完善的生产化能力。特别不要把“采用了性能优化机制”说成“已经取得某个吞吐或延迟提升”，也不要把“尝试重注册”说成“保证自动恢复”。

## 阅读导航

- [整体项目和一次调用](#整体项目和一次调用)
- [第一条 自定义协议与编解码](#第一条-自定义协议与编解码)
- [第二条 SPI 序列化与压缩](#第二条-spi-序列化与压缩)
- [第三条 Netty 通信与连接管理](#第三条-netty-通信与连接管理)
- [第四条 ZooKeeper 注册与发现](#第四条-zookeeper-注册与发现)
- [第五条 负载均衡策略](#第五条-负载均衡策略)
- [第六条 Spring 注解接入](#第六条-spring-注解接入)
- [简历表述与代码边界](#简历表述与代码边界)
- [一天复习安排与自测](#一天复习安排与自测)
- [源码导航](#源码导航)

## 整体项目和一次调用

### 项目定位

这是一个基于 Java、Netty、ZooKeeper 和 Spring 的轻量级 RPC 学习框架。仓库 README 说明其参考了 JavaGuide RPC 框架设计。项目重点是走通注册发现、代理调用、连接复用、编解码、服务执行和响应回填，不应等同于已经完成生产级服务治理的框架。

| 模块 | 职责 |
| --- | --- |
| `hello-service-api` | 两端共享接口和参数 DTO |
| `rpc-framework-common` | SPI、枚举、异常、单例工厂及线程池工具 |
| `rpc-framework-simple` | 协议、传输、注册发现、负载均衡、序列化压缩和 Spring 接入 |
| `example-server` | 创建服务实现、发布地址、启动 Netty 监听 |
| `example-client` | 注入接口代理并发起远程调用 |

### 控制面和数据面

ZooKeeper 相当于服务地址目录，不是 RPC 请求中转站。

~~~text
RPC 服务端 ──注册实例地址──> ZooKeeper <──查询和监听── RPC 客户端

RPC 客户端 ─────自定义 RPC 协议 / TCP─────> RPC 服务端
RPC 客户端 <──────────响应────────────── RPC 服务端
~~~

业务链路不是 HTTP。Netty 是网络框架，项目在 TCP 上实现了自己的二进制协议。访问 ZooKeeper 则使用 Curator 和 ZooKeeper 协议，也是独立于业务 TCP 连接的通信链路。

### 三种缓存不要混淆

| 缓存 | 所在进程 | key | value | 回答的问题 |
| --- | --- | --- | --- | --- |
| 本地服务表 | RPC 服务端 | 接口名加 group 加 version | Java 实现对象 | 收到请求后执行哪个对象 |
| 服务地址缓存 | RPC 客户端的发现逻辑 | 服务标识 | 实例地址列表 | 这个服务有哪些候选实例 |
| Channel 缓存 | RPC 客户端 | 远端地址 | 已建立的 Channel | 去这个实例时复用哪条连接 |

后两者不是服务端帮客户端缓存的内容。客户端也需要地址缓存，否则每次 RPC 都访问注册中心，增加发现开销和中心负载。

### 一次 RPC 的完整过程

1. 服务端启动时创建实现 Bean，写入本地服务表，并向 ZooKeeper 发布实例地址。
2. 客户端启动时为 `@RpcReference` 字段创建并注入 JDK 动态代理。
3. 业务调用接口方法，进入 `RpcClientProxy.invoke()`。
4. 代理构造 `RpcRequest`，携带接口、方法、参数类型、参数、group、version 和 UUID。
5. 服务发现从本地地址缓存或 ZooKeeper 获得候选列表，负载均衡选择一个地址。
6. 客户端查 Channel 缓存；没有可用连接时才建立 TCP 连接。
7. 先登记 `UUID → CompletableFuture`，再编码并发送请求。
8. 服务端切出完整协议帧，解压、反序列化，并把业务执行移交业务线程组。
9. 服务端查找本地实现，按方法名和参数类型反射调用，将原请求 UUID 写入响应。
10. 客户端解码响应，按 UUID 移除并完成 Future；等待线程继续执行，代理校验响应并返回结果。

对现有架构图可从仓库首页查看。它用于理解预期链路，不意味着所有并发和异常分支都已验证。

### 两分钟项目介绍

> 这个项目主要让我理解一次远程方法调用背后的完整链路。业务侧通过 Spring 注解声明服务和引用，客户端生成 JDK 动态代理，把接口调用转换成请求。客户端从 ZooKeeper 发现实例，选择目标并复用 Netty Channel，通过自定义协议进行序列化、压缩和网络传输。服务端解码后查找本地实现，在独立业务执行组中反射调用，再返回带原请求 UUID 的响应。客户端通过 UUID 与 Future 映射完成结果回填。网络处理是异步事件驱动的，但当前业务接口仍通过 get 等待结果，是同步调用模型。

## 第一条 自定义协议与编解码

### 简历对应内容

> 定义 magic、version、fullLength、messageType 等协议头字段，基于长度字段帧解码处理 TCP 粘包和拆包，支持请求、响应和心跳消息。

### 为什么要定义协议

TCP 提供有序字节流，不提供应用消息边界。发送两次请求，不保证对方正好读取两次；一次读取可能含有两帧，也可能只收到一帧的一部分。“粘包”和“拆包”在这里不是 TCP 出错，而是应用需要自己定义消息边界。

~~~text
发送： [完整请求 A] [完整请求 B]
读取可能是：
第一次 [请求 A 前半段]
第二次 [请求 A 后半段 + 请求 B]
~~~

本项目选择固定头部加可变消息体，由 `fullLength` 告诉接收端整帧有多长。相比分隔符方案，不需要对二进制消息体中的分隔符做转义；相对固定长度消息，能容纳不同大小的参数。但要校验长度、限制内存占用，并处理恶意输入。

### 当前头部定义

源码：[RpcConstants][src-constants]、[RpcMessageCodec][src-codec]。

| 偏移 | 长度 | 字段 | 当前作用 |
| --- | --- | --- | --- |
| 0 至 3 | 4 字节 | magic | 固定字节 `grpc`，识别本项目协议 |
| 4 | 1 字节 | version | 当前版本 1，不匹配即拒绝 |
| 5 至 8 | 4 字节 | fullLength | 头部与压缩后消息体的总长度 |
| 9 | 1 字节 | messageType | 请求 1、响应 2、PING 3、PONG 4 |
| 10 | 1 字节 | codec | 序列化算法编号 |
| 11 | 1 字节 | compress | 压缩算法编号 |
| 12 至 15 | 4 字节 | 帧 requestId | 编码时生成的递增整型序号 |
| 16 起 | 可变 | body | 序列化并压缩后的业务对象 |

总头长为 16 字节。magic 恰好使用字符 `grpc`，不代表兼容开源 gRPC，也不是 HTTP/2。magic 是协议识别，不是鉴权或完整性校验；version 当前也只是匹配校验，没有版本协商。

心跳帧没有消息体，长度是 16 字节。编码时跳过序列化和压缩，解码时根据类型恢复 PING 或 PONG 标识。

### 拆帧和对象解码是两个步骤

当前 Pipeline 使用 `RpcMessageFrameDecoder` 和 `RpcMessageCodec`。仓库中还有旧的 Encoder/Decoder 类，复习实际运行链路时应优先看当前 Pipeline。

~~~text
字节流 → 按长度切出完整帧 → 校验并读取头部 → 解压消息体 → 反序列化对象
~~~

[RpcMessageFrameDecoder][src-frame] 继承 `LengthFieldBasedFrameDecoder`：

~~~java
super(RpcConstants.MAX_FRAME_LENGTH, 5, 4, -9, 0);
~~~

| 参数 | 值 | 原因 |
| --- | --- | --- |
| maxFrameLength | 8 MiB | 限制一帧在网络上的长度 |
| lengthFieldOffset | 5 | magic 和 version 共占 5 字节 |
| lengthFieldLength | 4 | fullLength 是 4 字节 |
| lengthAdjustment | -9 | 长度字段已经表示整帧长度，抵消读到字段末尾的 9 字节 |
| initialBytesToStrip | 0 | 保留头部，交给下一步解析 |

计算关系是 `帧长 = 长度字段值 + lengthAdjustment + 长度字段末尾偏移`。因此这里是 `fullLength - 9 + 9`，结果仍为 fullLength。参数语义可对照 [Netty 帧解码文档](https://netty.io/4.1/api/io/netty/handler/codec/LengthFieldBasedFrameDecoder.html)。

例如一帧 100 字节，只有 40 字节到达时先累积等待；收到剩余部分后输出一帧。若还包含下一帧，则继续按下一帧长度切分。不能把“一次 channelRead”当成“一次 RPC”。

### 两套请求 ID 的区别

当前 Future 关联使用的是请求体里的字符串 UUID。协议头的整型 requestId 在每次编码时重新生成，没有作为原请求 ID 在响应头中回显参与关联。因此不能说“头部 ID 完成请求响应匹配”。这是后续可以统一的设计重复。

### 取舍和实现边界

- 固定头部易解析，但字段布局固定，演进需要版本兼容设计。
- 长度字段减少边界歧义，但不证明消息合法，也不负责 TCP 重传。
- 8 MiB 是压缩后网络帧的限制，不等于解压后对象大小限制；当前没有完整解压膨胀保护。
- 非法类型、未知算法、过小长度等校验仍需完善；没有 TLS、鉴权或校验和。
- 帧解码器有每连接的累积状态，不能跨连接共享。当前 Codec 标了 `@Sharable`，不代表所有 Handler 都可共享。

### 常见开源方案怎么对应

不要把传输协议、序列化格式和整个 RPC 框架混为一谈。

| 方案 | 对应关系 | 面试对比重点 |
| --- | --- | --- |
| gRPC | 通常通过 HTTP/2 传输，默认使用 Protocol Buffers 和生成代码 | 标准化、多语言和服务契约；不是本项目的 magic 对应协议 |
| Dubbo 的 dubbo 协议 | 基于 TCP 的自定义二进制 RPC 协议，支持长连接复用 | 与本项目固定头部、消息类型和请求关联的设计更接近 |
| Apache Thrift | IDL 和生成代码，Protocol 与 Transport 分层，可选择 Binary、Compact 等协议和不同传输 | 编码方式不等于底层传输，一套框架可支持多种组合 |

这些只是结构对比，不是性能排名。参考 [gRPC 简介](https://grpc.io/docs/what-is-grpc/introduction/)、[gRPC 架构](https://grpc.io/docs/what-is-grpc/core-concepts/)、[Dubbo 协议](https://dubbo.apache.org/en/overview/mannual/java-sdk/reference-manual/protocol/dubbo/) 和 [Thrift 分层](https://thrift.apache.org/docs/concepts)。

### 面试讲法

> TCP 没有应用消息边界，所以我定义了 16 字节固定头部，用 fullLength 表示整帧长度，通过长度字段帧解码器先切出完整消息，再校验、解压和反序列化。协议通过类型字段区分请求、响应和心跳，通过算法编号选择序列化与压缩实现。目前真正关联请求响应的是消息体 UUID，头部整型序号尚未统一使用。

## 第二条 SPI 序列化与压缩

### 简历对应内容

> 通过 SPI 接入 Java 原生、Kryo、Hessian、Protostuff 序列化，经过 Gzip 压缩后写入网络，降低网络开销并提高扩展性。

### 为什么序列化和压缩分开

Java 对象不能直接通过 Socket 传输，需要转换成字节；压缩则在已有字节上进一步减少体积，两者职责不同。

~~~text
发送：对象 → 序列化 → 字节 → 压缩 → 写头部及消息体 → TCP
接收：TCP → 拆帧 → 取消息体 → 解压 → 反序列化 → 对象
~~~

通过 `Serializer`、`Compress` 接口解耦，上层 Codec 不需要知道具体实现细节。新增实现时主要增加类和 SPI 配置，但还要补齐线上算法编号以及两端支持，不能只把类放进目录就声称协议兼容。

### 自定义 SPI 和 Java SPI 的区别

SPI 是服务提供者接口机制：核心模块定义接口，扩展模块提供实现，运行时发现并使用它们。

Java 标准 `ServiceLoader` 使用 `META-INF/services/接口全名`，支持懒发现及加载器范围内的提供者缓存，不是“启动时必然一次性实例化全部实现”。本项目没有直接用它选实现，而是自己解析 `META-INF/extensions/接口全名` 下的 `名称=实现类`。参见 [Java ServiceLoader 文档](https://docs.oracle.com/javase/8/docs/api/java/util/ServiceLoader.html)。

本项目获取实现：

~~~java
Serializer serializer = ExtensionLoader
        .getExtensionLoader(Serializer.class)
        .getExtension("hessian");
~~~

它主要提供按名称选择、懒加载、缓存，不应直接等同于 Dubbo 完整的自适应扩展、包装器和依赖注入体系。

### ExtensionLoader 到底缓存什么

源码：[ExtensionLoader][src-extension]、[Holder][src-holder]、[SingletonFactory][src-singleton]。

| 缓存 | 范围和 key | value | 避免的重复工作 |
| --- | --- | --- | --- |
| EXTENSION_LOADERS | 静态，扩展接口 Class | 该接口的 ExtensionLoader | 重复创建接口加载器 |
| cachedClasses | 每个加载器，名称 | 实现 Class | 重复读资源和解析名称映射 |
| cachedInstances | 每个加载器，名称 | Holder 中的实现实例 | 重复取创建路径及构造 |
| SingletonFactory | 工厂范围，实现类全名 | Holder 中的对象 | 同实现类被多个名称使用时重复创建 |

第一次获取 Hessian 的过程：

1. 校验 Serializer 是接口且带 `@SPI`，取得或建立加载器。
2. 为名称 hessian 取得唯一 Holder。
3. 若尚未加载名称映射，读取对应资源，解析并缓存 Class 映射。
4. 找到 HessianSerializer 的 Class，通过 SingletonFactory 获取实例。
5. 把实例写入名称 Holder，下次获取直接返回。

“读配置一次”和“创建一个实现一次”不是同一个缓存层级。加载资源时可以解析多个实现 Class，但不等于把所有实现实例都创建出来。

### 为什么 ConcurrentHashMap 之外还需要锁和 volatile

ConcurrentHashMap 保证单次容器操作安全，不能自动保证“检查为空 → 创建对象 → 放入缓存”只执行一次。ExtensionLoader 用 `putIfAbsent` 保证取得同一个 Holder，再对 Holder 加锁并二次检查，避免同名实现重复创建。

`Holder.value` 使用 volatile，让完成创建后发布的引用对其他线程可见。它解决发布和可见性，不负责保护实现对象后续的内部可变状态。

SingletonFactory 还有独立边界：当前新的外层对象表是 HashMap，却存在锁外读取，不能因为 Holder 内部 volatile 就宣称整个工厂完全并发安全。缓存会长期持有引用，也没有实现配置热重载或卸载。

### 共享实例是否线程安全

SPI 实例可能同时服务多个连接和线程。你的 KryoSerializer 用 ThreadLocal 隔离 Kryo，ProtostuffSerializer 用 ThreadLocal 隔离 LinkedBuffer，序列化后清空缓冲区以便复用。Hessian 的输入输出对象则在每次调用时创建。

因此应说“通过实现内部的线程隔离或无共享可变状态保证并发使用”，而不是“SPI 单例天然线程安全”。ThreadLocal 随长期工作线程持有资源，也需要考虑内存成本。

### 当前支持情况需要校准

- Hessian、Kryo、Protostuff 都有实现及协议编号；示例业务请求和响应默认选择 Hessian 加 Gzip。本次没有验证所有算法的完整互操作测试。
- 项目把 Kryo 的 SPI 名称写成 `kyro`，枚举也使用相同拼写。对外讲正确名称 Kryo，核对代码时不要忽略这个实际 key。
- JavaSerializer 类存在，但声明包为 `github.javaguide.serialize.jdk`，SPI 配置使用小写开头的 `github.javaguide.serialize.jdk.javaSerializer`，与实际类名不一致。
- SerializationTypeEnum 只有 Kryo、Protostuff、Hessian，没有 Java 原生算法编号。因此不能把 Java 原生序列化说成已在当前网络链路接通。
- Codec 压缩头字段固定写 Gzip 编号，实际压缩实现却依据消息属性选择。当前只有 Gzip 时一致，未来扩展其他算法前必须修正这处协议一致性问题。

### Gzip 的好处与代价

可压缩的大消息可能减少网络字节量，但压缩和解压消耗 CPU；很小或已压缩的消息可能收益很低，甚至变大。当前业务消息统一压缩，没有按大小阈值选择，也没有实现无压缩算法。

面试中可以提出“依据消息大小和实测压缩收益启用压缩”，但要明确这是改进方向。没有压测数据时，不报提升百分比，也不说总延迟必然下降。

### 面试讲法

> 我把序列化和压缩抽成两个 SPI 接口，编解码器根据头部编号选实现。自定义 ExtensionLoader 按接口缓存加载器，首次解析名称到 Class 的映射，再按名称懒获取并缓存实现。实例可能被并发共享，因此 Kryo 和 Protostuff 的非线程安全状态用 ThreadLocal 隔离。当前示例默认 Hessian 加 Gzip，Java 原生实现的配置和协议编号还需要补齐。

## 第三条 Netty 通信与连接管理

### 简历对应内容

> 基于 Netty 实现高性能 NIO 通信，客户端缓存可用 Channel，使用 CompletableFuture 关联请求与响应，服务端实现心跳保活和空闲连接关闭。

### 先区分连接 线程和请求

一条 TCP 连接在两端各有自己的 Socket 或 Channel 对象，不是两台主机共享同一个 Java 对象。一个 NIO EventLoop 可以管理多条连接，一条连接可以承载多个未完成请求。因此连接数量、线程数量和请求数量不需要一对一相等。

| 对象 | 职责 |
| --- | --- |
| NioServerSocketChannel | 服务端监听端口，接受新连接 |
| NioSocketChannel | 客户端或服务端接受后的通信连接 |
| EventLoop | 运行 I/O 事件循环及任务 |
| EventLoopGroup | 多个 EventLoop 的集合 |
| Selector | 发现多个连接的就绪事件 |
| Pipeline | 定义连接上的处理步骤 |
| Handler | 执行某一类事件或消息处理 |

多个客户端连接同一端口时，服务端通常是一个监听 Channel 加多个通信 Channel，不是每来一个客户端就创建一个监听 Channel。

### 为什么需要 Netty

简单阻塞式 Socket 模型中，空闲连接可能占用等待 read 的线程。NIO 通过就绪通知让少量线程管理多个连接，不必为每条空闲连接安排专属等待线程。Selector 的等待仍可阻塞，但不是每个连接各阻塞一个线程。

直接使用 Java NIO 还要处理注册分发、部分读写、缓冲区、任务协调和异常关闭。Netty 封装了这些基础设施及 Pipeline，项目可以聚焦协议和调用链。它的异步事件驱动 API 不等于项目使用了操作系统 AIO，也不自动保证业务逻辑高性能。参见 [Netty 入门指南](https://netty.io/wiki/user-guide-for-4.x.html)。

### 当前服务端是三个线程组

源码：[NettyRpcServer][src-server]。

~~~text
bossGroup：接受新连接，将通信 Channel 注册到 worker
workerGroup：网络读写、帧解码、消息编解码
serviceHandlerGroup：处理服务端 Handler，反射调用业务方法
~~~

boss 配置 1 个线程，worker 使用 NioEventLoopGroup 默认配置，业务组使用 `cpus * 2` 个执行线程。不要把默认 worker 数量说成固定等于 CPU 核数。

Channel 注册后通常由同一个 EventLoop 管理 I/O，而一个 EventLoop 可以负责多个 Channel。Pipeline 不是线程池，默认 Handler 回调沿 Channel 的执行器运行；本项目为业务 Handler 指定独立执行组。参见 [EventLoop 文档](https://netty.io/4.1/api/io/netty/channel/EventLoop.html)。

~~~java
p.addLast(new IdleStateHandler(30, 0, 0, TimeUnit.SECONDS));
p.addLast(new RpcMessageFrameDecoder());
p.addLast(rpcMessageCodec);
p.addLast(serviceHandlerGroup, nettyRpcServerHandler);
~~~

业务隔离避免耗时服务方法直接卡住 worker。但编解码和 Gzip 仍可能占用 I/O 线程，业务执行组也有有限容量及排队成本。同一 Handler 的事件不应被理解为每请求自动获得独立线程。

Pipeline 入站总体向后传播，出站总体反向传播，并只经过相应类型的 Handler。参见 [Pipeline 文档](https://netty.io/4.1/api/io/netty/channel/ChannelPipeline.html)。ByteBuf 可以使用堆内或直接内存，不能说全部 ByteBuf 都是堆外或整个链路完全零拷贝。

### 缓存 Channel 是什么意思

源码：[ChannelProvider][src-channel]、[NettyRpcClient][src-client]。

缓存是 `Map<String, Channel>`，key 实际使用 `InetSocketAddress.toString()`。设计意图是按远端主机和端口保存一个可复用的连接对象引用，不是保存握手报文、请求结果或专属线程。

假设已经建立如下连接，缓存概念上是：

~~~text
key：服务端 192.168.1.20:9998
          ↓
value：客户端的 Channel-A
       本地地址 192.168.1.10:51001
       远端地址 192.168.1.20:9998
~~~

第一次调用：发现目标 → 缓存未命中 → 建立 TCP 连接 → 保存 Channel → 发请求 → 收响应但不关闭连接。

第二次调用同一目标：缓存命中且 active → 复用 Channel 和原来的本地端口 → 不重新握手。连接失效后必须重新建连，新连接的本地端口可能不同；旧引用不能把关闭的 Socket 变回活连接。

缓存查找只使用远端地址，不是按两端端口组合查找；但实际 TCP 连接由本地和远端 IP、端口标识。同一远端地址可以建立多条连接，通常使用不同本地端口。

### 每个服务器是不是只有一条 Channel

不是 TCP 或 Netty 的限制。当前客户端的这个 Map 每地址只有一个槽位，服务器可同时接受许多客户端连接，同一个客户端也可对同一地址建多条连接。

不同接口若部署在同一个地址，可以复用同一 Channel；同 IP 不同端口则是不同缓存项。缓存只在本进程相应 ChannelProvider 的作用范围共享，不跨进程共享。

它是单 Channel 缓存，不是有借还、容量控制等机制的完整连接池。需要多连接时可以设计 `地址 → ChannelPool`，但并发 RPC 不必然要求多连接，requestId 可关联同一连接上的多个请求。

也不需要为缓存额外建立“每连接一个线程”的线程池：网络 I/O 已由 EventLoopGroup 承担，Map 只负责查找引用。普通线程池管理执行任务，连接缓存管理网络资源，两者不是互相替代。

当前查缓存、建连、放缓存不是原子过程，并发首次调用可能建立重复连接。ConcurrentHashMap 安全不等于创建过程只执行一次。可按地址共享正在建连的 Future，统一失败清理，这是改进方案而非现有实现。

### Future 为什么能关联请求和响应

源码：[UnprocessedRequests][src-pending]、[NettyRpcClientHandler][src-client-handler]。

发送和接收由不同执行路径完成，需要一个结果占位符连接二者。Future 不会因为被 new 出来就自动启动线程。

~~~text
业务线程：创建 Future → 放入 UUID 映射 → 发送 → get 等待
I/O 线程：收到响应 → 按 UUID 从映射移除 Future → complete
业务线程：得到结果，继续执行
~~~

先登记再发送是为了避免响应过快却查不到 Future。响应处理用 `remove(id)` 获得 Future，再完成它，及时删除已结束请求。重复或迟到响应当前会因找不到 Future 抛异常，而不是温和丢弃。

| 类型 | 当前含义 |
| --- | --- |
| ChannelFuture | Netty 的连接、写入、关闭等 I/O 操作完成结果 |
| `CompletableFuture<Channel>` | doConnect 中等待建连结果的桥接对象 |
| `CompletableFuture<RpcResponse>` | 一次 RPC 响应的结果占位符 |

写入成功不等于远端业务执行成功。当前 `resultFuture.get()` 让业务调用方同步阻塞，网络处理和回填才是异步的。不要在 EventLoop 中阻塞等待它负责处理的结果。参见 [ChannelFuture](https://netty.io/4.1/api/io/netty/channel/ChannelFuture.html) 和 [CompletableFuture](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CompletableFuture.html)。

### 心跳为什么需要

长连接没业务数据时，本地未必立即发现对端故障或网络中断。应用心跳提供通信活动和有限的存活依据；TCP 的 SO_KEEPALIVE 是另一层机制，周期由系统配置决定，不能替代框架自己的协议交互。

| 端 | 配置 | 自定义行为 |
| --- | --- | --- |
| 客户端 | IdleStateHandler(0, 5, 0) | 约 5 秒写空闲，发送 PING |
| 服务端 | IdleStateHandler(30, 0, 0) | 约 30 秒读空闲，关闭连接 |
| 服务端收到 PING | 心跳请求类型 | 返回 PONG |
| 客户端收到 PONG | 心跳响应类型 | 记录日志 |

IdleStateHandler 只产生事件，发心跳或关闭连接是自定义 Handler 的行为。参见 [IdleStateHandler 文档](https://netty.io/4.1/api/io/netty/handler/timeout/IdleStateHandler.html)。

不是严格每 5 秒定时发送：业务有写活动就不必发心跳。服务端检测的是没有任何读活动，而不是没有业务请求；健康心跳能一直维持连接。5 秒和 30 秒留有调度余量，不等于实现了“丢六次心跳判死”。

### 需要主动承认的边界

- `isActive()` 是本地连接状态，不保证远端业务健康。
- 客户端没有 PONG 超时判定、连续失败计数及完整主动重连机制。
- `doConnect()` 失败时只在监听器抛异常，没有 completeExceptionally，等待线程可能挂住。
- 响应没有超时，写成功后断连没有批量终止 pending 请求，可能残留 Future 和活跃计数。
- 当前同一个 NettyRpcServerHandler 被加入多个连接，却没有标 `@Sharable`，重复添加会被拒绝。不能把它作为多连接已验证无误的实现来讲。
- 心跳和空闲事件也进入服务端业务 Handler，业务拥塞可能延迟处理。客户端心跳路径调用 getChannel，缓存未命中时可能阻塞 I/O 线程。
- 服务端对业务结果调用 `result.toString()`，返回 null 时会异常；业务异常也没有完整编码成带原 UUID 的失败响应。

### 面试讲法

> 服务端采用 boss 接受连接、worker 处理 I/O，并用独立执行组承接业务调用。客户端按地址复用 Channel，通过 UUID 与 CompletableFuture 映射回填响应。网络处理是异步的，但业务 API 仍同步等待。客户端写空闲发 PING，服务端回 PONG并在读空闲时关闭连接；目前还需补齐响应超时、PONG 超时和断连请求清理。

## 第四条 ZooKeeper 注册与发现

### 简历对应内容

> 服务端在 /my-rpc 注册实例，通过 ShutdownHook 清理节点和线程池；客户端按接口名、group、version 发现地址，结合本地缓存和监听刷新。

### 服务标识和节点结构

源码：[ZkServiceProviderImpl][src-provider]、[ZkServiceRegistryImpl][src-registry]、[CuratorUtils][src-curator]。

服务标识直接拼接 `interfaceName + group + version`，用于区分接口、不同实现分组和版本。当前没有分隔或转义，后续可改成明确编码以避免歧义。

~~~text
/my-rpc
└── github.javaguide.HelloServicetest1version1
    ├── 10.0.0.8:9998  实例临时节点
    └── 10.0.0.9:9998  实例临时节点
~~~

服务父路径用于组织实例，实例叶节点用 EPHEMERAL 创建。临时节点随创建者 ZooKeeper 会话结束而删除，不随某次 RPC 结束而删除。会话过期需要时间，不是进程一挂节点立即消失。参见 [ZooKeeper 会话和临时节点](https://zookeeper.apache.org/doc/current/zookeeperProgrammers.html)。

### 客户端发现与缓存刷新

源码：[ZkServiceDiscoveryImpl][src-discovery]。

首次查询时读取服务路径子节点，缓存 `服务标识 → 地址列表`，并创建 PathChildrenCache 监听。后续读取缓存，再执行负载均衡选一个地址。

当前监听回调不是逐条添加或删除地址，而是再次 `getChildren()` 并整体替换缓存。因此准确表述是“监听驱动刷新”或“事件触发全量刷新”，不是严格的增量更新。PathChildrenCache 的能力参见 [Curator Path Cache](https://curator.apache.org/docs/recipes-path-cache/)。

### 上线 下线和重连

| 场景 | 当前机制 | 注意事项 |
| --- | --- | --- |
| 新实例上线 | 创建临时节点，监听端刷新列表 | 注册不等于服务已完全就绪 |
| 正常退出 | Hook 主动删除本实例节点 | 不会自动完成在途请求排空 |
| 进程崩溃 | 会话过期清理临时节点 | 存在故障检测窗口 |
| RPC 连接断开 | 后续查到 inactive 时按需建连 | 不等于 ZK 重连，不能自动恢复已发送请求 |
| ZK 短暂断连 | Curator 恢复连接，会话可能仍有效 | 地址缓存可能变旧 |
| ZK 会话失效后重连 | LOST 标记重注册，RECONNECTED 尝试恢复路径 | 失败补偿不完整 |

地址下线通知只更新地址缓存，不直接关闭 Channel 缓存，也不把原请求自动重发到别的节点。

### ZooKeeper 失效和重启如何理解

短暂断连可能是 `CONNECTED → SUSPENDED → RECONNECTED`，原会话仍有效，不必立即重新注册。会话失效则会清理临时节点，代码在 LOST 时设 `NEED_REREGISTER=true`，重连时尝试重新创建本地记录的路径。

ZK 重启不一定意味着会话失效，要根据恢复时间和会话状态判断。Curator 对状态的定义参见 [ConnectionState 文档](https://curator.apache.org/apidocs/org/apache/curator/framework/state/ConnectionState.html)。

已有缓存且目标仍可达的客户端，可以在注册中心短暂不可用时继续直接调用。没有缓存的首次发现、扩容后的新地址发现，以及下线更新则受影响，所以不是完全无感。注册节点存在也不证明业务、数据库等依赖健康。

当前操作使用基础退避时间 1000ms、最多 3 次的 ExponentialBackoffRetry；首次连接等待最多 30 秒。这不是“整个生命周期只重连三次”。样例 ZK 地址是单机 127.0.0.1:2181，源码支持传入地址列表不等于已经部署验证了高可用集群。

### ShutdownHook 清理范围

源码：[CustomShutdownHook][src-shutdown]、[ThreadPoolFactoryUtil][src-pools]。

`clearAll()` 在服务器 start 时注册退出回调，不是调用后立即清空服务。回调遍历当前进程记录的路径，删除地址后缀与本实例匹配的节点，再关闭工具类登记的线程池。

它不删除整个 /my-rpc，不删除其他实例，不直接清空远端客户端缓存，也不负责清理业务数据库。清理资源是防止存活进程的残留和及时注销，不是说进程终止后操作系统永远不会回收资源。

Netty 的三个执行组关闭写在 start 的 finally 中，不是 Hook 统一关闭；JVM 退出也不保证普通线程先运行完 finally。Curator 和 PathChildrenCache 当前缺少统一关闭。线程池等待 10 秒后，若只是超时返回 false，代码没有进一步强制关闭，仅在中断异常分支调用 shutdownNow。

Hook 是正常退出的清理机会，强制终止、断电或 JVM 崩溃时不保证执行。参见 [Java Runtime 文档](https://docs.oracle.com/javase/8/docs/api/java/lang/Runtime.html#addShutdownHook-java.lang.Thread-)。主动注销追求及时，临时节点负责异常情况下的最终清理。

### 恢复链路尚未闭环

- 重注册前已经清除标记，创建失败只记录日志，没有后台持续补偿。
- 先缓存地址再创建监听，监听创建失败后可能继续永久命中旧缓存，没有补建机制。
- 同地址快速重启时可能遇到旧会话节点，只检查存在、不检查归属，不能保证新进程已真正拥有节点。
- 重连后没有显式统一全量校准所有业务地址缓存，恢复主要依赖 Curator 监听事件及查询。
- 注册操作异常隔离、退出回调中的清理兜底和失败告警仍需完善。

建议的完整停机顺序是撤销发布、停止新请求、排空在途请求、关闭连接及执行组、关闭注册客户端。当前没有统一实现这一套，不能把建议写成已完成能力。

### 面试讲法

> 我用临时节点发布实例，客户端首次查询后缓存地址并监听子节点变化。正常退出主动注销，异常退出依靠会话过期清理。会话失效后记录重注册需求，重新连接时尝试恢复临时节点。已有缓存能支持注册中心短暂不可用时的部分调用，但监听补建、注册失败补偿和优雅停机还没有完整闭环。

## 第五条 负载均衡策略

### 简历对应内容

> 随机、轮询适用于性能相近场景；加权随机、加权轮询、最少活跃数适应性能差异；一致性哈希处理节点变化与分布偏差，通过 SPI 扩展和切换。

### 各策略解决的问题

| 策略 | 规则 | 好处 | 代价或限制 | 当前仓库状态 |
| --- | --- | --- | --- | --- |
| 随机 | 随机选一个地址 | 简单，无轮询计数 | 短时间可能不均匀 | 类存在，主 SPI 未登记 |
| 轮询 | 按计数取模轮流选 | 次数分配规则 | 请求成本不同仍会拥堵 | 类存在，主 SPI 未登记 |
| 加权随机 | 概率与权重成比例 | 按预设能力分流 | 短时波动，权重可能过时 | 未找到实现 |
| 加权轮询 | 按权重比例轮流选 | 比例较稳定，可平滑 | 有状态，需要权重来源 | 未找到实现 |
| 最少活跃数 | 选本客户端未完成请求最少的实例 | 对慢响应有一定反馈 | 非全局负载，依赖完整计数回收 | 已实现并登记 |
| 一致性哈希 | key 在哈希环上定位后继节点 | 稳定 key 映射，减少节点变化重映射 | 热点 key 仍集中，并发更新需保护 | 两版实现并登记，新版默认 |

这些策略并不自动探测故障、重试或保证幂等。节点是否还可选，首先取决于发现结果和健康判断。常见策略设计可参考 [Dubbo 负载均衡](https://dubbo.apache.org/en/overview/what/core-features/load-balance/)。

### 随机和轮询的例子

A、B、C 三个节点，随机概率各为三分之一，但可能连续三次选 A；轮询是 A、B、C、A、B、C。

随机源码用 `nextInt(size)`。轮询按服务维护 AtomicInteger，`getAndIncrement() % size` 得到下标。原子计数防止并发递增丢失，但不代表不同客户端协同使用全局序列。

请求次数均匀不等于 CPU、执行时间和排队长度均匀。节点变化会改变轮询列表及下标映射，不能用它保证同一用户稳定落在同一实例。

### 加权算法是需要掌握的原理而非已有功能

若 A、B、C 权重是 3、2、1，总权重 6，则期望概率是 50%、约 33.3%、约 16.7%。

加权随机可在 [0,6) 取随机整数：[0,3) 选 A，[3,5) 选 B，[5,6) 选 C。常见实现遍历累计权重判断区间。参见 [Dubbo 加权随机说明](https://dubbo.apache.org/en/blog/2018/08/10/dubbos-load-balance/)。

简单加权轮询可排成 A、A、A、B、B、C；平滑加权轮询可分散成 A、B、A、C、B、A。每轮把当前权重加配置权重，选最大者，再从其当前权重减去总权重。

如果要实现，还要定义配置或注册元数据中的权重来源、热更新、零权重及全零兜底。当前没有这些链路，不能说项目已根据 CPU 自动计算权重。

### 最少活跃数如何和 Future 配合

源码：[LeastActiveLoadBalance][src-least]、[ActiveRequestsRecorder][src-active]。

活跃数是本客户端发出但未完成的请求数，不是 TCP 连接数、服务端线程数或全局 CPU。计数粒度为 `服务标识 → 地址 → AtomicInteger`。

~~~text
当前 A=8，B=2，C=5 → 优先 B
多个节点并列最少 → 在并列集合中轮询
~~~

发送前 increment，Future 的 whenComplete 中 decrement。慢节点完成得慢，更容易积累未完成请求，后续减少被选择；快节点计数较快下降，更可能接到新请求。

响应超时及断连不完成 Future 时，计数也可能不下降。选择与 increment 不是一体的原子操作，并发选择可能集中；计数归零后删除条目与并发递增也需协调。若请求完全串行、每次完成后才发下一次，选择时可能全部为零，策略会退化为并列轮询。

### 一致性哈希和虚拟节点

普通 `hash(key) % N` 在 N 改变时容易大范围重映射。一致性哈希把节点和 key 放在同一有序哈希空间，用环上顺时针后继节点承接 key。

~~~text
简化环为 0 至 99，A=10，B=40，C=80
key=30 → B
key=60 → C
key=95 → 绕回 A

删除 B：原来归 B 的区间转给 C，其余区间不必整体取模
~~~

新版用 TreeMap 保存虚拟节点位置，每台物理实例生成 160 个位置，名称为 `地址#序号`。查询用 tailMap 找第一个不小于 keyHash 的节点，找不到就回到首节点。实例列表变化后检测新增和删除地址，更新环上的位置。

虚拟节点改善哈希区间分配偏差，但不能拆分单个热门 key。某用户占 80% 流量且固定落到 A，增加虚拟节点不会自动把这 80% 均分。所以一致性哈希重点是映射稳定性，不是通用热点消除或故障转移。

### 当前一致性哈希的关键边界

源码：[ConsistentHashLoadBalanceNew][src-hash-new]、[ConsistentHashLoadBalance][src-hash-old]。

默认新版使用 `服务标识 + 每次生成的 requestId` 作为 key。同一业务参数每次 UUID 不同，不能保证业务亲和。要实现用户或订单的稳定路由，应选择稳定业务字段并规定确定编码；不是简单换成任何对象的 toString。

新版选择器通过按类缓存的 SingletonFactory 创建，不同服务可能共享同一个可变环，存在隔离问题。环的 TreeMap、HashSet 原地更新，仅用 volatile 初始化标记及忙等不足以保证读写互斥；标记变为 true 后开始读取的线程仍可能与下一次重建交错。

旧版把 `Arrays.stream(parameters)` 直接拼接到字符串，并没有编码参数值，不能当作可靠的参数亲和实现。验证映射稳定性时应使用同一批固定业务 key 比较节点变化前后，不能每轮全换随机 UUID 后只看次数分布。

### SPI 扩展不等于外部配置切换

`LoadBalance` 带 `@SPI`，AbstractLoadBalance 先处理空列表和单节点，多个节点才调用具体 doSelect。新增实现后可登记名称，再由 ExtensionLoader 按名称获取。

主资源目前登记 loadBalance、loadBalanceNew、leastActiveLoadBalance，分别对应两版哈希和最少活跃。随机和轮询尚未登记；发现构造器固定选择 LOADBALANCENEW，没有读取外部策略配置，更没有热切换机制。

### 面试讲法

> 我定义统一 LoadBalance 接口及 SPI 扩展点，实现了随机、轮询、最少活跃数和一致性哈希。最少活跃数结合客户端 Future 生命周期维护未完成请求计数，哈希策略用虚拟节点组织实例并支持列表变化更新。当前默认使用新版哈希，但 key 还是请求 UUID，加权策略和外部配置切换尚未接通，不能把业务亲和和热点治理作为已完成能力。

## 第六条 Spring 注解接入

### 简历对应内容

> 自定义 RpcScan、RpcService、RpcReference，实现服务自动发布和客户端代理注入，业务通过注解配置接入。

### 注解各自负责什么

| 注解 | 标注位置 | 读取它的代码 | 作用 |
| --- | --- | --- | --- |
| RpcScan | 示例启动类 | CustomScannerRegistrar | 启用服务扫描，读取 basePackage |
| RpcService | 实现类 | 扫描器及初始化前后处理 | 创建服务 Bean，读取 group 和 version，发布服务 |
| RpcReference | 接口字段 | 初始化后后处理 | 创建远程接口代理并反射赋值 |

注解只是元数据，不能自行注册或发送网络请求。RUNTIME 保留运行时信息，RpcScan 上的 Import 把 Registrar 接进 Spring。

### 业务使用方式

~~~java
@RpcService(group = "test1", version = "version1")
public class HelloServiceImpl implements HelloService {
    // 真实业务实现
}

@Component
public class HelloController {
    @RpcReference(group = "test1", version = "version1")
    private HelloService helloService;
}
~~~

客户端只需要共享接口和 DTO，不需要持有远端实现对象。HelloController 必须由 Spring 管理，手动 new 的对象不会自动经过注入。RpcReference 标在字段上，不会自动把整个类注册成 Bean。

### 服务端 main 的实际启动顺序

源码：[NettyServerMain][src-server-main]、[CustomScannerRegistrar][src-scanner]、[SpringBeanPostProcessor][src-postprocessor]。

~~~java
ApplicationContext context =
        new AnnotationConfigApplicationContext(NettyServerMain.class);
NettyRpcServer server = context.getBean(NettyRpcServer.class);
server.start();
~~~

1. 执行容器构造方法。它不是创建空壳，而是解析配置、扫描、创建非懒加载单例等。
2. 读取启动类上的 RpcScan，由 Import 引入 CustomScannerRegistrar。
3. Registrar 扫描指定包的 RpcService 类，注册 BeanDefinition；也扫描固定 github.javaguide 包的 Component，使框架后处理器等被容器发现。
4. BeanDefinition 是创建对象的说明，不是对象本身，也不是 ZooKeeper 地址节点。
5. Spring 创建 HelloServiceImpl，完成属性填充并进入初始化处理。相关 Bean 的创建不是任意固定全序。
6. 初始化前的后处理发现 RpcService，读取 group、version，将服务对象放入本地表并发布 ZK 地址，返回原 Bean。这一步没有为服务端新建 RPC 代理。
7. 容器启动完成，构造方法返回。
8. 从容器获得 NettyRpcServer，此时存在服务器对象不代表已监听端口。
9. 调用 start，注册退出钩子、创建执行组、配置 Pipeline、绑定 9998，开始处理连接。

示例在 start 之前还调用了从容器拿到的 helloService.hello，这是本地测试，不走 RPC。当前是先发布地址、后绑定端口，应承认存在注册早于就绪的窗口。

Registrar 操作 BeanDefinition，BeanPostProcessor 操作已经创建的 Bean 实例，这是两个不同阶段。参考 [ImportBeanDefinitionRegistrar](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/annotation/ImportBeanDefinitionRegistrar.html) 和 [BeanPostProcessor](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/beans/factory/config/BeanPostProcessor.html)。

### 客户端 main 的实际启动顺序

源码：[NettyClientMain][src-client-main]、[HelloController][src-controller]。

~~~java
ApplicationContext context =
        new AnnotationConfigApplicationContext(NettyClientMain.class);
HelloController controller = context.getBean(HelloController.class);
controller.test();
~~~

1. RpcScan 引入扫描逻辑，Spring 发现 Component 标注的 HelloController。
2. 创建业务对象时，helloService 字段尚未被本框架注入。
3. 初始化后，SpringBeanPostProcessor 遍历当前类的声明字段。
4. 找到 RpcReference，读取分组、版本及字段类型 HelloService。
5. 创建 RpcClientProxy，用 JDK Proxy 生成实现 HelloService 接口的本地代理。
6. field.setAccessible(true)，field.set(bean, proxy)，把代理赋给业务字段。
7. 容器完成启动，业务侧拿到已经注入的 Controller。
8. 执行 test，调用代理的 hello 方法，此时才进入构造请求、发现目标、获取连接和发送 RPC 的流程。

注入的不是远端 HelloServiceImpl，也不是通过 Autowired 查到了远端 Bean。代理没有被单独注册成接口 BeanDefinition，是直接赋到业务 Bean 字段上的对象；字段持有引用，不意味着所有字段天然共享一个代理。

### 动态代理怎样转成远程调用

源码：[RpcClientProxy][src-proxy]、[RpcRequestHandler][src-request-handler]。

`Proxy.newProxyInstance` 根据接口创建代理，方法调用进入 InvocationHandler.invoke。它提取接口名、方法名、参数类型和参数，补充 UUID 及注解 group、version，然后调用传输层。

服务端根据相同服务标识找对象，用 getMethod 和 invoke 执行方法。客户端代理检查响应 ID 和状态，取出 data 返回给业务。

代理创建发生在启动阶段，业务 RPC 发生在方法调用阶段。表面像本地调用，不代表具有本地调用的可靠性和延迟，仍需处理网络超时、异常和幂等。

### 设计收益和边界

- 收益：业务无需重复写注册、代理创建、协议构造和传输逻辑，接口与通信实现解耦。
- JDK 代理要求接口，不能把任意实现类字段都当成支持的引用。
- 当前在初始化后注入，因此初始化回调中不能假定引用已经可用；可在属性填充阶段通过合适的扩展点改进。
- getDeclaredFields 不扫描父类字段，直接使用 bean.getClass 在 AOP 代理下也可能取不到预期元数据。
- 服务标识生成当前取实现类第一个接口，不是完整的多接口发布机制。
- 扫描器仍保留 Spring 默认过滤规则；框架 Component 扫描范围又写死，不能把它说成任意业务包的完整自动配置。
- 仍需显式启动 Spring 容器和调用 server.start，当前不是完整 Spring Boot Starter。

### 面试讲法

> RpcScan 通过 Import 接入 Registrar，扫描并注册服务 BeanDefinition。服务 Bean 创建时，后处理器读取 RpcService，保存实现并发布地址；业务 Bean 初始化后读取 RpcReference 字段，生成 JDK 动态代理并反射注入。业务调用进入代理后才构造 RPC 请求。当前注入时机和 AOP、继承兼容性仍需要完善。

## 简历表述与代码边界

### 面试前必须校准的六条表述

| 简历表述 | 当前证据与更准确的说法 |
| --- | --- |
| 自定义协议处理粘包拆包 | 有固定头部和长度帧解码；头部序号与消息体 UUID 未统一 |
| 接入四种序列化 | 有四类源码，但 Java 原生 SPI 类名和网络编号未接通；不要说四种已完整验证 |
| 高性能异步通信与保活 | 有 NIO、连接复用和业务隔离；业务 API 同步等待，无性能提升实测及完整 PONG 超时 |
| 增量监听及无资源泄露 | 实际是监听触发全量刷新；Hook 清理范围有限，恢复和资源关闭不完整 |
| 加权策略及配置化切换 | 未找到加权实现；随机轮询未登记主 SPI，默认策略写死；UUID 哈希不提供业务亲和 |
| 注解自动接入 | 已实现扫描发布和代理字段注入；端口仍手动启动，发布和注入时机存在边界 |

### 源码风险不等于完成整改

后续如果要完善，优先考虑：建连失败 Future 回填、响应超时及断连清理、服务端 Handler 多连接问题、哈希环服务隔离和并发安全、注册失败补偿及监听恢复、服务就绪和退出排空。其次再补权重模型、外部配置、安全与可观测性。

这些是建议，不是本次已经修复的内容。补重试时还要考虑业务幂等，不能因网络失败就对所有方法无条件重发。

### 基于当前代码的简历措辞参考

以下是机制级描述，不包含没有数据支持的性能指标：

1. 设计 16 字节自定义 RPC 协议头，基于长度字段帧解码处理 TCP 消息边界，支持请求、响应及心跳类型。
2. 设计序列化与压缩 SPI 扩展点，提供 Hessian、Kryo、Protostuff 实现，示例通过 Hessian 和 Gzip 完成对象编码与传输；Java 原生实现接入仍待完善。
3. 基于 Netty NIO 组织通信链路，按地址复用 Channel，以请求 UUID 和 CompletableFuture 关联响应，并通过独立业务执行组隔离业务处理，提供写空闲心跳和读空闲关闭逻辑。
4. 基于 ZooKeeper 临时节点完成实例注册发现，结合客户端地址缓存及监听驱动刷新，提供退出主动注销和会话失效后重连重注册逻辑。
5. 提供随机、轮询、最少活跃数及一致性哈希策略实现，设计 LoadBalance SPI 接口并接入哈希和最少活跃策略；通过虚拟节点组织映射和支持实例列表变化更新。
6. 通过 RpcScan、RpcService、RpcReference 及 Spring 扩展点实现服务扫描发布和客户端 JDK 动态代理注入。

实际面试应根据自己的真实贡献说明“做了什么改动、为什么改、如何验证”。不把仓库已有逻辑或参考设计全部包装成独立原创，也不为未修复问题提供虚构的修复经历。

## 一天复习安排与自测

### 八小时复习建议

| 时间预算 | 主题 | 应能脱稿做到 |
| --- | --- | --- |
| 30 分钟 | 整体项目和缓存区别 | 讲出两进程、注册中心和一次调用 |
| 1 小时 | 协议与 SPI | 写出 16 字节头、解释 -9、画出缓存层次 |
| 2 小时 | Netty | 讲清线程、Channel、请求、Future 和心跳 |
| 1 小时 | ZooKeeper | 区分上线下线、连接中断、会话失效与注销 |
| 1 小时 | 负载均衡 | 用例子比较策略，并承认当前接入差距 |
| 1 小时 | Spring | 顺着两个 main 逐步解释注册和代理注入 |
| 1.5 小时 | 模拟追问 | 不看文档回答下面问题，再回源码核对 |

这是学习时间建议，不是文档阅读耗时测量。如果还有第二天，优先做最小验证和记录结果，不要为了增加亮点在没有验证时宣称生产级能力。

### 高频追问与答案提示

| 问题 | 应回答的核心 |
| --- | --- |
| 你的 RPC 是 HTTP 吗 | 不是；当前自定义二进制协议基于 TCP，ZK 另有自己的协议 |
| 为什么 TCP 有序还需要帧长度 | 有序字节流不提供应用消息边界 |
| 为什么 lengthAdjustment 是 -9 | 抵消长度字段末尾的 5 加 4 字节 |
| 头部 requestId 关联 Future 吗 | 当前不是，使用请求体 UUID |
| SPI 缓存是不是一个 Map 就够了 | 容器安全与创建一次不同，Holder 锁和可见性各有职责 |
| 单例序列化器能直接并发共享吗 | 看内部状态，Kryo 和 LinkedBuffer 使用 ThreadLocal 隔离 |
| 为什么不每条连接一个线程 | NIO 多路复用，EventLoop 可管理多个 Channel |
| boss 会处理每次请求吗 | 不会，主要接受连接；已有连接由 worker 处理 |
| 缓存的是端口吗 | key 是远端地址，value 是活连接 Channel 引用，包含两端地址 |
| 一个服务器只能有一个 Channel 吗 | 不是，当前每远端地址只有一个客户端缓存槽位 |
| 连接池和线程池有什么区别 | 连接池管理连接资源，线程池管理任务执行 |
| writeAndFlush 成功就代表 RPC 成功吗 | 不代表，只是对应写操作成功 |
| CompletableFuture 会自动开线程吗 | new 和 complete 不会；此处是手动回填结果 |
| 当前是异步 RPC 吗 | 网络事件异步，业务接口 get 同步等待 |
| 5 秒和 30 秒分别是什么 | 客户端写空闲发 PING，服务端读空闲关闭，不是 RPC 响应超时 |
| 没收到 PONG 就自动重连吗 | 当前没有完整 PONG 超时和主动重连 |
| 为什么客户端缓存地址 | 降低发现开销，后续依赖监听更新，允许有条件继续调用 |
| 临时节点在进程崩溃后立即消失吗 | 不一定，需要会话过期判定 |
| Hook 清空整个注册中心吗 | 不，只删除当前实例匹配路径并回收受管理线程池 |
| ZK 重启就必须重注册吗 | 看会话是否失效；LOST 后重连才触发当前重注册逻辑 |
| ZK 不可用还能调用吗 | 有缓存且目标可达时可以，首次发现和地址变化会受影响 |
| 最少活跃数是谁统计的 | 当前客户端按服务和地址统计未完成请求，不是全局负载 |
| 一致性哈希能消除热点吗 | 不能；虚拟节点改善区间分配，热门 key 仍可集中 |
| 同一用户会固定落到同一实例吗 | 当前 UUID key 不保证，需要稳定业务 key |
| 加权和动态配置切换在哪里 | 当前缺少实现或接入，不能用算法原理冒充现成功能 |
| RpcService 自己会创建 Bean 吗 | 自定义扫描器注册定义，Spring 创建实例，后处理器发布 |
| RpcReference 是 Autowired 吗 | 不是，本框架生成代理并反射赋值 |
| 注解字段里是远端实现对象吗 | 不是，是本地 JDK 动态代理 |
| 启动时已经完成所有建连吗 | 没有，代理注入与首次业务调用建连是两个阶段 |

### 建议验证清单

以下均为待执行测试建议，不代表本文已运行并通过：

- [ ] 不看文档，从服务端 main 和客户端 main 讲完整链路。
- [ ] 测试半帧、连帧、超长帧、非法 magic 和 version。
- [ ] 验证每个实际接通的序列化实现能正确往返请求及响应。
- [ ] 并发首次调用同一地址，观察连接创建次数。
- [ ] 多客户端连接，验证服务端 Handler 生命周期。
- [ ] 对端无响应及写成功后断连，观察调用是否结束及 pending 是否清理。
- [ ] 停止业务写入，观察 PING、PONG及服务端读空闲行为。
- [ ] 实例上线下线，观察地址缓存而不只看 ZK 节点。
- [ ] ZK 短暂断连和会话过期分别测试，记录是否完成重注册。
- [ ] 用两个不同服务列表测试哈希环隔离，用固定 key 测试重映射比例。
- [ ] 并发改变实例列表，验证哈希查询正确性而不只验证重构次数。
- [ ] 检查继承字段、初始化时调用引用和 Spring AOP 下的注入行为。

## 源码导航

链接固定到本次源码基线。源码本身是实现证据，官方文档只解释机制，不能替本项目证明功能已跑通。

| 主题 | 优先阅读 |
| --- | --- |
| 协议头和帧解码 | [RpcConstants][src-constants]、[RpcMessageFrameDecoder][src-frame]、[RpcMessageCodec][src-codec] |
| SPI 和缓存 | [ExtensionLoader][src-extension]、[Holder][src-holder]、[SingletonFactory][src-singleton] |
| 序列化和配置 | [Serializer 配置][src-serializer-config]、[SerializationTypeEnum][src-serializer-enum]、[JavaSerializer][src-java-serializer] |
| 网络线程和服务执行 | [NettyRpcServer][src-server]、[NettyRpcServerHandler][src-server-handler]、[RpcRequestHandler][src-request-handler] |
| Channel 和 Future | [NettyRpcClient][src-client]、[ChannelProvider][src-channel]、[UnprocessedRequests][src-pending]、[NettyRpcClientHandler][src-client-handler] |
| 注册与发现 | [ZkServiceProviderImpl][src-provider]、[ZkServiceRegistryImpl][src-registry]、[ZkServiceDiscoveryImpl][src-discovery]、[CuratorUtils][src-curator] |
| 退出清理 | [CustomShutdownHook][src-shutdown]、[ThreadPoolFactoryUtil][src-pools] |
| 最少活跃和哈希 | [LeastActiveLoadBalance][src-least]、[ActiveRequestsRecorder][src-active]、[ConsistentHashLoadBalanceNew][src-hash-new]、[ConsistentHashLoadBalance][src-hash-old] |
| 策略接入 | [LoadBalance 配置][src-lb-config]、[ZkServiceDiscoveryImpl][src-discovery] |
| Spring 扫描和注入 | [CustomScannerRegistrar][src-scanner]、[SpringBeanPostProcessor][src-postprocessor]、[RpcClientProxy][src-proxy] |
| 启动与业务示例 | [NettyServerMain][src-server-main]、[NettyClientMain][src-client-main]、[HelloController][src-controller] |

[src-constants]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-simple/src/main/java/github/javaguide/remoting/constants/RpcConstants.java#L10
[src-frame]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-simple/src/main/java/github/javaguide/remoting/transport/netty/codec/RpcMessageFrameDecoder.java#L13
[src-codec]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-simple/src/main/java/github/javaguide/remoting/transport/netty/codec/RpcMessageCodec.java#L34
[src-extension]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-common/src/main/java/github/javaguide/extension/ExtensionLoader.java#L22
[src-holder]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-common/src/main/java/github/javaguide/extension/Holder.java#L8
[src-singleton]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-common/src/main/java/github/javaguide/factory/SingletonFactory.java#L19
[src-server]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-simple/src/main/java/github/javaguide/remoting/transport/netty/server/NettyRpcServer.java#L55
[src-server-handler]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-simple/src/main/java/github/javaguide/remoting/transport/netty/server/NettyRpcServerHandler.java#L31
[src-request-handler]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-simple/src/main/java/github/javaguide/remoting/handler/RpcRequestHandler.java#L30
[src-client]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-simple/src/main/java/github/javaguide/remoting/transport/netty/client/NettyRpcClient.java#L56
[src-channel]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-simple/src/main/java/github/javaguide/remoting/transport/netty/client/ChannelProvider.java#L16
[src-pending]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-simple/src/main/java/github/javaguide/remoting/transport/netty/client/UnprocessedRequests.java#L15
[src-client-handler]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-simple/src/main/java/github/javaguide/remoting/transport/netty/client/NettyRpcClientHandler.java#L46
[src-provider]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-simple/src/main/java/github/javaguide/provider/impl/ZkServiceProviderImpl.java#L83
[src-registry]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-simple/src/main/java/github/javaguide/registry/zk/ZkServiceRegistryImpl.java#L23
[src-discovery]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-simple/src/main/java/github/javaguide/registry/zk/ZkServiceDiscoveryImpl.java#L31
[src-curator]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-simple/src/main/java/github/javaguide/registry/zk/util/CuratorUtils.java#L30
[src-shutdown]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-simple/src/main/java/github/javaguide/config/CustomShutdownHook.java#L33
[src-pools]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-common/src/main/java/github/javaguide/utils/concurrent/threadpool/ThreadPoolFactoryUtil.java#L67
[src-least]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-simple/src/main/java/github/javaguide/loadbalance/loadbalancer/LeastActiveLoadBalance.java#L23
[src-active]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-simple/src/main/java/github/javaguide/loadbalance/ActiveRequestsRecorder.java#L9
[src-hash-new]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-simple/src/main/java/github/javaguide/loadbalance/loadbalancer/ConsistentHashLoadBalanceNew.java#L43
[src-hash-old]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-simple/src/main/java/github/javaguide/loadbalance/loadbalancer/ConsistentHashLoadBalance.java#L35
[src-lb-config]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-simple/src/main/resources/META-INF/extensions/github.javaguide.loadbalance.LoadBalance#L1
[src-scanner]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-simple/src/main/java/github/javaguide/spring/CustomScannerRegistrar.java#L42
[src-postprocessor]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-simple/src/main/java/github/javaguide/spring/SpringBeanPostProcessor.java#L60
[src-proxy]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-simple/src/main/java/github/javaguide/proxy/RpcClientProxy.java#L52
[src-server-main]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/example-server/src/main/java/NettyServerMain.java#L20
[src-client-main]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/example-client/src/main/java/github/javaguide/NettyClientMain.java#L12
[src-controller]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/example-client/src/main/java/github/javaguide/HelloController.java#L9
[src-serializer-config]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-simple/src/main/resources/META-INF/extensions/github.javaguide.serialize.Serializer#L1
[src-serializer-enum]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-common/src/main/java/github/javaguide/enums/SerializationTypeEnum.java#L12
[src-java-serializer]: https://github.com/BackOutSide/RPC-frame-work/blob/727416b59f8508bbeb4dbce30837d919be0b1ee3/rpc-framework-simple/src/main/java/github/javaguide/serialize/java/JavaSerializer.java#L1
