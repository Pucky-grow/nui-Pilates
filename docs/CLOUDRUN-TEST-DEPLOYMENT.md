# 腾讯云测试后端部署操作单

更新：2026-11-02，Asia/Shanghai。本单是隔离测试部署交接，不是生产上线证明。最新机器证据见 `docs/cloudrun-test-deployment-verification.json`。

**最新实测：23:04 开始的完整应用 HTTPS 验收通过，共 8 组检查。** 公网已返回 `pilates-server`，不再是预检入口；测试管理员登录、Redis 会话读写与退出失效、课程/会员查询、两张合成图片的 COS 上传及读取、私图访问拒绝均通过。证据见 `docs/evidence/cloudrun/redis-ca-fix-20261102/cloud-application-http-smoke-2.json`。用户核对当前线上版本为 **uni-test-008**，不能把旧 006/007 的日志当作当前结果。预约/签到云端业务验收、真实微信登录、开发者工具和真机仍待验证；MySQL 仍仅使用已确认的测试内网非 TLS 例外。

## 目标与已验证范围

- 微信环境：`uni-test-d5gwbyymua6bf2ada`，上海，服务 `uni-test`，账号 `100053657418`，AppID `wxd9bd556ad0c77104`。
- MySQL：`cynosdbmysql-41yi5vim`，`10.39.100.38:3306/uni_pilates_test`，实际 `8.0.30-cynos-3.1.19.002`，UUID `d1f6adf9-d5d5-11f1-9afc-0c42a17335be`。用户亲自完成账号初始化，随后 DMC 只读复核：58 表、1 个 runtime、1 个启用的 `yoyi_owner` ADMIN；59 条 SHOW GRANTS 与草案精确一致（57 表四项 DML、schema_history 只读），无额外全局/库级/列级/过程/角色权限。没有重建库或重导迁移。
- Redis：`crs-eyyecodn` / `yoyi-redis-test`，`10.60.1.2:6379`，兼容 7.0、引擎 7.1.10、代理 5.8.30、256MB。实例运行中，SSL 已开通，证书地址绑定 `10.60.1.2`。VPC `vpc-72qgp3wk` / 子网 `subnet-c4vyxl77`，`10.60.1.0/24`。安全组 `sg-jj3dltvk` 仅入站允许该子网 TCP 6379。公网未开启、自动续费关闭。
- Redis CA 从该实例 SSL 页下载，PEM SHA-256：`620fec36c5647475f0be9c51afe21d05d88ccf61cfadc578ceb069d8def97132`。仅公共 CA 进入 JAR；无私钥/连接密码进入镜像。普通 Redis 与 Redisson 共享 SSL bundle，并保持服务器地址校验。
- COS：上海私有桶 `yoyi-pilates-test-1505023965`。用户已确认并实际关联策略 `YoyiPilatesCosRuntimeTest`（292277964）至 `yoyi-cos-probe-test`（100054493475）：GetBucketACL，及 `objects/*` GetObject/PutObject。已从控制台复制实际策略与草案逐项对比一致；没有删除、公开读、ACL 修改、其他桶权限。资源包只是计费抵扣，不替代权限/存储桶配置。
- 本机真实 TLS Redis 客户端认证、PING、锁/解锁及地址不匹配/不可信 CA 拒绝已通过。新 AMD64 镜像的本地真实 HTTP 回归通过，包括 100 人抢 8 席（8 成功/92 满员，DB=8，占位=8，Redis=0）、取消重约、旧 ID、缩扩容、整节取消、故障及重启恢复。占位状态为 `BOOKED/SIGNED_IN/COMPLETED/NO_SHOW`，排除 deleted。该结果来自本机 MySQL 8.0.46/Redis 7.4.10，不代替云 MySQL/Redis/微信实测。

## 2026-11-03 管理员电脑测试入口

已启动并在浏览器打开 `http://127.0.0.1:5173/login`。这是本机 Web 管理端，API 代理到上述云端隔离测试后端；操作写入云测试库。账号 `yoyi_owner`，口令使用用户此前填写的 ADMIN_PASSWORD，口令不记录在本文。刷新页面后登录，可先检查教练、课程、会员查询，再使用“测试-”名称建立测试资料和排课。门店资料维护当前位于小程序管理员工作台，Web 的系统设置仅提供预约规则，不能将两者混淆。

