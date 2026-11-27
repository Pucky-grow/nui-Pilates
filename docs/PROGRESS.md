# 优仪普拉提 · 技术开发文档（进度快照 + 交接）

> 更新时间： 2026-09-24 ｜ 状态： **一期完成 + 二期 Phase 1-7 完成 + Phase 6(FREE模式)完成** ｜ 用途： 上下文压缩交接

## 二期 Phase 6（2026-09-24 完成）FREE 预约模式 + 教练门店运营

- **FREE 预约模式**：`booking.paymentMode=FREE`(默认)，会员无需课包直接预约、不扣课时、不创建订单/流水；PACKAGE 模式保留(可切换)。`booking.user_package_id` 改为可空。
- **教练门店运营**：新增 `home_hot_course`/`home_team_coach` 2 表 + 教练端接口(Banner CRUD/热门课程/教练团队/门店信息)；`/api/home` 读运营配置。
- **教练课程/排课权限放宽**：coach:schedule:create/update/cancel、coach:course:update(运营展示字段)、coach:booking:view；教练可创建/取消自己排课、编辑自己负责课程的展示信息。
- **教练工作台今日明细**：今日每节课的预约/已签到/待签到。
- **小程序**：预约确认页移除课包选择(立即预约)、首页弱化购买课包入口。
- 新增 `FreeBookingTest`；`SystemConfigService.set` 物理 upsert。测试 9/9。迁移 `migration_phase6_tables.sql` + `migration_phase6_perm.sql`。

## 二期 Phase 6-7（2026-09-24 完成）

**Phase 6 门店运营**：意见反馈(`feedback`表+小程序页+后台处理)、前台工作台(StaffDashboard + `/admin/statistics/dashboard` 权限改为 dashboard:view)、经营分析指标(到课率/爽约率/上座率/复购率，`/admin/statistics/business-analysis`)。

**Phase 7 营销**：邀请好友(`invitation`表+邀请码+绑定+首购奖励`InvitationRewardConsumer`)、`points.invite`配置。

新增 `feedback`/`invitation` 2 表(共 52 表)，迁移 `migration_phase67_tables.sql`。后端 `mvn test` 8/8。端到端冒烟全部通过。

**Phase 8(测试) 剩余**：全面权限越权测试、并发回归、完整测试清单。

## 二期 Phase 5（2026-09-24 完成）

课程体系 + 教练课程建设参与权：新增 `course_coach`(课程↔教练多对多)、`course_proposal`(课程方案)、`course_version`(版本) 3 表；教练可创建课程方案(草稿→提交→店长审核→生成正式课程+授课资格)、申请成为已有课程授课教练；排课校验授课资格(course_coach APPROVED)。权限点 coach:course:*/manager:course:*/admin:course:*。新增 `CourseProposalTest`(3/3 通过)。迁移 `migration_phase5_tables.sql` + `migration_phase5_perm.sql`。

**Phase 6-8 未做**：前台工作台 Web 页面、会员体系完善、邀请好友、意见反馈、经营分析、完整测试。

**Phase 1 权限**：5 角色(ADMIN/STORE_MANAGER/STAFF/COACH/MEMBER) + 16 细粒度权限点；教练排课权收窄(只能请假/调课)；演示账号 manager(店长)/staff(前台)。迁移 `migration_phase2.sql`。

**Phase 2 教练端**：工作台概览(`/api/coach-end/dashboard`)、请假(`coach_leave`+审批)、调课申请(`schedule_change_request`+审批)、训练模板(`training_record_template`)、学员档案(隐私感知 `user_privacy`)、我的排课范围筛选。

**Phase 3 预约**：候补(`waitlist`+自动转正+`WaitlistConsumer`)、预约规则(advance_days/late_minutes)、再次预约。

**Phase 4 私教**：教练可预约时间(`coach_availability`)、可用时段计算(工作-排课-请假)、`/api/coaches/{id}/available-slots`。

新增 6 表 + `migration_phase2_tables.sql`；后端 `mvn test` 5/5；端到端冒烟全部通过。

**Phase 5-8 未做**：前台工作台 Web 页面、会员体系完善、邀请好友、意见反馈、经营分析、完整测试。

---

## 1. 项目概述

「优仪普拉提 Yoyi Pilates」普拉提工作室数字化运营系统（商业级，非 Demo）：
**微信小程序用户端 + 小程序教练端 + Web 管理后台 + Java 后端**。
闭环： 微信登录→浏览课程/教练→买课包（微信支付）→预约（扣课时/锁库存）→签到→完成→教练写训练记录→用户评价→积分→续费→后台统计。

