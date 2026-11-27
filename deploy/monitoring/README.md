# 内网监控入口

监控覆盖层通过 Prometheus 抓取应用指标，经 Alertmanager 将触发和恢复通知发送到值守 Webhook。配置 `PROMETHEUS_IMAGE` 和 `ALERTMANAGER_IMAGE` 的审核通过镜像摘要，并通过 `ALERT_WEBHOOK_URL_FILE` 提供独立文件。文件仅包含 HTTPS 接收地址，由 Alertmanager 的 UID 65534 读取。部署前应核对文件权限和接收方。告警接收配置见 [Alertmanager 官方文档](https://prometheus.io/docs/alerting/latest/configuration/#webhook_config)。

以下为后续操作入口，须先解决监控制品安全阻断，再在已获准的目标 Compose 项目内启用：

```bash
docker compose --env-file /SECURE/staging-compose.env \
  -f deploy/docker-compose.yml -f deploy/docker-compose.monitoring.yml \
  --profile monitoring config
docker compose --env-file /SECURE/staging-compose.env \
  -f deploy/docker-compose.yml -f deploy/docker-compose.monitoring.yml \
  --profile monitoring up -d prometheus alertmanager
```

Prometheus 和 Alertmanager 使用应用的私有 Compose 网络。Prometheus 抓取 `server:9091`，指标保留 7 天或达到 512MB 上限。两个服务均在容器网络内访问，使用只读根目录、非 root 用户和轮转日志。

本机规则验证命令在 `deploy/monitoring` 下运行。

```bash
promtool check rules alerts.yml
promtool test rules alerts.test.yml
amtool check-config alertmanager.yml
```

部署后从内网核对 Prometheus 抓取和 Alertmanager 可用性。接入值守通道时，在获准的测试服务制造一次指标故障并恢复，核对接收端分别收到触发和恢复事件。

生产和 staging 使用 outbox，抓取目标的 `event_delivery: outbox` 标签会检测指标缺失或查询失败。应用磁盘规则使用 Spring Boot 暴露的 `disk_free_bytes` 和 `disk_total_bytes`，监测其路径所在文件系统。宿主机上的其他磁盘可单独接入基础设施监控。Redis 和数据库可用性由 readiness 检查。证书、加密备份年龄及指定目录磁盘的只读巡检如下。

```bash
python3 scripts/production/ops-check.py \
  --internal-base http://APP_INTERNAL:9091 --data-directory /DATA \
  --backup-manifest /SECURE/backup/manifest.json --tls-host TEST_HOST
```

巡检进程失败应接入经营方批准的值守通道；缺少接收人时只输出结果并返回非零，不自动向外发送。云端必须实测目标抓取、触发和恢复、证书续期与备份失败告警，确认频率、保留及责任人。