复现启动（只监听本机，不是公开部署 Web 前端）：

```bash
cd /Users/xiaopacai/Desktop/plates/admin
VITE_API_TARGET='https://uni-test-339305-11-1505023965.sh.run.tcloudbase.com' \
  npm run dev -- --host 127.0.0.1 --port 5173 --strictPort
```

实际排查中，云端对 Origin=http://127.0.0.1:5173 返回 403 Invalid CORS request。修订 `admin/vite.config.js` 的专用云测试代理：仅该固定目标启用，监听回环地址并固定端口；转发前校验来源只允许本机 5173，无 Origin 的服务端请求仍须后端鉴权；随后作为服务端请求转发，不转递浏览器 Origin，Bearer 不变。外站及编码 API 路径均在本机拒绝。云端 CORS、权限和运行镜像没有改动。

页面、SPA 深链接、JS 入口、真实云匿名鉴权、FREE 能力、外站来源拒绝已实测通过；来源边界的普通/编码路径复核通过。见 `docs/evidence/cloudrun/admin-local-cloud-proxy-20261103.json`。这些是代理/页面检查，不是完整浏览器登录与业务表单验收。当前小程序仍需独立接入云后端及体验版验证，不能将本机 Web 结果计为手机通过。

## MySQL 产品限制与测试例外

供应商工单 202611028033 已确认云托管内置 MySQL 无法开启 SSL。原 VERIFY_IDENTITY 探针保持原样，状态仍未通过。

提供的 `CloudrunPrivateMysqlPolicy` 只在单一 `staging` profile、上述环境/地址/端口/库/用户、显式 ACK 下允许 `sslMode=DISABLED`；运行后另核查 UUID/完整版本。`prod`、错误地址、错误库/账号和缺失 ACK 必须拒绝。此连接的 SQL 数据没有 TLS 加密，不能记录为 TLS 通过，也不能作为生产方案。用户已明确确认“仅用于该隔离测试”；不公开 MySQL，不把 root 配进应用。

测试运行账号使用 MySQL 8.0.30 支持的 `mysql_native_password` 以避免非 TLS 连接依赖自动取回未知 RSA 公钥；不得据此放宽生产认证。`@'%'` 只是 MySQL 主机匹配，依靠数据库仅内网及平台隔离限制来源，不代表已经验证实际云容器源地址。若产品支持可靠的来源限制，应在确认实际源地址后收紧；不猜测 10.60 子网就是内置 MySQL 所见来源。

## 凭据填写和账号初始化

用户填写本机 `build-artifacts/请填写部署凭据.env` 的 MYSQL_PASSWORD、WX_SECRET、ADMIN_PASSWORD。该文件 0600、已被 Git 忽略，不能上传成代码或发聊天。其余运行值保存在 `.secrets/cloud-acceptance/cloud-runtime.env`；Redis 已由用户填写，COS 密钥和随机 JWT 仅在本机安全合并。

生成受保护的账号草案（不连数据库、不提交凭据）：

```bash
python3 scripts/cloudrun/account_draft.py \
  --input build-artifacts/请填写部署凭据.env \
  --artifact build-artifacts/cloudrun-5b145153cfa9 \
  --java /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java \
  --output .secrets/cloud-acceptance/accounts-NEW
```

输出含新凭据和管理员 BCrypt(12) 散列，属于敏感文件。DMC 新口令的输入、确认和提交由用户本人完成：

1. 核对页面实例为 `cynosdbmysql-41yi5vim`，库为 `uni_pilates_test`。
2. 左侧“存储过程 → + → 新建”，安全性 **INVOKER**；将 `01-install-private-routine.sql` 完整定义提交。普通 SQL 窗口会拆分过程定义，不能用于安装。
3. 在 SQL 窗口运行 `02-call.sql`。过程绑定库/UUID/版本/迁移三个原始 checksum/FREE/空账号前置条件；创建唯一 runtime 账号，仅 57 业务表 SELECT/INSERT/UPDATE/DELETE、schema_history SELECT，无 DDL/授权/全局权限；在事务内创建一个 `yoyi_owner` 管理员及 ADMIN 角色映射，不改变角色权限。
4. CREATE USER/GRANT 为隐式提交；中途失败可能留下部分授权，必须停止并核查，不能自动删用户、覆盖密码或重试。重复调用应明确拒绝，不添加第二个管理员。
5. 运行 `03-readonly.sql`，确认 runtime 1 个、启用 ADMIN 1 个且授权精确。操作员成功后删除**含凭据的初始化存储过程**；DROP 只清除该辅助定义，不删账号/业务数据，执行前单独确认。

