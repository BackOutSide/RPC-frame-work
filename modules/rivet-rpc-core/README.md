# RivetRPC Core

`rivet-rpc-core` 实现协议编解码、Netty/Socket 传输、服务注册发现、负载均衡、序列化与压缩，以及 Spring 注解接入。公共异常、SPI 加载器和通用工具位于 `rivet-rpc-common`。

当前实现使用 ZooKeeper 作为注册中心；示例调用同步等待结果，并设置连接与响应超时。自动重试、熔断、限流和跨进程 trace 属于后续迭代。运行方式和协议细节见仓库根目录的 [README](../README.md)。

本项目从 JavaGuide 的 `guide-rpc-framework` 学习实现发展而来。保留原始源码中的作者信息，并在根目录说明来源与后续改动。
