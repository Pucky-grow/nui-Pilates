# 优仪普拉提 · Yoyi Pilates

单店普拉提预约系统，包含微信小程序、Web 管理后台和 Java API。会员、教练和管理员共用小程序，业务覆盖团课、私教预约、签到、排课和线下会员课时台账。

## 项目结构

```text
backend/              Spring Boot API、Maven 构建和后端测试
frontend/
  admin/              Vue 管理后台和浏览器测试
  miniprogram/        微信小程序、分包、资源和 Node 测试
database/             本地演示 SQL、历史迁移和生产版本化 SQL
deploy/               Docker、Compose、Nginx、云部署及配置示例
scripts/              开发、测试、构建和运维脚本
docs/                 开发、部署和验收手册
project.config.json   微信开发者工具的仓库导入入口
```

此仓库从原项目的当前工作区整理，包含当时尚未提交的功能代码。Java 包名、API 路径、小程序页面路由、数据库 SQL 和依赖锁文件沿用原项目。构建与测试路径随模块目录调整。本地环境配置、构建产物、上传文件和历史验收记录留在原项目。

## 本地开发

后端需要 JDK 17 或 21 和 Maven，Web 后台使用 `.nvmrc` 指定的 Node 24。以下命令从仓库根目录运行。

```bash
mvn -f backend/pom.xml -DskipTests package
python3 scripts/dev-local.py start
python3 scripts/dev-local.py status
npm --prefix frontend/admin ci
npm --prefix frontend/admin run dev
```

macOS 本地运行器为当前仓库路径创建独立的 MySQL 和 Redis，API 默认监听 `127.0.0.1:8090`。首次启动所需工具和本地小程序调试方式见[本机联调手册](docs/LOCAL-DEVELOPMENT.md)。

用微信开发者工具导入仓库根目录，根目录的 `project.config.json` 指向 `frontend/miniprogram/`。也可直接导入 `frontend/miniprogram/`。环境配置位于 `frontend/miniprogram/config/environment.js`，开发版和体验版沿用原来的隔离云托管环境，正式版需要单独配置。

## 验证

```bash
python3 scripts/test-isolated.py \
  '-Dtest=*,!ProductionServiceTest#schedulerVisitsBeyondFirstTwoHundredAndFiveHundredRows+timeoutOrderBatchContinuesBeyondFailedRowAndRollsBackThatOrder'
node --test frontend/miniprogram/tests/*.test.cjs
node scripts/check-miniapp.cjs
python3 scripts/check-admin-templates.py --scope all
npm --prefix frontend/admin run build
python3 scripts/production/secret-scan.py
```

H2 隔离测试需要本机 Redis 和已缓存的 Maven 依赖。上面的筛选排除了两项使用 `DATE_SUB` 的 MySQL 用例。可用 `python3 scripts/round3/accept.py --mysql-bin /PATH/TO/MYSQL_8_0_46/bin --tests ProductionServiceTest --skip-processes` 在隔离 MySQL 中运行该组用例。

原生模板检查使用微信开发者工具附带的编译器。开发者工具交互和真机验收项目见[微信验收清单](docs/WECHAT-STAGING-CHECKLIST.md)。

## 数据库与部署

`database/schema.sql` 和 `database/init_data.sql` 用于本地演示。生产初始化与升级使用 `database/production/` 下的 V001 至 V004，由离线工具按需执行。首个管理员也由离线工具创建。见[初始化、升级与恢复手册](docs/PRODUCTION-UPGRADE-RUNBOOK.md)。

```bash
docker build -f deploy/Dockerfile.server backend
docker build -f deploy/Dockerfile.admin frontend/admin
```

上述命令从源码构建单组件镜像。生产制品可通过 `scripts/production/build.py` 和 `scripts/production/assemble.py` 构建及组包。Compose 模板使用已验证的镜像摘要，以及外部 MySQL、Redis、对象存储和私有配置。

- 阿里云隔离测试部署见[阿里云手册](docs/ALIYUN-STAGING-RUNBOOK.md)。
- 微信云托管构建、组包及测试部署见[云托管准备手册](docs/WECHAT-CLOUDRUN-PREPARATION.md)。
- 上线前逐项核查业务、数据和平台条件。[上线检查表](docs/GO-LIVE-CHECKLIST.md)
