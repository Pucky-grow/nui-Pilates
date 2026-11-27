# Round3 业务环境升级与回滚手册（准备稿，未执行业务升级）

本手册中的业务环境操作须由环境负责人审阅、授权后执行。本轮实际写入仅发生在运行器创建的临时实例；没有业务库连接、预检查结果、备份或迁移记录。禁止根据隔离测试推断业务数据正常。

## 1. 版本、前提与停止条件

- 原部署只声明 `mysql:8.0`，无法确定其实际小版本或摘要。Round3 将配置固定为 `scripts/round3/mysql-baseline.json` 中的 MySQL **8.0.46** 和镜像索引摘要；这是一份待执行的目标配置，不是已经部署的版本。
- 实际演练为 macOS arm64 原生 MySQL Community Server 8.0.46。Linux 镜像未运行；部署平台须再次按同一基线验证大小写、时区、字符集、挂载权限及健康检查。不要自动升级正在运行的 MySQL 服务。
- `utf8mb4 / utf8mb4_general_ci`；`REPEATABLE-READ`；数据库会话 `+08:00`，业务时区 `Asia/Shanghai`。完整 sql_mode 和摘要见基线文件。保留原库版本/变量快照以判断是否需要独立的数据库产品升级。
- 当前声明的应用版本仍为 Maven `1.0.0`，必须连同 `round3-verification.json` 的源码 SHA256 标识交付；版本号本身不能区分 Round2/Round3。

| 应用与结构组合 | 结论 |
|---|---|
| Round3 + 新有效预约约束 + MySQL 8.0.46 | 原生隔离实例实际通过；目标容器平台还需确认 |
| Round3 + 旧 `uk_user_schedule` | 不支持取消后多次重约，禁止开放预约写入 |
| 原 Round2 + 新约束 | 不能作为自动安全回滚基线；会重新引入本轮修复的 Mock/短信/收入字段问题。须先回补修复并独立验收 |
| 更早应用 + 新约束 | 未验证；不得与新实例混合写入 |
| 旧结构恢复且存在重复取消历史 | 旧唯一键无法无损恢复，停止结构回滚 |

以下任一项出现即停止：目标身份/版本不符；只读规则出现未解释异常；备份恢复未验证；旧写入实例/任务/消费者仍活动；权限增量无法审核；迁移报错或产生非预期警告；迁移后人数/索引/权限不一致；Redis 校准不一致；有效 HTTPS/域名/应用配置缺失；微信端验收未完成。

## 2. 业务库只读核查（尚未执行）

先由负责人明确提供**只读**连接，确认主机、端口、数据库及账号权限；凭据存放在权限为 0600 的客户端配置中，不放命令行、报告或聊天。以下都是待执行示例：

```bash
mysql --defaults-file=/secure/readonly.cnf --database=CONFIRMED_DB \
  < database/check_round3_readonly.sql > /secure/round3-precheck.txt
mysql --defaults-file=/secure/readonly.cnf --database=CONFIRMED_DB \
  < database/check_round2_rollback.sql > /secure/round3-rollback-precheck.txt
```

Round3 查询只输出配置、结构、权限代码及异常数量，不输出会员资料。原 Round2 预检查包含行级标识，本轮业务验收优先使用 Round3 版本。回滚查询结果含历史关联标识时只由数据负责人保存在受控位置，对外报告数量。

审核项目：

1. `VERSION()`、`@@version_comment`、实际字符集/排序规则/sql_mode/隔离级别/时区；`SHOW CREATE TABLE booking` 和全部索引。
2. 重复有效预约、人数不一致/越界/负数、取消课程残留预约、孤儿/逻辑删除关联、非规范状态及删除标志、出勤缺失/重复/身份不匹配。
3. 未来排课缺少资格、异常时间/教室容量；NORMAL 会员等级前置数据。
4. 角色是否启用、原授权全集、孤立映射、软删除的目标权限/映射、STAFF/COACH 财务授权。已有越权授权不能由增量脚本默默保留为“通过”，须先报告和单独批准处置。
5. 保存 `booking.paymentMode` 等原配置。已有 PACKAGE 不得由迁移改成 FREE；本项目运营目标为 FREE 时另行审核配置变更。
6. 旧唯一键回滚冲突数量单独记录。它在新库多次取消后可以大于零，表示**禁止结构回滚**，不能当作允许删历史的理由。

