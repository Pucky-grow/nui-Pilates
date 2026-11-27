# 优仪普拉提 · 项目功能介绍（完整版）

> 用途：完整功能清单与实现说明，作为后续功能调整、二次开发、验收的权威参考。
> 更新时间：2026-09-24 ｜ 系统版本：v1.0

---

## 1. 项目概述

「优仪普拉提 Yoyi Pilates」是一套面向普拉提工作室的数字化运营系统，覆盖 **用户获客 → 登录 → 浏览课程 → 查看教练 → 购买课包 → 微信支付 → 预约 → 签到 → 上课 → 训练记录 → 评价 → 会员管理 → 营销 → 数据统计** 的完整商业闭环。

系统包含三端：

| 端 | 技术栈 | 服务对象 |
|---|---|---|
| 微信小程序（用户端 + 教练端） | 原生小程序（WXML/WXSS/JS） | 学员、教练 |
| Web 管理后台 | Vue 3 + Vite + Element Plus + Pinia + ECharts | 管理员、教练 |
| Java 后端 | Spring Boot 3.2 + MyBatis-Plus + MySQL 8 + Redis + Redisson | 三端共用 |

### 技术要点
- **模块化单体 + DDD 分层**（interfaces → application → domain → infrastructure）
- **事件驱动**：`EventPublisher` 抽象，开发环境内存总线 / 生产 RocketMQ（`yoyi.mq.type` 切换）
- **微信支付 V3** 全流程实现 + `pay.mock-enabled` 开发开关
- **微信登录** `code2session` + `wx.mock-login` 开发开关
- 品牌视觉：米白/奶油/鼠尾草绿，高级简洁女性化

---

## 2. 账号与权限体系（三类账号）

| 账号 | 身份载体 | 登录方式 | 角色 | 界面 |
|---|---|---|---|---|
| **学员** | `user`(微信 openid) | 微信一键登录 | MEMBER | 小程序用户端（4 tab） |
| **教练** | `sys_user` + `coach`(可绑微信) | ① 账号密码 ② 绑定微信后一键登录 | COACH | 教练工作台 + Web 后台受限菜单 |
| **管理员** | `sys_user` | 账号密码 | ADMIN | Web 管理后台全菜单 |

### 权限矩阵

| 能力 | 学员 | 教练 | 管理员 |
|---|---|---|---|
| 浏览课程/教练/课包/文章 | ✅ | ✅ | ✅ |
| 购买课包/预约/签到/评价 | ✅ | ✅ | ✅ |
| 查看本人课表/学员 | — | ✅(仅自己) | ✅(全部) |
| 签到/填写训练记录 | — | ✅(仅自己授课) | ✅ |
| 课程定义/分类/价格/课时 | — | ✅ | ✅ |
| 教室管理 | — | ✅ | ✅ |
| 排课(新建/编辑/取消) | — | ✅(教练归属限本人) | ✅ |
| 会员/课包/订单/退款/优惠券/内容/财务/统计 | — | — | ✅ |
| RBAC 账号/系统配置/操作日志 | — | — | ✅ |

**权限点**（`sys_permission`）：`dashboard:view`、`course:view/create/update/delete`、`schedule:view/create/update/cancel`、`coach:view/manage`、`member:view/update/adjust`、`package:view/manage`、`order:view/refund`、`coupon:manage`、`content:manage`、`classroom:manage`、`finance:view/export`、`statistics:view`、`system:manage`。

**登录保持**：小程序 token 有效期 2 小时，超时自动清除登录态并跳登录页重新登录（前端 `loginAt` 校验 + 后端 `mp-ttl=7200000`）。

---

## 3. 小程序用户端功能

### 3.1 登录页 `pages/login/login`
- 微信一键登录（学员）
- 演示身份快速切换（学员A/学员B/教练王老师，调试用）
- 账号密码登录（教练 coach01 / 管理员 admin）
- 已登录且未超时自动跳过

### 3.2 首页 `pages/index/index`
- Banner 轮播（后台可配置，支持跳转课程/课包/文章/链接）
- 快捷入口 4 个（立即预约/私教预约/购买课包/教练团队，图标化）
- 新人体验课专区（限购一次）
- 今日课程、热门课程
- 教练团队、普拉提知识（文章）
- 工作室地址入口

### 3.3 课程 `pages/course/course` + `course-detail`
- 课程列表：日期(今天/明天/后天/周六/周日)、时段(上午/下午/晚上)、难度筛选
- 课程卡片：名称/日期/时间/教练/教室/时长/剩余名额/消耗课时
- 课程详情：介绍、适合人群、训练重点、注意事项、近期排期、学员评价

