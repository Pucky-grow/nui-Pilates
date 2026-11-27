# 微信云托管：Java 与 MySQL 测试环境准备

准备核查日期：2026-11-01；连接进展更新：2026-11-02（Asia/Shanghai）。这是测试环境准备手册，不是上线通过证明。

**本轮最新状态（2026-11-02 23:04）：** 完整后端的 8 组真实公网 HTTPS 检查已通过，包括专用管理员登录、Redis 会话读写/退出撤销、管理查询、COS 合成图片上传读取及私图边界。云 MySQL JDBC 身份/结构/精确授权的先前预检也已通过，连接仍为用户确认的隔离测试非 TLS 例外。CA 缺失与 Java 8 误部署已有明确修复记录；不需要再次上传旧包。用户核对线上版本为 uni-test-008，镜像摘要尚未独立取得。云端预约/签到、真实微信登录、开发者工具和真机仍待验证。**当前操作以 [测试部署操作单](CLOUDRUN-TEST-DEPLOYMENT.md) 和 [最新机器状态](cloudrun-test-deployment-verification.json) 为准**，下文保留此前准备流程。

**13:43 工单记录（历史）：** 腾讯云工程师在 `202611028033` 确认微信云托管内置 MySQL 不支持开启 SSL，不继续等待该内置库的 SSL 开关。原模板/探针要求 `VERIFY_IDENTITY`，保持未通过状态。后续用户已确认并在 uni-test-002 预检中验证仅限固定隔离 staging 目标的显式内网例外，不作为生产/TLS 通过。下文早先“平台能否开启待核实”是查询时的历史阶段。

**连接验收最新进展（2026-11-02）：** 上海私有测试桶 `yoyi-pilates-test-1505023965` 已通过 **Mac 独立 SDK** 的 owner ACL、真实 HTTPS 合成图片写读/哈希及匿名 403 验证，程序身份策略仍仅覆盖 `objects/acceptance/*`；完整应用 COS 权限及云端访问链路未验。DMC 重新登录后，身份 SELECT 实际成功（33ms），返回 `uni_pilates_test`、`8.0.30-cynos-3.1.19.002`、版本注释 `20230630`、UUID `d1f6adf9-d5d5-11f1-9afc-0c42a17335be`、会话 `+08:00`。随后已复查版本历史、表/引擎/字符集、FREE、管理员及目标 runtime 账号数量，结果见下表；此前连接超时保留为历史尝试。**原生 TLS 当前禁用，阻断 JDBC VERIFY_IDENTITY；平台能否支持开启仍待核实，实际 JDBC 未执行。** 不能把 DMC 元数据查询计为应用 JDBC/TLS 通过。详见 [连接验收记录](CLOUD-CONNECTION-ACCEPTANCE.md) 与 [机器证据](cloud-connection-verification.json)。

**历史结构初始化实测（2026-11-01）：** 用户重新开通后，DMC 已核实新实例 `cynosdbmysql-41yi5vim`，实际为 `8.0.30-cynos-3.1.19.002`，版本注释 `20230630`，UUID `d1f6adf9-d5d5-11f1-9afc-0c42a17335be`。已在该隔离实例创建 `uni_pilates_test`，实际执行原始 V001/V002/V003，58 张表、三个版本全部 DONE，步数分别 55/24/5，校验和一致；重复执行返回 SKIP。FREE 模式、全部表 utf8mb4、八类业务空表及 STAFF/COACH 无财务权限映射已检查。**这只证明当时云端结构初始化与已执行 SQL 的兼容性，尚未证明 Java/Redis/HTTP/微信业务联调。** 见 [MySQL 8 实测记录](evidence/cloudrun/cloud-mysql8-initialization.json)。旧 5.7 的只读观察仍保留在 [历史记录](evidence/cloudrun/cloud-mysql-readonly.json)，不是当前实例。准备包 README 是生成时快照，当前状态以本文和机器记录为准。

## 现在做到哪一步