## 上传与运行配置

入口已由用户服务设置截图确认：`https://uni-test-339305-11-1505023965.sh.run.tcloudbase.com`，公网访问已开启。首次浏览器实访为 `SERVICE_VERSION_NOT_FOUND`，这是历史失败记录；23:04 的实际业务 HTTPS 已通过。截图提示的浏览器测试域名操作有效期与应用状态分别核查，不能据此绕过平台限制。自定义域名列表为空不代表默认入口关闭。`PUBLIC_BASE_URL` 已写入受保护运行配置；用户已将运行配置填写到平台，凭据不进入上传 ZIP 或报告。

首次上传包 `build-artifacts/cloudrun-prep-9503bf4750f3/java-upload.zip`（SHA-256 `483b443324cc868e6f2e8961c61c166207d0406552c57e970f43e582382e2aca`）已确认缺少 Redis CA，不能再用于完整后端。修复后的最新包见 `build-artifacts/current-cloudrun-preparation-path.txt` 指向的目录及本单下方 CA 修复记录。另生成 `build-artifacts/云托管环境变量-仅粘贴到控制台.json`，0600、Git 忽略，供用户复制到该服务的环境变量 JSON 输入。此文件含真实运行凭据，只能填平台受保护环境变量，不能放入代码包、截图、聊天或普通文档。它不是待上传代码文件。管理员口令不在该 JSON 中。

控制台交接步骤：

1. 服务 `uni-test` → 部署发布：若选择方式支持本地代码/ZIP，选上述 ZIP；目录构建时只能选同包 `java-upload/`。若实际下拉仅支持仓库或镜像，停止并核对支持方式，不改公开仓库、不上传整个准备包，也不复用 COS 程序密钥当发布凭据。
2. 在高级设置/服务设置中设置端口 **8080**、1 核/2GiB、min=1/max=1、stdout，核对上海 VPC/子网。环境变量切 JSON 后粘贴上述受保护文件全文，再保存；确认运行模式 application，保留预设 staging/Redis TLS/Mock 关闭。已有公网入口保持现状，不公开 9091/MySQL/Redis。
3. 只向该隔离测试服务发布。保存平台实际构建版本、镜像摘要和脱敏结果；若构建/启动失败，取错误信息，不截图环境变量。
4. 构建成功且版本就绪后检查 `/health`，再执行本单下方云 JDBC、Redis、COS 和实际业务 HTTP 验收。`/health` 成功只证明存活；平台 404 或缺少版本时不进行会员体验验收。

只上传新准备包内 `java-upload.zip`，或等价的四文件目录：Dockerfile、.dockerignore、container.config.json、pilates-server.jar。不要上传项目根目录、`.secrets`、本地凭据、数据库账号草案或整个准备包。

服务端口 8080，staging，1 核/2GiB，min=1/max=1，stdout。网络应为上述 VPC/子网；保留公网出口用于微信 API/COS，数据库和 Redis 不开公网。首轮不购买其他资源，不开自动扩容/自动续费。

在平台受保护环境变量中填写私有 `cloud-runtime.env` 对应值。`PILATES_RUN_MODE=application` 为完整后端；`preflight` 为独立只读预检，没有登录/预约 API，即使 /ready 返回 200，businessReady 也为 false，不能交付会员使用。

