# 生产结构初始化、升级及恢复操作手册

在隔离实例验证迁移、恢复及目标版本后，按确认的维护窗口运行。

## 版本与兼容关系

| 版本 | 结构 | 运行条件 |
|---|---|---|
| Round2/3 旧应用 | 新有效预约索引，尚无生产扩展 | 不可假定其懂得 `auth_version` / outbox / 私有文件 |
| 当前应用 | V001 至 V003，启用台账另需 V004 | FREE 模式、最小权限运行账号、持久 outbox。启动会核对版本、权限和时区 |
| 未来结构 | 尚无兼容承诺 | 当前应用拒绝未知版本；先做候选制品往返测试 |

V001 是现有完整结构（含 Round2 有效预约约束），V002 仅基础角色权限、FREE 规则和模板。V003 增加凭据版本、私有文件、outbox 及通知唯一约束。V004 增加线下会员、余额、课时台账与相关权限。生产新库使用这套版本化 SQL 初始化。

MySQL 8.0.46 只保留历史兼容基线。8.0 已结束常规维护；云端版本需按 RDS 当期维护公告确认。MySQL 8.4 本地结果和云 RDS 内核验证分别记录。

## 先确认目标和只读预检查

1. 用安全终端取得只读连接，确认资源 ID、地域/VPC、库名、服务器 UUID、备份恢复目标；所有结果与执行人记录在受保护工单。无连接就不判断真实数据状态。
2. 客户端配置置于 Git 外的 0600 普通文件，不在命令行带口令：

```bash
mysql --defaults-file=/SECURE/readonly.cnf --database=EXPLICIT_DATABASE < database/check_production_readonly.sql
mysql --defaults-file=/SECURE/readonly.cnf --database=EXPLICIT_DATABASE < database/check_round2_rollback.sql
```

3. 核对产品/完整版本/参数、SHOW CREATE/索引、生成列、有效预约重复、人数与占位不符、签到缺失/异常、关联孤立、未来排课资格、权限映射及旧唯一索引回滚冲突。占位为 `deleted=0` 且 BOOKED/SIGNED_IN/COMPLETED/NO_SHOW；CANCELLED/REFUNDED 不占位。取消排课可用库存恒为 0。
4. 非零异常只报告规则和数量，不自动补资格、改人数、删预约、改权限或历史交易。逐类确定历史处理方案后重新审阅。演示账号名称命中只是待核查提示，不能据此自动删除真实账号。
5. 通知业务键重复必须先审核；V003 不会自动删除。缺列、未知结构、未知/脏版本、校验和改变都应停止。已有权限映射需备份并比较差异，禁止执行旧 `migration_account.sql` 的清空授权逻辑。

## 备份与停写窗口

- 暂停小程序预约入口及全部写 API，停止旧后端实例、定时任务和事件消费者。只改 DNS 不会停止旧实例；须核对进程/容器清单及连接来源。
- 若旧系统用了 RocketMQ，先核对并保留未完成消息、消费位点及业务效果，形成经审阅的导出/重放方案。不得直接删除 broker 卷或当作已迁移到 outbox。
- 备份业务库（包括权限、签到、预约历史、课包账务、文件记录、事件及版本表）、文件对象/OSS 版本、外部配置与制品摘要。配置秘密另行加密保管，不能进入仓库或镜像。
- 以下工具只读源库，要求显式服务器 UUID 与停写确认；`age` 接收方公钥不是秘密，解密私钥保存在独立安全位置：

```bash
python3 scripts/production/backup.py --mysql-bin /MYSQL/bin \
  --client-config /SECURE/backup.cnf --expect-server-uuid CONFIRMED_UUID \
  --database EXPLICIT_DATABASE --files /PRIVATE/upload-root \
  --recipient AGE_PUBLIC_RECIPIENT --output /SECURE/new-backup-directory --writes-paused
```

- 工具输出加密 SQL、文件归档和受保护的校验清单；前后聚合变化则失败。真实 OSS 备份还需对象清单/版本 ID、校验和及受控复制流程，不能把本地目录演练算作 OSS 恢复。
- 实际恢复到**另一隔离实例及新库/新目录**，不覆盖原库：

```bash
python3 scripts/production/restore-isolated.py --mysql-bin /MYSQL/bin \
  --client-config /SECURE/isolated-restore.cnf --expect-server-uuid RESTORE_UUID \
  --backup /SECURE/backup-directory --identity-file /SECURE/age-identity \
  --new-database NEW_EMPTY_NAME --new-files /PRIVATE/new-restore-root \
  --confirm-isolated-target --report /SECURE/restore-report.json
```

工具拒绝相同源实例、已有库/目录、损坏归档、越界/符号链接和文件引用校验不一致。失败保留隔离残留供检查，不自动 DROP。小合成数据的实测秒数不代表真实数据量的恢复时间；正式 RPO/RTO、保留期限及恢复操作人待经营方确认。

