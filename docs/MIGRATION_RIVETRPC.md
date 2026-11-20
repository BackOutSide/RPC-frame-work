# RivetRPC 命名迁移

本次迁移调整项目身份和源码结构，保留现有 v1 协议行为。当前仓库尚未发布稳定版本；需要让 Consumer 与 Provider 使用同一套新坐标和接口包名。

| 旧名称 | 新名称 |
| --- | --- |
| Maven parent `github.javaguide:guide-rpc-framework` | `io.github.backoutside:rivet-rpc-parent` |
| `rpc-framework-common` | `modules/rivet-rpc-common` |
| `rpc-framework-simple` | `modules/rivet-rpc-core` |
| Java 包前缀 `github.javaguide` | `io.github.backoutside.rivet` |
| 根目录中的 Hello、点评示例模块 | `examples/hello/` 与 `examples/dianping/` 下的对应模块 |

示例模块的 Maven artifactId 统一以 `rivet-rpc-example-` 开头。所有 SPI 描述符的文件名和实现类名、示例的 `@RpcScan` 包路径均随 Java 包名更新。

**混部边界**：服务注册键包含接口的全限定类名，因此旧包名的 Consumer 无法发现新包名的 Provider。升级时需要同时切换两端；若将来要求滚动升级，应先实现双注册或显式服务别名，并补兼容测试。

本次保持 `/my-rpc` 注册根路径、`rpc.*` 配置键、16 字节协议头、`grpc` magic 和协议版本 `1`。这些标识的变更属于独立的协议 v2 迁移，不能只靠改名完成。

本地验证命令：`mvn -B -ntp clean package`。点评示例仍需 MySQL、Redis 和 ZooKeeper；运行步骤见[业务示例说明](../examples/dianping/README.md)。