- Redis：`REDIS_HOST=10.60.1.2`、6379、密码认证、`REDIS_TLS=true`、`REDIS_SSL_BUNDLE=tencent-redis`、CA classpath 路径按模板。用户名为空表示使用购买时的默认账号；若改自定义账号，先验证其命令权限，不能只改前端字段。不要关闭证书校验、不要用别的主机名绕过 IP 绑定。
- MySQL：使用限定的 runtime，不用 root；只在已审阅测试内网例外后启用对应 ACK。三份迁移已 DONE，不再初始化或 ALTER booking。
- 微信：真实 wx.login/code 换会话需 WX_SECRET，Mock 永远 false；未配置不能伪装成功。PHONE、短信 Mock、支付/退款发起、课包销售、订阅通知保持关闭。
- `PUBLIC_BASE_URL` 需填平台实际 HTTPS 服务入口；用户附件仍通过受控后端文件代理，不把私有 COS 改公开。CORS 只填实际管理端 HTTPS 域名；TRUSTED_PROXIES 只填已确认代理，不猜网段。
- 只开放应用端口；9091 的 actuator/Prometheus 保持私有。/health 是存活检查，依赖就绪仍检查内部 `/actuator/health/readiness` 和实际业务请求，不凭存活返回 UP 宣称联调通过。

微信云托管页面此前受工具访问限制时，不提取浏览器 cookie、不绕过访问检查。可由用户在控制台上传发布；若选择官方 CLI，需要用户另行建立并提供受保护的 CLI 登录配置，再按官方流程操作。不能把 CloudBase 新建环境当作当前已有微信云托管环境。

## 发布后验收与停止条件

### 2026-11-02 首次云部署失败后的排查交接

用户提供的发布单日志显示：`uni-test-001` 的代码解压、镜像构建、推送均成功，标签 `uni-test-001-20261102204343`，推送摘要 `sha256:3e8b342786acd0c0fd4039a66f60fd5da47488ea418ab667fdb2e07f328e236e`（manifest list）。20:45:26 出现 TCP 8080 存活/就绪探针 connection refused；末尾仅有 20:48:22 的 Spring Boot/staging 启动日志，用户确认没有后续日志。292 秒是发布单所报创建版本耗时，不能当作 Java 的完整启动耗时，也不能从此推出数据库口令、Redis TLS、COS 或 OOM 故障。具体退出原因和健康检查初始等待值仍未知。

已核对用户粘贴的微信官方《服务问题排查指引》《部署发布》：

- 端口：代码、Dockerfile、上传设置均为 8080；未发现监听 127.0.0.1 的配置。此结论是静态/本机证据，不代替云容器实际监听检查。
- JDK/构建：云日志实际运行 Java 17.0.20.1，与制品要求一致；Dockerfile 不在构建时连接 MySQL 或使用运行凭据；只有一个 ENTRYPOINT，无多 CMD 覆盖。
- 模板变量：文档列举的 MYSQL_ADDRESS/MYSQL_USERNAME 是模板约定，本项目实际使用 DATABASE_URL/MYSQL_USER/MYSQL_PASSWORD；不能盲目改名。配置指向云测试目标，真实云认证/连通尚待执行。
- 文档所述 2 MiB 代码包限制与本次实际接受 ZIP 并完成构建的证据不同，不是本次启动失败解释；保留差异记录，不据此改写制品。
- 版本创建后环境变量和规格会锁定；更新配置应生成新版本，不能仅重试旧版本并以为它读取了最新设置。
- 后续真实微信登录仍走 code2Session，用户贴文档提示的“开放接口服务”与公网出口是不同开关；当前没有微信 API 超时证据，不因本次启动失败盲目修改该开关或证书验证。

当前不改业务源码、不重建 JAR，先使用已验证制品中的独立只读预检入口：

1. 在服务设置将环境变量 JSON 替换为本机 `build-artifacts/云托管预检环境变量-非业务服务.json`。它只将 PILATES_RUN_MODE 从 application 改为 preflight，其余目标/凭据/隔离策略保持原值。文件 0600、Git 忽略，不能上传为代码或截图。
2. 保存生成新版本。若页面要求重新选择代码，可选已有镜像标签 `uni-test-001-20261102204343`；找不到时使用同一已验证四文件 ZIP，端口仍为 8080。不得新建公开仓库或生成宽权限发布凭据。
3. 只读预检使用同一 runtime 对 MySQL 做身份、结构、授权检查，再查询 COS 私有 ACL；启动 HTTP 8080 不等待这些网络检查。收集 PREFLIGHT 开头的脱敏日志以及 `/health`、`/ready` 结果。其 businessReady 始终 false，不向会员交付。该已有入口不验证 Redis。
4. 预检成功后，用 `build-artifacts/云托管启动日志环境变量-完整业务服务.json` 生成新的完整应用版本（application + ROOT INFO，保持 DEBUG/TRACE 关闭），取得完整启动阶段记录，再验证 Redis TLS 和业务 HTTP。不能将预检成功记为完整后端上线。

