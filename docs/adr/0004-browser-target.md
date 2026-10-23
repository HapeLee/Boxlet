# ADR 0004：用 KMP/CMP 补一个浏览器目标

- 状态：已采纳（传输方式已被 [ADR 0005](0005-boxlet-protocol.md) 取代）
- 日期：2026-10-21

> **2026-10-21 更新**：Web 端已改走 SP/1 bridge（浏览器 ↔ 原生节点 WebSocket），不再用 `fetch`
> 直连对端的 LocalSend API，原来的 `WebApi.kt` 已删除。因此下文中
> “目标必须关闭 HTTPS”“只能往一个方向扔文件”的结论只属于直连方案，不再适用于当前实现。
> 浏览器仍然**不能接收、不能被自动发现**（沙箱限制没变），只是发送路径换成了明文 WS，
> 与目标设备是否开启 HTTPS 无关。

## 背景

桌面与 Android 都是 Compose Multiplatform。浏览器目标的价值是：**没装客户端的人**（借用别人的电脑、电视浏览器、临时设备）也能把文件发给局域网里跑着 Boxlet 的机器。

官方生态里对应的是 web 版（浏览器打开、手动连接局域网设备）。要判断这条路的边界，先看浏览器能做什么。

## 浏览器沙箱的硬限制

| 限制 | 后果 |
|---|---|
| 不能监听 TCP 端口 | 无法提供 `/register`/`/upload`，因此**不能作为接收方被别的设备发现** |
| 不能发 UDP 多播 | 无法自动发现；只能手动输入目标地址（或用户自己扫网段） |
| 不能自定义 TLS 信任 | 目标开 HTTPS（自签名）时，握手直接被浏览器拒绝，连指纹固定都无从谈起 |
| 混合内容策略 | 页面若由 HTTPS 托管，连 `http://192.168.x.x` 也会被拦 |

结论：Web 端只能是**发送方**，且目标设备必须关闭「加密传输」（HTTP 模式）。这不是实现取舍，是浏览器能力上限；官方 web 版同样如此。

## 决策

### 1. 技术栈：Kotlin/Wasm + Compose Multiplatform

不写一套原生 HTML/JS，而是新增 `webApp` 模块：

- 与桌面/Android 同属 CMP，UI 写法一致，后续可以把设备卡片、传输进度这些组件抽到共享源集。
- 协议 DTO 直接复用：把 `core` 里纯序列化的 `protocol` 包下沉到 `commonMain`，JVM 与 wasm 共享，避免第二份字段定义。

### 2. 不复用 core 的传输层

`core` 的网络栈是 JDK Socket + OkHttp + BouncyCastle，wasm 上一个都用不了。Web 端需要自己的实现：

- HTTP：浏览器 `fetch`（`@JsFun` 包装），JSON 用 `kotlinx-serialization`（wasm 支持）。
- 发送流程：`POST /register` 拿 info → `POST /prepare-upload` 拿 `sessionId` 与逐文件 `token` → 逐个 `POST /upload`。
- 文件读取：`<input type="file">` + `File.arrayBuffer()`。

### 3. 服务端补 CORS

浏览器跨域访问必须带 CORS 头与 OPTIONS 预检，否则连 `/info` 都读不到。已在 `LocalSendApiServer` 统一追加：

- `Access-Control-Allow-Origin: *`（不带凭据）
- `Access-Control-Allow-Methods: GET, POST, OPTIONS`
- `Access-Control-Allow-Headers: Content-Type, Content-Length`
- OPTIONS 预检直接返回 204

安全影响：任意网页都能读本机 `/info` 并尝试发送文件。局域网内本来就是可信模型，且上传需要用户在自己设备上点「接受」，可接受；不开放 `download` 相关端点的跨域写操作。

### 4. 不提供服务端渲染与上传入口

Web 端只做「浏览器 → 设备」的单向发送。反向（设备 → 浏览器）已经有 Download API（ADR 0003），但那需要设备主动生成分享链接，与本目标无关。

## 后果

正面：

- 不用装客户端就能发文件，补齐了整个生态里唯一的空白角色。
- 协议 DTO 只有一份，字段漂移风险降低。
- Web 端 UI 与桌面同构，后续共享组件成本低。

负面：

- **目标必须关 HTTPS**：这是最容易踩的坑，UI 上要明确提示（连不上时区分「地址不对」与「对方开了加密」）。
- 浏览器不能被动接收，也无法自动发现，体验弱于原生端。
- 多一个构建产物与一条工具链（Kotlin/Wasm），CI 时间变长。
- `core` 的 `protocol` 包要下沉到 `commonMain`，需要把 `System.currentTimeMillis()` 换成跨平台时钟。

## 参考

- `webApp/` 模块（Kotlin/Wasm + CMP）
- `core/src/commonMain/kotlin/dev/boxlet/core/protocol/`
- ADR 0002（协议一致性）、ADR 0003（Download API）
