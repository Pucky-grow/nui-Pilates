# 微信云托管测试环境部署

本手册适用于隔离测试环境。准备包的 `java-upload.zip` 是平台源码构建输入，数据库、运行参数及凭据在各自的管理入口配置。

1. 使用通过 `verify.py` 校验的准备包。选择 ZIP 中的 Dockerfile 构建，容器端口为 `8080`。
2. 用 `database/00-readonly.sql` 核对测试库身份和版本。新库按版本化 SQL 初始化，再以独立运行身份核对授权。启用课时台账前应用 V004 并审阅 `database/grant-studio-ledger.draft.sql`。
3. 参考 `private-configuration/preflight.env.example` 完成依赖预检。业务运行使用 `private-configuration/runtime.env.example`；专用私网测试例外另有 `runtime-private-test.env.example`。数据库连接参数以实际目标和运行环境的核验结果为准。
4. 通过平台受保护配置填写 MySQL、Redis、JWT、微信和 COS 凭据。Redis 使用应用所在网络可达的 TLS 端点，COS 使用私有桶和最小权限运行身份。
5. 先确认实例健康，再用合成账号验证登录与退出、预约、签到、管理查询和文件读写。微信工具及真机分别记录结果。

发布失败时保留当前实例及诊断信息，按经验证的镜像与数据库兼容方案处理。数据库迁移和镜像版本需要分别核对。