若预检也不能监听或被杀且没有 Java 异常，需平台返回实例退出原因/探针事件；若日志报告 mysql 或 cos-private-acl 失败，按对应阶段继续定位，不盲调健康等待时间、不移除启动校验、不自动重建账号/数据库。

### 21:07 发布单后的实际云端预检结果

用户提供的 `uni-test-002` 日志显示创建版本 176 秒、发布 3 秒均完成；镜像标签 `uni-test-002-20261102210729`，manifest list 摘要 `sha256:989e482f0925cc1d3363b9cf8185e73798935be8f12512e219386b696457d1e4`。控制台截图显示服务正常、1 个实例。

随后实际浏览器 HTTPS 检查确认：

- `/health`：`{"mode":"preflight","status":"UP","businessReady":false}`。
- `/ready`：`{"mode":"preflight","dependenciesReady":true,"mysqlTls":false,"businessReady":false}`。

按该已核对源码的预检入口，dependenciesReady 为 true 代表云容器已使用限定运行账号通过 MySQL JDBC 的目标身份/结构/checksum/步数/精确授权/时区/严格模式/FREE 检查，并通过 COS 私有 ACL 检查。证据为 `cloud-preflight-health.jpg` 和 `cloud-preflight-ready.jpg`。此结果仅证明只读预检范围：MySQL TLS 仍为 false，未检查云 Redis，也未执行登录/预约或云端 COS 对象读写。完整后端启动失败的具体根因尚未确定，但本次预检已验证容器对外监听和上述两项依赖。

下一步使用已校验的 `build-artifacts/云托管启动日志环境变量-完整业务服务.json` 替换环境变量并保存生成新版本。该文件恢复 application 模式，仅新增 ROOT INFO 日志，其余目标和密钥与原运行配置逐项一致，Redis 仍须 TLS 校验。完整应用就绪后再进行业务 HTTP 和 Redis 验收；不能回退到预检版并宣称会员可用。

### uni-test-003 的确定根因及 CA 制品修复

003 创建版本失败（用户提供发布单耗时 193 秒）；后续用户提供的 21:27:49–21:28:11 运行日志明确出现 `FileNotFoundException: class path resource [certificates/tencent-redis-ca.pem] cannot be opened because it does not exist`。Redisson 信任管理器创建失败，经 stringRedisTemplate/JWT 过滤器依赖链导致 Tomcat 启动回滚并反复重启。端口拒绝是后续表现；当前证据不支持通过关闭 TLS、改端口、增购规格或盲调探针来处理。

本机核查发现公共 CA 源文件存在且 SHA-256 正确，但旧成品 JAR 没有该资源。`.gitignore` 的 `*.pem` 将它从 `git ls-files --exclude-standard` 构建快照中排除。此前 TLS 集成夹具使用外部合成 CA 文件，未覆盖发布包内 classpath CA，原“镜像已验证”不能证明这一缺失场景。

最小修复：

- `.gitignore` 仅为 `server/src/main/resources/certificates/tencent-redis-ca.pem` 添加精确例外，其他 PEM 和私钥继续忽略；该文件只有公共证书，哈希仍为 `620fec36c5647475f0be9c51afe21d05d88ccf61cfadc578ceb069d8def97132`。
- 构建快照缺 CA 时立即拒绝；Maven 打包后核对 JAR 路径和资源哈希。
- 新离线 `RedisTrustCheck` 从最终镜像中的嵌套 JAR、以与应用一致的 Spring PEM 加载器创建信任管理器；检查无网络、无数据库、无真实凭据。上传包复核也要求该检查证据，旧缺 CA 的包现在必须拒绝。
- 增加 classpath CA 回归；已有应用类字节与上版逐项对比，变化仅新增离线检查工具和公共 CA，不改变预约、鉴权或 TLS 配置。

