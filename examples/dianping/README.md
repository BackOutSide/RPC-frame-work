# RivetRPC 点评业务示例

一期覆盖用户的四个动作：查店铺、看券、抢券、查订单。订单在这里是优惠券领取记录。抢券接口返回 `ACCEPTED` 表示已经受理，订单状态到 `CONFIRMED` 表示 MySQL 已完成落库。同一个用户重复领取同一张券时返回原订单号。

## 业务和进程边界

| 模块 | 职责 |
| --- | --- |
| `examples/dianping/web` | Spring Boot HTTP 入口，解析 Redis 登录态，将业务调用转交给 RPC Provider |
| `examples/dianping/api` | 两端共享的 Java 接口和 DTO，包括 `ShopQueryService` 和 `VoucherOrderService` |
| `examples/dianping/provider` | 店铺查询缓存、优惠券查询、秒杀受理、异步建单和订单状态。店铺与订单模块同进程部署，由 Provider 访问 MySQL |
| `modules/rivet-rpc-core` | Spring 注解接入、ZooKeeper 注册发现、Netty 客户端和服务端，以及自定义协议 |

```text
HTTP 客户端 → dianping-web → RivetRPC / Netty → dianping-provider
                    │                              │
               Redis 登录态              Redis 店铺缓存 / 秒杀 / Stream
                                                   │
                                                  MySQL

ZooKeeper：Provider 注册地址，Web 查询与监听服务实例。
```

### 保留的黑马点评能力

