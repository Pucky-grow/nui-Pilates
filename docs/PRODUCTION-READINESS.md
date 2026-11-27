# 生产整改与部署就绪核查

核查日：2026-10-16。范围：单店 FREE 团课、私教预约、签到、小程序三种工作区与 Web 管理端。本轮未正式发布。

## 结论与执行边界

第一批业务代码整改与本地隔离验收已完成；监控候选制品的安全门禁未通过，仍有下表列出的发布前条件。用户确认尚未购买阿里云服务器，因此没有创建、购买或部署云资源，没有推送远程仓库、改 DNS 或开放公网。未连接真实业务库，连只读预检查也未执行；没有迁移、补数据、调整真实权限或清理真实 Redis。测试数据均为合成资料。

| 项目 | 实际状态 | 能证明的范围 |
|---|---|---|
| Java / Web / 小程序逻辑整改 | 本地通过 | 见源码、去重后的用例清单及制品哈希 |
| MySQL 8.0.46 | 实测通过 | 原生隔离实例的迁移、集成、恢复；Linux 容器的生产配置和 HTTP |
| MySQL 8.4.11 | 本地容器通过 | 干净初始化、最小权限、真实 Web/HTTP、100 人抢约、容量及依赖恢复；没有把 8.0 的全部旧库迁移测试计为 8.4 通过 |
| Redis 7.4.10 | 本地通过 | 认证、锁、Lua、库存、会话、断连恢复；未验证云 Tair/TLS |
| Web 真实联调 | 本地通过 | Vite 代理、Chrome、生产构建、TLS Nginx、SPA、真实测试后端 |
| 文件存储 | 本地私有实现通过 | 真实 multipart、公开素材、私有授权/过期、稳定引用与卷恢复；OSS SDK 已集成，真实 OSS 未调用 |
| 微信开发者工具交互 | 待验证 | 原生模板编译不能替代登录、上传、域名或 UI 验收 |
| iOS / Android | 分别待验证 | 未使用真机完成本轮用例 |
| 阿里云 ECS/RDS/Tair/OSS | 阻塞 | 尚无资源、账号操作授权、地域、预算和指定私有仓库 |
| 隔离备份恢复 / 应用回滚 | 本地通过 | 两个不同 MySQL 实例、加密备份、文件引用；兼容候选镜像往返保留历史 |
| 正式上线 | 条件未满足 | 不宣称可上线，阻断项见文末 |

机器结果和准确命令见 [production-readiness-verification.json](production-readiness-verification.json)，可纳管证据在 [evidence/production/](evidence/production/)。原始运行目录位于本机 `/tmp/pilates-production-final-*`，不是唯一的交付证据。

## 基线与版本依据

开始时没有找到项目 `AGENTS.md`，没有可引用的项目附加指令。已核对 README、Round2/3 SQL、验收 JSON、Round3 升级手册、管理员工作台记录及关联实现。旧工作台的 54 个后端方法和 34 项小程序检查是历史记录，没有直接记成本轮通过。

原仓库没有首次提交。先排除秘密、上传、日志、备份、构建结果和依赖，扫描后建立本地基线 `f0eb000e5f913ebe23fe9872700da063de8bad68`。没有远程提交或推送，原有代码未重置。本轮保留为可审阅的工作区修改。根目录及前后端构建上下文各有 `.dockerignore`。

| 组件 | 本轮选择 | 依据和兼容验证 |
|---|---|---|
| Java | 编译目标 17；镜像 Temurin 17.0.20.1_1 | 本机构建 JDK 21 的 release 17；真实 Linux 运行是 JRE 17 |
| Spring Boot | 4.1.1 | 旧版本维护风险；采用仍受维护版本，并按官方迁移 Boot4 starter、测试和安全自动配置 |
| MyBatis-Plus / Redisson | 3.5.17 Boot4 / 4.8.0 | 实际 JDBC、事务、锁、Security、HTTP 回归；没有仅以编译成功判定 |
| JDBC 驱动 | MySQL Connector/J 9.7.0 | 以最终 SBOM 为准；驱动版本不能当作数据库服务器版本 |
| JSON | Jackson 2.21.7 / 3.1.7 | 保留官方 Jackson2 兼容模块以维持原接口；该模块已弃用，后续迁出需另验 |
| Servlet / 加密库 | Tomcat 11.0.25 / BC 1.85 | 对扫描发现的高风险依赖作定点更新 |
| Node / Web | Node 24.20.0 LTS；Vue 3.5.44、Vite 8.3.4 等按锁文件 | npm ci、生产构建、真实 Chrome 与 npm audit |
| Nginx | 1.30.6 Alpine slim，zlib 1.3.2-r1 | 采用官方安全修复版，避免旧全量镜像的额外模块风险 |
| 数据库 | 8.0.46 兼容基线 / 8.4.11 本地 LTS 演练 | 8.0 已结束常规维护；云 RDS 内核和维护政策仍需单独确认 |
| 事件 | 同事务数据库 outbox | 移除未部署的 RocketMQ 桥接；首发单实例无需另运维 broker |

