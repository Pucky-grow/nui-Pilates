# 微信公开发布与一键登录核查

核查日期：2026-11-03，Asia/Shanghai。结论：**当前不具备直接向普通用户公开发布的条件；微信登录云端会话兑换实测失败。** 本次未上传、提交审核或发布小程序，未变更云网络授权、密码、TLS 或业务规则。

2026-11-05 补充：[域名与主体核对](WECHAT-DOMAIN-AND-SUBJECT-CHECK.md)。微信云托管支持原生调用免配置自有域名，不能把“暂无自有域名”直接列为必须购买域名的阻断项；当前代码尚未实现该接入。用户说明为个人申请、小程序备案已完成，实际平台主体类型和备案状态仍未独立读取核实。以下 11 月 3 日的运行证据保持原样。

## 实际结果

| 项目 | 方法与结果 | 能证明的范围 |
|---|---|---|
| 小程序发布配置 | `miniprogram/config/environment.js`：trial/release 的 baseUrl 为空 | 体验/正式环境会主动拒绝请求，当前不能直接上传供别人使用 |
| 实际开发者工具配置 | 官方 CLI 读取运行时：AppID 匹配，develop，127.0.0.1:8090，useMockLogin=true，页面为 pages/login/login | 当前页面按钮仍走本机开发配置；不是云端真实登录流程 |
| 微信开发者工具状态 | 2.02.2608070；官方安装检查 compatible=true；登录有效、skill 版本相同，Codex 授权成功 | 本机调试通道可用，不表示手机或小程序发布验收通过 |
| 模拟器无响应提示 | 用户报告 12:37:59 提示；随后 currentPage 和受控表达式可正常返回 | 后续未复现该提示；历史卡顿根因未确定，不能认定业务死循环或已彻底修复 |
| 模拟器访问云端 | 第一轮健康接口成功；后续出现一次健康请求超时 | 完整应用曾实际可达，也存在连接超时，需保留失败证据 |
| 无效固定 Mock code | 模拟器请求云端 `/api/auth/wx-login` 返回 50000，未接受登录 | 没有观察到 Mock 身份成功，但错误处理/微信上游调用异常；不是预期的 40100 业务拒绝 |
| wx.login 获取凭证 | 在正确 AppID、已登录工具中调用 wx.login，成功返回 code，仅在运行时内使用 | 已从开发者工具取得凭证；尚未得到微信服务端校验成功的证据 |
| 真实 code → 云端会话 | 13:41:53 开始的第三轮：wx.login 成功，随后 `/api/auth/wx-login` 返回 50000 | **真实微信登录未通过**；没有执行成功后的 me/退出撤销验证 |
| 云运行日志 | 用户提供 uni-test-008 于 13:36:24.700 的 `ResourceAccessException` | 微信 HTTP 客户端 I/O 异常；尚不能细分 DNS、TLS、代理或连接故障，不能据此归因 AppSecret 错误 |
| 登录组件回归 | `node --test miniprogram/tests/environment-auth.test.cjs`：7 项通过 | Node 适配器与环境/重试/清理逻辑，不是微信服务端或真机证明 |
| 验收脚本适配器 | 四个模拟场景通过，包括正向、兑换拒绝、网络超时、负向错误码；报告不含合成 code/Token | 验证脚本不误报成功、不泄漏值；不能计入真实微信通过数 |

机器记录：[wechat-release-login-verification.json](wechat-release-login-verification.json)。第三轮真实结果：[devtools-real-login-3.json](evidence/wechat/20261103-release-login/devtools-real-login-3.json)。前两轮与本机网络失败分别保留，不重复累计。

## 已确认的发布阻断项

