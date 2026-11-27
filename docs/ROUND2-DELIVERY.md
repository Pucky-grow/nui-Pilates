# 第二轮交付：排课与免费预约

本轮依据当前源码实施，未连接、迁移或写入真实业务数据库，未生成真实排课，未发布部署。所有自动化写入发生在自建的临时 Redis、H2 内存库或专门创建的临时 MySQL 实例。历史评审的“已过期”“已通过测试”不作为本轮事实依据。

## 1. 实施与修复对应

先完成第一批并验证，再进入第二批。

| 报告编号/目标 | 主要文件、方法 | 修改原因与结果 |
|---|---|---|
| P0-01 | `admin/src/views/{Schedules,CourseProposals,Coaches,Members,Packages,Orders,Coupons,Articles,Feedback}.vue` | 修复 `onMounted` 引用暂时性死区变量；增加加载、空列表、错误和重试状态。9 页做真实 Chrome 挂载验证。 |
| P0-02 | `system/security/SecurityUtils.requireMemberId`；全部会员 Controller 和幂等表达式；`system/application/LoginUserLoaderImpl` | 明确 MP_USER 的 `user.id` 与后台的 `sys_user.id`。`requireUserId` 兼容方法也执行同一主体检查。真实 JWT 的跨编号空间反向测试覆盖预约、订单、课包、训练、评估、消息、营销等入口。 |
| P0-03 | `training/application/TrainingService.createRecord/pageStudentRecords`；`coach/application/CoachStudentService.hasServed/requireAccess`；`CoachTrainingController` | 授权只信任真实且未取消的预约/授课关系，不再信任可自行写入的训练记录；核对会员、教练、排课，统一直接列表接口的隐私检查。无 bookingId 仍需已有可信关系。 |
| P0-04 | `database/schema.sql`；`BookingTxService`；`BookingController` | 有效预约使用生成列唯一键，取消保留历史，重新预约生成新 ID。移除阻断立即重约的固定时间幂等占位，保留前端提交锁、数据库唯一键和限流。 |
| P0-05、P1-01/03 | `booking/application/ScheduleExecution`、`BookingService`、`BookingTxService`、`StockService`、`CourseScheduleMapper.lockById` | 锁覆盖事务提交，数据库条件更新检查行数；取消排课与全部 BOOKED 退约返还同步提交；签到与出勤插入原子完成。Redis 只反映已提交数据，写缓存失败不回滚已提交业务，后续写入及对账恢复。 |
| 排课校验 | `course/application/ScheduleService.create/createRecurring/updateWithAction`；`ScheduleChangeRequestService.approve`；`CoachLeaveService.approve` | 资源互斥锁内统一检查冲突、资格、教室容量和批准请假；审批与排课变更同事务；保留周期 API，不建设周期 UI。 |
| Web 排课、资格 | `Schedules.vue`；`ScheduleAdminController`；`AdminCourseProposalController.assignCoach`；`CourseCoachService.assign` | 名称选择、已批准教练筛选、未来课表、容量编辑、取消；单门课显式指派/审批/恢复资格，不自动授权所有课程。 |
| 可约状态 | `course/application/BookingPolicy`、`CourseQueryService`、`ScheduleView`、`BookingQueryService` | 查询与提交共用公共规则，统一余位、不可约原因和 onlyAvailable；个人重复与每日限制仍在提交时验证。 |
| 免费预约与刷新 | `miniprogram/pages/{course,course-detail,booking-confirm,private-booking,checkin}`；`coach-end/{api,schedule,dashboard}`；`utils/{request,util}` | FREE 不读/不要求课包，不调用订单支付；保留非 FREE 分支。修复私教首次加载、教练预选、返回刷新、失败重试和周范围算法；绑定微信教练使用自己的 MP_USER Token。 |
| 权限与环境 | `RbacService.permCodesOf`、`CoachBinding`、`LoginUserLoaderImpl`；Web/小程序管理请求封装；`admin/vite.config.js`、`admin/nginx.conf`、`deploy/*` | 停用角色不再聚合权限；微信教练仅取得实际后台教练角色的业务权限，不复制首页/经营数据权限；管理 API 使用 `/api/admin`，避免与 SPA 路由冲突。 |

上表 Java 文件位于 `server/src/main/java/com/yoyi/pilates/`。测试改动还包括 H2 隔离、每个用例清空独立测试数据、关闭测试定时任务以及已有测试的合法授课资格准备。`server/pom.xml` 增加可覆盖的构建目录，测试不使用原有 target 的旧 class 文件。