核查依据：[Spring 支持版本](https://github.com/spring-projects/spring-boot/wiki/Supported-Versions)、[系统要求](https://docs.spring.io/spring-boot/system-requirements.html)、[JSON 兼容模块](https://docs.spring.io/spring-boot/reference/features/json.html)、[MyBatis-Plus](https://baomidou.com/getting-started/)、[Redisson](https://redisson.pro/docs/integration-with-spring/)、[Node 维护状态](https://github.com/nodejs/Release)、[MySQL 生命周期](https://www.mysql.com/support/eol-notice.html)、[Nginx 安全公告](https://nginx.org/en/security_advisories.html)。依据核查日为 2026-10-16；之后部署前应重新核对公告。

基础镜像标签及远端摘要固定在 `deploy/image-lock.json`。本地制品为 Linux/arm64，不能默认部署到 x86 ECS；选 x86 时须在对应架构重建、扫描及验收。制品 manifest 记录基线提交、构建时工作区快照 SHA256、逐文件哈希、jar 哈希和三个镜像 ID。当前发布镜像未推送，镜像 ID 不是已存在于私有仓库的远端摘要。测试/文档和微信源码的后续修订另记录哈希，不改变已验证的 Java/Web 镜像内容。

最终制品版本 `staging-d9c017ecc5b4`，交付目录 `build-artifacts/staging-d9c017ecc5b4/`（不纳入 Git）：jar、镜像离线归档及校验和、构建 manifest、部署模板、生产 SQL、工具与验收文档。`scripts/production/assemble.py` 校验运行源码与已测镜像快照一致，再单独记录部署文档补充包的校验和；不推送、不部署。参数实测记录见 `evidence/production/mysql-container-parameters.json`。

## 修复文件与原因

| 文件或目录 | 修复及业务影响 |
|---|---|
| `server/pom.xml`、`admin/package*.json`、`.nvmrc` | 维护版本与扫描修复；保留 Java 17 及现有架构 |
| `database/production/`、`infra/tools/DatabaseTool.java` | 干净 V001/V002/V003、校验和、DIRTY 停止、显式恢复、旧库接管、最小账号、stdin 一次性管理员 |
| `ProductionEnvironmentGuard` / `ProductionDatabaseGuard` | 在生产/测试启动拒绝开发 Mock、缺失启用配置、root/DDL 账号、未知结构、错误时区及非 FREE 数据库规则 |
| `AuthService`、`LoginProtection`、JWT / AccountService | BCrypt 前拦截账号/IP 失败；有限期恢复、可信代理、凭据版本撤销、受控创建/停用/重置和本人改密 |
| `Accounts.vue`、小程序 `admin/accounts/`、`System.vue` | 复用工作台补账号生命周期和真实规则表单，修复分页字段；不提供任意权限编辑器 |
| `infra/storage/`、`FileController` | 默认私有、OSS 角色/外部凭据、类型/解码/尺寸/限流、命名空间归属、稳定引用和 5 分钟签名 |
| `CoachCourseController`、干净权限种子 | 补齐既有本人课程/方案能力的六个权限点；微信绑定教练可按实际授权上传封面，撤销后立即拒绝方案/上传。不授予全局首页、财务、会员写入或系统权限 |
| 小程序上传辅助、Banner/课程编辑/头像 | 上传后使用可读预览，防止稳定引用直接显示及域名重复拼接；保存由后端归一到稳定引用 |
| `infra/delivery/`、BookingService | 关键事件与业务同事务持久化；失败回滚、受控重试、数据库消费幂等 |
| `PilatesScheduler`、`TaskMutex`、`OrderService` | keyset 分批、上海时区、调度互斥；历史超时订单逐笔事务，失败行不会挡住后续批次 |
| `UserMapper.lockById`、BookingService | 数据库会员行锁补强跨排课每日上限，避免只依赖 Redis 会员锁 |
| `PaymentService`、`RefundService`、capabilities 和相关页面 | 首发新支付/退款默认关闭且生产禁止打开；历史查询、回调和零元历史订单必要处理保留 |
| 课包、个人资料、门店、隐私及协议页面 | FREE 保留已有课包查询；关闭不可用短信流程；真实资料缺项标待配置，政策是待审核草稿 |
| `deploy/`、`scripts/production/`、`DeliveryMetrics` | 非 root、只读根目录、资源/日志、TLS 代理、就绪与存活、监控、加密恢复及可复现构建 |

无真实短信、支付、退款系统开发；未扩展周期排课界面、候补或新的前台业务。既有签到及有预约历史的排课变更限制保留。

## 证据矩阵与统计口径

本轮最终去重方法清单由 Surefire XML 汇总；同一方法复跑只保留最后结果。V002 权限种子已改为按角色编码关联，避免 DML 回滚消耗自增 ID 后重试出现错位；新增中断恢复演练验证角色 ID 跳号时授权完整且无孤立映射。首次回归的组件适配器还要求“管理员有退款按钮”，与本轮关闭新退款的规则矛盾，出现一项失败；已把断言改为保留历史金额查询且不展示退款动作，相关真实 HTTP/Web 回归已通过。原失败和后续结果均保留，不改业务约束来迎合旧断言。

新增教练 HTTP 用例初次因测试请求字段误用失败，改用接口已有字段后通过；没有放宽生产校验。最终为 **91 个不同后端测试方法、39 项小程序逻辑测试**。静态文件、编译文件数、SQL 场景及同一方法复跑均未叠加进这两个数量。

严格 SQL 模式的首次故障夹具只改了服务器全局参数，但 Connector/J 自动恢复了会话严格模式，未触发预期拒绝。已在隔离负例中显式关闭驱动的自动截断检查，再验证非严格会话被启动检查拒绝、恢复后重新就绪；正常部署仍保留驱动默认保护。[驱动参数说明](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-result-sets.html)。

| 约束 / 能力 | 当前源码 / SQL | 实际证据及范围 |
|---|---|---|
| 有效预约唯一、取消多次重约、旧 ID 隔离 | `uk_active_user_schedule`、BookingService | RoundOne 重约/唯一、Round3 oldBookingId、13 项 SQL 场景；历史行保留 |
| 锁覆盖提交、条件更新、签到原子、人数口径 | ScheduleExecution、预约/attendance mapper | Round3 beforeCommit、RoundOne 条件失败/attendance 注入；签到重复只生效一次 |
| 容量、整节取消、资源重叠、周期/审批不绕过 | SchedulingService 和管理入口 | SchedulingAndFreeFlowTest 的并发、编辑、周期、审批方法；AdminWorkspaceTest 管理流 |
| 100 人抢 8 席、跨课每日上限 | Redis 锁 + DB 排课/会员行锁 + 唯一键 | 原生 DB 同步屏障、两个 Spring HTTP 上下文；Linux 单进程 100 请求实测 |
| 提交后 Redis 失败、回滚/重建/重启 | ScheduleStock、事务同步、校准任务 | RoundOne / Round3 故障用例；Linux Redis/MySQL 停止恢复、容器重建 |
| 权限撤销及身份/收入边界 | JWT 实时加载 RBAC、auth_version、范围 DTO | Round3Http、MemberHttp、AdminWorkspace、ProductionService；组件适配器不算后端拒绝证据 |
| 登录阻断、过期恢复和会话撤销 | LoginProtection、AccountService | 阻断后 spy 证明没有调用 BCrypt；真实 HTTP 验证旧 Token 失效 |
| 生产关闭 Mock / 新购 / 支付退款 | 启动检查、能力开关、服务端入口 | ProductionGuard / Round3Configuration / positivePayment...；PaymentIdempotency 保留历史幂等及零元处理 |
| 文件上传/引用/可读与私有 | FileService、FileReferenceAdvice | ProductionService 真实 multipart 后保存课程、Banner、教练头像，DB 为稳定引用；私有越权/过期服务测试 |
| 可靠事件、重复/失败、重启 | outbox 事务、通知唯一键 | enqueue 失败回滚、消费失败重试、重复消费；真实后端重启 5 个待办事件各完成一次 |
| 超过单批任务 | scheduler keyset、逐单事务 | 205/505 行场景、后段到期记录及单笔失败回滚；不以扫描第一批成功算完成 |
| 初始化、失败中断、最小账号 | DatabaseTool、schema_history | 故障注入、明确 resume、重复 skip、拒绝非空 fresh、接管保留授权、运行账号 DDL 被拒 |
| 恢复与回滚 | backup/restore-isolated、候选镜像 | 另一 MySQL UUID，预约/签到/容量/权限/outbox/文件逐项核对；镜像往返不降结构、不覆盖新增记录 |

占位定义：`booking.deleted=0 AND status IN ('BOOKED','SIGNED_IN','COMPLETED','NO_SHOW')`。CANCELLED、REFUNDED 和逻辑删除不占位。排课人数须与占位相同；可预约排课 Redis 库存为容量减占位，已取消/结束排课为 0。不能只数 BOOKED。每个并发方法的成功/业务失败原因、排课人数、各状态及 Redis 值保存在 `case-evidence.jsonl`；容器 HTTP 单列 attempts=100、successes=8、失败原因、DB=8、occupied=8、stock=0。

原生 8.0 集成测试使用运行器创建的专用账号与数据库；该测试账号有 fixture DDL 权限，不能用其证明生产最小权限。生产账号的证明来自另起的 staging 后端及 Linux 镜像，实际 DDL 和修改 schema_history 被数据库拒绝。

两个 HTTP 后端上下文共享真实 MySQL/Redis，但在同一个测试 JVM；本轮没有重新执行两个独立 OS 后端进程的测试，不能由此声称多实例部署已验收。首发 Linux 验收是单后端实例，符合当前部署方案，存在单点和维护停写窗口。

小程序 Node 测试为逻辑/请求桩；JS/JSON 静态解析和原生 wcc/wcsc 编译为不同类型，不相加成业务覆盖数，不算开发者工具或真机通过。微信验收按环境分别留待执行。

## 安全扫描与保留风险

已对最终 server/admin/edge 镜像执行 Trivy，前端执行 npm audit；扫描摘要、工具版本、时间及必要组件清单随证据交付。三个应用镜像未检出 HIGH/CRITICAL，Web 依赖审计为零。server 仍有 27 MEDIUM、32 LOW 的 Ubuntu 运行库记录，admin/edge 为零；不能称为“零漏洞”。freetype/png 的可用中危补丁已补齐，其余以扫描当时供应商状态为准。云部署前应重新扫描，并对剩余项作适用性评估和明确风险接受；不得把本轮扫描永久复用。

镜像扫描对象为交付的三个应用镜像，不代表阿里云 RDS/Tair 内核通过。MySQL 8.0 仅隔离兼容夹具，不作为新生产选版。Jackson2 兼容模块、单实例可用性、依赖滚动更新、文件孤立对象人工核查、任务积压/终态失败和值守计划仍需维护。

秘密扫描覆盖拟纳管源码和交付证据，排除已忽略的本地依赖、测试产物及秘密目录；报告不输出密码或 Token。所有临时测试实例均核对目标身份并在结束清理，仅操作自己创建的资源。原 `127.0.0.1:8090` 开发服务未因本轮构建被自动替换，它不是新生产制品部署证据。

## 升级、恢复与发布阻断项

操作入口：[数据库升级及回滚](PRODUCTION-UPGRADE-RUNBOOK.md)、[阿里云测试落地](ALIYUN-STAGING-RUNBOOK.md)、[微信工具/真机](WECHAT-STAGING-CHECKLIST.md)、[正式发布清单](GO-LIVE-CHECKLIST.md)。旧 `docs/上云/` 已注明历史方案失效，不能继续按旧 CloudBase 路线操作。

正式发布至少尚缺：

1. 已授权云账号、地域、规格/预算、私有仓库及测试资源；根据实际架构构建验收制品。RDS/Tair 的内核、参数、认证/TLS、VPC 和 OSS 角色访问/恢复均须实测。
2. 测试 HTTPS 域名、DNS/证书续期、所需备案、微信测试应用授权及合法 request/upload/download 域名；真实微信登录未验。
3. 微信开发者工具、iOS、Android 的业务工作台、免费预约/多次重约/签到、图片、返回刷新、键盘、长列表、断网与时区边界验收。手机号与订阅提醒默认关闭，未验不承诺。
4. 经营主体、门店真实联系方式/地址、隐私联系与保存期限、注销/更正的可执行流程和政策审核；当前政策只是草稿。
5. 真实业务库只读预检查、历史异常和旧文件/MQ 待办盘点、备份权限与恢复目标。没有连接，真实数据状况未知，不能判断现在适合直接迁移。
6. 生产规模的恢复演练、经营方确认的 RPO/RTO、维护窗口、操作人、备份保留/密钥保管、持续监控与告警接收人。小合成数据恢复秒数不是生产恢复承诺。
7. 监控候选 v3.13.4 存在 10 HIGH，v3.15.0 仍有 8 HIGH，禁止直接部署，须更换修复后的制品或批准的托管监控；当前可运行只读巡检但不代表持续告警已交付。部署前重新扫描并评估应用镜像剩余中低危；确认兼容候选回滚制品。不得直接回退 Round2/3 老二进制或删除历史来重建旧索引。

本轮没有执行上述外部操作，也没有以购买、部署或发布作为完成本地交付的前提。