### 3.4 教练 `pages/coach-list` + `coach-detail`
- 教练列表（头像/姓名/职位/从业年限/擅长/评分）
- 教练详情（简介/证书/学员评价）
- 私教预约入口

### 3.5 私教预约 `pages/private-booking`
- 流程：选教练 → 选训练目标 → 选时间 → 确认预约

### 3.6 课包商城 `pages/package-list` + `package-detail`
- 课包列表（次卡/期限卡/私教课包）
- 课包详情：选优惠券、积分抵扣、立即购买

### 3.7 订单 `pages/order-list` + `order-detail`
- 我的订单列表、订单详情
- 支付（mock 支付/真实微信支付）、取消待支付订单

### 3.8 预约 `pages/booking` + `booking-confirm` + `booking-detail` + `checkin`
- 我的预约列表（按状态筛选）
- 预约确认：选课包扣课时、填训练目标
- 预约详情：取消预约、去签到、去评价
- 签到：扫码签到 / 手动确认签到

### 3.9 训练 `pages/training`
- 训练记录列表（教练填写的内容）
- 训练统计：累计次数/时长/本月/连续天数 + 周/月趋势

### 3.10 身体评估 `pages/assessment`
- 身体评估记录（身高/体重/体脂/围度/体态观察）
- 围度体重趋势

### 3.11 课程评价 `pages/review`
- 创建评价（课程/教练/环境三评分 + 文字）
- 我的评价列表

### 3.12 优惠券 `pages/coupon` / 积分 `pages/points`
- 领券中心、我的优惠券
- 积分余额、积分流水

### 3.13 优仪课堂 `pages/article` + `article-detail`
- 文章分类、文章列表、文章详情
- 点赞、收藏

### 3.14 消息中心 `pages/message`
- 站内消息列表、未读数、标记已读

### 3.15 我的 `pages/profile`
- 个人信息、会员等级、累计训练/积分/累计消费
- 我的预约/课包/训练/评估/评价/优惠券/积分/消息
- 教练工作台入口（教练身份）
- 当前登录管理：当前登录身份显示、切换账号、退出登录

---

## 4. 教练端功能（小程序分包 `coach-end/`）

| 页面 | 功能 |
|---|---|
| `dashboard` | 教练工作台：账号密码登录、微信绑定/解绑、今日课程、待填写训练记录 |
| `schedule` | 我的课表（7 天范围，仅自己授课的排课） |
| `students` | 某节课学员列表（头像/等级/剩余课时/状态）、教练代签到 |
| `training` | 填写训练记录（目标/内容/强度/反馈/建议） |

**数据权限**：教练只能查看/操作自己授课的排课和学员；不能给其他教练排课。

---

## 5. Web 管理后台功能（Vue3 + Element Plus）

| 视图 | 功能 | 权限 |
|---|---|---|
| `Login` | 账号密码登录 | 公开 |
| `Dashboard` | 今日/本月营业额、预约、上课、新增会员；营业额趋势、热门课程 | dashboard:view |
| `Courses` | 课程增删改、上下架、分类管理 | course:* |
| `Schedules` | 单次/周期排课、冲突检测、取消排课 | schedule:* |
| `Coaches` | 教练增删改 | coach:manage |
| `Members` | 会员查询、编辑等级/有效期、调整课时 | member:* |
| `Packages` | 课包增删改、适用范围 | package:manage |
| `Orders` | 订单查询、退款 | order:view/refund |
| `Coupons` | 优惠券增删改 | coupon:manage |
| `Articles` | 文章/Banner 管理 | content:manage |
| `System` | 系统配置/账号说明 | system:manage |

**菜单按角色渲染**：管理员全菜单；教练只显示 数据看板/课程管理/排课管理/我的学员。

---

## 6. 后端模块与核心 API

### 模块划分（包根 `com.yoyi.pilates`）

| 模块 | 职责 |
|---|---|
| `common` | 统一返回/异常/基类/单号生成/脱敏/幂等限流注解/操作日志切面 |
| `system` | 认证(JWT)、RBAC、系统配置、操作日志 |
| `user` | 微信登录、用户资料 |
| `member` | 会员档案、会员等级 |
| `course` | 课程/分类/教练/证书/教室/排课(冲突检测) |
| `booking` | 预约/签到/取消/完成/爽约（并发核心） |
| `product` | 课包定义/用户课包/课时流水 |
| `trade` | 订单/微信支付/退款 |
| `marketing` | 优惠券/积分/新人礼包 |
| `training` | 训练记录/身体评估/课程评价 |
| `content` | 文章/文章分类/点赞收藏/Banner |
| `notify` | 站内消息/订阅消息 |
| `stats` | 看板统计 |
| `home` | 首页聚合/工作室信息 |
| `infra` | 事件总线(MQ)/Redis/Redisson/定时任务 |

