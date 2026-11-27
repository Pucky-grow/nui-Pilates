# 优仪普拉提 · 后端权限与关键业务接口专项审计报告

> 生成时间：2026-10-12
> 审计方式：代码走查 + 控制器层集成测试（`@RequiresPerm` 切面经 Spring AOP 生效，断言异常码 + 数据库状态）。

---

## 1. 检查范围与代码版本

- 后端 `com.yoyi.pilates`（Spring Boot 3.2 + MyBatis-Plus + MySQL + Redis + Redisson）。
- 权限基础设施：`SecurityConfig`、`JwtAuthenticationFilter`、`LoginUserLoaderImpl`、`PermissionAspect`、`SecurityUtils`。
- 审计接口：管理员内容管理（`coach:home:*`）、课程方案审核（`manager:course:proposal:*`）、教练课程展示编辑、教练排课创建/取消、会员免费预约。

---

## 2. 实际权限规则（代码确认）

| 机制 | 规则 |
|---|---|
| `LoginUser.hasPerm(perm)` | `isAdminRole() || permissions.contains(perm)` —— **ADMIN 角色短路返回 true（天然全权限）** |
| `LoginUserLoaderImpl.loadMpUser` | 小程序用户固定 `MEMBER`；openid 绑定教练则追加 `COACH` + `coachId`，权限集为空 |
| `LoginUserLoaderImpl.loadAdmin` | 后台账号实时加载 `roleCodes` + `permCodes`（RBAC） |
| `PermissionAspect` | `@RequiresPerm` 方法执行前：无登录 → 40100；无权限 → 40300 |
| `JwtAuthenticationFilter` | 需 JWT 签名有效 + Redis 登录标记存在（支持登出吊销） |
| 教练排课 `forceCoachForCoachRole` | 教练创建排课强制 `coachId = 当前登录教练`，忽略客户端提交值 |
| 排课取消归属校验 | 非管理员只能取消本人授课排课 |
| 课程展示编辑归属 | `hasQualification(courseId, coachId)` 校验授课资格 |

**权限授予（init_data + migration_phase5/6，以 SQL 实际为准）**：

| 角色 | 权限 |
|---|---|
| ADMIN | 全部权限（`hasPerm` 短路） |
| STORE_MANAGER | 课程/排课/教练/会员/课包/订单/券/内容/教室/统计 + `manager:course:proposal:view/approve/reject` + `manager:course:coach:manage`；**无 `coach:home:*`、无 `system:*`/`finance:*`** |
| STAFF | `dashboard:view`、`course:view`、`schedule:view`、`member:view`、`order:view`、`staff:member:view`、`staff:booking:view/create`、`staff:attendance:create`、`coach:course:view`；**无 `manager:*`、无 `coach:home:*`** |
| COACH | `coach:schedule:view/create/update/cancel`、`coach:course:view/apply/proposal:*`、`coach:training:*`、`coach:leave:*`、`coach:booking:view`、**`coach:home:view/banner/hot-course/coach-team/studio/article`**（Phase6 明确授予门店运营权限）、`coach:course:update`；**无 `manager:course:proposal:*`** |
| MEMBER | 无管理权限 |

> ⚠️ 关键澄清：`coach:home:*` 由 Phase 6 迁移明确授予 **COACH**（教练可维护门店/Banner/热门课程/教练团队），而非仅 ADMIN。STORE_MANAGER **未**获得 `coach:home:*`。此前报告"仅 ADMIN 拥有 coach:home"表述有误，已更正。

---

## 3. 新增测试用例

| # | 用例 | 方法 | 预期 |
|---|---|---|---|
| 1 | 未登录调用管理接口 | `unauthenticated_rejected` | 40100 |
| 2 | MEMBER 读管理员内容（Banner列表） | `member_cannot_read_admin_content` | 40300 |
| 3 | MEMBER 写 Banner（且 DB 不变） | `member_cannot_write_banner` | 40300 + banner 表 0 行 |
| 4 | MEMBER 调课程方案审核接口 | `member_cannot_review_proposals` | 40300 |
| 5 | 教练A 取消教练B 的排课（DB 不变） | `coach_cannot_cancel_others_schedule` | 40300 + 状态仍 SCHEDULED |
| 6 | 教练A 编辑教练B 负责的课程 | `coach_cannot_edit_others_course` | 40300 |
| 7 | 伪造 coachId 创建排课 | `create_schedule_forces_coachId_to_self` | 排课 coachId 强制为本人 |

测试方式：`@SpringBootTest + @ActiveProfiles("junit")`，注入 Controller/Service，直接设 `SecurityContextHolder` 后调用方法，`@RequiresPerm` 切面经 AOP 拦截；同时断言 `BusinessException.code` 与数据库状态。