`booking.status` 是 VARCHAR，数据库没有枚举 CHECK；当前占位定义是未删除的 BOOKED、SIGNED_IN、COMPLETED、NO_SHOW。其他当前不可处理的状态（包括历史 PENDING）、大小写异常或空值必须单独报告、暂停升级，不能自动改为 CANCELLED。

历史异常处理单独形成工单：规则、数量、拟核对依据、负责人、批准动作、恢复办法。只读排查不自动补人数、返课时、删预约、修关联或授资格。

## 3. 维护窗口、备份与迁移顺序

1. 确认目标镜像、应用源码清单、配置、微信验收及权限差异都已通过审核。保存旧应用包/配置/静态资源和完整版本清单。
2. 暂停预约、取消、签到、排课/容量/资格及相关审批写入；停止**全部旧后端实例**、定时任务和预约相关 MQ 消费者。不能只隐藏按钮或只停一个实例。核实在途请求/事务已结束，保留故障日志和消息偏移。
3. 有存量支付/退款在途时，先由原业务负责人安排必要处理及回调重试窗口；不得吞掉回调、删除路由或伪造成功。无法保证保留账务处理时停止升级。本轮不触发真实交易。
4. 停写后做完整备份：业务 schema 全表（含 booking、attendance、课时流水、会员、订单/支付/退款、RBAC、配置）、触发器/事件/过程，另存数据库账号授权、binlog/GTID 坐标及保留策略。备份上传文件和应用配置的恢复材料；密钥只进受控凭据备份，不进入验收报告。
5. 用独立实例验证备份可恢复：表/索引/生成列元数据、关键表数量、按主键分段校验、预约/出勤/课时/交易关联及角色映射。记录备份时点 T0。未验证恢复成功就不能继续。
6. 再执行一次只读预检查。仅在旧键存在、新列/新键不存在、数据及权限前置条件均满足时执行预约 DDL：

```bash
mysql --defaults-file=/secure/migration.cnf --database=CONFIRMED_DB \
  < database/migration_round2_booking.sql
```

MySQL 8 的这条 ALTER 是原子 DDL，但有隐式提交，不能用外层事务撤销。不要使用 `--force`。重复执行在本轮 8.0.46 上报 **1091**，结构保持不变；这是明确停止信号，须核实实际结构后跳过，不能盲重试。

7. 审阅差异后执行最小权限 DML：

```bash
mysql --defaults-file=/secure/migration.cnf --database=CONFIRMED_DB \
  < database/migration_round2_permissions.sql
```

该脚本使用事务、保留已有授权，不给账号分配 ADMIN 角色，不给 STAFF 新增排课写权限，只向启用且未删除的指定角色增量授权。软删除映射/权限须由前置核查阻断，不能靠 INSERT IGNORE 当成恢复成功。比较迁移前后的 `sys_user_role` 与角色权限全集；只允许已审核的增量。

8. 新库路径只运行当前 `schema.sql`、`init_data.sql`。schema 已含新约束，init 已含相应权限；**不再执行预约升级 DDL，也不盲跑历史 migration 文件**。init 包含演示课程/排课和初始化账号，仅用于已授权的新环境；不得导入现有业务库。
9. 启动一个新版本实例，先保持写入关闭、定时任务及消费者受控。确认生产没有开发 profile，Mock 开关均关闭，售卡关闭，未配置短信能力拒绝请求，JWT/微信/域名等生产配置由负责人核实。

## 4. 迁移后检查与 Redis 校准