## 2. 本轮默认业务规则

- ADMIN、STORE_MANAGER 管理排课；STAFF 不新增排课写权限。教练创建/取消要求实际 `coach:schedule:*` 权限及本人归属；微信教练若绑定后台账号，其有效业务权限来自该账号的启用 COACH 角色。后台账号不会因此获得会员身份。
- 仅未开始的 SCHEDULED 排课可编辑、取消。有任何预约历史时仅可调容量，不允许改课程、教练、教室、日期或时段，包括已经取消的预约历史。
- 已有 SIGNED_IN 时拒绝整节取消，并提示人工核对；已经开始也拒绝取消。没有异步退课作为成功条件。
- 单次、周期、编辑、调课均检查未来时间、起止顺序、正整数容量、教室容量、教练/教室启用、课程上架、APPROVED 资格、批准请假与重叠排课。客户端不能设置人数计数、版本或状态字段。
- 课程下架、资格停用后停止新增预约；已有预约保留，不自动删除或退约。
- 免费预约可在开课前取消；课包模式保留原免费窗口/迟取消规则。签到为开课前 30 分钟至结束前，重复签到不重复写出勤。
- 每日上限继续取 `booking.max_per_day`；同会员互斥覆盖跨排课事务。未取消的 BOOKED、SIGNED_IN、COMPLETED、NO_SHOW 计入当日上限。
- 门店日期、时间统一按 Asia/Shanghai。管理端默认查未来 30 天；会员日期遵循 `booking.advance_days`，没有改成 30 天。

## 3. 实际数据流与并发边界

1. 店长从 `/api/admin/schedules/options` 选择上架课程、启用且有资格的教练、启用教室，提交时间与 `maxCapacity`。
2. 排课服务获取统一资源锁后开启事务，检查时段和容量，插入 `bookedCount=0`，提交后生成 Redis 余位缓存。
3. 查询聚合课程/教练/教室，并计算 `remaining=max(0,maxCapacity-bookedCount)`；BookingPolicy 返回公共可约状态及原因。页面据此显示按钮。
4. 预约先取会员锁与排课锁，再开事务、锁定排课行，复核公共规则、重复预约和每日上限；数据库条件占座并插入预约。FREE 不要求课包、不扣课时、不建订单。
5. 事务提交后在锁内按最新数据库校准 Redis，再释放锁。新预约不依赖 Redis 预占，过期缓存不会成为错误判满依据。
6. 取消预约针对具体 bookingId 条件推进，释放人数、按历史课包关联返还；重复旧 ID 不影响新预约。重约新增历史记录，由数据库有效预约唯一键兜底。
7. 整节取消、所有待签到预约及课时返还在一个事务中完成。附属通知在事务、锁之外执行，失败不会使退课回滚或返回假取消结果。
8. 定时完成和库存对账也遵守排课互斥；Redis 校准失败时删除可疑缓存，下一次写入/定时对账从数据库恢复。

资源锁采用门店级粗粒度锁，串行化排课创建和资源变更；正常预约仍按会员及排课锁运行。本轮没有引入新的技术栈或分布式事务系统。

## 4. 接口和字段变化

- 新增公开 `GET /api/booking-rules`：`bookingMode,advanceDays,deadlineMinutes,serverDate,timezone`。
- 新增 `GET /api/admin/schedules/options`：`courses,coaches,classrooms,qualifications`。
- 新增 `PUT /api/admin/courses/{courseId}/coaches/{coachId}`：显式指派、审批或恢复单个资格，要求 `manager:course:coach:manage`。
- 保留排课 POST、PUT、取消 POST 和周期 POST。列表现在返回聚合 ScheduleView，原有排课 ID、关联 ID、时间、人数、状态继续存在；不返回可供客户端修改的 version。
- ScheduleView 新增 `bookable,bookingStatus,unbookableReason,bookingMode`；管理列表还包含 `editable,cancellable,hasBookingHistory,managementReason`。
- 公共状态包括 AVAILABLE、FULL、NOT_OPEN、DEADLINE、CANCELLED、ENDED、COURSE_OFF_SHELF、COACH_DISABLED、ROOM_DISABLED、QUALIFICATION_DISABLED。它们与个人重复/限额拒绝分开。
- 现有后台 Controller 同时保留 `/admin/...` 兼容别名。Web 与小程序管理封装将管理请求规范到 `/api/admin/...`；反向代理只转发 `/api/`，SPA 继续使用 `/schedules` 等页面路由，不把页面路由当 API。
- 教练课表返回同一聚合视图，含课程名、余位和公共可约状态；不改变本人归属。

