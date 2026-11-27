# Round3 验收交付：预约一致性、MySQL 8 与真实联调

## 1. 本轮结论与范围

代码修复和已具备条件的隔离测试已完成。**原生 MySQL Community Server 8.0.46 实际通过，真实 HTTP/Web 联调实际通过；目标 Linux 容器镜像、微信开发者工具业务流程和真机仍待验证，不能认定全部验收或可以上线。**

没有业务库只读连接，因此本轮**连业务库预检查也尚未执行**；已交付只读查询和操作手册。没有业务库迁移、补数据、改权限、创建排课或清理，没有部署、发布小程序、真实会员通知或真实支付。

首次核查实际读取了指定的 Round2 文档、JSON、四份 SQL 及关联源码/配置/测试，另核对真实 JUnit XML。项目及已检查的祖先目录没有找到 AGENTS.md。本轮没有扩展周期排课界面、首页运营、前台代办、候补或支付退款功能；`rank` 转义仅修复现有取消事件查询的 MySQL 8 兼容错误，零元结算分支仅保留历史订单必要处理。

## 2. 实际环境与目标基线

| 项目 | 实际证据 / 范围 |
|---|---|
| 本机“26.7” | 在新建、禁用网络的临时实例读取 `VERSION()=26.7.0`、`@@version_comment=Homebrew`。它是该 Homebrew mysqld 的报告版本，不是 MySQL 8 小版本，不能作为 MySQL 8 验收 |
| 目标原配置 | `deploy/docker-compose.yml` 原为浮动 `mysql:8.0`，不能得知已部署小版本；本轮未连接既有服务器 |
| 本轮固定配置 | `mysql:8.0.46@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b`，摘要来自官方镜像注册表；未拉起容器 |
| 实测数据库 | Oracle 官方 macOS arm64 MySQL 8.0.46 压缩包；SQL 返回 `8.0.46 / MySQL Community Server - GPL`；独立临时 datadir/socket、随机回环端口、随机凭据，结束销毁 |
| 数据库变量 | utf8mb4 / utf8mb4_general_ci；REPEATABLE-READ；全局和会话时区 +08:00，门店业务 Asia/Shanghai；完整 sql_mode 见下文及 JSON |
| Redis | 最终使用独立原生 Redis **7.4.10**、随机回环端口/密码、DB15、禁用持久化；早期 8.10.1 结果只作补充，不代替 Redis7 证据 |
| 后端 | Java 21.0.12.1、Spring Boot 3.2.12、MyBatis-Plus 3.5.7、Redisson 3.32.0；JUnit 关闭定时任务、内存 MQ、开发 Mock；另启动两个独立 Java 进程共享该测试库/Redis并实际重启其中一个 |
| Web | Node 24.20.0、npm 11.19.0、Chrome 155.0.8059.40；真实 Vite 代理；源码编译的 Nginx 1.28.0、生产静态构建、仓库双层配置、临时回环 HTTPS 证书 |
| 微信工具 | 已安装版本 36.6.0，CLI 登录探测退出 246，登录状态未知；无已确认隔离测试 AppID/真实登录凭据/合法 HTTPS 域名，未执行业务流程 |
| 真机 | 本轮未使用，所有对应业务验收待验证 |

固定 sql_mode：`ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION`。

