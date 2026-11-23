# 本机小程序联调

在仓库根目录运行以下命令。

```sh
python3 scripts/dev-local.py start
python3 scripts/dev-local.py status
python3 scripts/dev-local.py stop
```

`start` 编译并在后台持续运行后端（127.0.0.1:8090），同时启动专属 MySQL 8.0.46 和 Redis 7.4.10。重复启动会报告已有进程。修改后端代码后先 stop 再 start。关机后需要重新 start；这不是开机自启服务。

数据、凭据、上传文件和日志保存在 `~/Library/Application Support/yoyi-pilates-local/<项目路径摘要>/`。新仓库路径对应独立数据目录。停止服务会保留这个 MySQL 数据库，重启时会重建临时 Redis。8090 被占用或启动失败时，可通过 `status` 查看原因和该目录中的日志。

首次启动使用 `database/schema.sql` 和 `database/init_data.sql` 初始化独立数据库，并检查 V003 交付结构和 V004 课时台账结构。初始化中断后再次启动会要求先检查，以避免重复导入。种子排课覆盖初始化当天起七天。教练 1 获得本地团课课程 1 和私教课程 7 的测试授课资格。

本机依赖默认来自 `/tmp/pilates-round3-tools`，缺失时先运行 `sh scripts/round3/bootstrap-macos.sh`；需要 Maven 和 Java 21（默认 `/opt/homebrew/opt/openjdk@21`）。可用 `PILATES_LOCAL_TOOLS` 和 `PILATES_LOCAL_JAVA_HOME` 指定二进制目录；不支持连接外部数据库。构建目录位于私有状态目录，避开桌面同步产生的重复 class 文件。

微信开发者工具可导入仓库根目录或 `frontend/miniprogram/`。默认的 develop 和 trial 连接原有隔离云托管。要联调本机后端，在本地副本中设置 `frontend/miniprogram/config/environment.js` 的 develop 值，再重新编译。

```js
develop: { transport: "http", baseUrl: "http://127.0.0.1:8090", useMockLogin: true, mockCode: "round3_local_member" },
```

开发者工具的本地 HTTP 调试设置只适用于本机测试。体验版和发布版继续使用平台的域名与证书校验。

本机地址供同一台电脑上的模拟器使用。真机联调使用已配置的隔离云托管，或可访问的隔离 HTTPS 后端及合法域名。release 保持待配置状态。

本地启用开发 Mock 微信身份；关闭 Mock 支付、Mock 短信、课包销售和定时任务，消息总线使用本机内存。此启动及 HTTP 冒烟检查不能代替真实微信登录、开发者工具操作或真机验收。