新制品 `cloudrun-6d1cf46a24bf` 已完成 AMD64 构建及成品镜像 CA 加载检查。其云端复验独立记录，不能把本地修复通过当作云 Redis 握手或完整后端启动通过。下一次需重新上传新 ZIP 生成新版本；环境变量继续使用完整业务 INFO 配置（application + Redis TLS），无需重建数据库或账号。

### uni-test-004：平台按 JAR 自动生成 Java 8 Dockerfile

004 的用户提供日志显示 `Downloading package (JAR)`，随后平台写入 `FROM openjdk:8u292-jdk-slim`、`COPY *.jar` 的自动 Dockerfile。最终 `JarLauncher` 因 class file 61.0（本项目 Java 17）超过 Java 8 支持的 52.0 而退出。另有平台 `/app/cert/initenv.sh` 退出 137，但不能仅据此判定 OOM；已知 Java 版本不匹配足以解释应用不能启动。本次尚未进入新包的 Redis CA 加载阶段，不能将其记录为 CA 修复失败或成功。

为避免误选单个 JAR，将同一已验证 ZIP 字节不变复制为可直接选择的 `build-artifacts/yoyi-cloudrun-java17-ca-fixed.zip`，哈希仍为 `7754ef7208c0c783911df86e2dcf7f46718786932516bb05846e351fe40b04ae`。重新核对其四文件白名单、Java 17 固定镜像 Dockerfile、非 root 用户及内嵌 JAR 公共 CA 均正确；未修改源码或凭据。

下一次页面必须为“手动上传代码包 → 压缩包”，选择上述**整个 ZIP**，不解压后选 JAR；高级设置 Dockerfile 选“有”、名称 `Dockerfile`、目标目录空、端口 8080。环境变量继续用完整业务 INFO 配置。发布前的代码包名称应以 `.zip` 结束；构建日志应出现 ZIP 解压与读取该 Dockerfile，不应再出现平台自动写入 Java 8 Dockerfile。若仍自动识别为 JAR，停止重试并核对所选文件和页面选项。

### uni-test-005 发布完成后的公网模式核查

005 用户提供发布单显示创建版本 181 秒、发布 4 秒均完成，已使用 ZIP 和 Java 17 固定镜像 Dockerfile；镜像标签 `uni-test-005-20261102215923`，manifest list 摘要 `sha256:c4c5a5e5a02ba3d3f632a1b2f6fd308727a227f16c8a534adc4fa2cb797982ec`。

但实际 HTTPS 检查在 Asia/Shanghai 22:07:12 仍返回 `/health` 200 + mode=preflight、businessReady=false；`/ready` 200 + dependenciesReady=true；`/api/auth/me` 404。再次请求已带 no-cache，服务端也返回 no-store/no-cache。当前只能确认平台版本发布完成、公网入口仍提供只读预检，尚不能确认 005 完整应用启动或 Redis 连通。需要根据 005 的 PREFLIGHT/Started 日志和版本流量确认是该版继承了预检模式，还是入口仍路由到旧版。在公网确认为完整应用前，不向登录接口发送管理员凭据或开展业务写入。

随后用户只读查看服务环境变量并确认 `PILATES_RUN_MODE=preflight`，与公网响应一致。下一步直接将该值改为 `application`，并设 `LOGGING_LEVEL_ROOT=INFO`，保存生成新版本。正确 Java 17/CA 修复镜像已经存在，本次只需配置切换，不要求再次上传 ZIP。已创建版本会锁定原值，不能仅重启 005 期待变更生效。其余凭据和隔离/TLS 配置继续使用既有完整配置。

### uni-test-006：Redis TCP 内网连接超时

006 已实际进入 Java 17/staging 完整应用。用户提供 22:33:52 运行日志，最底层为 `io.netty.channel.ConnectTimeoutException: connection timed out after 3000 ms: 10.60.1.2/10.60.1.2:6379`。此故障发生在 TCP 建连，尚未到 TLS 握手或 AUTH，不能归因为密码/证书校验；deprecated 配置警告也不是该异常。按已核对的 RedissonConfig 调用顺序，TCP 连接发生于信任管理器创建之后，说明新包已越过此前的 classpath CA 缺失点。