## 5. 数据库迁移、兼容与回滚

所有真实库脚本均未执行。

### 现有库

1. 在维护窗口前人工确认目标库，备份数据和角色映射。
2. 人工运行只读 `database/check_round2_booking.sql`：确认 MySQL 版本、实际建表与索引、有效重复、人数差异、已取消排课残留 BOOKED/SIGNED_IN、未来资格与角色映射。
3. 有异常先处置，尤其旧版异步取消尚未完成的数据；新版不会根据旧 MQ 事件去退掉仍在 SCHEDULED 的课程。不能跳过这些检查直接升级。
4. 停止旧版写入并排空旧任务后，另行审批执行 `migration_round2_booking.sql`。DDL 有隐式提交，不支持旧/新代码混合滚动写入。
5. 审核后执行最小 `migration_round2_permissions.sql`；它不授予全局首页/经营权限，不修改 STAFF 写权限，也不覆盖已有 PACKAGE 配置。清理相关用户 RBAC 缓存或等 10 分钟失效。
6. 若该环境本轮验收采用 FREE，保存原值后另行执行可选 `configure_round2_free.sql`，等配置缓存 60 秒后读取 booking-rules 确认。

旧约束 `(user_id,schedule_id,deleted)` 在取消后仍占唯一键。新约束使用 STORED 生成列 active_user_id：未删除且 BOOKED/SIGNED_IN/COMPLETED/NO_SHOW 时取 user_id，其余为 NULL；唯一键 `(active_user_id,schedule_id)` 利用 MySQL 多个 NULL 不冲突的语义保留取消历史。没有反复软删除，也不复用 bookingId，attendance、课时流水和历史事件关联不变。

### 全新空库

只在确认空库、获得初始化授权后依次执行当前 `schema.sql`、`init_data.sql`。当前 schema 已含新版约束，init 已含本轮最小权限；不要再执行旧 booking 升级 DDL，不要盲跑历史 migration_account/phase2/phase5/phase6。初始课程不自动获得全员资格，需从界面显式指派。

### 回滚限制

只读 `check_round2_rollback.sql` 若返回同用户、排课、deleted 的多条历史记录，旧唯一键无法无损恢复。不能删除预约、出勤或流水来强行回滚。优先向前修复，或使用兼容新约束的代码版本。只有停写、备份、预检查无冲突并另行审批，才考虑脚本注释中的反向 DDL。权限只撤销本轮新增映射，必须依据升级前备份；不得按角色批量清空。FREE 只恢复先前保存的配置值。

## 6. 环境与测试命令

开发后端默认为 8090；`admin/.env.example` 中的 VITE_API_TARGET 可由 `.env.local` 覆盖。生产变量示例在 `deploy/.env.example`，没有真实密钥。Compose 传递 MYSQL_USER；自定义账号需由环境负责人预先配置。管理容器增加 SPA fallback。未自动部署，也未执行 Nginx/Docker 运行验收。

```bash
# 根目录；不使用业务 Redis，必须从此运行器进入集成测试。
python3 scripts/test-isolated.py -q

# 真实浏览器联调：先在另一终端启动仅本机测试前端。
npm --prefix admin run dev -- --host 127.0.0.1 --port 5179 --strictPort
PILATES_LIVE_WEB_URL=http://127.0.0.1:5179 python3 scripts/test-isolated.py -q

# 9 页挂载/错误态及排课交互；HTTP 适配器为模拟，单独记录为组件测试。
node scripts/check-web-mount.mjs

npm --prefix admin run build
node --test miniprogram/tests/booking-flow.test.cjs
node scripts/check-miniapp.cjs

# 可选：自建临时 MySQL，禁用网络，仅随机 Unix socket；绝不使用已有 MySQL。
python3 scripts/check-mysql-migration.py
```

运行器从 PATH 选定 Java 的 java.home，避免本机 Maven 默认 Java 26 与现有 Lombok 不兼容。使用 `server/target-round2`，保留原 target 中的旧重复 class 文件。测试环境固定为 H2 内存库、随机端口/密码临时 Redis，关闭定时任务；缺少隔离参数时测试配置不回落到现有 6379。

### 本轮实际结果