- 重跑只读检查。确认旧键消失、新 STORED 生成列及唯一键存在；所有历史 ID/关联保留，四种占位状态与逻辑删除语义正确。
- 核对角色停用/撤权：本轮授权每次读数据库，旧 RBAC Redis 缓存不会继续授权。不得清空其他应用缓存。
- 先比对 `booked_count` 与四种状态的实际占位数；不一致时停止，校准脚本不会修 MySQL。
- 保持**所有写入及后台任务停止**，确认应用 Redis 的主机、数据库号及 `pilates:stock:` 命名空间归属。以下操作脚本默认 dry-run；Redis 密码由安全环境变量 `REDISCLI_AUTH` 注入，不写入 shell 历史。

```bash
python3 scripts/round3/reconcile-stock.py \
  --mysql-client /reviewed/mysql/bin/mysql --defaults-file /secure/readonly.cnf \
  --database CONFIRMED_DB --expected-version 8.0.46 \
  --redis-host CONFIRMED_REDIS --redis-port CONFIRMED_PORT --redis-db CONFIRMED_DB_NUMBER
```

审阅 dry-run 的异常数、SET/DEL 数量后，负责人确认停写，才在相同命令末尾添加 `--apply-paused-writes`。仅 SET/DEL 已存在排课 ID 对应的精确库存键，最终逐键 GET 校验；不调用 FLUSHDB/FLUSHALL、不做通配清理。取消/结束/已删除课程库存应不存在，待上课程库存等于容量减实际占位数。发生中途错误保持停写，查明目标后重跑可推导缓存校准。

运行中的恢复由 `StockService.reconcileAll` 按主键分批遍历、每节获取同一 Redisson 锁；包含取消和结束课程，不再受原 LIMIT 1000 截断。正常写入在事务提交后校准。缓存写入失败不谎报数据库回滚；数据库是人数和可约状态的依据。全量操作脚本只用于停写窗口，不代替运行中的分布式锁。

经授权使用专用验收账号做必要烟测，核对数据库/Redis/会员刷新三方结果。未通过微信验收或未解释权限/人数差异时保持关闭；通过后再逐项恢复任务、消费者及写入。恢复时间、操作人和最终检查另行留档。

## 5. 回滚决策与新增数据保留

| 回滚方式 | 适用条件与动作 |
|---|---|
| 应用回滚 | 只能回到已验证兼容新结构且保留安全修复的版本；通常保留新结构和全部新预约。禁止直接恢复早期异步取消/旧 ID 逻辑 |
| 结构回滚 | 停写、完整备份、只读回滚检查零冲突、应用适配已验证；不能有同用户/排课/deleted 多条历史。保持 bookingId、attendance、课时流水原样 |
| 备份恢复 | 用独立实例恢复 T0 并验证；不直接覆盖当前库。先保存升级后库 T1、binlog、消息位置及新增/更新预约、出勤、课时、交易数据，再决定核对与重放方案 |

结构回滚的候选 DDL 仅供单独审核，不在本轮执行：

```sql
ALTER TABLE booking
  ADD UNIQUE KEY uk_user_schedule(user_id,schedule_id,deleted),
  DROP INDEX uk_active_user_schedule,
  DROP COLUMN active_user_id;
```

出现 1062 必须停止，不能删预约、软删历史或改 bookingId 来重建旧键。本轮隔离演练已证明多次取消历史会使旧键恢复失败；合法单历史记录路径可回退且保留数据。

一旦升级后开放写入，T0 恢复会丢失新数据。应在隔离环境按 booking.id/booking_no、状态变更时间、attendance.booking_id、课时业务键及支付业务键核对 T0→T1 增量；保留原标识和幂等关系。未经审核不能把新预约直接导入旧唯一结构，不能把真实支付事件重新触发。不能证明无损时优先保留新结构并向前修复。

权限回退只撤销经备份证明“本次新增”的映射；不按角色清空权限，不删除原授权。配置恢复原值后核对配置缓存。数据库结构/数据恢复完成后，再按上一节校准本应用库存，禁止清空共享 Redis。