已在正确账号的腾讯云控制台只读复核：Redis crs-eyyecodn 运行中，10.60.1.2:6379，vpc-72qgp3wk/subnet-c4vyxl77；SSL 已开通、IP 绑定正确、证书有效。实际绑定 sg-jj3dltvk，入站仅允许 10.60.1.0/24 TCP 6379；子网没有 ACL，已启用 10.60.0.0/16 LOCAL 路由。未扩大安全组、未改密码、未开放公网、未关闭 TLS。证据见 `redis-network-readonly.json` 及相关截图。

仍需确认服务实际开启“对接 VPC”并选择上述 VPC/子网，且变更已进入当前版本。不能把健康探针显示的 10.39.* 容器地址当成 Redis 所见来源来直接放宽授权。若绑定正确仍超时，需要进一步取得源地址/流量或平台互联证据；如确需新增规则，另行审阅具体来源、目标和端口。服务设置变更后需要创建新版本生效，保持完整 application 模式。

随后用户报告修改网络后发布 007，创建版本 77 秒、发布 2 秒均成功；日志 check_eks_virtual_service 为 succ。但 22:44:12 的实际 HTTPS `/health` 仍返回 mode=preflight/businessReady=false，此前同轮 `/api/auth/me` 为 404。此状态不能证明 Redis 网络调整已经通过完整应用验证。待核对当前 PILATES_RUN_MODE 与线上版本/流量；如配置仍继承 preflight，保留新 VPC/子网，只切换 application（可附加 ROOT INFO）并创建新版本。当前不再次构建/上传、不改密码、不扩大安全组。

### 23:04：完整后端的实际 HTTPS / MySQL / Redis / COS 检查

用户随后提供 22:51–22:52 日志：006 仍报 Redis 连接错误，007 输出 PREFLIGHT。它们属于不同历史版本，不能据此判定当前公网应用失败。22:53:56、23:03:58 的只读公网请求已返回完整应用 `/health`（code=0、service=pilates-server），匿名 `/api/auth/me` 返回业务码 40100。23:04 开始的脚本验收进一步通过 8 组检查：

| 检查组 | 实际结果及能证明的范围 |
|---|---|
| 完整应用健康 | HTTP 200，code=0，service=pilates-server；单独此项只证明存活 |
| 匿名管理鉴权 | 身份与会员管理请求均返回 40100 |
| 能力边界 | FREE；销售、支付/退款发起、手机号、微信提醒均关闭 |
| 管理员登录与身份 | 使用既有专用管理员；登录及 me 均成功且包含 ADMIN；按已核对实现执行 MySQL 查询/登录时间更新、Redis Lua/SET/hasKey |
| 管理查询 | 课程与会员管理 API 均 code=0；不在报告输出会员资料 |
| 错误登录 | 随机合成且不存在的用户名/口令被 40100 拒绝，不测试真实会员口令 |
| COS 应用读写 | PUBLIC_ASSET、AVATAR 各上传一张 69 字节合成 PNG；代理读取的字节及 SHA-256 一致；私图 no-store，公开路径/无登录访问/无效签名均拒绝 |
| 会话撤销 | logout 成功，原 Token 再次访问 me 返回 40100；证明 Redis DEL 撤销已生效 |

报告：`cloud-application-http-smoke-2.json`，命令入口：

```bash
python3 scripts/cloudrun/http_smoke.py --config /SECURE/test-smoke.properties --allow-synthetic-uploads \
  --report docs/evidence/cloudrun/NEW/cloud-application-http-smoke.json
```

该入口从本机 0600 配置读取 `origin`、`admin.username` 和 `admin.password`，三项必须对应同一隔离测试服务。请求使用 HTTPS 证书校验并禁止重定向。Token 和签名 URL 仅留在进程内，报告使用新的输出路径。带上传参数时会保留合成对象与 file_object 记录供核对。此次合成对象合计 138 字节，ID/哈希见报告。

首次脚本运行仅健康检查成功，匿名检查阶段发生 URLError，尚未读取管理员凭据或上传对象；保留 `cloud-application-http-smoke.json` 为失败证据，具体网络原因未确定。随后只读检查恢复、第二次完整通过，不将两次结果重复累计。

