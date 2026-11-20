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
