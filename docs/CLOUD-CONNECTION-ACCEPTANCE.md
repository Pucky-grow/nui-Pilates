# 真实 COS 与云 MySQL 应用连接验收

更新：2026-11-02，Asia/Shanghai。**最新：完整云后端的 8 组真实 HTTPS 检查已通过，包含管理员登录、Redis 会话撤销、管理查询及 COS 应用读写/私图限制。云 MySQL JDBC 已通过限定账号只读预检，使用用户确认的隔离测试非 TLS 例外。** 原 `VERIFY_IDENTITY` 路线仍未通过，不能作为生产方案。预约/签到云端联调和微信设备验收仍待验证；未发布小程序。当前结论见 [部署操作单](CLOUDRUN-TEST-DEPLOYMENT.md) 和 [机器状态](cloudrun-test-deployment-verification.json)，下文保留独立连接探针阶段的历史证据。

当前机器记录：[cloud-connection-verification.json](cloud-connection-verification.json)。原云库结构初始化证据保留在 [历史记录](evidence/cloudrun/cloud-mysql8-initialization.json)，不重复累计为应用联调覆盖。

## 已完成的真实验收与边界

| 验证类型 | 环境与实际结果 | 能证明的范围 |
|---|---|---|
| COS 控制台/权限核查 | 主账号 `100053657418`；上海新桶 `yoyi-pilates-test-1505023965`，私有读写、SSE-COS、`environment=test` | 专用测试资源的配置观察；旧广州桶 `uni-1505023965` 保持原状，未推断其用途 |
| COS 专用身份配置 | 用户完成创建 `yoyi-cos-probe-test`；控制台观察到直接关联 `YoyiPilatesCosProbeTest` / `292186436`，无附加策略 | 仅 GetBucketACL 和 `objects/acceptance/*` 的 PutObject/GetObject；无删除、公开 ACL 或管理权限 |
| 真实 COS 只读预检 | 2026-11-01，Mac + 已审核 JAR 的真实 provider/SDK，经 HTTPS 核对桶 owner UIN 和 owner-only ACL，通过 | 桶归属和本次配置具备必要 API 权限；ACL Owner 不是 caller principal 身份接口 |
| 真实 COS 写读 | 同环境，69 字节合成 PNG 上传/下载，字节及 SHA256 一致；匿名 HTTPS 403，通过 | 独立 SDK 链路，不能代替部署后端、会员文件授权、云容器或微信验收 |
| 本轮 DMC 只读复核 | 重新登录后目标版本/UUID、三条 DONE、58 表、InnoDB/utf8mb4、FREE 和严格模式均符合 | 当前云库身份、指定元数据和迁移记录正常；没有运行 Java 驱动或并发业务测试 |
| 本轮 TLS 元数据及客服回复 | `have_ssl=DISABLED`、CA/证书配置为空，DMC cipher 为空；13:43:03 客服明确内置 MySQL 不支持开启 SSL | 当前内置库不能满足本次 JDBC `VERIFY_IDENTITY` 要求；未证明有平台 TLS 代理 |
| 实际 Java JDBC | **未执行 / 阻塞** | 不计为连接失败或通过；受控私网执行位置、TLS 和运行账号尚不齐备 |
| 完整后端 / 微信开发者工具 / 真机 | 未执行 | 均未验收通过 |

用户授权创建新上海桶及限定测试前缀的程序身份权限。用户自行完成验证码、最终凭据生成，并将凭据填入本机受保护配置；代理没有生成、打印或回传密钥。控制台观察和 SDK 成功不能单独证明所填密钥的 caller principal，机器记录保留此限制。

两份真实 COS 报告：

- [只读预检](evidence/cloudrun/connection-acceptance/cos-live-preflight-20261101-01.json)
- [真实写读与匿名 403](evidence/cloudrun/connection-acceptance/cos-live-write-read-20261101-01.json)

唯一测试对象保留在 `objects/acceptance/cos-20261101T155545Z-73b25ece9c624ce6853f18ac954904da.png`，69 字节，SHA256 `77c0c5fab564cc808b0d50323b189f613de77e881f62ba9635311749c9735336`。没有删除对象或重复上传。新桶创建时为 0 对象，不能继续把这一创建前计数当作当前计数。

现有 CAM 策略文件 `deploy/wechat-cloudrun/cos-probe-policy.draft.json` 已实际应用并绑定，文件名中的 draft 不表示尚未创建。其权限仅适用于探针前缀；完整应用使用 `objects/*` 时需单独审阅运行身份权限，不能直接宣称完整应用存储已配置。

## 本轮 MySQL 实测

目标是上海 `uni-test` 的 `cynosdbmysql-41yi5vim` / `uni_pilates_test`。截图确认内网地址 `10.39.100.38:3306`、公网关闭、自动暂停 10 分钟。未更改自动暂停、网络或数据库配置。