| 项目 | 实际状态 |
|---|---|
| 小程序对应腾讯云账号 | 用户通过小程序/公众号登录确认：`100053657418` |
| 环境 / 服务 | 微信云托管 `uni-test`，上海 |
| VPC | 截图已选择 `uni-test` / `vpc-72qgp3wk`，子网已可选择；保存结果和真实连通仍需核查 |
| 原个人账号网络 | `100053657475` 下的 `vpc-2y0c09wg` 不属于本次目标，不使用、不删除 |
| 云 MySQL | 58 表、三个 DONE、FREE；runtime 及管理员各 1；云预检 JDBC 只读核查通过，使用限定测试内网非 TLS 例外 |
| Redis | 已购买并运行，SSL/地址绑定和项目 VPC 已核对；本机 TLS 适配通过，云容器实际连接仍待验 |
| Java | 最新制品 cloudrun-6d1cf46a24bf，Java 17/AMD64；修复公共 Redis CA 打包，成品 JAR 和镜像加载检查通过，业务类字节不变 |
| 镜像 | 前版已实际云构建/推送；最新 CA 修复版本地已构建并扫描，等待重新上传，不能重试旧缺 CA 镜像 |
| 私有 COS | provider 和本地合约已测；Mac 独立 SDK 已完成真实桶 ACL、HTTPS 写读/哈希及匿名 403；仅测试前缀，完整应用权限与云端链路仍待验 |
| 云部署 | 002 只读预检正常，003 完整应用因缺 CA 重启；修复包等待云端复验，尚不可交付会员 |

**没有云 Redis 时，可以准备 Java 镜像、隔离测试和 MySQL 结构，不能把云端完整预约后端标为运行通过。** 当前已增加腾讯 COS 私有存储实现并保留 OSS，部署值见 [AMD64/COS 验收与配置](WECHAT-CLOUDRUN-AMD64-COS.md)。完整应用 COS 权限/访问链路、云数据库运行身份/TLS、网络及微信联调仍为独立门槛；不能通过切换 dev、固定 Mock 身份或本地临时文件目录绕过。

初始化时记录的实例参数（2026-11-01，保留历史值；2026-11-02 的复查与差异见随后表格）：

| 参数 | DMC 实查结果 |
|---|---|
| 产品 / 版本注释 | 云托管 MySQL（Cynos 内核），`20230630` |
| 完整版本 | `8.0.30-cynos-3.1.19.002`；托管实例未暴露容器镜像摘要 |
| 全局 / 当前会话隔离级别 | 均为 `READ-COMMITTED` |
| 全局 / 当前会话时区 | 均为 `+08:00`，系统显示 `CST` |
| SQL 模式 | `ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION` |
| autocommit / 引擎 | `1` / InnoDB；非 InnoDB 表数 0 |
| 实例默认 / 测试库字符集 | `utf8mb3` / `utf8mb4`；测试库排序规则 `utf8mb4_0900_ai_ci` |
| TLS | DMC `SHOW SESSION STATUS LIKE 'Ssl_cipher'` 值为空；不能判定应用 JDBC TLS 已通过。实际私网地址、CA/证书和 VERIFY_IDENTITY 连接仍需单独验证，不改为关闭校验 |

2026-11-02 本轮 DMC 只读复查结果：

| 核查 | 实际结果 |
|---|---|
| 目标身份（33ms） | 库、完整版本、版本注释、UUID 与上述目标一致；会话 `+08:00` |
| `schema_history`（8ms） | V001/V002/V003 均为 DONE，原始校验和一致，步数分别 55/24/5 |
| 结构与账号聚合（33ms） | 58 表；非 InnoDB / 非 utf8mb4 表异常数均为 0；FREE；`sys_user=0`，`uni_pilates_runtime` 账号计数 0 |
| TLS 变量（9ms） | `have_ssl=DISABLED`、`require_secure_transport=OFF`，`ssl_ca` / `ssl_cert` 为空；`tls_version=TLSv1.2,TLSv1.3` 仅为协议配置列表，不能证明 TLS 已启用 |
| SQL 模式差异 | 本轮记录的 SQL 模式包含 `IGNORE_SPACE`；不覆盖上表历史记录 |