## 初始化与迁移

离线入口（无 Web 服务，不输出口令）：

```bash
java -Duser.timezone=Asia/Shanghai \
  -Dloader.main=com.yoyi.pilates.infra.tools.DatabaseTool \
  -cp /REVIEWED/pilates-server.jar org.springframework.boot.loader.launch.PropertiesLauncher \
  --config /SECURE/migrator.properties --expect-server-uuid CONFIRMED_UUID \
  --directory /REVIEWED/database/production --mode fresh
```

- 新库：由独立管理员预建空库，再用 `fresh` 顺序执行。MySQL 单条 DDL 原子；V002 种子用单事务和角色编码关联，不假定角色 ID 从 1 开始；迁移工具用会话 advisory lock、版本校验和和已完成步数追踪，拒绝重复并行执行。
- 未纳管的 Round3 旧库通过完整只读核对后，由操作人选择 `--mode adopt-round3`。工具核对表、列、预约和通知条件，将 V001/V002 标记 BASELINED，随后执行指定目录中的后续版本。更旧或不匹配的库先单独审核 Round2 升级。
- 已完成且校验和相同的版本会跳过。校验和变化时工具停止，应核对已执行的原版本化 SQL。
- 中断：默认拒绝 DIRTY。先逐条对照 SQL、information_schema 和 `schema_history.applied_steps` 确认状态。仅在操作人已确认记录步数与真实结构一致后用 `--mode resume-verified`。若 DDL 已提交但进度记录尚未提交，存在不确定窗口；必须人工审核、记录补正方案，不能盲目重试或删除历史。
- 迁移账号权限限定在指定库。`provision` 模式由另外的账号管理员执行，示例字段见 `deploy/database-tool.properties.example`：创建 scoped runtime / migrator；遇已有同名账号安全失败，不复用未知宽权限。生产 RDS 如不支持该方式，使用平台安全渠道创建普通账号并显式授权、核对 SHOW GRANTS。
- 运行账号的普通业务表权限为 SELECT/INSERT/UPDATE/DELETE。schema_history 仅 SELECT，studio_wallet_entry 和 studio_admin_audit 仅 SELECT/INSERT。启用台账前核对新增表的运行权限。迁移凭据由离线工具单独使用。
- `--mode bootstrap` 仅空后台账号表可用，用户名/姓名读受保护配置，口令由 stdin/控制台隐藏输入；至少 14 字符、最多 72 字节，BCrypt 12。已有账号就拒绝，不自动重置。

## 部署后检查与 Redis

1. 仍停写时启动一个新后端，核对结构历史、最小权限、会话严格 SQL 模式、会话 +08:00、JVM/容器 Asia/Shanghai、无 Mock、FREE、私有 OSS、关闭的渠道。交付镜像拒绝 dev/test 等 profile；原始 jar 离线工具不受该镜像开关影响。
2. 内网分别检查 liveness 和 readiness，外网不可访问 actuator。复查只读异常计数及权限差异。
3. 只校准目标排课对应的 `pilates:stock:{scheduleId}` 键，不清空共享 Redis。沿用 `scripts/round3/reconcile-stock.py` 的显式目标/只读预览和逐排课处理；执行前审核其参数，禁止 FLUSHDB/FLUSHALL。服务重建库存仅以已提交 DB 为准。
4. 先用合成测试账号做免费团课/私教预约、取消重约、签到、容量和整节取消；核对人数、四种占位状态、可用 Redis 库存及通知/outbox，不能只看返回码。
5. 确认旧后端、旧调度、旧 MQ 消费者全部停止后，按维护窗口恢复写入，继续监控错误、队列、库存、备份及 TLS。

## 回滚与停止条件

- **应用回滚**：仅切换经过当前结构往返验证的后端镜像摘要，保留数据库和文件。先停写并导出升级后新增/变化的预约 ID、状态、签到、权限、事件和文件引用。候选镜像必须是 Java 后端且懂得当前授权失效、outbox 与私有文件语义。不能直接退回 Round2/3 老二进制。
- **结构回滚**：不自动提供破坏性 down SQL。V003 可能已有文件/事件/新凭据版本，旧应用无法理解。优先前向修复。Round2 旧唯一索引在已有多次取消重约历史时无法重建，必须停止，禁止删历史强行回滚。
- **备份恢复**：恢复到新实例、核对后再讨论切换。旧备份缺少升级后的新增预约，不可直接覆盖；须保存并逐条对账、确定重放/人工处理及必要通知方案，再重新审批。
- 停止条件：任何非零异常未解释、版本/校验和不符、权限扩大、健康失败、库存不一致、恢复未通过、未知消息积压、真实微信/云服务未验。不要因接口暂时可登录就解除停写或发布。
