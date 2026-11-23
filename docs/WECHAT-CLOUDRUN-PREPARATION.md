# 微信云托管 Java 准备包

在源仓库根目录用 JDK 17 或 21、Maven 和具备 buildx 的隔离 Docker 上下文准备制品。

```bash
python3 scripts/cloudrun/build.py --docker-context YOUR_CONTEXT --java-home /PATH/TO/JDK
python3 scripts/cloudrun/prepare.py --artifact /PATH/TO/CLOUDRUN_BUILD
python3 scripts/cloudrun/verify.py --bundle /PATH/TO/PREPARED_BUNDLE
```

`build.py` 从当前源码构建 Java 17 JAR 和 Linux amd64 镜像，并验证镜像内 Redis 信任证书。 `prepare.py` 校验源码及迁移哈希，生成准备包和包含 Dockerfile、容器配置、JAR 的 `java-upload.zip`。 `verify.py` 校验文件清单、源码对应的制品和上传 ZIP。

准备包可通过 `python3 tools/verify.py --bundle /PATH/TO/PREPARED_BUNDLE` 独立复核。部署时参考 [测试环境部署手册](CLOUDRUN-TEST-DEPLOYMENT.md) 和 [镜像及 COS 配置](WECHAT-CLOUDRUN-AMD64-COS.md)。

数据目录包含空测试库准备 SQL、只读检查、V001 至 V004 和运行账号授权草稿。迁移入口 `tools/database.py` 需指定用户私有配置、经确认的服务端 UUID 和隔离库名。可先用 `--check-only` 验证输入。管理员初始化草稿由 `tools/account_draft.py` 生成，口令通过标准输入处理。
