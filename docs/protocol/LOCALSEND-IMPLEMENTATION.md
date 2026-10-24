# LocalSend 兼容链路实现

更新：2026-10-25。

本项目的 LocalSend 适配器沿用 [官方 v2.2 协议](https://github.com/localsend/protocol/blob/main/README.md)：JSON 元数据准备、逐文件二进制上传、并行传输、接收确认、PIN、SHA-256 与取消。它与 BP/2 分别维护身份授权和任务状态，发送由统一路由选择。原生 BP/2 默认设置本轮未调整。

## 代码边界

| 文件 | 职责 |
|---|---|
| `core/.../net/ApiClient.kt` | 按 HTTPS/证书指纹缓存客户端，封装元数据请求、可取消的准备/上传调用、取消与连接资源回收 |
| `core/.../net/LocalSendUploadBody.kt` | 按声明大小流式读取源、进度、源大小变化检查；旧开发扩展的尾摘要仍须显式协商 |
| `core/.../net/HttpServer.kt` | 连接接入/关闭、请求头、路由调用、响应和连接复用 |
| `core/.../net/HttpRequestBody.kt` | 固定长度与 chunked 请求体边界、分块扩展/尾部字段、有界行和元数据读取 |
| `core/.../net/ApiServer.kt` | 官方 API 路径与 DTO、协议状态码映射 |
| `core/.../engine/TransferEngine.kt` | 接收确认、PIN、存储、发送并发/重试、任务状态和会话归属 |

## 本轮完善

### 快速发送选项

Android、Compose 桌面和 WinUI 的“设置 → 传输”提供“LocalSend 快速发送（跳过预哈希）”。开启后不预读文件计算摘要，接收方同意后直接上传；标准上传元数据不带整文件 `sha256`，文件 body 不追加私有尾摘要。它保持标准 LocalSend 格式，但不提供这一层应用层整文件 SHA-256 比对。HTTPS 加密由独立设置控制，BP/2 的流式校验独立生效。

该选项复用已有持久化字段：快速发送开启等价于 `createChecksums:false`，关闭等价于 `createChecksums:true`。已有偏好保持原值，新配置默认仍启用发送校验（快速发送关闭）；不新增相反含义的第二个配置字段。接收端 `verifyChecksums` 保持独立，未提供摘要时不进行该摘要比对。

修改对新建发送任务生效，无需重启接收服务。回归测试使用真实 TLS/标准 LocalSend 通道，确认接收前不打开源、上传大/小/空文件时各打开一次，且元数据不携带 `sha256` 或私有尾摘要标记。

### 连接与传输

- 准备请求和上传使用可取消的 OkHttp Call。取消协程会关闭网络调用，覆盖等待接收确认、网络写入阻塞以及响应体只收到一部分的情况。
- 停止引擎会取消客户端调用、回收空闲连接并关闭自有 dispatcher 执行器；再次启动时重建客户端。客户端创建和回收互斥，避免同一指纹并发建池。
- 下一次发送等待上一条取消通知完成，通知使用取消时捕获的端点，避免晚到消息作用于另一目标或新任务。取消后的进度回调受原协程状态约束。
- 接收取消必须来自原发送 IP，并匹配当前 sessionId；在未拿到准备结果时允许原发送 IP 通过无 sessionId 请求取消。旧会话 ID 不匹配时不取消新会话。LocalSend 取消入口不处理 BP/2 任务。
- HTTP 接收端启用 TCP_NODELAY，并保留批量缓冲写入；活跃及排队连接合计最多 128 个，handler 并发最多 64 个。
- 仅在请求体已完整消费时复用连接；拒绝未读完的请求体时直接响应并关闭，不等待大段剩余上传，也不把残留数据当作下一条请求。这遵循 [RFC 9112 的连接持久性要求](https://www.rfc-editor.org/rfc/rfc9112.html#section-9.3)。
- 固定长度或 chunked 请求体截断、分块大小非法/溢出、错误终止符都按失败处理。支持合法分块扩展与有界 trailers。
- 请求行/分块行上限 16 KiB，请求头合计最多 64 KiB、128 个字段。拒绝不一致的 Content-Length、同时指定 Content-Length 与 Transfer-Encoding、无效传输编码；数值相同的重复长度仍可接受。
- HTTP/1.0 默认关闭连接，HTTP/1.1 识别 Connection 的多项 token；响应只写一次 Connection。`show` 参数改由 JSON 编码器处理换行、制表符和转义。

## 验证方式

```powershell
# 先构建，避免编译与性能测量争用资源
.\gradlew.bat :androidApp:assembleDebug :desktopApp:compileKotlin :webApp:compileKotlinWasmJs :engine-host:installDist

# 包含独立 JVM 的原有吞吐门槛，不降低阈值
.\gradlew.bat :core:jvmTest :core:wasmJsNodeTest :app-shared:jvmTest

# 同存储的 BP/2 与标准 LocalSend 适配器对照；不等于官方客户端或无线实测
.\gradlew.bat :core:bp2Benchmark

# JSON-lines IPC、两进程收发/重启续传与 WinUI 构建
.\windowsApp\run.ps1 -BuildOnly -Test
```

针对性测试见 `ApiClientCancellationTest`、`HttpFramingTest`、`EngineTransferTest`、`ProtocolInteropTest` 和 `HttpServerShutdownTest`；BP/2 的取消隔离由 `Bp2EngineTransferTest` 覆盖。性能结论应结合完整样本与波动范围，不能将更换调度方式或开启 TCP_NODELAY 本身当作已证明的吞吐提升。
