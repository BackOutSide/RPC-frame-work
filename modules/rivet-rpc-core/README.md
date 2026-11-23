# RivetRPC Core

`rivet-rpc-core` 实现协议编解码、Netty/Socket 传输、服务注册发现、负载均衡、序列化与压缩，以及 Spring 注解接入。公共异常、SPI 加载器和通用工具位于 `rivet-rpc-common`。

当前实现使用 ZooKeeper 作为注册中心，示例调用同步等待结果。Netty 支持按总时限进行幂等重试，并使用 OpenTelemetry 记录客户端和服务端 span。应用在第一次 RPC 前配置 Java agent 或注册 OpenTelemetry SDK 后即可采集。全局 RequestID 使用 `traceId`，活动的 RPC span 内可通过 `RpcTracing.currentRequestId()` 读取。

运行方式和协议细节见[仓库说明](../../README.md)。控制台 trace 验证步骤见 [Hello 示例](../../examples/hello/README.md)。

本项目从 JavaGuide 的 `guide-rpc-framework` 学习实现发展而来。保留原始源码中的作者信息，并在根目录说明来源与后续改动。
