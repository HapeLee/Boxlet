# ADR 0003：实现 Download API（浏览器分享）

- 状态：已采纳
- 日期：2026-10-21

## 背景

[ADR 0002](0002-protocol-conformance.md) 的审计结论里，`prepare-download` / `download` 是唯一完全缺失的协议能力：两个路由直接回 403，本机 `download` 字段恒为 `false`。

官方规范（v2.2 §5）的这一段是“反向传输”：**发送方**起 HTTP 服务，把 `http://<ip>:<port>` 交给对方浏览器下载。文档明确要求这一段用**明文 HTTP**，因为浏览器不接受自签名证书。

## 决策

### 1. 独立明文端口，不与主服务共用

主服务（53318）是 HTTPS + 可选 mTLS，浏览器无法使用。分享服务单独起一个 `LocalSendHttpServer(https = false)`：

- 绑定端口用 `0`（系统分配），实际端口写回 `share.port` 与 URL。避免与 `53318`/`53319` 上可能存在的其他进程抢端口。
- 生命周期与分享会话一致：`startShare` 创建、`stopShare` 销毁，进程退出/引擎 stop 时一并关闭。
- 服务只监听局域网，不做路由转发，不开 UPnP。

### 2. 端点严格按规范

| 方法 | 路径 | 行为 |
|---|---|---|
| `GET` | `/` | 分享页（文件列表 + 下载链接 + PIN 输入） |
| `POST` | `/prepare-download?pin=&sessionId=` | 返回 `{info, sessionId, files}`；`files` 不含 `metadata` |
| `GET` | `/download?sessionId=&fileId=` | 二进制流，带 `Content-Length` 与 `Content-Disposition` |

- 会话 id 在分享创建时生成，`prepare-download` 反复返回同一个，支持刷新页面。
- 无活动分享时两个 API 回 403 `Rejected`（与 ADR 0002 的语义一致）。
- PIN 复用设置里的接收 PIN：`/`、`/prepare-download` 都要带 `?pin=`，错误回 401。
- 下载文件直接读源文件，不复制到临时目录；文件在分享期间被外部修改/删除时该连接报错并断开。

### 3. `download` 字段随分享状态变化

`download` 语义是“本机提供浏览器下载能力”。实现为：有活动分享时为 `true`，否则 `false`。切换时补发一次多播 announce，让对端及时更新。

**已知偏差（重要）**：官方规范假定 URL 就是 `http://<ip>:<sender-port>`，即主服务端口。Boxlet 主服务是 HTTPS，浏览器打不开，所以分享 URL 里带的是**独立的明文端口**，由 UI 直接展示给用户复制。按 `download = true` 硬拼主端口的官方客户端会得到证书错误——这是“主服务必须 HTTPS”与“Download API 必须明文”两条约束不可兼得的结果，选择保留 HTTPS 主服务。

### 4. 不接受明文替代主服务

不考虑“分享期间把主服务切成明文”：那会改变设备指纹派生方式（HTTP 模式指纹是随机串）与 mTLS 行为，正在进行的接收会话也会中断，代价远大于收益。

## 后果

正面：

- 协议端点、DTO、状态码完整，`download` 能力声明与实际一致。
- 不装任何客户端也能用浏览器取文件，这是 LocalSend 的核心便利之一。
- 明文只出现在用户主动开启、可随时关闭的分享端口上，主通道仍然加密。

负面：

- 局域网内同网段任何拿到 URL 的人都能下载（PIN 可选，默认关闭是因为分享本身就是“我要给别人”的主动动作）。
- 分享期间多一个监听端口；`download = true` 会让官方客户端尝试主端口并失败（见上文偏差）。
- 浏览器下载没有断点续传（未实现 `Range`），大文件中断需重下。

## 参考

- `localsend/protocol` v2.2 §5（Download API）、§5.2、§5.3
- 实现：`core/src/jvmSharedMain/kotlin/dev/boxlet/core/net/ShareServer.kt`