### 已确认决策
| 项 | 结论 |
|---|---|
| 目录 | 单仓库多模块： `plates/{miniprogram, server, admin, deploy, docs, database}` |
| 后端 | Java 17(本机 JDK21) + Spring Boot 3.2.12 + MyBatis-Plus 3.5.7 + MySQL 8 + Redis + Redisson， 模块化单体， 包内 DDD 分层 |
| MQ | `EventPublisher` 抽象； dev=内存总线（afterCommit 派发）, prod=RocketMQ(`yoyi.mq.type` 切换） |
| 支付 | 微信 V3 全流程已实现； `yoyi.pay.mock-enabled=true` 时 mock 支付走同一结算路径 |
| 微信登录 | `yoyi.wx.mock-login=true` 时 code 即 mock openid(`mock_{code}`) |
| 小程序 UI | 原生 + TDesign(tdesign-miniprogram), 米白/奶油/鼠尾草绿品牌色 |
| 后台 | Vue3 + TS + Vite + Element Plus + Pinia + ECharts |

## 2. 环境（本机已验证）

- Java 21 (compile target 17)、Maven 3.9.16、Node 24、Redis 8 运行中（`redis-cli ping`=PONG)
- **MySQL 8 运行中但 root 密码未知** → 测试用 H2(MODE=MySQL); 待用户提供密码后导入 `database/schema.sql + init_data.sql`
- 常用命令： `cd server && mvn -q -DskipTests compile` / `mvn -q test -Dtest=BookingConcurrencyTest`

## 3. 数据库（P2 ✅)

`database/schema.sql` **41 张表**, `init_data.sql` 演示数据（排课用 CURDATE()+N 生成未来7天）。
约定： 全表 `id/created_at/updated_at/deleted`; 金额 DECIMAL(10,2); 状态 VARCHAR 枚举； 逻辑删除。
**索引名已全局唯一**(H2 要求， 已用脚本重命名如 `uk_user_member`)， 改 schema 后须重新生成测试 schema:
```bash
sed -E -e '/^CREATE DATABASE/d' -e '/^USE /d' -e '/^SET NAMES/d' -e '/^  DEFAULT CHARACTER SET/d' \
  -e "s/\) ENGINE=InnoDB COMMENT='[^']*';/);/" database/schema.sql > server/src/test/resources/schema-h2.sql
```
幂等唯一索引： `booking(user_id,schedule_id,deleted)`、`order.order_no`、`payment.out_trade_no`、`user_package(order_id,package_id)`、`attendance.booking_id`、`course_review.booking_id`。
防超扣条件 UPDATE: `course_schedule.incrBooked(booked_count<max_capacity)`、`user_package.deductCredits(remaining>=N)`、`order.markPaidIfPending`、`payment.markSuccessIfPending`、`coupon.deductStock`、`user_coupon.markUsedIfUnused`。
本机演示账号由启动器生成独立口令，保存在私有状态目录的 demo-accounts.json。

## 4. 后端架构（server/, 包根 com.yoyi.pilates)

```
common/    Result/ResultCode(4xxxx业务码)/PageResult/BusinessException/GlobalExceptionHandler/
           BaseEntity(@TableLogic)/NoGenerator/JsonUtils/MaskUtils/RedisKeys/
           @Idempotent+@RateLimit+@OpLog(注解+切面, SpelKeyResolver)/MybatisPlusConfig/RedisConfig/WebConfig
infra/     mq/(EventPublisher+EventConsumer+DomainEvent+Topics | InMemoryEventPublisher(dev) |
           RocketMqEventBridge(prod))  config/RestClientConfig
system/    security/(JwtService+JwtAuthenticationFilter+SecurityConfig+LoginUser+LoginUserLoader+
           RequiresPerm+PermissionAspect+SecurityUtils+CoachBinding接口)
           application/(RbacService角色权限Redis缓存10min + LoginUserLoaderImpl + SystemConfigService60s缓存)
           infrastructure/(SysUser/Role/Permission/UserRole/RolePermission/SystemConfig/OperationLog 实体+Mapper)
user/      WxMpClient(code2session+mock+手机号AES解密)/AuthService/UserService/AuthController
member/    MemberService(首登自动建档) + 实体Mapper
course/    CourseQueryService(聚合视图防N+1)/CourseAdminService(实现CoachBinding)/ScheduleService(冲突检测)
booking/   BookingService(锁外校验→Redisson锁→Redis DECR→TxService)/BookingTxService(独立Bean!)/
           BookingQueryService/StockService/ScheduleCancelledConsumer/BookingController/CoachEndController
product/   PackageService(适用性/扣减/返还/发放/调整+流水) + 实体Mapper
trade/     (P7进行中) OrderService/PaymentService/RefundService/WechatPayClient + 实体Mapper
marketing/ (P7进行中) PointsService/CouponService + 实体Mapper
```