- 第一批：新增 12 项隔离后端测试通过；9 页 18 项 Chrome 挂载/空列表/加载失败检查通过，Web 构建通过。
- 最终后端：67 项通过、0 失败、0 错误、0 跳过；包含真实 JWT HTTP 反向验证、MySQL 唯一键语义对应的 H2 约束、100 人抢 8 席、跨课每日限额、取消/签到/缩容竞态、条件更新失败、缓存恢复、FREE 团课私教及既有非 FREE 回归。
- Web：22 项 Chrome 组件检查通过；另有无 HTTP Mock 的隔离后端浏览器联调，覆盖店长创建、扩容、无课包会员多次取消重约、旧 ID 无影响、人数核对、整节取消。签到通过真实 HTTP 调用，在仅测试库调整到签到窗口后验证。
- 小程序：8 项逻辑用例通过；58 个 JavaScript 文件、54 个 JSON 文件的全量语法通过，受影响 WXML 事件绑定与 Page 方法对应。
- 独立 MySQL：7 项通过，包括当前完整 schema/init、最小权限迁移重复执行、STAFF 无写权限、旧唯一键缺陷复现、升级 DDL、新有效唯一键及多次重约历史。测试实例禁用网络，仅随机 Unix socket，结束已销毁；本机实际版本为 MySQL 26.7.0，因此仍需目标 MySQL 8 核对，不能将其说成 MySQL 8 验收。
- 构建通过；Vite 存在已有大包体积及 CJS 提示，未把优化打包扩展到本轮。
- 失败过程：首次 Maven Java 26 导致 Lombok 编译失败；旧 target 重复 class 导致扫描超时；多个测试上下文共用 H2 名称导致建表冲突。均已修复运行方式后重跑。故障注入的缓存、通知 ERROR 日志是预期测试，不是遗留失败。
- 待人工：实际业务库版本/数据、目标 MySQL 8 环境迁移、Nginx/Compose 部署、微信开发者工具和真机、真实微信登录与订阅服务。没有以自动化或 Mock 替代这些人工验收。

结构化汇总为 `docs/round2-verification.json`。原始 JUnit 结果位于 `server/target-round2/surefire-reports/`；真实浏览器报告为 `server/target-round2/live-browser.json`。测试库直接调整签到时间只用于缩短隔离验收，真实门店不能照此改数据库。

## 7. 人工操作验收

1. 负责人核对只读检查和迁移版本，按需配置 FREE；`GET /api/booking-rules` 返回 FREE、正确门店日期及预约窗口。
2. 用 ADMIN 或 STORE_MANAGER 登录 Web。创建/确认上架课程、启用教练与教室，在“排课管理 → 授课资格”选单门课程，指派/审批指定教练；停用后可恢复。不得给全部教练批量授权。
3. 在排课管理选择未来日期、名称、时间及人数上限 8。确认列表显示名称、时间、上限 8、已约 0、剩余 8，且会员窗口内显示可约。
4. 用无课包会员登录小程序，选择该日团课或私教。私教应首次即加载、可预选教练；FREE 不显示购卡前置要求，不创建订单或扣课时。
5. 预约后核对已约 1、剩余 7；取消后 0/8；立即重约，再取消、再重约。旧 bookingId 的重复退约不能取消新预约。
6. 扩容为 10，确认新增名额可使用。已有 6 人不能缩到 5；有任何预约历史时不能直接改时间/教练等。已有签到、已开始的课不能整节取消。
7. 等到签到窗口后扫码或手动签到，重复签到不新增出勤；确认预约 SIGNED_IN。默认预约截止与签到开放均为提前 30 分钟，因此应先在截止前预约，再等待签到窗口，不能绕过截止。
8. 另建未开始、无签到的课，整节取消后检查所有 BOOKED 预约、人数和历史课时返还；重试取消不得重复返还。
9. 验证下架/资格停用、满员、截止、取消、结束等提示；返回详情、课表后应刷新。接口断开时必须显示加载失败，不能显示成无课程。
10. STAFF 不能创建/改容量/取消；教练仅在实际权限与本人归属内操作；后台账号不能通过数值 ID 重合进入会员业务。

## 8. 明确留待后续的事项

周期排课 UI、自动滚动排课、首页运营保存/字段问题、前台代办、候补完整生命周期、支付退款、积分优惠券、多门店、系统配置管理页面、课程方案全流程校验等没有在本轮扩展。保留现有商业分支并通过既有回归，不代表这些模块已完整验收。短信验证码直接返回等已知非本轮问题仍需在真实上线前处理。此交付不能据此宣称整项目可上线。