旧 DMC 会话的查询曾在 connect 阶段 ETIMEDOUT（10001ms；11 月 2 日重试 10004ms）。用户用原密码重新登录正确实例后，新会话恢复查询。**根因未定**；不把恢复归因于已证明的休眠、密码过期或网络故障，也不把旧失败覆盖掉。

| 本轮查询 | 实际值 / 耗时 |
|---|---|
| 身份 SELECT | `uni_pilates_test`；`8.0.30-cynos-3.1.19.002`；注释 `20230630`；UUID `d1f6adf9-d5d5-11f1-9afc-0c42a17335be`；会话 `+08:00`；33ms |
| schema_history | V001/V002/V003 均 DONE；校验和与原迁移一致；步数 55/24/5；8ms |
| 聚合只读查询 | 58 张基础表；非 InnoDB/非 utf8mb4 异常表 0；未 DONE 迁移 0；FREE；`sys_user` 总数 0；命名为 `uni_pilates_runtime` 的数据库账号数 0；33ms |
| 会话 SQL/隔离 | `IGNORE_SPACE,ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION`；`READ-COMMITTED` |
| TLS 变量 | `have_ssl=DISABLED`、`require_secure_transport=OFF`、`ssl_ca=''`、`ssl_cert=''`、`tls_version=TLSv1.2,TLSv1.3`；9ms |
| DMC Ssl_cipher | 空；33ms；仅属于 DMC 服务端会话 |

`sys_user` 查询统计全部管理账号；原实际执行 SQL 的别名 `administrator_count` 不代表按 ADMIN 角色过滤。指定 runtime 账号计数为 0 也不能推断所有其他命名账号均不存在。本次没有创建账号、改授权、初始化管理员、迁移或写入业务数据。

最新独立证据：[DMC 复核记录](evidence/cloudrun/connection-acceptance/mysql-dmc-readonly-20261102.json)、[身份图](evidence/cloudrun/connection-acceptance/dmc-restored-identity-20261102.jpg)、[TLS 结果图](evidence/cloudrun/connection-acceptance/dmc-tls-disabled-20261102.jpg)。可复用只读入口是 [database-connection-readonly.sql](../deploy/wechat-cloudrun/database-connection-readonly.sql)，先执行身份语句，核对一致后再逐条执行其余查询；不得在错误目标上继续。

## 当前阻断与执行顺序

