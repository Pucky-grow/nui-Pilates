# 小程序云托管原生接入候选

2026-11-06。此改动只增加客户端网络能力，当前 develop 仍连接本机，trial/release 仍关闭。没有上传小程序、写云数据库或切换云版本。

会员接口、管理端、教练端和登录页员工入口现共用 `miniprogram/utils/transport.js`。HTTP 模式保留 `wx.request` / `wx.uploadFile`；cloudrun 模式使用 `wx.cloud.callContainer`，环境和服务必须明确配置，不自动回退公网 HTTP。

参数与返回值依据微信官方 [api-typings](https://github.com/wechat-miniprogram/api-typings/blob/master/types/wx/lib.wx.cloud.d.ts) 核对：`CallContainerParam` 支持配置、路径、方法、请求头、数据、超时、响应类型及重定向选项，返回含 `data` 和 `statusCode`。本候选使用明确环境、`X-WX-SERVICE`、JSON 返回和禁止自动重定向。客户端只沿用应用 Bearer Token，不接受调用者伪造微信身份头，也不以微信请求头代替现有服务端鉴权。

GET/HEAD 的非空文字、数字和布尔值参数编码进路径查询串，保留 0 / false；对象和数组查询参数明确拒绝。POST/PUT 等沿用各端原有 JSON 请求体和身份规则。三端会话保持分离，员工登录不会转成会员登录。切换接入方式、环境或服务时，本机缓存身份会清除。

验收后用于开发/体验配置的候选字段为：

```js
{ transport: 'cloudrun', cloudEnvironment: 'uni-test-d5gwbyymua6bf2ada', cloudService: 'uni-test', useMockLogin: false }
```

这不是实际激活指令。须先解决后端真实微信会话兑换失败，随后验证环境绑定、真实原生请求和手机体验，再启用相应环境；release 继续单独验收。

## 小图片上传候选与待验收项

已增加原生小图片上传候选：读取用户选中的本地图片，按 PNG/JPEG/GIF 魔数选择固定的安全文件名，组装 multipart ArrayBuffer，经 `callContainer` 提交到既有 **`/api/upload`**。服务端的 `FileController` 接收 `file` / `purpose` / `ownerId`，继续由 `FileService` 验证身份、用途、对象归属、图片解码和大小；没有增加后端授权、修改 COS 策略或向客户端提供 CAM 密钥。

客户端只接受已有三种用途 `AVATAR`、`BODY_RECORD`、`PUBLIC_ASSET` 和受限正整数会员编号，其他字段拒绝。预检文件大小后读取二进制，单文件上限 **500KiB**，完整 multipart 上限 **512KiB**。超限、空文件、非图片、无文件系统能力、文件读取失败或读取过程中切换环境，均明确失败，不降级公网 HTTP，不记录本地路径。不自动重试上传，避免意外重复写入。

微信官方 [Cloud 类型定义](https://github.com/wechat-miniprogram/api-typings/blob/master/types/wx/lib.wx.cloud.d.ts) 的 `CallContainerParam` 注明 `data` 可为 ArrayBuffer，并可传请求头；[API 类型定义](https://github.com/wechat-miniprogram/api-typings/blob/master/types/wx/lib.wx.api.d.ts) 说明 `FileSystemManager.readFile` 不指定编码时读取 ArrayBuffer，支持指定读取长度，`stat` 返回文件大小。**这些定义并不能证明微信实际链路已正确转发 multipart。** 本轮仅验证客户端编码通过标准 multipart 解析器还原，尚未进行微信云端或真机上传，不得标记为真实上传通过。

超出 500KiB 的门店/课程图片需要先压缩后重新选择。后续须以少量合成图片验证原生链路实际请求体、服务端用途鉴权、COS 入库记录和预览；若平台实际限制更低，应进一步下调客户端限制。既有图片的手机展示、私有预览地址及下载域名也尚未验收，不能因为上传改走原生链路就宣称所有图片访问免域名配置。

本轮 Node 测试验证请求编码、会话隔离、无静默降级、未配置拒绝、二进制 multipart 还原和图片边界等客户端契约；不等于真实微信登录、手机预约签到或正式上架已通过。
