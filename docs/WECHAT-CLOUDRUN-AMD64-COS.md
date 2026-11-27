# 微信云托管：AMD64 镜像与私有 COS 适配验收

日期：2026-11-01，Asia/Shanghai。本记录覆盖本机构建和隔离测试；没有上传、推送镜像、部署云服务、购买 Redis 或操作业务库。云端 MySQL 初始化证据仍见 [测试准备手册](WECHAT-CLOUDRUN-PREPARATION.md)，本次没有重新执行云库初始化。

**连接进展补充（2026-11-02）：** 后续已使用本机 Mac 独立 SDK，在上海专用私有测试桶完成 owner ACL、真实 HTTPS 合成图片写读/哈希与匿名 403 验证；程序身份只授权 `objects/acceptance/*`。这不改变下文原本地镜像验收的范围，也不代表云端完整应用 COS 权限/访问链路通过。DMC 已恢复并复核目标身份、58 表、三个 DONE 校验和/55-24-5 步数、InnoDB/utf8mb4 异常数 0 及 FREE；`sys_user=0`、目标 runtime 账号计数 0。当前 `have_ssl=DISABLED`、`require_secure_transport=OFF`、`ssl_ca` / `ssl_cert` 为空，原生 TLS 禁用阻断 JDBC VERIFY_IDENTITY；`tls_version` 的 TLSv1.2/TLSv1.3 列表不代表 TLS 已启用。平台能否支持开启仍待核实，实际 JDBC 未执行。最新范围见 [连接验收记录](CLOUD-CONNECTION-ACCEPTANCE.md) 与 [机器证据](cloud-connection-verification.json)；已有制品与 ZIP 保持生成时快照。

## 交付内容

- 本地镜像：`yoyi-server:cloudrun-5d01bc24aa8d`，`linux/amd64`，运行用户 `10001:10001`，仅声明应用端口 8080。镜像本地 ID 为 `sha256:5989cad56303c5194aa66615467fac619a6c4f772853c09288e7d0fcbf216152`，不是已推送仓库的摘要。
- JAR SHA-256：`c82f6987a2f51d83888a228bccee22ec87036eceaa1398fc5b923adf1a88a2fc`。显式使用已安装 JDK 21 编译，类文件目标 Java 17；镜像内是固定版本 Temurin 17 JRE。
- 制品路径保存在 `build-artifacts/current-cloudrun-release-path.txt`；四文件上传准备包路径保存在 `build-artifacts/current-cloudrun-preparation-path.txt`。旧生产制品及其指针保留。
- 新增腾讯 COS provider，保留 OSS 实现及默认选择。`STORAGE_TYPE=cos` 才启用 COS；没有自动搬迁 OSS 中已有对象。
- 实际结果与命令见 [机器记录](cloudrun-amd64-cos-verification.json)。不能把本地结果视为云端 COS、Cynos JDBC/TLS、微信开发者工具或真机通过。

## Colima 修复与构建

沙箱内曾显示 Broken；宿主机只读检查显示现有 `pilates-staging` 实际停止，沙箱对系统启动信息的权限限制影响了状态判断。本次启动原 profile，保留原磁盘及 2 CPU / 4 GiB 配置，没有删除、重建 VM 或安装 Rosetta。Colima 注册的 QEMU 提供 AMD64 执行能力。

Homebrew 已有 buildx/compose 插件；补充用户 Docker CLI 插件链接后可被发现。全局默认 Docker context 仍为 `default`，本任务显式使用 `colima-pilates-staging`，避免操作未知引擎。

