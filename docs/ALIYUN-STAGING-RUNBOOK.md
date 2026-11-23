# 阿里云隔离测试环境落地手册

使用 `deploy/aliyun/` 和 `deploy/docker-compose*.yml` 为已确认的隔离测试环境准备部署。记录目标资源、配置和镜像摘要。

## 开通前需要确认

| 项目 | 待提供的非秘密信息 |
|---|---|
| 账号与授权 | 目标账号/安全登录渠道、允许操作的测试资源范围、运维来源地址 |
| 地域、网络 | 地域/可用区、已有 VPC 或允许新建、CIDR 不重叠 |
| 预算 | 测试规格、流量/存储/备份预算、保留时间及停止/清理责任人 |
| 仓库 | 指定私有 Git 与 ACR/兼容私有镜像仓库；不自动创建公开仓库 |
| HTTPS | 已确认归属的测试域名、DNS 权限、备案与证书渠道 |
| 微信 | 已获授权的测试应用、平台操作权限、测试成员及 iOS/Android 设备 |
| 经营资料 | 主体、门店地址/电话、隐私联系人、保存/注销流程、RPO/RTO |

使用经确认的测试 AppID。密钥和口令通过受保护文件、云角色或安全终端注入。

## 推荐首发拓扑与边界

单 ECS：一个 Java 后端 + Web 静态服务 + HTTPS Nginx；同地域 VPC 内的 RDS MySQL、Tair/兼容 Redis、私有 OSS。数据库和缓存无公网连接，应用及监控端口不对外映射；运维入口限来源。首发为单后端实例，有维护停写窗口和单点故障限制，不宣称高可用。

外部请求 → 443 Nginx → 8080 后端；管理页面由内部静态容器提供。9091 仅内网探针/Prometheus。文件经后端授权代理到 OSS：公开素材有明确公开端点，会员文件短时签名，Bucket 默认私有并阻止公开访问。运行角色仅 GetObject/PutObject 到目标 Bucket 的 objects/ 前缀，不给删除/全 OSS 管理权限。

已验证本地 MySQL 8.0.46；其常规维护已结束。提供固定摘要 MySQL 8.4.11 LTS 的独立演练入口。选择云端版本前核对当期 RDS 可购内核、维护生命周期、升级限制、驱动及本项目实际测试，不能仅按“都是 MySQL 8”判定兼容。

