# 微信云托管 MySQL TLS 能力核询草稿

2026-11-02，Asia/Shanghai。以下保留原核询草稿。用户已自行提交工单 `202611028033`；代理于已登录工单页只读核对，腾讯云工程师在 **13:43:03** 回复：微信云托管自带的 MySQL 不支持开启 SSL。代理未发送回复、催单或结单。证据见 [原文](evidence/cloudrun/connection-acceptance/vendor-mysql-no-ssl-20261102.txt)。

当前不再将“等待找到内置库 SSL 开关”列为下一步。现有 `VERIFY_IDENTITY` 验收仍未通过；后续需选择支持 TLS 的独立数据库，或另行审阅仅内网连接的架构、安全边界和风险接受。内网隔离不提供数据库传输加密，本次未批准或执行降低 TLS 要求。

## 可复制的核询内容

我们正在为微信云托管 Java 应用接入已有隔离 MySQL 测试库，需要保留 JDBC `sslMode=VERIFY_IDENTITY`，验证证书链、服务端身份并使用 TLS 1.2 或以上。

- 主账号 UIN：`100053657418`
- 云托管环境 / 服务：`uni-test`；地域：上海
- MySQL 实例：`cynosdbmysql-41yi5vim`；数据库：`uni_pilates_test`
- 当前内网地址：`10.39.100.38:3306`；公网关闭
- 完整版本：`8.0.30-cynos-3.1.19.002`；版本注释：`20230630`
- DMC 重新登录后查询已恢复，当前实例身份和迁移记录一致。

实际只读查询：

```sql
SHOW VARIABLES WHERE Variable_name IN
  ('have_ssl','require_secure_transport','ssl_ca','ssl_cert','tls_version');
SHOW SESSION STATUS LIKE 'Ssl_cipher';
```

结果：`have_ssl=DISABLED`、`require_secure_transport=OFF`、`ssl_ca` 和 `ssl_cert` 为空、`tls_version=TLSv1.2,TLSv1.3`，DMC 会话 `Ssl_cipher` 为空。

请确认：

1. **这个微信云托管托管实例**是否支持启用原生 MySQL TLS？如支持，请提供适用于该产品/实例的入口或受控操作流程、是否会重启/中断连接及回退方法。
2. 应用应使用哪个实际内网主机名/端口，如何获取可信 CA 链及证书更新信息？若使用当前 IP，请确认服务端证书是否包含该 IP 的 SAN，以支持 `VERIFY_IDENTITY`。
3. 若提供的是平台加密代理，请明确客户端到代理、代理到数据库分别采用的加密和身份校验方式，以及适用的官方 JDBC 配置；不能仅以“走内网”替代 TLS。
4. 在不开放数据库公网、不购买额外服务器的前提下，当前账号是否有受支持的一次性容器命令/运维执行能力，可运行 Java 17/21 的只读 JDBC 验收？请提供准确入口及费用边界。数据库连接必须发生在运行阶段，不能放进 Docker 构建阶段。
5. 如该托管形态不支持以上能力，请明确限制和可支持上述要求的替代方式。请勿直接注销、重建、迁移数据库或开启公网。

附件可选：`docs/evidence/cloudrun/connection-acceptance/dmc-tls-disabled-20261102.jpg`。只提交此无秘密结果图；不要附带数据库登录页、CAM 密钥页或 `.secrets` 配置。

## 收到答复后如何继续

先审核平台针对本实例的答复，再进行必要配置。当前没有执行 TLS 配置变更、重启、运行账号授权、数据库公网开放或资源购买。

通过门槛是实际 Java 驱动使用 `VERIFY_IDENTITY` 完成可信证书链和主机名/IP 校验，并返回非空 TLS cipher；控制台开关、`have_ssl=YES` 或内网可达本身都不能代替此结果。之后才继续受限运行账号、真实 JDBC 探针和完整应用联调。