```bash
colima start --profile pilates-staging --activate=false --ssh-config=false
docker --context colima-pilates-staging buildx inspect
python3 scripts/cloudrun/build.py \
  --docker-context colima-pilates-staging \
  --java-home /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

构建器捕获源码快照、哈希、测试报告和四文件上下文，明确 `--platform linux/amd64 --load`；相同源码版本目录已存在时安全停止，不覆盖旧镜像标签。新改动应生成新的内容版本。不要因为 Mac 是 ARM64 就直接上传原 ARM64 镜像。

本机默认 JDK 26 的 Lombok 注解处理与当前项目不匹配；受控构建使用显式 JDK 17 或 21，不修改用户全局 JAVA_HOME。制品清单记录实际 JDK 与 Java 17 字节码校验。

## COS 行为与配置

新增文件：`CosStorageSettings.java`、`CosObjectStorage.java`。修改 `server/pom.xml`、`ProductionEnvironmentGuard.java`、`application-staging.yml`、`application-prod.yml` 及云托管环境清单。预约、角色授权、文件归属和签名 URL 业务规则没有放宽。

| 配置 | 含义 |
|---|---|
| `STORAGE_TYPE=cos` | 选择 COS，默认仍为 oss |
| `COS_REGION=ap-shanghai` | 从实际桶所在地域确认；不是 endpoint URL |
| `COS_BUCKET` | 控制台显示的完整桶名，带腾讯 COS 数字 APPID 后缀；不是小程序 AppID 或腾讯云 UIN |
| `COS_SECRET_ID` / `COS_SECRET_KEY` | 受限 CAM 身份的凭据，必须显式提供；不启用未知默认凭据链 |
| `COS_SESSION_TOKEN` | 可选 STS Token；配置后使用临时凭据，没有自动续期，须到期前受控轮换并重启 |

真实值只放平台受保护配置或受限配置文件；不放 Dockerfile、ZIP、Git 或聊天。`deploy/wechat-cloudrun/runtime.env.example` 只有空白清单。

实现使用固定 COS SDK `5.6.283`，强制 HTTPS 和证书校验，连接/排队 3 秒、socket 5 秒、单次请求总超时 10 秒、最多重试 1 次。每个请求总耗时仍受平台入口、重试与应用链路影响，真实云请求需验证 15 秒入口限制，不能从 SDK 单次配置推断整条链路必然低于 15 秒。

启动必须读取桶 ACL，并验证仅桶所有者有授权；公开读/写、匿名 FULL_CONTROL、其他授权或 ACL 查询失败均拒绝启动。不创建桶、不修改 ACL、不返回对象直链。只读写 `objects/` 下的受限对象键，单图最多 5 MiB；异常不串联可能包含签名信息的 SDK 原始错误。

文件仍通过现有 FileService 记录稳定 objectId，校验业务身份后签发后端代理访问地址。私有地址 5 分钟过期；公开素材走明确的公开接口。COS 私有桶不妨碍应用按既有权限公开宣传素材。

启动 ACL 检查**不能证明 Bucket Policy、旧对象 ACL 或 CDN 未公开**。真实环境还需以下验收：

1. 在实际目标账号创建专用测试私有桶，核对地域；暂不放真实会员资料。不修改其他业务桶。
2. 逐项审查桶策略、已有对象 ACL、CDN/自定义域名和公开访问配置，确保不存在匿名读取私有对象的路径。
3. 为运行身份仅授予目标桶的 GetBucketACL，以及 `objects/*` 的 PutObject / GetObject；不授予管理桶、改 ACL、删除对象或全账号权限。按控制台实际 CAM 资源格式设置，不直接套用其他账号的 APPID。
4. 配置真实凭据，验证 ACL 查询、真实 HTTPS 上传与下载、合成图片字节/哈希一致；直接匿名请求新对象必须为 403，不以登录后的控制台预览代替。
5. 通过应用 HTTP 验证会员归属、未服务教练拒绝、签名过期、公开素材、重启后访问；断开 COS 权限/网络应失败，不能退回本地文件或返回伪成功。
6. 使用 STS 时验证到期失败、受控轮换及重启恢复；本适配没有自动调用 STS 或 KMS 客户端加密。

SDK 的可选 KMS 客户端加密依赖被排除：普通私有 COS ACL/PUT/GET 不使用该功能，其依赖带入旧 okhttp / ini4j 高危项。Bouncy Castle 复用项目已有的 `bcprov-jdk18on 1.85`，排除旧 `bcprov-jdk15on`。真实 SDK 签名 ACL/PUT/GET 的本机 HTTP 合约测试已覆盖该依赖裁剪；这不证明真实 COS 可用。

从 OSS 切换现有业务环境前，必须另做对象清单、复制与哈希核对、保留原存储的回退方案。数据库 objectId 不等于对象已迁移，不能只改 STORAGE_TYPE 就宣称历史图片迁移完成。本次没有迁移任何云对象。

参考：[COS Java SDK](https://github.com/tencentyun/cos-java-sdk-v5)、[私有读写与访问控制](https://cloud.tencent.com/document/product/436/50708)、[Java SDK HTTPS 说明](https://intl.cloud.tencent.com/zh/document/product/436/38956)。

## 验证与适用范围

单元/SDK 合约：`CosObjectStorageTest` 8 项，`ProductionGuardTest` 4 项，共 **12 项**，失败/跳过均 0。包括私有 ACL、错误配置、HTTPS/证书/超时、临时凭据、对象读写/关闭、路径/大小及错误脱敏。真实 SDK 合约使用本机 loopback HTTP 和合成凭据，不调用腾讯云；生产配置没有放开 HTTP 或自定义 endpoint。

```bash
trivy image --docker-host unix:///Users/xiaopacai/.colima/pilates-staging/docker.sock \
  --image-src docker --platform linux/amd64 --scanners vuln --timeout 10m \
  --format json --output /ABSOLUTE/scan.json yoyi-server:cloudrun-5d01bc24aa8d

env JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  python3 scripts/production/container-accept.py \
  --docker-context colima-pilates-staging --mysql-bin /opt/homebrew/bin \
  --release build-artifacts/cloudrun-5d01bc24aa8d \
  --web-fixture-release build-artifacts/staging-d9c017ecc5b4 \
  --skip-browser --mysql-series 8.0 --output /ABSOLUTE/NEW_EVIDENCE_DIRECTORY
```

隔离演练的后端是实际 AMD64 镜像，在 ARM64 Colima 内经 QEMU 执行，进程架构检查为 x86_64。数据库服务为固定镜像 MySQL **8.0.46**，Redis 为固定 **7.4.10**；这两个配套容器及未修改的 admin/edge 代理测试夹具使用原平台。Homebrew **26.7.0 是 mysql 客户端版本**，不是该集成测试的数据库服务版本，也不能代替云端 8.0.30 Cynos 验证。

演练创建随机命名的独立 Compose 项目和卷，仅向 127.0.0.1 暴露测试端口；结束只删除该项目，不清空共享 Redis。应用使用原有 staging 显式隔离 local 存储例外，以验证 HTTP 文件边界和持久卷；prod 仍拒绝 local。微信登录能力在夹具中明确关闭，合成管理员调用 HTTP；没有把 Mock 当作真实微信登录。

占位预约口径为 `deleted=0 AND status IN ('BOOKED','SIGNED_IN','COMPLETED','NO_SHOW')`。容量 8 / 100 并发结果要求成功 8、其余明确拒绝、DB booked_count=占位数=8、Redis=0。另验证多次取消重约旧 ID、缩容拒绝、扩容、整节取消、文件权限、MySQL/Redis 中断恢复、非 root/只读文件系统、重建/重启及严格 SQL 模式拒绝。

这是单后端实例测试；不是多实例并发验收。Nginx 真实 HTTPS/API、SPA 深链接和静态资源已测，浏览器交互明确 `skipped-by-scope`；微信开发者工具、iOS/Android 均未测。

首个候选镜像扫描有 3 项 HIGH，已弃用：补丁固定修复 libbrotli1，并排除不使用的 COS KMS 客户端依赖。最终镜像扫描 HIGH=0、CRITICAL=0、MEDIUM=29、LOW=32；中低项仍保留，不使用忽略规则隐藏。扫描数据库和镜像时间均见原始 JSON，结果需在真正部署前按当时漏洞库再评估。

首轮 AMD64 演练最后一项负向启动检查等待不足，应用仍在初始化。只将夹具等待从 30 秒延至 120 秒，继续要求同一个严格模式拒绝日志及恢复成功；没有修改应用校验或 SQL 条件。旧失败证据保留，不与最终通过项重复累计。

## 上云前仍需完成

本地镜像与 COS 代码交付不等于具备生产上线条件。Mac 独立 SDK 已通过真实 COS ACL、HTTPS 写读/哈希与匿名 403，完整应用 COS 权限及云端链路仍待验证。云 MySQL 本轮元数据已复核，但原生 TLS 当前禁用，平台开启能力仍待核实，实际 JDBC 未执行；scoped 运行账号和初始管理员尚未建立。Redis 仍未购买；平台健康检查及网络/可信代理、真实云 HTTP、微信登录/开发者工具/真机仍待验证。继续用 staging 和隔离数据，依赖齐备前不点击发布。

生成/复核最小准备包：

```bash
python3 scripts/cloudrun/prepare.py --artifact build-artifacts/cloudrun-5d01bc24aa8d
python3 scripts/cloudrun/verify.py --bundle /ABSOLUTE/PREPARED_BUNDLE
python3 scripts/cloudrun/check.py --bundle /ABSOLUTE/PREPARED_BUNDLE --report /ABSOLUTE/wrapper.json
```

包内 `java-upload.zip` 仅包含 Dockerfile、.dockerignore、container.config.json、pilates-server.jar。它是匹配已验证本地镜像构建上下文的代码上传包，不是腾讯云已经发布的容器镜像；云端再次构建仍需检查产物平台、扫描结果和运行行为。