### 关键设计（续作必须遵守）
1. **统一响应** `Result{code,message,data}`, 0=成功； 异常抛 `BusinessException(ResultCode)`。
2. **事务陷阱**: `@Transactional` 方法被同类自调用会失效 → 事务方法放独立 Bean（参照 BookingTxService)。
3. **预约防超卖四道防线**: @Idempotent(Redis SETNX) → Redisson锁（scheduleId) → Redis DECR(<0回补） → DB条件UPDATE+唯一索引； 失败回补 Redis 库存。
4. **支付回调幂等四道防线**: out_trade_no唯一 → payment乐观推进 → order乐观推进 → user_package(order_id+package_id）唯一， 重复回调直接返回成功。
5. **事件**: `eventPublisher.publish(Topics.X, DomainEvent.of(tag, key, payloadMap))`, afterCommit 派发； 消费者按 topic 订阅、按 tag 过滤、消费必须幂等； 发布器用 ObjectProvider 延迟解析消费者（避免循环依赖）。
6. **认证**: JWT + Redis登录标记(`pilates:login:token:{token}` 支持登出吊销）; 每次请求经 LoginUserLoader 实时加载角色权限； MP_USER 角色=MEMBER; 教练用 admin-login(coach01), coachId 经 CoachBinding 注入； 接口权限 `@RequiresPerm("xxx")`, 敏感操作 `@OpLog`。
7. **业务规则走 system_config**(SystemConfigService.getInt): 免费取消4h、预约截止30min、每日上限3、积分规则等， 键见 init_data.sql。
8. **课时变动必须写 package_credit_log 流水**(PackageService 内聚）。
9. MapperScan 只扫 `com.yoyi.pilates.**.infrastructure.mapper`。

## 5. 进度明细

| 阶段 | 状态 | 验证 |
|---|---|---|
| P1 架构设计 | ✅ | 计划已批准 |
| P2 数据库 | ✅ | 41 表 schema + init_data；H2 全量建表通过 |
| P3 后端基座 | ✅ | 统一返回/异常/JWT+RBAC/幂等限流注解/操作日志/事件总线 |
| P4 用户系统 | ✅ | 微信登录(mock)+会员+RBAC |
| P5 课程系统 | ✅ | 课程/分类/教练/教室/排课冲突检测/周期排课 |
| P6 预约系统 | ✅ | **并发测试3/3**: 100并发抢8名额 |
| P7 交易营销 | ✅ | 课包/订单/支付(Mock+V3)/退款/券/积分；**幂等测试2/2** |
| P8 训练系统 | ✅ | 训练记录/身体评估/评价+教练评分重算 |
| P9 内容消息+定时任务 | ✅ | 文章/Banner/消息/订阅消息mock/7类定时任务 |
| P10 小程序用户端 | ✅ | 25 页面(登录/首页/课程/教练/课包/订单支付/预约/签到/训练/评估/评价/内容/营销/我的) |
| P11 教练端 | ✅ | coach-end 分包(工作台/课表/学员/签到/写记录) |
| P12 管理后台 | ✅ | Vue3+Element Plus 12视图(Dashboard/课程/排课/教练/会员/课包/订单/券/内容/系统) |
| P13 部署 | ✅ | docker-compose(MySQL/Redis/RocketMQ/server/admin/nginx)+Dockerfile×2+README |

**后端全量 `mvn test` 通过（5 测试 0 失败）。**

## 6. 待办（可选完善项，非阻塞）

- 生产部署需注入真实微信 AppID/Secret/支付密钥，关闭 mock 开关
- 订阅消息真实推送需 openid 映射 + access_token（mock 已预留）

## 7. 真实环境验证记录（2026-09-23，MySQL 8 实机）

已用本机 MySQL(密码已提供) 导入 schema + init_data 并在 **8090 端口**（本机 8080 被他人 Tomcat 占用）跑通端到端闭环：

```
登录 → 首页(3 banner/今日课程) → 课包商城 → 下单 → mock支付 → 课包到账10课时
→ 预约未来排期 → 扣1课时(剩9) → 取消预约 → 返还(剩10)
```

验证过程修复的 3 个真实环境 bug（H2 未暴露）：
1. **Redisson 空密码 AUTH 报错** → 新增 `infra/config/RedissonConfig.java` 显式装配，仅当密码非空才 setPassword（与 spring-data-redis 对齐）。
2. **JDBC `characterEncoding=utf8mb4` 不被接受** → 三个 application-*.yml 改为 `UTF-8`。
3. **`order` 是 MySQL 保留字** → `OrderEntity` 表名改 `` @TableName("`order`") ``（自定义 SQL 已用反引号）。

启动方式：
```bash
cd server && mvn -q clean package -DskipTests
MYSQL_PASSWORD=<密码> SERVER_PORT=8090 java -jar target/pilates-server.jar
# 小程序 app.js 的 baseUrl 需指向实际端口(默认8080被占用时改8090)
```

## 7. 原始需求

完整 Prompt(90节）在会话历史中； 要点已落入计划文件与本文。文案禁区： 不用"治疗/治愈/根治/医疗诊断", 用"训练/改善/体态管理"。