上述复查没有创建账号、迁移或写入业务数据。原生 TLS 当前禁用会阻断 `VERIFY_IDENTITY` 验收；需核实此云托管实例是否支持受控开启并提供可验证的主机名/CA，不能根据协议列表推断已启用，也不改为关闭校验。实际 JDBC 探针尚未运行，可达内网的受控执行位置仍需确认。

## 1. 先在微信云托管开通 MySQL

**本次第 1、2 节已完成。** 以下是首次流程，供后续新隔离环境复用；当前 `uni_pilates_test` 已有 58 张表，不再建库或重导，后续使用第 3 节的只读检查。

1. 确认环境 `uni-test`、上海、小程序 AppID `wxd9bd556ad0c77104`。
2. 左侧 **MySQL → 开通数据库 → MySQL 8.0**。界面默认 5.7 时必须改成 8.0。
3. 管理员密码由经营方自行设置、保存在密码管理器；不用发到聊天、仓库或命令行。
4. 仅开通这个隔离测试环境的数据库。不要在另一个腾讯云账号另买 CDB，以免无法使用既有微信侧赠送额度。
5. 开通成功后记录：产品名、完整版本、实例/集群 ID、地域、内网地址、端口、备份与 TLS 入口。密码不记录进本手册。
6. 暂不加载旧 `database/schema.sql`、`database/init_data.sql`，不创建真实会员或排课。

之前控制台显示的是 CPU 720 核·小时、内存 1440 GiB·小时、MySQL 720 CCU·小时和 720 GB·小时等赠送额度，有效期三个月。用完或到期可能产生费用；不代表每个月补满或整套系统三个月无限免费。数据库开通弹窗的 0.01 元/月只是系统库占用说明，不是数据库总价。