Redis 实际认证连接及会话/Lua 命令已通过应用请求验证。评审制品配置为 rediss + STRICT + 指定 CA，控制台 Redis SSL 开启；本次未独立采集 TLS 握手协议/密码套件，不能把本地 TLS 夹具结果充作云端协议抓取。云端分布式锁、预约库存、故障重启及多实例竞争仍未执行。用户核对当前线上版本为 uni-test-008；这是控制台人工关联，不是 HTTP 自报版本。008 的镜像摘要与运行制品指纹尚未独立取得。

**下一步体验前检查：** `/api/capabilities` 实测 `studioConfigured=false`，先由管理员填写真实门店资料，再配置课程、教练资格、场地和测试排课；不能用合成地址冒充真实店铺。小程序当前 trial/release 地址为空，develop 为本机地址且 Mock 登录；连接本云后端必须使用真实 wx.login、Mock=false，并核实平台允许的 HTTPS request/upload/download 域名。手机不能使用 127.0.0.1 访问本机服务。若默认测试域名不允许配置，先采用官方支持的云调用接入或具备条件的自定义 HTTPS 域名，不能关闭域名校验冒充验收通过。本轮未上传或发布小程序。

发布仅在运行账号/管理员、实际服务域名、完整运行凭据和测试 MySQL 例外已审阅后进行。取得真实云构建版本和镜像摘要，保存经脱敏的构建/启动结果。不能用“本地 image built”代替云启动。

1. 从云容器执行运行身份 JDBC：固定 DB/UUID/版本、严格模式、+08:00、58 表/3 DONE、FREE、精确 grants；此项标记 private non-TLS，不能标记 MySQL TLS 通过。
2. 云容器对 Redis 使用 CA 和 IP 验证进行认证/PING、带唯一前缀的短期锁与 Lua/库存操作，并检查不修改现有业务 key；不执行 FLUSHDB/FLUSHALL。记录 TLS 协议和证书验证结果，不输出密码。
3. 通过真实应用 HTTP 使用 `yoyi_owner` 登录；错误口令与无 Token 的管理调用必须拒绝。合成图片上传/读取、匿名私图拒绝、授权边界要在云端重验。COS 原先 Mac 独立 SDK 通过不替代该链路。
4. 测试成员数据仅合成或明确加入体验的测试账号。依次验证免费团课/私教预约、取消重约、旧 bookingId、容量编辑/取消、签到和权限撤销；保存成功数/业务原因/DB/四状态占位数/Redis库存，不只记 HTTP 200。
5. 小程序 trial/release 当前没有真实 HTTPS baseURL；取得域名后配置环境和合法 request/upload/download 域名，再用开发者工具和真机验。浏览器不计微信设备通过；不发布正式小程序。

任何目标身份变化、错误权限、Mock 启用、凭据缺失、TLS 地址不匹配、启动失败、库存不一致、对用户数据产生非预期写入均停止放量。修复后只重跑相关失败和回归。

## 回退

首轮云服务没有旧业务版本可回退。发布失败时停止该测试版本/路由，保留 MySQL、Redis 和 COS 数据与证据；先检查错误配置，不删库/不清共享缓存。应用回退使用明确兼容同一 schema 的已验证镜像，不能把 preflight 模式称作业务回退。未执行结构迁移，因此不执行 Round2 结构回滚或重新建立旧唯一索引。

## 本地复现入口

```bash
python3 scripts/cloudrun/redis_tls_check.py --docker-context colima-pilates-staging \
  --java-home /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  --report docs/evidence/cloudrun/NEW/local-tls.json
python3 scripts/production/container-accept.py --docker-context colima-pilates-staging \
  --mysql-bin /opt/homebrew/bin --release build-artifacts/cloudrun-5b145153cfa9 \
  --web-fixture-release build-artifacts/staging-3a568e49a068 --skip-browser --redis-tls \
  --output docs/evidence/cloudrun/NEW/business-tls
```

报告明确区分本机 TLS/HTTP、云控制台、云 JDBC/Redis、云 HTTP、微信开发者工具和真机。此轮未进行多实例云并发或生产库操作，不能宣称生产可上线。