官方依据： [MySQL 生命周期](https://www.mysql.com/support/eol-notice.html)、[ECS 安全组](https://help.aliyun.com/zh/ecs/user-guide/start-using-security-groups)、[ECS RAM 角色](https://help.aliyun.com/zh/ecs/user-guide/identity-and-access-security/)。

## 资源模板校验及费用

`deploy/aliyun/` 使用锁定的 aliyun/alicloud provider 2.0.0。先在无云凭据模式运行以下命令校验语法与 provider schema。

```bash
cd deploy/aliyun
tofu init -backend=false
tofu validate
# 之后填写经营方批准的非秘密参数；保持秘密不进入 tfvars/state。
# 账号、地域、预算明确后，才在受保护工作目录审阅 plan。
```

模板包含 VPC/VSwitch、安全组、ECS、RDS、Tair、私有版本化加密 OSS、RAM 最小策略；不包含密码、自动 DNS、正式小程序发布或支付配置。`rds_lifecycle_approved=false` 会阻止创建；占位参数不能直接 apply。ECS、数据库及 Bucket 有 prevent_destroy，清理需另行审批，不能强行删 state 绕过保护。

先从选定地域控制台取得规格与价格：ECS/系统盘、RDS/存储/备份、Tair、OSS 请求/版本/存储、出网、私有仓库及证书。无地域和预算时不报价、不创建计费资源。ECS 的公网出带宽为显式参数；为 0 时需已批准的 NAT/受控出网，不能假定它能访问微信、镜像仓库。无需 MQ 主机。

## 逐步部署（资源和授权确认后）

1. 记录资源 ID、规格、地域/VPC、内网地址、云端完整版本。只使用合成数据。运维网络只允许批准来源；443 测试来源须另行确认。RDS/Tair 白名单限制 ECS 内网地址，关闭其公网连接。
2. 核对 RDS `SELECT VERSION(),@@version_comment`、字符集 utf8mb4、排序规则、sql_mode、隔离级别、时区及 TLS。Tair 明确 ACL/密码、TLS 开关、证书与端口，验证脚本/锁支持和超时。不要把 8.0.46 本机结果写为 RDS 通过。
3. 通过受保护管理渠道创建新空测试库、独立迁移账号和最小运行账号。执行 [结构手册](PRODUCTION-UPGRADE-RUNBOOK.md) 的迁移及一次性管理员初始化；真实业务库不在此流程内。
4. OSS 私有、版本化和加密，附加最小 RAM 角色到 ECS；验证容器取到短期凭据及必要的 IMDS 安全设置。不因凭据失败而公开 Bucket 或降级为代码内置 AccessKey。长期凭据只有经批准并外部注入时才可用。
5. 将经本地验证的 **server/admin/edge 三个镜像**按不可变摘要推送到指定私有仓库，记录远端摘要与源制品映射。推送需已有授权；不覆盖旧 release 标签。不在 ECS 修改源码或临时 npm install。
6. Linux 秘密文件归属和权限：应用配置 `/etc/pilates/secrets/application.properties` 由 UID 10001 读取，0400；其父目录限制访问。TLS 私钥 root:101、0440，证书目录 root:101、0750。Compose 普通文件 secrets 不会自动替你修改 uid/mode，必须在主机检查。不要将实际配置加入 Git。
7. 依据 `deploy/application.properties.example` 填入配置：RDS hostname/CA 校验、Tair TLS、随机 JWT、真实测试微信 AppID/秘密、OSS 角色、同一公开 HTTPS origin、唯一受信代理 `172.29.88.10/32`。默认关闭手机号、短信、新购课、Mock 支付、候补和微信提醒。未验证的微信提醒在首发不承诺。
8. 审阅非秘密 Compose 参数并用 `docker compose config` 检查。内网默认模板只绑 loopback；需要批准的公网 HTTPS 时才叠加云入口覆盖：

```bash
docker compose --env-file /SECURE/staging-compose.env \
  -f deploy/docker-compose.yml -f deploy/docker-compose.aliyun.yml config
# SERVER_IMAGE / ADMIN_IMAGE / EDGE_IMAGE 均为已审核的私有仓库 @sha256。
# HTTPS_BIND_IP 为明确批准的主机接口；不能用随意开放所有端口替代安全组。
```

9. 逐项确认后启动指定测试环境，先检查内网 readiness/liveness，再验证 HTTPS 证书链、Nginx API、SPA 深链接、静态资源、上传限制、代理头及监控不外露。不要把依赖短暂故障的 readiness 失败作为无限重启依据。
10. 配置测试 DNS 与续证；验证到期告警。境内 API/网站域名备案及主体资料按平台当前规则核实，微信小程序备案由微信平台办理，不能混同于阿里云网站备案。依据：[阿里云 ICP 流程](https://help.aliyun.com/zh/icp-filing/basic-icp-service/user-guide/icp-filing-application-overview)、[平台备案范围](https://help.aliyun.com/zh/icp-filing/basic-icp-service/support/faq-about-icp-filing-preparations)。
11. 微信平台配置 request/uploadFile/downloadFile 合法域名及隐私指引，使用真实微信登录。不得关闭域名/证书校验冒充正式环境。按 [微信清单](WECHAT-STAGING-CHECKLIST.md) 进行测试账号验收。

## 云环境通过标准

记录每一项命令、时间、资源 ID、实际版本和数据对账；日志中不出现密码/Token/OpenID/手机号/签名链接。

- 干净初始化、最小权限、首次管理员、重置/停用撤销旧会话；ADMIN/STORE_MANAGER/STAFF/COACH 接口授权与会员身份隔离。
- 免费团课和私教预约、取消多次重约、旧 ID 隔离、签到、容量/整节取消；100 人抢 8 席、同会员跨课上限、并发和失败回滚。分别记录成功数、失败原因、人数、四种占位及 Redis 最终库存。
- RDS/Tair 的短时断连、认证/TLS、Redisson Lua/锁和连接恢复；外部不可访问数据库、缓存或监控。
- OSS 越权失败、5 分钟过期、公开素材、管理员与会员上传、容器重建后可读取；真实 OSS 备份与恢复。
- outbox 重试/幂等、失败人工重试、任务超单批、服务重启；仅站内信通过不能写成微信提醒通过。
- 真实 Web 及小程序工作台；微信工具、iOS、Android 分开记结果。候选制品回滚及隔离恢复须在云实际数据规模重演。

## 监控与资源保留

`deploy/monitoring/` 有内部 Prometheus 抓取与告警规则，`deploy/docker-compose.monitoring.yml` 接受已审核的监控镜像。`scripts/production/ops-check.py` 提供只读就绪、磁盘、备份和证书到期检查。指标覆盖 HTTP 和业务错误、延迟、连接池、积压和失败事件、任务结果及库存差异。在云环境配置并验收监控频率、告警接收人、备份计划和值守责任。

测试结束仅停止自己创建的服务；按批准的保留期决定快照、加密备份、Bucket 版本及资源清理。未明确授权不自动 destroy、删卷、删 OSS 或清共享 Redis。停 ECS 不代表 RDS/Tair/OSS/磁盘停止计费，需逐项核对账单和资源清单。