参考：[微信云托管计费说明](https://cloud.tencent.com/document/product/876/113602)。账号实际余量以当前控制台为准。

## 2. 创建空测试库，先跑只读版本检查

通过该实例自己的“数据库管理/SQL 窗口”入口进入。平台入口及账号权限要以实际页面核对；不要把普通 CDB 的菜单位置当成微信云托管的完成证据。

官方提供 [DMC 数据库管理说明](https://docs.cloudbase.net/database/configuration/db/tdsql/dmc-data-manage)，支持在数据库管理窗口执行 SQL。

确认连接到**新开的隔离测试实例**后，手工执行准备包中的：

```text
database/01-create-test-database.sql
```

只创建 `uni_pilates_test`，字符集 utf8mb4、排序规则 utf8mb4_0900_ai_ci。若同名库已存在，脚本报错并停止；先核查已有内容，不删除重建。

随后在 SQL 窗口选择这个库，执行：

```text
database/00-readonly.sql
```

检查通过标准：

- `full_version` 和 `version_comment` 明确显示目标产品与 MySQL 8 内核；记录完整小版本，不能只写“8.0”。
- 记录服务器 UUID；若平台不提供 `@@server_uuid`，停止后续离线工具执行，研究该产品可核验的实例身份方案，不取消校验。
- `existing_table_count=0`；不是 0 则按已有库处理，不能继续 fresh。
- 测试库与表必须 utf8mb4；服务端默认值单独记录。当前实例全局默认 utf8mb3，不改整个实例，通过显式建库保证全部 58 表均为 utf8mb4。记录排序规则、sql_mode、隔离级别、时区和 TLS 状态。
- 严格 SQL 模式可用；应用连接须建立 `+08:00` 会话。无需修改整个实例的全局时区来冒充会话设置成功。

可提供版本和参数结果用于核对，不提供密码、连接配置文件或真实业务数据。本轮不改变真实业务库。

## 3. 已实测的 DMC 全新测试库初始化

本次没有从 Mac 直连云私网，也没有公开数据库。使用既有、已登录的 DMC 数据库管理页面执行。为保留原离线迁移工具的语句切分、校验和与进度语义，增加了测试专用生成入口：

```bash
python3 scripts/cloudrun/dmc_prepare.py \
  --bundle build-artifacts/cloudrun-prep-985d9469c64f \
  --expect-server-uuid d1f6adf9-d5d5-11f1-9afc-0c42a17335be \
  --full-version 8.0.30-cynos-3.1.19.002 \
  --confirm-isolated-database uni_pilates_test \
  --output build-artifacts/NEW_DMC_OUTPUT_DIRECTORY
python3 scripts/cloudrun/dmc_check.py
```

生成器先校验准备包，调用已审核 JAR 中实际 `DatabaseTool.split`，原三份迁移内容和 checksum 不变。不连接网络，不生成密码、CREATE USER 或 GRANT。已生成并执行的目录为 `build-artifacts/cloudrun-dmc-20261101`。

操作顺序和停止条件：

1. DMC 选择 `uni_pilates_test`，左侧 **存储过程 → 加号 → 新建存储过程**。名称见生成目录 manifest，安全性选 **INVOKER**，数据访问选 **MODIFIES SQL DATA**，下一步把完整定义替换为 `01-install-routine.sql`，提交。
2. **不要把完整过程定义放进普通 SQL 窗口。** 本次实测普通窗口会拆成 220 句，首句报 1064；已取消剩余 219 句，复查表数仍为 0。对象管理入口提交完整定义成功。
3. 过程内绑定本库、UUID、完整版本、字符集，要求严格 SQL/autocommit，并持有与原工具相同的会话迁移锁。新库必须无表、触发器、事件或其他过程；不会覆盖已有结构。
4. SQL 窗口单独执行 `02-call.sql`。错误 UUID 实测报 1644，且表数仍为 0；正确目标实测返回 APPLIED。V002 基础数据在一个事务内提交，DDL 逐步记录进度；任何错误回滚当前事务并停止，已提交 DDL 不会伪装成全部回滚。
5. 再执行同一 CALL，三个版本 checksum/步数完全匹配且为 DONE 时才返回 SKIP。部分执行、DIRTY、变更或未知历史立即停止；不自动恢复、不删除历史。失败后使用原升级手册核对真实结构与步数。
6. 运行 `deploy/wechat-cloudrun/database-postcheck.sql`。当前版本记录为 55/55、24/24、5/5，全部 DONE，总表数 58，FREE；账号、微信用户、会员、教练、课程、排课、预约、订单均为 0。`SHOW CREATE TABLE booking` 已确认新有效预约生成列/唯一索引，旧 `(user_id,schedule_id)` 唯一约束不存在。

初始化过程为 SQL SECURITY INVOKER，不给调用者提升权限；未给应用运行账号授予 EXECUTE，尚未创建运行账号。它只是当前隔离库初始化入口，不替代业务环境升级方案。SQL `FOR UPDATE SKIP LOCKED` 已在空 outbox 实际执行成功，但没有验证任务竞争或事件投递。

本轮没有验证中途断连/进度落后结构时的人工恢复、并发迁移、取消重约或旧库升级路径；当前通过项不能替代这些验收。初始管理员也尚未建立，`sys_user=0` 是预期结果，不存在可直接使用的演示密码。

参考：[MySQL 调用者权限](https://dev.mysql.com/doc/refman/8.0/en/stored-objects-security.html)、[DDL 隐式提交](https://dev.mysql.com/doc/refman/8.0/en/implicit-commit.html)。

### 3.1 原离线入口（取得受控私网连接之后）

复用现有离线 `DatabaseTool`，按原始 V001/V002/V003 的 SHA-256 与进度表执行。三份迁移文件原样复制，未重写业务规则，未加载演示资料。新库不能再次执行 Round2 booking ALTER。

**执行位置必须是已获授权、能访问 MySQL 内网的运维位置。** 本机没有现成私网通道，不为导入临时开放数据库公网、不新增收费服务器。DMC 初始化已按上节执行；以后使用离线工具时应读取现有 DONE 记录并 SKIP，不把它当空库重导，也不重建数据库。

准备包提供 `tools/database.py`，只接受显式隔离库 `uni_pilates_test`、已核实 UUID、Git/上传目录之外的 0400/0600 配置文件。工具不输出凭据；真实文件采用模板中的普通 ASCII `key=value` 格式，不用转义或续行；初始管理员显示名先用 ASCII，避免 Java Properties 编码歧义。

1. 在受控私网运维位置放置准备包，复制 `private-configuration/database-tool.properties.example` 到受保护目录；填写真实内网连接、TLS 信任和管理员凭据。模板只是清单，不能直接运行。
2. 确认该连接只指向上述隔离测试实例及空库。设置真实文件权限 `chmod 600`。
3. 验证包及本地参数。以下路径为占位符，替换为实际准备包路径；UUID 来自第 2 步。

```bash
python3 /PATH/TO/BUNDLE/tools/verify.py --bundle /PATH/TO/BUNDLE
python3 /PATH/TO/BUNDLE/tools/database.py \
  --bundle /PATH/TO/BUNDLE --config /SECURE/uni-migrator.properties \
  --expect-server-uuid CONFIRMED_SERVER_UUID \
  --confirm-isolated-database uni_pilates_test --mode fresh --check-only
```

4. 只有操作者已确认隔离目标后，去掉 `--check-only` 执行 `fresh`。本工具不会创建数据库，也不会启动 Web、Redis、短信或支付服务。
5. `fresh` 后检查 `schema_history` 三个版本全部 DONE、checksum 与包内文件一致；V002 设置 FREE，且没有演示账号、课程、排课、会员和订单。
6. 管理员连接可以在初始化后执行 `--mode provision` 创建限定本库的 runtime/migrator。运行账号仅业务表 DML、schema_history SELECT；已有同名账号会失败，不复用未知宽权限。确认容器来源子网后再填写 host，不能把 `%` 当默认值。DMC 使用其他来源时单独限定运维账号 host。
7. 账号初始化使用 `--mode bootstrap`，仅空 `sys_user` 表可运行；密码在本机隐藏 stdin 输入，不能写进命令行或聊天。它不是业务会员数据初始化。

执行错误、未知版本、DIRTY、checksum 变化、数量异常均停止，不自动回滚 DDL或删除历史。重复完成的同版本会 SKIP。显式修复/续跑只参考已有 [生产升级手册](PRODUCTION-UPGRADE-RUNBOOK.md)，本新库包装器不提供未经复核的 adopt/resume 模式。

## 4. Java 构建包怎样准备、之后怎样上传

准备入口在项目目录运行：

```bash
python3 scripts/cloudrun/prepare.py --artifact build-artifacts/cloudrun-5d01bc24aa8d
```

生成目录写入 `build-artifacts/current-cloudrun-preparation-path.txt`。保留原生产制品和当前本地开发服务，准备工作不修改其文件。

复核入口（包装器检查仅使用合成配置，不连接任何数据库）：

```bash
python3 scripts/cloudrun/verify.py --bundle /PATH/TO/BUNDLE
python3 scripts/cloudrun/check.py --bundle /PATH/TO/BUNDLE \
  --report docs/evidence/cloudrun/database-wrapper.json
```

准备包里的 `java-upload/` 和 `java-upload.zip` 都只包含四项：

```text
Dockerfile
.dockerignore
container.config.json
pilates-server.jar
```

Java 类文件为 Java 17，准备包校验 JAR、当前 main/pom 及已构建的四文件上下文。当前本地镜像 `yoyi-server:cloudrun-5d01bc24aa8d` 已以 Linux/amd64 构建、扫描并进行隔离运行；具体范围和仍待验证项见 [验收记录](WECHAT-CLOUDRUN-AMD64-COS.md)。旧 ARM64 镜像不上传。本机镜像尚未推送，ZIP 只是云端代码构建入口，云端再构建/启动的结果需要另验。

重建入口（不会推镜像，使用独立内容版本并运行相关单元测试）：

```bash
python3 scripts/cloudrun/build.py --docker-context colima-pilates-staging \
  --java-home /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

云托管控制台支持本地代码/文件夹时，选择**只包含四项文件的 `java-upload` 目录**，具体 ZIP/文件夹方式以实际控制台为准；不要上传项目根目录或 `private-configuration/`。GitHub 当前未授权，首轮无需为打包开放仓库或触发自动发布。私有仓库应采用平台支持的私有方式，不改公开。

**现在不要点“发布”。** 页面发布通常包含构建和启动；不能把它当作无运行费用的文件存放操作。缺少 Redis 或私有持久存储时，完整后端会被启动校验拒绝，这是预期保护。

## 5. 完整业务服务发布前的配置值

| 项目 | 值 / 要求 |
|---|---|
| 服务 | `uni-test`；小程序 X-WX-SERVICE 后续与实际服务名一致 |
| 端口 | **8080**；不是 80 或本地开发代理 8090 |
| 运行环境 | `staging`，不可切 dev；默认 Mock/短信/支付/购课全部关闭 |
| 首轮规格 | 1 核 / 2GiB，1 个实例；最终以内存和负载实测为准 |
| 实例数量 | 完整业务测试时 min=1 / max=1，确保定时任务与 outbox 执行；实例启动后消耗资源额度 |
| 网络 | 已选择的新 VPC / 子网，保留平台公网出口；真实 DB/Redis/TLS 连通需验证 |
| 公网入口 | 暂关；未来 Web 管理端使用受控 HTTPS 入口和鉴权 |
| 日志 | stdout，禁止输出连接密码、JWT、AppSecret、个人资料 |
| 健康 | 管理端口 9091 保持私有；平台若无法使用 TCP 8080 健康检查，先处理受控健康端点再发布，不公开整个 actuator |
| MySQL | scoped runtime，不用 root；V001/V002/V003 DONE，FREE、严格模式、+08:00 会话 |
| Redis | 独立凭据和实际验证的 TLS；尚未开通时保留为空并禁止发布 |
| 存储 | `STORAGE_TYPE=cos` 与 COS_REGION/BUCKET/SECRET_ID/SECRET_KEY，可选 SESSION_TOKEN；专用私有桶启动检查，真实策略/访问验收另做；OSS 仍保留 |
| 微信 | 当前仍是 wx.login/code 换 JWT；callContainer 传输、可信微信身份与 AppID 校验另行适配验收 |

`private-configuration/runtime.env.example` 是非秘密清单，真实值只在平台受保护配置里填写，不能放 Dockerfile、container.config.json、ZIP 或 Git。

## 6. 本阶段完成标准与下一步

- 本地准备完成：JAR/source 哈希一致、上传白名单验证、迁移原文哈希一致、离线包装器安全拒绝错误配置。
- 云 MySQL 准备完成：已实际核实 8.0.30 Cynos、UUID、参数并从隔离空库初始化；原 5.7 状态已被新实例观察取代。
- 结构初始化完成：历史三份迁移及重复执行已检查；2026-11-02 另已复查 58 表、三个 DONE 校验和/步数、编码/引擎及 FREE。scoped 数据库运行账号和初始管理员计数仍为 0。原生 TLS 当前禁用阻断 VERIFY_IDENTITY，平台开启能力及受控私网执行位置待核实，实际 JDBC 未执行；不能以 DMC root 查询代替应用账号验收。
- 本地 AMD64 镜像、COS 代码与 SDK 合约已完成验证；Mac 独立 SDK 的真实 COS ACL/HTTPS 写读/哈希及匿名 403 也已通过。后续完整应用 COS 权限/访问链路、Redis、环境配置、云端真实 HTTP、微信开发者工具/真机均独立验收。

最终仍需实测免费团课/私教预约、取消重约、历史 bookingId、签到、容量/整节取消、角色撤销及库存一致性。此次准备不扩大支付、候补、首页等功能，不发布小程序。