### 主要 API（RESTful）

**认证**：`POST /api/auth/wx-login`、`POST /api/auth/admin-login`、`POST /api/auth/logout`、`GET /api/auth/me`、`PUT /api/auth/profile`、`POST /api/auth/bind-phone`

**公开浏览**：`GET /api/home`、`GET /api/banners`、`GET /api/studio`、`GET /api/courses[/{id}]`、`GET /api/course-categories`、`GET /api/course-schedules[/{id}]`、`GET /api/coaches[/{id}]`、`GET /api/packages[/{id}]`、`GET /api/articles[/{id}]`、`GET /api/article-categories`

**预约**：`POST /api/bookings`、`DELETE /api/bookings/{id}`、`GET /api/bookings/{id}`、`GET /api/my/bookings`、`POST /api/attendance/check-in`

**课包/订单/支付**：`GET /api/my/packages`、`GET /api/my/packages/{id}/logs`、`POST /api/orders`、`GET /api/my/orders`、`GET /api/orders/{id}`、`POST /api/orders/{id}/cancel`、`POST /api/payment/wechat/create`、`POST /api/payment/wechat/callback`、`POST /api/payment/mock-pay`

**训练/评估/评价**：`GET /api/training-records`、`GET /api/training-records/stats`、`GET /api/assessments`、`GET /api/measurements/trend`、`POST /api/reviews`、`GET /api/my/reviews`、`GET /api/courses/{id}/reviews`

**营销**：`GET /api/coupons`、`POST /api/coupons/{id}/receive`、`GET /api/my/coupons`、`GET /api/my/points`、`GET /api/my/points/records`

**消息**：`GET /api/notifications`、`GET /api/notifications/unread-count`、`POST /api/notifications/{id}/read`、`POST /api/notifications/read-all`

**教练端**：`GET /api/coach-end/today`、`GET /api/coach-end/schedules`、`GET /api/coach-end/schedules/{id}/students`、`POST /api/coach-end/bookings/{id}/check-in`、`POST /api/coach-end/training-records`、`GET /api/coach-end/pending-records`、`POST /api/coach-end/bind-wechat`、`POST /api/coach-end/unbind-wechat`

**管理后台**（`/admin/...`，带 `@RequiresPerm` 权限校验）：课程/分类/教练/教室/排课/会员/课包/订单/优惠券/文章/Banner/统计 CRUD

---

## 7. 数据库设计（41 张表）

### 核心表清单
- **用户会员**：`user`、`user_profile`、`member`、`member_level`
- **教练课程**：`coach`(含 openid)、`coach_certificate`、`course`、`course_category`、`classroom`、`course_schedule`
- **预约签到**：`booking`、`attendance`
- **课包**：`package`、`package_item`、`user_package`、`package_credit_log`
- **交易**：`order`、`order_item`、`payment`、`refund`
- **营销**：`coupon`、`user_coupon`、`points_record`
- **训练评估**：`training_record`、`body_assessment`、`body_measurement`、`course_review`
- **内容**：`article`、`article_category`、`article_like`、`article_favorite`、`banner`
- **消息**：`notification`、`notification_template`
- **系统**：`sys_user`、`sys_role`、`sys_permission`、`sys_user_role`、`sys_role_permission`、`operation_log`、`system_config`

### 关键约定
- 通用字段：`id/created_at/updated_at/deleted`（逻辑删除）
- 幂等唯一索引：`booking(user_id,schedule_id,deleted)`、`order.order_no`、`payment.out_trade_no`、`user_package(order_id,package_id)`、`attendance.booking_id`、`course_review.booking_id`
- 防超扣条件 UPDATE：`course_schedule(booked_count<max_capacity)`、`user_package(remaining>=N)`、`order(PENDING→PAID)`、`payment(PENDING→SUCCESS)`、`coupon(库存)`、`user_coupon(UNUSED→USED)`
- 系统规则走 `system_config` 表（预约/取消/积分等，后台可改）

---

## 8. 核心业务流程