数据库下载与版本依据：[MySQL 官方下载](https://dev.mysql.com/downloads/mysql/8.0.html)；Redis 来源和校验：[官方发布说明](https://redis.io/docs/latest/operate/oss_and_stack/stack-with-enterprise/release-notes/redisce/redisce-7.4-release-notes/)、[官方散列记录](https://github.com/redis/redis-hashes)。实际 SQL 结果优先于下载页或 CLI 描述。镜像摘要、下载 SHA256 和固定变量保存在 `scripts/round3/mysql-baseline.json`。Linux 容器、Redis/Nginx 实际部署镜像及公开 HTTPS/域名仍须由部署平台确认，不能把本机原生运行说成容器已通过。

## 3. Round2 统计核实：不重复累计

| 原统计 | 实际来源与能够证明的范围 |
|---|---|
| 67 后端 | 13 份 `server/target-round2/surefire-reports/TEST-*.xml`，按 classname + 方法名去重，67 通过；不是 67 条不同业务规则 |
| 其中浏览器 | `WebSchedulingBrowserTest.realBrowserRealHttpFreeBookingAndSignin` 是上述 67 中的 **1** 项，内部有 5 个浏览器检查，不能再加 5 个后端测试 |
| 22 组件 | 9 页各空/失败态 18 项，加 4 项排课交互；真实 Chrome，但 axios HTTP 适配器是模拟；不证明后端授权或真实数据库 |
| 8 小程序 | `miniprogram/tests/booking-flow.test.cjs` 的 Node VM/Page 测试；不是真实 wx.login、开发者工具或真机 |
| 58 JS / 54 JSON | 语法文件数量，不是业务测试数量 |
| 7 迁移检查 | `scripts/check-mysql-migration.py` 的 7 个 cases 标签；引擎为 26.7.0。不是 7 个独立 MySQL8 集成测试 |

Round2 67 项作为历史证据保留，本轮未无意义地重跑所有首页等无关用例。本轮复用直接相关方法，在真正 MySQL8 上执行；新增缺失覆盖并回归变更涉及的支付/前端逻辑。**不能把 67、58、3、16、组件子检查加成一个“验收总数”。**

本轮实际分层：

- 59 个选定 JUnit 方法：**54 个实际 MySQL8 相关方法 + 5 个无数据库配置单测**，全部通过；54 中 44 为服务/MockMvc，10 为真实 TCP HTTP（9 个 Round3HttpTest + 1 个新库启动测试）。MockMvc 的真实 JWT 不等于真实网络。
- 另有 3 个 H2 支付回归通过：重复/并发模拟结算、历史零元结算；不作为 MySQL8 或真实支付证据。
- 两个独立 Java OS 进程的 4 组 HTTP/重启场景通过；与 JUnit 方法数分开记录。
- 13 个 SQL/迁移/备份检查通过；原始 MySQL 报告中的 JUnit/双进程阶段标签，不当作额外迁移用例。
- Chrome 真实联调：Vite 下 7 个业务子检查、生产式 Nginx 下真实登录/课表/静态资源/深链接与 API 检查通过；它们包含在上述 1 个浏览器 JUnit 方法中。另新增 3 个金额边界组件适配器检查，不能代替 HTTP 授权检查。
- 小程序 **16 个唯一 Node 逻辑方法**，UTC 和 America/Los_Angeles 下各通过一次，不计成 32；60 个 JS、54 个 JSON 语法通过。工具和真机状态仍是待验证。

## 4. 当前源码 → 测试 → 环境 → 结果

Java 路径以下按 `server/src/main/java/com/yoyi/pilates/` 计；测试见 `server/src/test/java/com/yoyi/pilates/`。原始结果、全部方法名、源码/证据 SHA256 在 `round3-verification.json`，53 个 fixture 方法还各有数据库人数、四状态占位、Redis 与并发失败原因快照。

| 要求 | 当前实现/约束 | 直接证据方法或入口 | 环境 / 结果 |
|---|---|---|---|
| P0-01 首屏异常 | 9 个 Vue 页的生命周期与状态；本轮金额界面调整 | Round2 22 组件证据；本轮 `round3-boundaries.js`、`realBrowserViteProxyAndProductionNginx` | 旧充分证据复用；新 3 组件检查及真实 Chrome/HTTP通过 |
| P0-02 主体空间隔离 | `SecurityUtils.requireMemberId`、`LoginUserLoaderImpl` | `MemberHttpBoundaryTest.realAdminJwtCannotReadOrMutateMemberNamespacesDespiteIdCollision`；`actualLoginFourRolesAndRevenueBoundary` | MySQL8 + MockMvc/真实HTTP通过；四种后台角色不能冒充相同 ID 的会员 |
| P0-03 可信授课/隐私 | `TrainingService`、`CoachStudentService` 不信任自造训练关系 | `MemberAndTrainingBoundaryTest` 的 3 个方法 | MySQL8通过；未放宽授权 |
| P0-04 新旧预约隔离 | `active_user_id` STORED + `uk_active_user_schedule`；取消保留历史、新 ID | SQL 演练；`repeatedRebookingRetainsHistoryAndOldIdsAreHarmless`、`oldBookingIdCannotSigninOrReplayCancellationIntoNewBooking` | MySQL8服务/HTTP通过；直接 SQL 第二有效记录被拒 |
| P0-05 提交与锁 | `ScheduleExecution.run` 取锁后开事务，提交后校准、最后解锁 | `scheduleLockCoversBeforeCommitAndAfterCompletion`；100 抢8、双进程场景 | 同步屏障证明 beforeCommit 阻塞时另一操作不能进入，afterCompletion 时仍持锁；人数/库存通过 |
| 条件更新行数 | `BookingTxService` incr/decr、状态推进及排课 update 均检查；签到 insert 检查 | `failedConditionalScheduleUpdateCannotRefundOrPublishSuccess`、`failedTransactionNeverPublishesUncommittedInventory` | MySQL8通过；失败不能退课或发布成功 |
| 签到原子/幂等 | `BookingService.checkInLocked` 状态和 attendance 同事务、唯一键，仅首次转换发事件 | `attendanceFailureRollsBackBookingStatus`、`signedInScheduleCannotBeCancelledAndDuplicateSigninIsIdempotent`、`concurrentRepeatedSigninEmitsOneEventAndAwardsOnlyOnce` | MySQL8通过；写入故障回滚，重复不增出勤 |
| 整节取消 | 同锁内同步取消 BOOKED，已有 SIGNED_IN 拒绝；旧取消事件不修改仍开课的预约 | `cancellationIsAtomicAndRedeliveryDoesNotRefundAgain`、`cancelAndSigninRaceHasOnlyConsistentOutcomes`、`bookingAndWholeCancellationRaceIsConsistent` | MySQL8通过；成功后不能预约/签到，失败原预约保留 |
| 容量 | `ScheduleService.updateWithAction` 对四状态实际人数设置下限、提交后校准 | `capacityExpansionAndFloorUseActualOccupiedCount`、`bookingAndShrinkRaceCannotExceedCapacity`、`twoInstanceShrinkAndCancellationRaces` | MySQL8/双HTTP实例通过；6不能缩5，8→10新增名额可用 |
| 资源冲突与入口一致 | 统一资源锁；单次/编辑/周期/调课审批共用校验 | `parallelOverlappingCreationOnlyOneWins`、`differentCoachesCannotShareOverlappingRoom`、`editingAlsoChecksConcurrentResourceConflicts`、`recurringPartialInsertAndApprovalConflictRollbackCompletely`、`overlappingHttpCreatesAcrossInstancesHaveOneWinner` | MySQL8/真实HTTP通过；周期部分插入回滚、审批冲突不推进状态 |
| 资格和撤权 | 单课程显式 assign/恢复；授权每请求读当前数据库 | 浏览器资格指派/停用/恢复；`revokedPermissionAndDisabledRoleRejectOnBothInstancesWithoutCacheEviction` | MySQL8/真实HTTP通过；旧 Redis 授权值不能继续授权 |
| 四状态人数口径 | BOOKED/SIGNED_IN/COMPLETED/NO_SHOW 都计占位/当日限额 | SQL生成列检查；`signedCompletedAndNoShowAllOccupyCapacityAndDailyLimit` | MySQL8通过；不是只数 BOOKED |
| 缓存与重启 | DB为准；失败后删除可疑键；全量按主键对账 | `cacheFailureAfterCommitDoesNotLieAboutDbAndNextWriteRecovers`、`failedCacheSynchronizationAndEvictionAfterCancellationRecoverByReconciliation`、`reconciliationDoesNotStarveRowsAfterTheOldThousandRowLimit`；OS进程重启、校准脚本 | MySQL8/Redis7通过；故障为可控注入，未做 Redis集群网络分区演练 |
| FREE 团课/私教 | 不要求课包、不建订单、不扣课时 | `freeGroupAndPrivateDoNotTouchTradeOrCredits`、`realHttpFreePrivateAndUnconfiguredCapabilities`；真实浏览器团课/多次重约/签到 | MySQL8真实HTTP通过；微信登录在此明确是开发 Mock，不算真实微信认证 |
| 时间边界 | `StudioTime` Asia/Shanghai，前后端共用公共可约规则 | `exactShanghaiDeadlineAndCheckinWindowBoundaries`；Node 周一/周日/跨月/跨年 | 后端精确边界、两宿主时区 Node通过；设备显示待验证 |
| 安全配置与历史处理 | 非开发禁 Mock；未配短信关闭；新购课受服务端开关控制；金额按角色返回 | `Round3ConfigurationTest` 5项；四角色真实HTTP；H2 3项；Node历史零元分支 | 自动化范围通过；保留真实回调及历史查询，没有真实第三方调用 |

## 5. 并发与故障的实际数字

占位口径：`booking.deleted=0 AND status IN ('BOOKED','SIGNED_IN','COMPLETED','NO_SHOW')`；取消、退款及已删除不占位。待上课程的 Redis 应为容量减占位；取消/结束时应无键。`booked_count` 与按上述定义计算的实际占位必须一致。

| 场景（实际某次观察；允许的先后顺序不止一种） | 成功 / 失败 | 最终数据库及缓存 |
|---|---|---|
| 两个 OS 进程、容量8、100不同会员HTTP抢位 | 8 / 92，失败全部为名额已满 | booked=8，占位=8，Redis=0 |
| 同会员取消后8个并发重约（服务集成） | 1 / 7，重复预约拒绝 | booked=1，占位=1，Redis=7 |
| 双 OS 进程的满班重约 | 1 / 7；本次失败为名额已满 | booked=8，占位=8，Redis=0；旧 ID 无效果 |
| 同会员跨6节课（无其他当日预约） | 3 / 3，当日上限 | 当日占位总数3；逐节人数/库存见快照 |
| 双 OS 进程跨6节课（已有1节） | 2 / 4，当日上限 | 当日总数3，原班 booked/占位=8，Redis=0 |
| 已约6人、再预约与缩到5并发 | 预约成功、缩容拒绝 | 扩到10后可补满；整节取消后 booked/占位=0，库存键删除 |
| 预约/整节取消 | 允许预约先提交后同步取消，或预约被取消状态拒绝 | 最终 CANCELLED、booked/占位=0，无库存键 |
| 签到/整节取消 | 两操作仅一个状态转换胜出；本次观测签到胜出，取消拒绝 | SIGNED_IN、attendance=1、booked/占位=1，Redis=7 |
| 同教练/同教室并发创建或编辑冲突 | 2个请求仅1个成功，另一个资源冲突 | 冲突课程没有插入/覆盖；周期与审批回滚不留半状态 |
| 16个并发重复签到 + 已处理事件顺序再投递 | 16个幂等应答，无失败，仅1次状态转换 | attendance=1、签到事件=1、积分流水=1；重投递后积分仍为5，booked/占位=1，Redis=7 |
| attendance故障、条件更新失败、事务故障 | 注入操作被拒；已提交通知故障不回滚业务 | 原预约/人数/出勤按事务断言核对，见对应方法快照 |
| 提交后缓存写入/删除均失败 | DB取消成功、故意留下旧库存 | 对账后取消库存删除；超过1000节的末尾也可恢复 |
| OS进程真正退出再启动，故意库存999 | DB读返回满班余位0；后续取消/重约成功 | 最终 booked=8、占位=8、Redis=0 |
| 操作手册库存校准 | dry-run未写；实际 SET5 / DEL2；逐键GET核对 | 7节全部一致，无关键保留；人数异常时0次Redis写入并停止 |

最终签到事件修复只复跑32个直接相关服务/HTTP方法，其他已通过的方法和双OS进程证据复用；统计按方法去重。

完整逐用例并发次数、失败代码/原因、各状态数量及多节快照保存在 JSON 和 `server/target-round3/case-evidence.jsonl`，不只看返回码。同步屏障用例在事务提交前刻意阻塞，缓存与其他连接仍只能看到已提交人数；故障注入与真实 HTTP/真实进程测试分开标记。

## 6. 修改文件与必要性

| 文件组 | 本轮发现的问题及修复 |
|---|---|
| `common/config/DevelopmentCapabilities.java`、`WxMpClient`、`PhoneController`、`application*.yml` | 非开发环境即使误开开关也拒绝 Mock；缺微信配置明确关闭；无真实短信实现时发码/绑定关闭，不返回验证码。开发能力仍可用于隔离联调 |
| `OrderService`、`PaymentService`、小程序 `order-detail` | 暂停售卡缺服务端控制；生产 Mock 入口（含零元绕过）收紧。历史零元订单由创建结算参数入口完成并让客户端刷新；回调、存量查询/取消及结算核心保留 |
| `StatsController`、`AdminTradeController`、Web `Dashboard/Orders/Login` | 前台/教练看板含收入，前台订单能获取金额；服务端剔除/白名单保留必要历史字段，界面相应隐藏，去掉登录页预填口令。ADMIN/店长原有金额查询保留 |
| `RbacService` | 原授权缓存可能使停用/撤权等待10分钟；当前请求读数据库，旧缓存不参与授权；仍保留精确缓存清理兼容方法 |
| `BookingService` | 重复签到仍重复发事件；现仅在首次成功状态转换提交后发布，16并发与重投递效果断言通过 |
| `StockService`、`PilatesScheduler` | 原对账只扫1000节且只处理待上课程；按主键分页、沿用同一锁、包含取消/结束清理，并逐节处理故障 |
| `WaitlistEntity` | 真正MySQL8日志发现未转义 RANK 语法错误；仅加字段转义，并补现有取消消费者查询回归 |
| `PilatesApplication` | JWT鉴权不需要 Spring 默认用户；禁用默认用户自动配置，避免测试日志输出自动生成口令 |
| 小程序 `app/config/environment/utils/request/coach-end/api/admin/api/project.config.json` | 原固定私网地址和Mock身份容易进入体验/生产；按环境选地址，非开发强制HTTPS和真实登录、缺地址拒绝；环境切换/退出清理身份，鉴权失败只重试一次、不递归登录 |
| `migration_round2_permissions.sql` | 原脚本未排除删除/停用角色；增加事务及启用/未删除过滤，不扩 STAFF/COACH 范围。预约DDL原语句保留；重复安全失败在8.0.46实测 |
| `check_round3_readonly.sql`、`deploy/docker-compose.yml/.env.example` | 只读核查补版本注释、变量、关联/出勤/权限前置条件和数量；固定MySQL镜像/变量，明确售卡与短信关闭；没有实际部署 |
| 相关 Java/Node/浏览器测试、`reset-mysql.sql`、`scripts/round3/*`、旧隔离运行器及浏览器入口 | 真实MySQL/Redis、事务屏障、双实例/双进程、恢复与财务边界覆盖；凭据使用私有文件/环境/CDP，不再放浏览器URL；编译在临时目录，避免工作区重复class污染 |

可审核源码 SHA256 列于机器报告。原 Round2 文档/JSON 和旧验收证据作为历史保留；本轮只对实际发现的缺陷及兼容问题修改代码。

## 7. 重现入口、升级手册与停止条件

本机需要 Java17/21、Maven依赖缓存、Node依赖、C编译工具及OpenSSL。运行器 `mvn -o` 缺缓存会安全失败，不回落到已有服务。工具准备从官方地址下载到临时目录，不启动或替换已有服务：

```bash
sh scripts/round3/bootstrap-macos.sh
PATH=/tmp/pilates-round3-tools/redis-7.4.10/src:$PATH \
PILATES_NGINX_BIN=/tmp/pilates-round3-tools/nginx/sbin/nginx \
python3 scripts/round3/accept.py \
  --mysql-bin /tmp/pilates-round3-tools/mysql-8.0.46-macos15-arm64/bin

PATH=/tmp/pilates-round3-tools/redis-7.4.10/src:$PATH python3 scripts/round3/supplement.py
TZ=UTC node --test miniprogram/tests/*.test.cjs
TZ=America/Los_Angeles node --test miniprogram/tests/*.test.cjs
node scripts/check-miniapp.cjs
python3 scripts/round3/collect.py
```

`--sql-only` 仅SQL演练，`--skip-processes` 会明确缩小验证范围；正式报告所依据的是默认完整入口。其他平台使用普通隔离测试用户提供同版本独立二进制目录，不接受已有数据库URL。运行器拥有全部可写端点，随机凭据、端口和数据库名；定时任务关闭，不连接部署Compose的业务卷。`reconcile-stock.py` 本身默认dry-run；应用校准需明确停写断言，详见手册，不在本轮业务环境执行。

- [升级/回滚逐步手册](ROUND3-UPGRADE-RUNBOOK.md)：只读核查、维护窗口、旧实例/任务停止、备份恢复、迁移顺序、精确库存校准、应用/结构/备份恢复区别、升级后增量数据保留。
- [微信开发工具/真机逐步清单](ROUND3-WECHAT-CHECKLIST.md)：W01–W17、T01–T07，每种设备分别记录，当前均待验证。
- [机器证据](round3-verification.json)：验证类型、环境、命令、结果、阻塞原因、方法名及SHA256。

演练中修正过测试入口问题：init 的 USE 目标、重复DDL实际错误码、随机代理端口CORS、缺NORMAL等级的测试准备、Redis探针数据库号；没有为通过测试放宽数据库/授权约束。恢复后的 SHOW CREATE 会增加冗余列字符集声明，现核对真实列/索引元数据与全部预约历史及权限映射，未把文本格式差异当结构损坏。

## 8. 最终状态与阻断项

| 最终问题 | 判定 |
|---|---|
| 代码与隔离测试完成？ | 本轮修复及可执行隔离验收完成，以上实际范围通过 |
| 目标MySQL8实际通过？ | **8.0.46原生实例通过**；目标Linux镜像/实际部署配置尚未实跑或确认 |
| 真实HTTP/Web联调通过？ | **通过**：真实测试后端、MySQL8/Redis7、Chrome、Vite和生产式Nginx；不等于已部署 |
| 微信开发工具 / 真机通过？ | **均未通过验收、均待验证**；未用浏览器/Node代替 |
| 真实业务库仅预检查？仍未迁移？ | **预检查尚未执行、仍未迁移**；没有明确只读连接，只交付清单 |
| 可以进入业务环境升级？ | 可进入人工审阅/准备；**尚不具备执行升级或上线条件** |

真实 RocketMQ 未启动：事件故障通过注入、重投递通过直接调用消费者验证，不能当作 Broker 投递/重试的端到端通过。部署平台演练需核实其消息偏移、旧消息兼容与受控恢复。

具体阻断：业务库实际版本/数据/权限与备份恢复前提未核查；目标容器平台未验证；测试应用/HTTPS合法域名/开发工具登录条件缺失；真实微信认证和完整开发工具流程未执行；真机未验收。解决后按手册重新判定，不用其他引擎、版本或模拟设备结果代替。
