# 独立 COS 连接探针

`CosConnectionProbe.java` 是显式调用的运维探针，不启动 Spring、Redis、数据库或 Web 服务。它使用当前受审核 JAR 内的实际 `CosObjectStorage` 与 COS SDK，不替换 provider，也没有自定义 endpoint、HTTP、跳过证书或删除对象开关。

入口类：`com.yoyi.pilates.infra.storage.CosConnectionProbe`。

```text
--config /SECURE/cos.properties
--confirm-test-bucket FULL_BUCKET
--confirm-region ap-shanghai
--mode preflight|write-read
--report /ABSOLUTE/NEW_REPORT.json
```

Python 包装器负责校验当前 JAR 并抽出 `BOOT-INF/classes/` 与 `BOOT-INF/lib/`，使用 JDK 17/21 的 `javac --release 17` 编译此源文件，运行时类路径由探针输出 classes、JAR classes 和 `lib/*` 组成。无需改动已有 JAR 或重建业务镜像。报告目标必须不存在，其父目录必须已存在；不覆盖历史证据。

配置必须为普通 ASCII `key=value` 文件，权限恰为 0400 或 0600，拒绝最终符号链接、重复/未知键、反斜杠转义、非 ASCII、控制字符及超过 64 KiB 的文件。真实文件应由包装器限制在 Git/上传制品之外；不要把真实配置内容放进聊天、命令参数或日志。

```properties
cos.region=ap-shanghai
cos.bucket=
cos.secret-id=
cos.secret-key=
cos.expected-owner-uin=<BUCKET_OWNER_UIN>
cos.session-token=
```

桶名必须包括 COS 数字 APPID 后缀；不从小程序 AppID/UIN 猜桶名。配置桶/地域必须与两项 CLI 确认一致。`cos.session-token` 可省略或空白；没有自动 STS 续期。

最小权限为目标桶 GetBucketACL 与 `objects/*` 的 PutObject/GetObject。不要赋予创建桶、修改 ACL/策略、列举或删除权限。桶策略、旧对象 ACL、CDN/自定义域名应由已有受控审查身份或控制台查看，运行凭据不因此扩大权限。

- **preflight：** 只读两次桶 ACL。独立 SDK 客户端先严格比对 Owner ID 为 `qcs::cam::uin/100053657418:uin/100053657418`，再执行真实 provider 的 owner-only ACL 检查；不上传、不读取对象。返回 passed 仅代表这两个读取检查通过。
- **write-read：** 在上述门槛之后，生成有效 1×1 PNG，并写入唯一 `objects/acceptance/cos-<UTC>-<UUID>.png`；真实 provider HTTPS 下载后逐字节与 SHA-256 比较；再用无认证、证书校验开启、拒绝重定向的独立 Java HttpClient 请求同一对象，必须返回 403。301/302、200 或其他状态均失败。

实际 PUT 前，先以 CREATE_NEW、0600 创建报告并强制落盘 checkpoint：其中含 `objectKey`、`writeAttempted=true`、`writeConfirmed=false`、`status=incomplete`；只有 checkpoint 完成才发送 PUT。成功返回后才设置 `writeConfirmed=true`。后续报告通过同目录临时文件原子替换，并核验原目标仍是本进程创建的 inode；拒绝覆盖既有或被替换的报告。因此包装器超时/进程终止时仍可从 checkpoint 找到可能存在的对象位置；探针不删除对象。经营方可稍后按精确 key 处理合成测试对象，不执行前缀批量清理。预期只产生一个微小合成对象及少量请求，但仍使用实际云资源；写前须确认是隔离测试桶及适用计费。

输出仅含允许记录的地域、桶名、预期 UIN、合成 key/哈希、检查状态与匿名 HTTP 状态。SDK、HTTP wire 日志在探针进程内关闭，异常原因、响应内容、密钥及请求 URL 不输出。`status=passed` 仍不能证明所有对象私有、Bucket Policy/CDN 完成审查、应用业务授权或签名代理地址验收通过。

离线测试入口为 `com.yoyi.pilates.infra.storage.CosConnectionProbeOfflineTest`。已用当前 JAR 的 classes/lib 编译并执行；包含文件权限/符号链接、配置解析、目标绑定、canonical owner、脱敏、匿名 HTTPS/重定向策略、合成对象键/PNG、落盘 checkpoint、不覆盖他人报告和替换检测。该测试不构造 SDK 客户端、不联网、不写云对象；不能计为真实 COS 通过。