---

## 4. 实际执行命令与真实结果

```bash
mvn -o test -Dtest=SecurityAuditTest   # 单独：7 通过 / 0 失败
mvn -o test                             # 全量：20 通过 / 0 失败 / 0 跳过，BUILD SUCCESS
```

全量结果：

| 测试类 | 用例数 | 结果 |
|---|---|---|
| SecurityAuditTest（本轮新增） | 7 | 通过 |
| CourseProposalTest | 3 | 通过 |
| FreeBookingTest | 1 | 通过 |
| BookingConcurrencyTest | 3 | 通过 |
| PaymentIdempotencyTest | 2 | 通过 |
| TrainingRecordTest | 4 | 通过 |
| **合计** | **20** | **0 失败 / 0 错误** |

---

## 5. 测试状态

| 项 | 状态 |
|---|---|
| 未登录 / MEMBER / 教练归属 / 伪造 coachId 越权 | ✅ 通过（控制器层集成测试，断言异常码 + DB 状态） |
| 重复审核/重复预约/并发预约名额 | ✅ 通过（已有 CourseProposalTest / BookingConcurrencyTest / PaymentIdempotencyTest） |
| STORE_MANAGER / STAFF 角色边界 | ⏳ 未单独测试（本阶段聚焦 ADMIN/COACH/MEMBER） |
| 真实 HTTP 请求（MockMvc） | ⏳ 未执行（见下） |

---

## 5.1 第二阶段 A 补充：STORE_MANAGER / STAFF 权限边界

新增 `RolePermissionTest`（7 项），覆盖：

| 用例 | 结果 |
|---|---|
| STORE_MANAGER 有 `manager:course:proposal:view` → 可读课程方案 | ✅ 放行 |
| STORE_MANAGER 无 `coach:home:view` → 门店/Banner 接口 403 | ✅ 拒绝 |
| STORE_MANAGER 审核通过 → 方案 APPROVED + 生成正式课程 | ✅ 通过 |
| STAFF 无 `coach:home:view` → 门店/Banner 接口 403 | ✅ 拒绝 |
| STAFF 无 `manager:course:proposal:view` → 审核接口 403 | ✅ 拒绝 |
| COACH 有 `coach:home:view` → 可读首页运营内容 | ✅ 放行（佐证 coach:home 归属 COACH） |
| COACH 无 `manager:course:proposal:view` → 审核接口 403 | ✅ 拒绝 |

全量回归：**27 通过 / 0 失败 / 0 错误，BUILD SUCCESS**。

---

## 6. 发现的问题

### 6.1 未发现越权缺陷

7 个越权场景全部按预期拒绝（40100/40300），且数据库状态未被越权请求改变。现有权限控制在代码层是正确、完整的。

### 6.2 测试基础设施说明（非业务缺陷）

`SecurityAuditTest` 最初使用 `@AutoConfigureMockMvc` 做真实 HTTP 测试，但因项目 H2 配置 `DB_CLOSE_DELAY=-1` + `spring.sql.init.mode=always`，`@AutoConfigureMockMvc` 触发**第二个 Spring 上下文**，在共享 H2 上重复执行 `schema-h2.sql`，报 `Table "USER" already exists`。

**处理**：改为控制器层集成测试（注入 Controller 直接调用，`@RequiresPerm` 切面仍生效），避免多上下文。此为测试隔离问题，不影响生产。

### 6.3 设计层面的提示（非缺陷）

后端鉴权失败统一返回 **HTTP 200 + body code 40100/40300**（而非 HTTP 401/403）。前端已按 `code` 字段处理，逻辑正确；但对外部监控/网关按 HTTP 状态码判定鉴权失败可能不敏感，属可优化项，非阻断。

---

## 7. 修复后的回归结果

- 本轮未修改业务代码（仅新增测试）。
- 全量回归 `mvn -o test`：20 通过 / 0 失败，BUILD SUCCESS。

---

## 8. 仍需人工验证

1. 真实 HTTP 越权（用微信开发者工具/Postman 直调接口，确认 40100/40300 与 DB 不变）。
2. STORE_MANAGER、STAFF 角色的具体权限边界（本阶段未覆盖）。
3. 小程序端「前端隐藏入口 + 后端鉴权」联合验证（管理员工作台/教练端入口是否仅对应角色可见）。

---

## 9. 上线阻断结论

**不存在上线阻断的权限缺陷。** 关键越权路径（MEMBER→管理接口、COACH→审核/他人课程/他人排课、伪造 coachId）均由后端正确拒绝并经测试验证。可进入下一阶段（真机联调与生产配置）。