### 8.1 预约（高并发防超卖，四道防线）
1. `@Idempotent`（Redis SETNX，userId:scheduleId 防连点）
2. Redisson 分布式锁（scheduleId 粒度）
3. Redis DECR 预占库存（<0 回补判满）
4. DB 事务：排课条件 UPDATE 占座 + 预约唯一索引 + 课时条件扣减 + 流水

取消预约：规则引擎读 `system_config`（免费取消时限4h/超时扣课时），同事务释放座位 + 返还课时（或按规则扣课）。

### 8.2 微信支付（回调幂等，四道防线）
1. `payment.out_trade_no` 唯一
2. `payment` 状态乐观推进（PENDING→SUCCESS）
3. `order` 状态乐观推进（PENDING/PAYING→PAID）
4. `user_package(order_id,package_id)` 唯一（防重复发课包）

### 8.3 课包课时
一切课时变动 = 条件 UPDATE（防超扣）+ `package_credit_log` 流水（可审计对账）。余额 = 流水累计。

---

## 9. 营销与积分体系

| 能力 | 说明 |
|---|---|
| 新人礼包 | 注册自动发新人券 + 注册积分 |
| 优惠券 | 新人券/满减券/折扣券/私教券；领券、核销、关单退回、限购、库存 |
| 积分 | 注册/购买/签到/评价 获得；抵扣订单；积分流水 |
| 邀请好友 | 预留（未完整实现） |

积分规则（`system_config`）：注册+50、消费1元+1、签到+5、评价+20、100积分抵1元。

---

## 10. 消息与通知

- **站内消息**：预约成功、课程取消、开课提醒、会员到期、课时不足、支付成功（模板渲染 + 业务键幂等去重）
- **订阅消息**：微信订阅消息（mock 模式仅记录日志，生产走微信 API）
- 消息中心：未读数、列表、标记已读

---

## 11. 定时任务（`PilatesScheduler`）

| 任务 | 频率 |
|---|---|
| 关闭超时未支付订单（30min） | 每 5 分钟 |
| 课程结束自动完成/爽约 | 每 5 分钟 |
| 开课提醒（未来 2 小时） | 每 10 分钟 |
| 会员到期 / 课时不足提醒 | 每天 10:07 |
| 过期课包处理 | 每天 03:00 |
| Redis 库存对账 | 每小时 |

---

## 12. 安全与幂等

- JWT 认证 + Redis 登录态标记（支持登出吊销）
- 接口级权限 `@RequiresPerm` + 操作日志 `@OpLog`
- 幂等 `@Idempotent`、限流 `@RateLimit`
- 手机号脱敏（138****8888）、敏感信息环境变量化
- 全局异常统一返回 `{code,message,data}`
- 数据权限：教练仅看自己授课的排课与学员

---

## 13. 部署与配置

### 目录结构
```
plates/
├─ miniprogram/   小程序（pages/ 25 页 + coach-end/ 4 页 + assets/）
├─ server/        Spring Boot 后端（15 模块）
├─ admin/         Vue3 管理后台（12 视图）
├─ database/      schema.sql(41表) + init_data.sql + migration_account.sql
├─ deploy/        docker-compose + Dockerfile×2 + nginx.conf
└─ docs/          文档
```

### 启动方式
```bash
# 数据库
mysql -uroot -p < database/schema.sql && mysql -uroot -p < database/init_data.sql
# 后端（mock 登录/支付/MQ）
cd server && mvn -q clean package -DskipTests
MYSQL_PASSWORD=xxx SERVER_PORT=8090 java -jar target/pilates-server.jar
# 管理后台
cd admin && npm install && npm run dev   # http://localhost:5173
# 小程序：微信开发者工具导入 miniprogram/
```

### 关键配置（`application.yml`）
- `yoyi.wx.mock-login`：mock 登录开关
- `yoyi.pay.mock-enabled`：mock 支付开关
- `yoyi.mq.type`：memory / rocketmq
- `yoyi.jwt.mp-ttl`：小程序 token 有效期（当前 2 小时）

### 测试账号
- 管理员：`admin`，口令见本机私有 `demo-accounts.json`
- 教练：`coach01`，口令见本机私有 `demo-accounts.json`（关联王老师）

---

## 14. 演示身份（开发调试）

`app.js` 的 `mockCode` 控制微信登录身份：
- `demo_user_1` / `demo_user_2` → 学员
- `demo_coach_1` → 教练（王老师，已绑定微信）

生产环境将 `useMockLogin` 置 false，走真实微信登录。
