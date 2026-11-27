# Redis CA 部署包缺失修复

2026-11-02，隔离测试环境 uni-test。003 的真实运行日志表明 classpath CA 文件不存在，Redisson 初始化失败导致 Tomcat 回滚重启；不是镜像构建失败。

根因：`.gitignore` 的 `*.pem` 排除了公共 CA，基于 Git 文件清单的构建快照没复制它。旧的本地 TLS 夹具使用外部合成 CA 文件，未覆盖发布包内的 classpath 路径。

修复仅精确放行这一个公共证书，并在构建和上传包验证中检查 JAR 内资源及哈希。最终非 root AMD64 镜像通过 `RedisTrustCheck`，使用 Spring PEM 加载器实际创建信任管理器，检查期间无网络和真实凭据。其他 PEM、私钥仍被忽略，Redis TLS/地址校验保持开启。

新制品：`build-artifacts/cloudrun-6d1cf46a24bf`。

新上传包：`build-artifacts/cloudrun-prep-821bfc58ef5a/java-upload.zip`。

ZIP SHA-256：`7754ef7208c0c783911df86e2dcf7f46718786932516bb05846e351fe40b04ae`。

验证：针对性构建测试 20 项（19 通过、1 项真实 TLS 夹具按条件跳过）；独立真实 TLS 夹具 4 项全部通过，其中 3 项与构建重复，共 20 项不同测试通过。成品镜像加载、旧缺 CA 包拒绝、新 ZIP 内嵌 JAR 核对及 8 项离线包装器边界检查通过。漏洞扫描 HIGH/CRITICAL 为 0；MEDIUM 29、LOW 32。原有应用类字节与旧 JAR 相同，仅新增离线检查工具和公共 CA。前轮已通过的业务回归继续作为其原环境证据保留，没有重复累计。

上传新 ZIP 生成新版本，使用现有“云托管启动日志环境变量-完整业务服务.json”（application、staging、8080、Redis TLS）。不需要重建数据库、账号或更换口令。仅重试旧镜像或保存同一环境变量不能补回缺失资源。

云端 Redis 握手和完整后端启动仍待新版本复验；002 的 MySQL/COS 只读预检通过仍有效，不能替代 Redis、COS 对象读写或预约 HTTP 验收。

机器证据：`docs/evidence/cloudrun/redis-ca-fix-20261102/ca-fix-verification.json`。