- **店铺详情和缓存**。店铺详情先读 Redis，未命中时由一个 Provider 回源 MySQL 并回填。同一热点同时过期时，其他请求等待回填；不存在的店铺短时缓存。
- **登录态**。使用带有效期的 Redis Token。一期用本地演示账号完成认证。
- **优惠券和秒杀**。一人一券，Lua 在 Redis 中检查库存并受理，Redis Stream 将建单工作交给后台消费者。MySQL 事务和唯一约束保护库存和订单，消费者在事务提交后确认消息。消费组可能重复投递，订单按业务唯一键处理。[Redis Stream 消费组](https://redis.io/docs/latest/develop/use-cases/streaming/)
- **订单查询**。已登录用户凭订单号查看自己的受理或落库状态。

### 保留的 RPC 能力

- 使用 `@RpcService` 发布服务、`@RpcReference` 注入动态代理，保留共享接口和序列化 DTO 的调用方式。
- 保留自定义协议、长度字段拆帧、Hessian 序列化、Gzip 压缩、Netty 连接复用和心跳，以及请求 ID 与 `CompletableFuture` 的关联。
- 保留 ZooKeeper 发现和 SPI 负载均衡。一期使用随机策略。监听地址、注册地址、端口和客户端超时均可配置。
- 客户端在超时或连接失败时结束等待。服务端返回关联当前请求的错误响应。秒杀重试由业务的受理记录去重。
- 只读方法在共享 API 接口上标注 `@RpcIdempotent`，Web 端最多尝试 2 个不同 Provider，全部尝试共用 `rpc.client.timeout-ms` 预算。`seckill` 未标注，框架不会自动重放写请求。
- Netty Consumer 对实例做本地熔断、按服务限制并发；Provider 在业务线程入队前执行全局有界准入，过载时返回关联请求 ID 的 `OVERLOADED`。默认值只是演练起点，需用压测校准。

支付和核销属于订单后续功能。探店笔记、关注、签到和附近商户可以作为独立业务迭代。当前主线先建立热点读和秒杀写的压测基线，再根据 MySQL、Redis、应用线程及队列的观测数据选择扩容手段。分库分表属于数据层迭代。

## 本地运行

在 RivetRPC 仓库根目录使用 JDK 17、Maven 和 Docker Compose。

```powershell
docker compose -f examples/dianping/compose.yaml up -d --wait
mvn -B -ntp clean package
```

在两个终端分别启动 Provider 和 Web。

```powershell
java -jar examples/dianping/provider/target/rivet-rpc-example-dianping-provider-1.0-SNAPSHOT.jar
```

```powershell
java -jar examples/dianping/web/target/rivet-rpc-example-dianping-web-1.0-SNAPSHOT.jar
```

默认 HTTP 地址为 `127.0.0.1:18080`，RPC Provider 为 `127.0.0.1:19998`，Provider 健康检查为 `http://127.0.0.1:18081/actuator/health`。Compose 将 MySQL、Redis、ZooKeeper 分别映射到本机的 `13306`、`16379`、`12181`。首次启动初始化数据库和演示数据；券 `1` 从建库前一天到后 30 天有效，初始库存为 20。

## 本地监控

Web 和 Provider 均使用 Spring Boot Actuator 与 Micrometer，默认在各自的 HTTP 端口提供 `/actuator/health`、`/actuator/metrics` 和 `/actuator/prometheus`。应用监听地址默认是 `127.0.0.1`，因此这些管理端点默认仅能从本机访问。Provider 的健康响应不公开依赖详情。`/actuator/prometheus` 需要 `micrometer-registry-prometheus` 依赖并显式暴露；这里已在两个示例进程中配置，参见 [Spring Boot 3.5 指标文档](https://docs.spring.io/spring-boot/3.5/reference/actuator/metrics.html)和[端点文档](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html)。

本地验证端点：

```powershell
Invoke-WebRequest http://127.0.0.1:18080/actuator/prometheus | Select-Object -ExpandProperty Content
Invoke-WebRequest http://127.0.0.1:18081/actuator/prometheus | Select-Object -ExpandProperty Content
```

如需使用 `observability/compose.yaml` 的容器化 Prometheus 抓取在宿主机运行的 Java 进程，启动 **Web 的终端** 时先设置 `$env:DIANPING_WEB_BIND_HOST = '0.0.0.0'`，启动 **Provider 的终端** 时先设置 `$env:PROVIDER_HTTP_BIND_HOST = '0.0.0.0'`，再运行上面的 `java -jar` 命令。这是显式的本地联调选择：两个 HTTP 端口此时会监听所有宿主机网卡，应只在受信任网络使用，或用主机防火墙限制 `18080` 和 `18081`。RPC 端口仍使用原有配置。Prometheus 通过 `host.docker.internal` 抓取宿主机进程；Compose 为 Linux Docker 增加了 `host-gateway` 映射。

从仓库根目录启动 Prometheus、Alertmanager 和本地通知接收器：

```powershell
docker compose -f examples/dianping/observability/compose.yaml up -d
```

打开 `http://127.0.0.1:19090/targets` 查看 Web、Provider 和探针的采集状态，在 `http://127.0.0.1:19090/alerts` 查看规则状态。Alertmanager 页面在 `http://127.0.0.1:19093`，接收器在 `http://127.0.0.1:19094/events` 记录实际送达的告警和恢复通知。运行一次 RPC 调用后，可在 Prometheus 查询 `rivet_rpc_client_duration_seconds_count` 和 `rivet_rpc_server_duration_seconds_count`。

如需 Grafana，可改用下面的命令；其 Prometheus 数据源会自动加载，Web UI 绑定本机 `13000` 端口，首次登录使用镜像默认的 `admin/admin` 并立即修改密码。镜像分别固定为 [Prometheus 3.16.0](https://github.com/prometheus/prometheus/releases/tag/v3.16.0) 和 [Grafana 13.3.0](https://github.com/grafana/grafana/releases/tag/v13.3.0)，Grafana 使用官方的 [`grafana/grafana` 镜像](https://grafana.com/docs/grafana/latest/setup-grafana/installation/docker/)。

```powershell
docker compose -f examples/dianping/observability/compose.yaml --profile ui up -d
```

默认按双 Provider 加独立探针采集。采集覆盖不足、延迟升高报 Warning，RPC 逻辑调用故障率超标或探针连续失败报 Critical。探针启动、单副本配置、规则口径及自动化演练见[监控与告警评测](observability/README.md)。

停止监控容器但保留历史数据：

```powershell
docker compose -f examples/dianping/observability/compose.yaml --profile ui down
```

### 双 Provider 故障演练

两个 Provider 使用同一套示例依赖。在第二个终端设置不同的 HTTP/RPC 端口后启动同一 JAR：

```powershell
$env:PROVIDER_HTTP_PORT = '18082'
$env:PROVIDER_HTTP_BIND_HOST = '0.0.0.0'
$env:RPC_SERVER_PORT = '19999'
java -jar examples/dianping/provider/target/rivet-rpc-example-dianping-provider-1.0-SNAPSHOT.jar
```

Prometheus 默认抓取两个 Provider 的 `18081` 和 `18082`。在 `/targets` 确认两个目标都为 `UP` 后再注入故障。停止任一目标应收到抓取覆盖 Warning，恢复后在接收器看到 Resolved。绑定 `0.0.0.0` 时按上文限制宿主机端口访问。

确认 ZooKeeper 中同一服务出现两个地址，再停止其中一个 Provider，连续调用 `GET /api/shops/1` 并查看 `rivet_rpc_client_retries_total`、`rivet_rpc_client_duration_seconds_count`。失效实例尚未被 ZooKeeper 摘除时，已标注幂等的读请求可在剩余预算内切换到另一实例。`POST /api/vouchers/1/seckill` 未标为幂等，不应期待框架自动重试；超时也不能证明远端没有执行。记录从故障到读请求稳定成功的时间；本仓库未预先给出生产 RTO。

可以在 Web 进程中设置 `DIANPING_RPC_PER_ATTEMPT_TIMEOUT_MS=800` 来试用客户端有界切换。默认值 `0` 保留原来的等待策略。启用后，幂等请求在还有其他候选且允许再次尝试时，会为后续候选预留时间。前一次尝试的预算涵盖建连和等待响应，最后一次尝试使用剩余总预算。这里的 800 毫秒用于联调，实际值按健康实例的延迟和故障演练结果选择。

可用以下命令另测建连挂起、回包迟到、非幂等请求单次发送和双节点超时。测试用未完成的连接 Future 模拟建连挂起，响应故障走实际回环 TCP。

```powershell
mvn -B -ntp -pl modules/rivet-rpc-core -am '-Dtest=RpcCallExecutorTest,NettyBoundedFailoverIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

| 请求 | 结果 |
| --- | --- |
| `GET /api/shops/1` | 返回演示店铺 |
| `GET /api/shops/1/vouchers` | 返回该店铺优惠券 |
| `POST /api/sessions` | 传入 `{"username":"alice","password":"demo-alice"}` 获取 `token` |
| `POST /api/vouchers/1/seckill` | 使用 `Authorization: Bearer <token>`，受理时返回 `202` 和 `orderId` |
| `GET /api/orders/{orderId}` | 使用同一 Token，查询 `PENDING` 或 `CONFIRMED` |

运行烟测。首次运行会用 `alice` 的资格抢一张演示券，以后重复运行会检查同一个订单。

```powershell
& .\examples\dianping\smoke.ps1
```

停止两个 Java 进程后可运行 `docker compose -f examples/dianping/compose.yaml down`。MySQL 和 Redis 使用命名数据卷，下次启动会读取现有数据。仍在队列中的订单依赖 Redis 的持久化，MySQL 只能恢复已经落库的部分。