1. **客户端尚未接入云后端。** trial/release 地址为空，开发态是固定 Mock。不得为了发布测试而开启服务端 Mock、使用默认身份或降低角色授权。
2. **真实微信登录云端兑换失败。** 后端已实现 wx.login → code2session → 会员档案/JWT 的流程，但实测 50000。准备配置中的 AppID 与项目一致、AppSecret 非空且两份准备文件一致，仅能证明本地配置存在；不是实际云配置或 AppSecret 有效性证明。
3. **合法域名未验收。** 根目录工程及私有配置将 urlCheck 关闭，而小程序目录配置为开启。关闭工具校验的结果不能代替平台 request/upload/download 合法域名。当前 HTTP 接入使用默认测试域名，未实现 callContainer 接入。腾讯云说明默认域名用于测试，不应直接用作生产入口；需按选定接入方式核对正式配置。[官方说明](https://cloud.tencent.com/document/product/1243/77192)
4. **协议与隐私仍是草稿。** 隐私页仍写计划使用阿里云、地域和期限待确认，与已部署的腾讯云测试环境不一致；用户协议包含尚未销售的购课描述及占位声明。经营主体、联系方式、保存期限、注销受理和健康资料处理范围须由经营者确认，并与平台隐私保护指引一致。不能编造这些信息。[平台隐私指引说明](https://cloud.tencent.com/document/product/1301/97930)
5. **当前后端仍是隔离测试环境。** MySQL 使用用户仅批准用于 uni-test 的内网非 TLS 例外，不应在未重新审阅前改成公开生产使用；未改变该限定。
6. **平台与完整业务验收不足。** 本轮没有核实公众平台的小程序备案、服务类目、隐私指引审核、正式版本审核/发布状态；云端预约/取消重约/签到及手机体验也尚未完整通过。源码存在 AppID、后端健康或管理员登录通过均不能代替这些项目。微信小程序备案应在公众平台办理；本轮未判断该账号是否已完成。[官方备案入口说明](https://cloud.tencent.com/document/faq/243/97691)

## 登录异常的下一步定位

- `WxMpClient.code2session` 通过 RestTemplate 请求 `api.weixin.qq.com/sns/jscode2session`。用户此前提供的微信官方排查文档提示：普通 sns/jscode2session/getAccessToken 调用与“开放接口服务”代理设置可能冲突。**这只是候选原因，开关状态未找到，未确认。**
- 当前全局异常日志只输出 ResourceAccessException 的顶层类型，缺少底层原因。下一步应获取或增加脱敏的根因类型（如 SSLHandshakeException、UnknownHostException、SocketTimeoutException），禁止打印带 AppSecret/code 的请求 URL、完整响应、Token 或 session_key。
- 明确底层原因后修复对应的 DNS/网络/受信 CA 或平台调用方式。不得通过关闭 HTTPS 校验、修改固定会员身份或放宽授权使测试通过。若涉及平台开关，先核对确切功能和目标，再生成新测试版本。
- 再用全新 wx.login code 验证会话签发、me、退出与旧 Token 失效。不能重用已消费或状态不明的 code。
- 接通客户端测试配置、开启域名验证、补齐门店/课程测试资料后，走体验版手机测试；通过后再审阅公开发布方案。备案、审核和正式发布为后续平台步骤，本轮没有执行。[官方发布说明](https://cloud.tencent.com/document/product/1301/55140)

## 模拟器与工程范围

已核查登录/首页启动逻辑，未发现明显无限循环；7 项组件用例覆盖登录失败不递归重试。该静态结果不能证明所有运行场景都不会卡顿。根工程指向 miniprogram/，但 packOptions.ignore 为空，仓库含约 3.1GB 构建产物；这是需排除的文件扫描负担假设，**不是已确认根因**。没有修改开发者工具安装、强制结束进程、清理缓存、删除项目或连续刷新。

本轮首次长函数诊断因工具处理 await 的限制没有开始执行；改用 Promise 链后已获得上述实际结果。该失败属于诊断适配器，不能归因业务应用。登录 code、OpenID、Token、session_key 和 AppSecret 没有写入报告。
