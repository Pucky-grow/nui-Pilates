# 内网监控入口

配置按 Prometheus 语法编写。v3.13.4 LTS 候选用于无外网的语法/启动验证，但最终扫描检出 10 HIGH，v3.15.0 候选也仍有 8 HIGH，均不批准部署；摘要和拒绝理由记录在证据中。必须先取得已修复并扫描通过的镜像或经营方批准的托管监控方案。Compose 故意不提供可直接启用的镜像默认值，要求 `PROMETHEUS_IMAGE` 为已审核的不可变摘要。版本依据：[官方发布](https://prometheus.io/download/)。没有配置 Alertmanager、remote_write 或外部接收人，不会向会员或其他人发消息。

以下为后续操作入口，须先解决监控制品安全阻断，再在已获准的目标 Compose 项目内启用：

```bash
docker compose --env-file /SECURE/staging-compose.env \
  -f deploy/docker-compose.yml -f deploy/docker-compose.monitoring.yml \
  --profile monitoring config
docker compose --env-file /SECURE/staging-compose.env \
  -f deploy/docker-compose.yml -f deploy/docker-compose.monitoring.yml \
  --profile monitoring up -d prometheus
```

服务使用同一内部网络抓取 `server:9091`，不发布 9090 端口，存储保留 7 天 / 512MB。只读根目录、非 root、限制内存/CPU、轮转日志；操作人须按真实磁盘与业务量调整保留期。不要通过 Nginx 公开 Prometheus 或 actuator。

校验入口（容器内）为 `promtool check config /etc/prometheus/prometheus.yml`、`promtool check rules /etc/prometheus/alerts.yml`、`promtool check healthy --url=http://127.0.0.1:9090`。本轮已实际校验规则并启动无外网的隔离 Prometheus；应用指标另在真实 Linux 后端抓取成功。该结果不证明云端已有持续监控、告警路由或值守。

HTTP/业务错误、延迟、Hikari 连接池、任务、outbox 积压/失败、人数差异及磁盘规则见 alerts.yml。Redis/数据库可用性以 readiness 为准，依赖故障不使 liveness 失败。证书、加密备份年龄及磁盘的只读巡检：

```bash
python3 scripts/production/ops-check.py \
  --internal-base http://APP_INTERNAL:9091 --data-directory /DATA \
  --backup-manifest /SECURE/backup/manifest.json --tls-host TEST_HOST
```

巡检进程失败应接入经营方批准的值守通道；缺少接收人时只输出结果并返回非零，不自动向外发送。云端必须实测目标抓取、触发和恢复、证书续期与备份失败告警，确认频率、保留及责任人。
