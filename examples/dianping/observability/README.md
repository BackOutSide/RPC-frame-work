# RPC 监控与告警评测

负责人为项目维护者。V1 用 Consumer 逻辑 RPC 结果和独立 RPC 探针判断服务影响，用抓取覆盖率定位监控或副本异常。Prometheus 通过 Alertmanager 向本地测试 Webhook 发送告警和恢复通知。

## 启动监控

使用 [点评示例启动说明](../README.md#本地监控) 启动 Web 和两台 Provider。使用相同的 ZooKeeper 地址另起一个探针进程，以下示例由 Docker 内的 Prometheus 抓取宿主机进程。

```powershell
mvn -B -ntp package
java -jar examples/dianping/probe/target/rivet-rpc-example-dianping-probe-1.0-SNAPSHOT.jar --server.address=0.0.0.0
```

探针默认监听 HTTP `18083`，每 15 秒通过真实服务发现和 Netty 调用一次只读 `RpcProbeService.ping()`。Provider 的该方法返回固定值。探针以单独的 job 抓取，业务调用告警只读取 Web 的 job。对于容器到宿主机抓取，仅在可控的联调网络开放管理端口。

```powershell
docker compose -f examples/dianping/observability/compose.yaml up -d
Invoke-RestMethod http://127.0.0.1:19094/events
```

Prometheus 目标页在 `127.0.0.1:19090/targets`，Alertmanager 在 `127.0.0.1:19093`。接收器保留最近 256 条包含接收时间的通知及原始 payload。联合查看 payload 中每条告警的 `status`、`startsAt` 和接收时间。联调中的抓取和求值间隔都是 15 秒，指标暴露路径为 `/actuator/prometheus`。

| 规则 | 联调条件 |
| --- | --- |
| 抓取覆盖 Warning | Provider 的可抓取目标少于预期数（默认 2），持续 1 分钟。Web 抓取失败或目标缺失也报 Warning。单 Provider 部署可同步调整 [抓取目标](prometheus.yml) 和 [预期目标数](alerts.yml)。 |
| 可用性失败率 Critical | Consumer 逻辑调用 5 分钟内至少 20 次，可用性失败率超过 5% 且持续 2 分钟。调用主动取消从统计基数中排除，`remote_error` 单独用于诊断。 |
| P95 Warning | 同一服务 5 分钟内至少 20 次逻辑调用，P95 高于 2 秒且持续 5 分钟。 |
| 主动 RPC Critical | 独立探针连续 3 次失败且结果仍新鲜。探针抓取失败、结果缺失或太旧时发监控 Warning。 |

生产部署应按实际流量与 SLO 校准规则，并选择能送达负责人的通知渠道。本地 Webhook 只用于联调和留证。

排查时先按 `service` 查看 Consumer 的 `rivet_rpc_client_duration_seconds_count` 和 `outcome`，再对照 `rivet_rpc_client_attempts_total`、`rivet_rpc_client_retries_total` 与 Provider 的 `rivet_rpc_server_rejections_total`。如果错误集中在服务发现，再查看 ZooKeeper 缓存年龄和刷新失败指标。

## 评测

规则测试使用与 Prometheus 同版本的 `promtool`。规则用例覆盖目标消失、抓取失败和短暂抖动，以及失败率边界、取消、低流量、计数器重置、延迟和探针数据的新鲜度。

```powershell
promtool check rules examples/dianping/observability/alerts.yml
promtool test rules examples/dianping/observability/alerts.test.yml
amtool check-config examples/dianping/observability/alertmanager.yml
```

端到端评测需要 Java 17、Maven、Python 3.10+ 和原生 Prometheus/Alertmanager 可执行文件。脚本启动独立的测试 ZooKeeper、两个 Netty Provider、一个业务流量 Consumer、一个探针及监控组件。所有端口只绑定本机且由脚本临时分配。测试业务是只读 RPC，所需服务随脚本启动。

```powershell
mvn -B -ntp install
mvn -B -ntp -f examples/dianping/probe/pom.xml dependency:build-classpath '-Dmdep.includeScope=test' '-Dmdep.outputFile=target/evaluation-classpath.txt'
python examples/dianping/observability/evaluate.py --prometheus prometheus --alertmanager alertmanager --fast
```

如果可执行文件不在 PATH，把命令中的两个程序名换成完整路径。去掉 `--fast` 可用仓库原始时间窗口评测。快速模式在临时配置中缩短统计窗口、采集、探测和通知等待时间，原始规则的时间边界由 `promtool test rules` 独立校验。

| 端到端用例 | 验收证据 |
| --- | --- |
| 健康基线 | 两个 Provider 均被真实抓取，Consumer 返回成功，探针产生首个完成时间。 |
| 停一个 Provider | 幂等调用由幸存实例接管，接收端收到 Warning。核对成功率、p99 和重试增量。 |
| 双 Provider 停止，零业务流量 | 接收端收到探针 Critical。 |
| 双 Provider 停止，发起业务调用 | Consumer 返回失败，接收端收到独立的逻辑失败率 Critical。 |
| 恢复双 Provider | 实际调用重新成功，接收端收到失效告警的 Resolved。 |
| 单独停止探针 | 正常 RPC 仍成功，探针采集 Warning 在接收端出现并在探针重启后解除。 |

结果保存在探针模块 `target/monitoring-evaluation-*/`，包括 `report.json`、通知原文、临时配置和子进程日志。报告中的 p99 是评测客户端经测试 HTTP 入口看到的耗时。故障至通知时延包含规则等待和接收端投递。此单机评测验证告警链路，跨主机故障和生产告警时延需在目标环境另测。单套 Compose 依赖同一个 Prometheus 和 Alertmanager 投递通知，监控链自身的故障需要独立监控来发现。

2026-11-23 本机快速评测六个场景通过。停一个 Provider 后 31/31 次只读调用成功，Prometheus 观测到 8 次重试，Warning 送达耗时约 8.7 秒。双 Provider 停止后，零流量探针 Critical 约 3.9 秒送达。恢复后，采集覆盖、探针失败和逻辑失败率三条告警均收到 Resolved。这些时间来自快速评测的临时窗口。