1. **审阅数据库接入路线。** 用户自行提交的 [工单](https://console.cloud.tencent.com/workorder/detail?ticketId=202611028033) 已明确内置 MySQL 不支持开启 SSL，[原文证据](evidence/cloudrun/connection-acceptance/vendor-mysql-no-ssl-20261102.txt)已保存，不继续寻找该内置库的 SSL 开关。保留当前 TLS 验收标准时，需选择支持 TLS 的独立数据库并核查实际证书身份和私网路径；如要继续利用内置库，需单独审阅仅内网访问的隔离、最小权限、凭据保护及剩余明文风险，明确批准后再设计不同验收入口，不能把原 TLS 项目标为通过。本次没有更改 `sslMode`、关闭证书验证、购买资源或迁移。`VERIFY_IDENTITY` 是当前模板与探针的验收标准，不是微信小程序强制要求，也不是现有 EnvironmentGuard/DatabaseGuard 必然拒绝所有非 TLS 连接的源码结论。
2. **确认受控私网执行位置。** 当前目标 VPC `vpc-72qgp3wk` 观察到 1 子网，VPN/NAT 网关和云服务器均为 0；没有已确认的可复用私网执行通道。绑定 VPC 不自动证明可达微信托管数据库的 `10.39.*` 地址。需核实平台运行阶段的受支持方式；不把数据库连接放入 Docker 构建，不为此开启公网、购买服务器或部署可能反复重启的一次性探针服务。
3. **受限运行账号单独配置。** 在前两项具备可执行方案后，再审核创建 `uni_pilates_runtime` 的具体范围。仅本库 57 张业务表 DML、schema_history SELECT，无全局/DDL/授权/角色权限；本次未执行。现有 DatabaseTool 的 provision 同时创建 runtime 与 migrator，不能为只读 JDBC 验收直接调用而附带创建迁移账号。凭据生成/填写由用户在受保护位置完成，不放聊天、截图、SQL 示例或命令参数。
4. **执行真实 JDBC 探针。** 使用下方入口、审核过的 JAR/驱动及受限账号，在可达目标的受控位置执行，输出新的无秘密报告。必须通过 UUID、TLS 身份验证、模式/时区、58 表/迁移哈希和最小权限检查。失败时按阶段停止，不自动修数据或放宽条件。
5. **再进入完整后端与微信联调。** runtime 账号、应用 COS 权限、Redis、微信配置与平台健康检查齐备后，才安排真实 HTTP、预约/签到一致性和开发者工具/真机验收。独立 JDBC 探针本身不依赖 Redis；本阶段无需先购买 Redis。

## 受保护配置与可重复入口

配置位于 `.secrets/cloud-acceptance/`，目录 0700、文件 0600，已被 Git 忽略：

```text
cos.properties             # 用户已填写；有真实秘密，不读取到输出
mysql-runtime.properties   # 已填目标地址/库/用户名；尚无可用运行账号凭据
```

非秘密模板：`deploy/wechat-cloudrun/cos-acceptance.properties.example` 和 `mysql-acceptance.properties.example`。模板和上传 ZIP 不含真实秘密。当前 MySQL URL 使用实际 IP 与 `sslMode=VERIFY_IDENTITY`；其证书 IP SAN 尚未验证，该配置不是连接通过证明。

`scripts/cloudrun/connection_accept.py` 验证上传包和 JAR 哈希，使用 JAR 内实际 provider/SDK/JDBC 驱动编译外部探针，不启动 Spring、Redis、定时任务或完整后端，不改动已通过的 AMD64 镜像。报告必须是新的路径，已有证据不覆盖；原 SDK/JDBC 输出不回显或持久化。

```bash
# 离线安全检查；已完成 COS 37 条、MySQL 59 条断言与包装器 35 个用例。
# 断言数与用例数分开记录，不累加为业务覆盖；已有充分证据不重复跑。
python3 scripts/cloudrun/connection_accept.py --service mysql \
  --bundle build-artifacts/cloudrun-prep-42e8696c7182 \
  --java-home /ABSOLUTE/INSTALLED_JDK17_OR_21 \
  --offline-check --report /ABSOLUTE/NEW_MYSQL_OFFLINE.json

# 真实 COS 已按此入口完成；无需为了本次 MySQL 诊断重复写读。
python3 scripts/cloudrun/connection_accept.py --service cos \
  --bundle build-artifacts/cloudrun-prep-42e8696c7182 \
  --java-home /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  --config .secrets/cloud-acceptance/cos.properties \
  --confirm-test-bucket yoyi-pilates-test-1505023965 --confirm-region ap-shanghai \
  --mode preflight --report /ABSOLUTE/NEW_COS_PREFLIGHT.json

# 仅在 TLS、账号与私网执行位置齐备后运行；当前不具备执行条件。
python3 scripts/cloudrun/connection_accept.py --service mysql \
  --bundle build-artifacts/cloudrun-prep-42e8696c7182 \
  --java-home /ABSOLUTE/INSTALLED_JDK17_OR_21 \
  --config /SECURE/mysql-runtime.properties \
  --expect-server-uuid d1f6adf9-d5d5-11f1-9afc-0c42a17335be \
  --confirm-isolated-database uni_pilates_test \
  --report /ABSOLUTE/NEW_MYSQL_LIVE.json
```

MySQL 探针仅设置本连接只读与时区，不迁移、建账号或写业务数据。通过要求包括 TLS 1.2/1.3、非空 cipher、`VERIFY_IDENTITY`、目标 UUID/库/完整版本、非 root、严格 SQL、`+08:00` 会话、58 表、三个 DONE 的校验和/55-24-5步数、FREE 和逐表最小权限。

COS 探针的 `write-read` 会留下唯一的有效合成 PNG，先记录 key 后执行，不自动删除。实际证据与离线测试分开，DMC/SDK/JDBC/完整 HTTP/微信工具/真机各自记录状态。

外置候选的能力仍需区别产品核实：[独立 TDSQL-C MySQL 官方 SSL 文档](https://cloud.tencent.com/document/product/1003/109673)说明支持开启 SSL 和下载 CA，但不适用于当前微信托管内置库。该文档还提示 Serverless 暂停后的首次严格 SSL 连接可能失败；若采用该候选，必须单独验证恢复/重试和 `VERIFY_IDENTITY`，不照抄降级到 PREFERRED 的建议，也不假定最便宜规格即可满足全部要求。当前未核报价，未选购任何替代实例。

本次只新增/更新证据、只读复查 SQL 和文档，未修改运行代码或不可变 JAR/ZIP/镜像。文档链接、9 份 DMC 取证文件哈希、3 份迁移哈希、2 份 COS live 报告哈希和只读 SQL 范围检查通过；源码模式扫描 852 文件，已识别模式发现数 0。该扫描不是完整秘密审计，亦不计为业务测试。见 [证据一致性记录](evidence/cloudrun/connection-acceptance/metadata-document-verification-20261102.json)。
