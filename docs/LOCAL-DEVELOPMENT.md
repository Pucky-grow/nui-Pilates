# 本机小程序联调

在项目根目录运行：

```sh
python3 scripts/dev-local.py start
python3 scripts/dev-local.py status
python3 scripts/dev-local.py stop
```

`start` 编译并在后台持续运行后端（127.0.0.1:8090），同时启动专属 MySQL 8.0.46 和 Redis 7.4.10。重复启动会报告已有进程。修改后端代码后先 stop 再 start。关机后需要重新 start；这不是开机自启服务。

数据、随机凭据、上传文件和私有日志存于 `~/Library/Application Support/yoyi-pilates-local/<项目路径摘要>/`，目录仅当前用户可访问。停止服务保留数据库；Redis 是独占的临时缓存，重启会重建。启动器不使用现有 3306/6379，不读取外部数据库连接配置，不清理业务数据库或共享 Redis。8090 被其他服务占用时启动失败，不终止其他进程。失败时查看 status 的原因与该目录日志；不要公开凭据或原始日志中的身份信息。

首次启动使用当前 schema.sql 和 init_data.sql 初始化独立数据库，不重复应用 Round2 迁移。初始化异常中断后再次启动会停止并要求检查，避免重复导入。已有数据库不自动迁移，也不自动补充过期排课；种子排课是首次初始化当天起七天。仅为本地团课课程1、私教课程7的教练1创建明确的测试授课资格，不更改角色授权规则。

后台账号由启动器创建，各账号的随机口令保存在私有状态目录的 `demo-accounts.json`，文件权限为 0600。已有本机演示库启动时，启动器会根据已知演示哈希识别并轮换旧口令。

本机依赖默认来自 `/tmp/pilates-round3-tools`，缺失时先运行 `sh scripts/round3/bootstrap-macos.sh`。需要 Maven、Java 21（默认 `/opt/homebrew/opt/openjdk@21`）以及系统 `htpasswd`。可用 `PILATES_LOCAL_TOOLS` 和 `PILATES_LOCAL_JAVA_HOME` 指定二进制目录。构建目录位于私有状态目录，避开桌面同步产生的重复 class 文件。

微信开发者工具打开本项目 miniprogram，使用 develop 环境，重新编译并登录。现有 baseUrl 已是 `http://127.0.0.1:8090`，开发登录 code 为 `round3_local_member`。若工具提示 HTTP/合法域名校验失败，只在本地调试设置中启用“不校验合法域名、web-view（业务域名）、TLS版本以及HTTPS证书”。不要将此设置用于体验版或发布环境。

此地址供同一台电脑上的模拟器使用；手机的 127.0.0.1 指向手机本身，真机需另行配置可访问的隔离 HTTPS 服务和合法域名。trial/release 未配置 HTTPS 时仍关闭请求。

本地启用开发 Mock 微信身份；关闭 Mock 支付、Mock 短信、课包销售和定时任务，消息总线使用本机内存。此启动及 HTTP 冒烟检查不能代替真实微信登录、开发者工具操作或真机验收。
