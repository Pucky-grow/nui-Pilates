# 微信云托管镜像与私有 COS

云托管构建入口采用 Java 17 和 Linux amd64。后端以非 root 用户运行，监听 `8080`。构建过程核对 JAR 内的 Tencent Redis 公共 CA，并在最终镜像中离线创建信任管理器。

## COS 配置

- 应用用 `STORAGE_TYPE=cos` 选择 COS。地域和桶名使用已核对的隔离测试资源。
- 基于 `private-configuration/cos-runtime-policy.draft.json` 的草稿填写自己的资源路径并复核策略。应用的运行身份仅需读取桶 ACL，并在业务对象前缀下读写。
- COS 访问密钥和可选的 STS 会话令牌由平台受保护配置注入。应用启动检查桶归属 ACL。
- 使用合成图片验收上传、公开素材读取、私有图片授权读取、过期和越权拒绝，并复查匿名访问与实例重建后的持久性。

准备包说明列出构建及制品复核命令。实际测试部署步骤见 [部署手册](CLOUDRUN-TEST-DEPLOYMENT.md)。
