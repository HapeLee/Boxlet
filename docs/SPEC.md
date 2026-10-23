# Boxlet 项目规范

> 局域网文件互传客户端，目标主协议为 Sendee Protocol 2（SP/2），默认启用标准 LocalSend v2.2 兼容，统一发现并按目标自动路由。
> Android 使用 Jetpack Compose + Material 3；桌面使用 Compose Multiplatform + Fluent Design（compose-fluent-ui）。
> 本文件是产品与工程规范入口；SP/2 线格式与状态机以 [SP/2 规范](SENDEE-PROTOCOL-2.md) 为准。当前运行时仍是 LocalSend v2.2 + ADR 0007 扩展、SP/1，迁移阶段见 [ADR 0008](adr/0008-sendee-primary-protocol.md)。

---

## 1. 目标与非目标

### 1.1 目标

1. **自有主协议与默认兼容**：Sendee 设备优先使用 SP/2，标准 LocalSend v2.2 适配器默认同时发现与接收；选中仅支持 LocalSend 的设备就直接用其协议发送，不要求手动切模式，也不承担旧版 Sendee 回退。协议身份、帧与续传状态分别隔离，统一 UI/任务模型。当前端口与运行时行为仍见第 4 节；目标路由与 Quick Share 研究边界见 [ADR 0009](adr/0009-unified-device-discovery-and-routing.md)。
2. **双端原生体验**：Android 遵循 Material 3 与系统交互习惯；桌面遵循 Windows 11 Fluent Design 与桌面交互习惯（侧边导航、Mica、键盘/鼠标友好）。
3. **核心与 UI 解耦**：网络与协议层不依赖任何 Compose / Android UI API，可被将来的原生桌面壳（WinUI 3 / Avalonia / Tauri / Qt）直接复用或通过进程协议驱动。
4. **最新且可复现的工具链**：版本统一收敛在 `gradle/libs.versions.toml`，禁用动态版本。
5. **安全默认**：SP/2 使用 TLS/WSS 与持久化设备身份，首次明确配对，已信任设备固定证书指纹，不自行设计加密算法；浏览器证书信任与授权单独处理。现有链路在迁移前保留 HTTPS 默认。

### 1.2 非目标（当前里程碑）

- iOS / macOS 客户端（架构预留，不实现）。
- LocalSend 兼容层的断点续传 / 暂停（协议本身没有）；仅 SP/1 提供进程内续传。
- WebRTC 传输（协议 v3 实验特性）。
- 云中转、账号体系、跨网段穿透。
- 自动更新、代码签名、安装包分发流水线。

---

## 2. 技术栈与版本矩阵

> 基线日期：2026-10-21。所有版本均为当日可获取的最新稳定版；升级必须先改本表再改代码。

| 组件 | 版本 | 用途 / 说明 |
|---|---|---|
| JDK | 21 (LTS) | 构建与桌面运行；字节码目标 17 |
| Gradle | 9.6.1 | AGP 9.4 要求 ≥ 9.6.0，取 AGP 默认配套版本 |
| Android Gradle Plugin | 9.4.0 | 使用 AGP 9 新 DSL；Android app 使用内置 Kotlin |
| Kotlin | 2.4.21 | KMP、序列化、协程编译器插件 |
| Compose Multiplatform | 1.12.2 | 桌面 UI 与 `webApp`（wasmJs） |
| Kotlin/Wasm | 2.4.21 | 浏览器目标；只编译 `core` 的 `commonMain` 协议 DTO |
| compose-fluent-ui | `io.github.compose-fluent:fluent:v0.1.0` | 桌面 UI；图标 `fluent-icons-extended:v0.1.0` |
| Android Compose BOM | 2026.09.00 | 统一 Android Compose 版本 |
| Material 3 | BOM 管理（1.4.0） | Android UI |
| Activity Compose | 1.13.0 | Android 入口 |
| Lifecycle | 2.11.0 | `collectAsStateWithLifecycle` |
| AndroidX Core KTX | 1.19.1 | Android 基础 |
| DocumentFile | 1.1.0 | SAF 目录写入 |
| Kotlin Coroutines | 1.11.0 | 并发、流 |
| kotlinx.serialization | 1.12.0 | 协议 JSON / 持久化 JSON |
| kotlin.time（Kotlin 标准库） | 2.4.21 | `Instant` / `Clock`，RFC 3339 时间戳 |
| Okio | 3.18.2 | 跨平台文件 IO、SHA-256、流式上传 |
| OkHttp | 5.5.0 | HTTP 客户端、TLS 指纹固定 |
| BouncyCastle | 1.86 (`bcprov-jdk18on`, `bcpkix-jdk18on`) | 自签名 X.509 证书生成 |
| Android compileSdk / targetSdk | 37 | AGP 9.4 支持上限 |
| Android minSdk | 24 | 与 LocalSend 支持的 Android 7.0 对齐 |
| JVM target | 17 | 桌面与 Android 库统一 |

### 2.1 依赖纪律

- 所有第三方版本只能出现在 `gradle/libs.versions.toml`，模块内使用 `libs.*` 别名。
- 禁止 `+`、`latest.release`、`SNAPSHOT`（compose-fluent 的 `v0.1.0` 除外，它是正式发布标签）。
- `core` 与 `app-shared` **不得**依赖 Compose、Android Context、AndroidX UI。
- 新增依赖必须说明：用途、许可证、是否带来平台限制。

### 2.2 为什么桌面不共享 Compose UI

需求明确 Android 用 M3、桌面用 Fluent，两者设计语言与交互模型差异大。强行共享 UI 会产生大量 `expect/actual` 和平台判断，体验两边都不像原生。因此：

- **共享状态层**（`app-shared`）以 `StateFlow` 暴露 UI 状态；
- **各端独立 UI**（`androidApp` / `desktopApp`），只做渲染与事件转发；
- 这同时为"桌面原生壳"保留了最干净的接入面。

---

## 3. 模块架构

```
┌──────────────────────┐   ┌──────────────────────┐   ┌──────────────────────┐
│     androidApp       │   │     desktopApp       │   │  native desktop shell│
│  Compose + M3        │   │  CMP + Fluent        │   │  WinUI/Avalonia/...  │
└──────────┬───────────┘   └──────────┬───────────┘   └──────────┬───────────┘
           │                          │                          │ JSON-lines
           └────────────┬─────────────┘                          │
                        ▼                                        ▼
              ┌──────────────────────┐                 ┌──────────────────────┐
              │     app-shared       │                 │     engine-host      │
              │ 设置/历史/收藏/状态机 │                 │ 无头进程 + 控制协议  │
              └──────────┬───────────┘                 └──────────┬───────────┘
                         └────────────────┬───────────────────────┘
                                          ▼
                                 ┌──────────────────┐
                                 │       core       │
                                 │ 协议/证书/发现/  │
                                 │ HTTP 服务端/客户端│
                                 └──────────────────┘
```

| 模块 | 类型 | 职责 | 禁止 |
|---|---|---|---|
| `core` | KMP (`android`, `jvm`) | 协议 DTO、JSON、证书、UDP 发现、HTTP(S) 服务端与客户端、收发会话状态机、存储接口 | UI、Android Context、持久化业务 |
| `app-shared` | KMP (`android`, `jvm`) | 设置/历史/收藏持久化，引擎装配，UI StateFlow，业务动作 | Compose、Context |
| `androidApp` | Android app | M3 UI、SAF/MediaStore 存储实现、文件选择、Application 级单例 | 协议实现 |
| `desktopApp` | JVM app | Fluent UI、AWT 文件选择、文件系统存储实现、窗口/托盘 | 协议实现 |
| `engine-host` | JVM app | 无头运行 core+app-shared，stdin/stdout JSON-lines 控制协议 | UI |
| `webApp` | KMP (wasmJs) | 浏览器端发送文件（`fetch` + `<input type=file>`），复用 `core` 的协议 DTO | 监听端口、UDP、TLS |

### 3.1 源码集策略

`core` 与 `app-shared` 的目标只有 Android 和 JVM，二者共享同一套 JVM API（`java.net`、`javax.net.ssl`、`java.security`）。因此业务代码放在自定义中间源集 `jvmSharedMain`：

```
core/src/commonMain       # 预留：纯 Kotlin 常量与接口（可为空）
core/src/jvmSharedMain    # 99% 实现
core/src/androidMain      # 预留 actual
core/src/jvmMain          # 预留 actual
```

`jvmSharedMain` 同时被 `androidMain` 与 `jvmMain` 依赖。若未来支持 iOS，必须先把 `jvmSharedMain` 中的平台调用下沉为 `expect/actual`——此约束写入评审清单。

### 3.2 依赖方向

`androidApp/desktopApp/engine-host → app-shared → core`。**禁止反向依赖**，`core` 不知道 `app-shared` 的存在。

---

## 4. LocalSend v2.2 实现与未来兼容层规范

当前 Sendee 之间可协商启用 `sha256-trailer-v1` 流式校验扩展，省去发送前预哈希，见 [ADR 0007](adr/0007-streaming-checksum-and-upload-scheduling.md)。这是迁移前实现；切换 SP/2 后移除这些私有扩展，本节只约束标准 LocalSend 兼容层，不约束 SP/2 的帧、版本或端口。

### 4.1 常量

| 名称 | 值 |
|---|---|
| 协议版本 | `2.2` |
| 默认端口（TCP + UDP） | `53318`（Boxlet 默认，刻意避开官方端口） |
| 官方 LocalSend 端口 | `53317`（`Protocol.LOCALSEND_PORT`，手动改为该值才与官方客户端互通） |
| IPv4 多播组 | `224.0.0.167` |
| IPv6 多播组（预留） | `ff12::fd3a:e420` |
| announce 突发延迟 | `100ms / 500ms / 2000ms` |
| 发现请求超时 | `500ms` |
| 单文件 422 重试上限 | `3` |
| 发送并发 | `2` |
| PIN 每 IP 失败上限 | `3` |

### 4.2 发现（UDP 多播，只发不问）

报文（camelCase，发送时额外带 `"announce": true`，接收方忽略未知字段）：

```json
{
  "alias": "Boxlet",
  "version": "2.2",
  "deviceModel": "Windows 11",
  "deviceType": "desktop",
  "fingerprint": "4BADDE...52FA",
  "port": 53318,
  "protocol": "https",
  "download": true,
  "announce": true
}
```

流程：

1. 启动时向每个符合条件的网络接口加入多播组并发送 3 次 announce。
2. 收到 announce 后**不要 UDP 回复**，改为向 `message.port` 发起 `POST /api/localsend/v2/register`；HTTPS 时把报文 `fingerprint` 作为 TLS 固定指纹（TOFU 例外：未见过该设备时可先不固定，注册成功后再固定）。
3. 只有 register 成功才进入设备列表；去重键为 `fingerprint`；同一设备多网卡/IP 合并为多个 channel。
4. 扫描动作额外执行 `/24` 子网回退扫描（并发 50），用于多播被禁的网络。
5. 自身报文按 `fingerprint` 丢弃（多播 loopback 会收到自己的包）。
   - 收到 `announce == true` 的公告 → 发起 `register`（HTTP）。
   - 收到 `announce == false` 的回退应答 → **不再回 register**，直接用报文内容把对方记进设备列表（对方已证明自己在这里）。
   - 自己的 `register` 失败（对端 HTTP 被防火墙挡住）→ 发一条 `announce == false` 的回退应答，让对方反过来连自己。
   - 见 [ADR 0002](adr/0002-protocol-conformance.md)。
6. UDP 多播 socket 与 TCP 服务端共用本机端口。端口不同（如 Boxlet `53318` 与官方 `53317`）的两端**互相不可见**，因为二者既收不到对方的多播包，`/24` 扫描也打到不同端口；与官方客户端互通须手动统一端口。

**当前里程碑只实现 IPv4**，IPv6 多播在代码中保留常量与接口，见 §11。

### 4.3 HTTPS 与证书

- RSA-2048 自签名证书，`CN=LocalSend User`，无 SAN，有效期 100 年。
- 指纹 = `SHA-256(certificate DER)` 的**大写十六进制**（64 字符）。
- 私钥/证书/指纹首次启动生成并持久化为 `security.json`；后续启动复用。
- 服务端：`wantClientAuth = true`（**可选**客户端证书，浏览器仍能打开 Web 页），信任管理器**接受任意自签名客户端证书**（不校链），从 TLS session 读取对端证书计算指纹。
- `fingerprint` 语义：**HTTPS 模式下报文里的 `fingerprint` 被忽略**（协议原文 "ignored in HTTPS mode"），设备身份一律取证书指纹；拿到证书后用证书值覆盖报文值，两者不一致**不构成拒绝理由**（见 [ADR 0002](adr/0002-protocol-conformance.md)）。HTTP 模式没有证书可依据，才使用报文里的随机串。
- 客户端：始终出示本机证书（官方 LocalSend 在非 Web 模式下强制 mTLS）；由于服务端可能发送空 CA 列表，客户端使用强制 alias 的 `X509ExtendedKeyManager`，确保 `wantClientAuth` 场景也会发送证书。TLS 握手阶段固定指纹；主机名验证关闭（对端以 IP 访问且证书无 SAN）。
- HTTP 模式（设置关闭加密）：`fingerprint` 使用随机 UUID 大写串，不做证书校验；仅建议可信网络使用。

### 4.4 HTTP API

统一响应错误体：`{"message":"..."}`。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/localsend/v2/register` | 设备注册；返回本机 info（不含 port/protocol） |
| GET/POST | `/api/localsend/v2/info` | 本机信息（兼容探测） |
| POST | `/api/localsend/v2/prepare-upload?pin=` | 传输请求 |
| POST | `/api/localsend/v2/upload?sessionId=&fileId=&token=` | 裸二进制上传 |
| POST | `/api/localsend/v2/cancel?sessionId=` | 取消（sessionId 可缺省） |
| POST | `/api/localsend/v2/prepare-download?pin=` | 浏览器分享：返回 `{info, sessionId, files}`；无活动分享时 403 `Rejected` |
| GET | `/api/localsend/v2/download?sessionId=&fileId=` | 分享文件流；无活动分享时 403 `Rejected` |

`prepare-download` / `download` 在主端口与独立的分享端口上都提供，读同一个分享会话与同一个 PIN：
主端口是给「按公告的 `port` 访问」的对端用的（官方客户端也走这里），分享端口是给浏览器打开的明文 URL。
| POST | `/api/localsend/v2/show?token=` | 单实例唤醒 |
| GET | `/` | Web 信息页（无文件分享） |
| GET | `/i18n.json` | Web 页文案（静态） |

DTO 字段名必须逐字一致：`alias / version / deviceModel / deviceType / fingerprint / port / protocol / download / sessionId / fileId / token / fileName / size / fileType / sha256 / preview / metadata.modified / metadata.accessed / info / files`。

状态码语义：

| 码 | 语义 | 客户端处理 |
|---|---|---|
| 200 | 成功 | — |
| 204 | 无文件需要（`files` 为空） | 直接结束 |
| 400 | 参数/JSON 错误 | 会话失败 |
| 401 | 需要 PIN / PIN 错误 | 重新输入 PIN |
| 403 | 被拒绝 / 令牌或 IP 不匹配 | 标记 declined |
| 409 | 已有其他会话 | 标记 recipientBusy |
| 422 | SHA-256 校验失败 | 重传，最多 3 次 |
| 429 | PIN 尝试过多 | 标记 tooManyAttempts |
| 500 | 服务端内部错误 | 会话失败 |

`fileType` 是 MIME 字符串；`sha256` 为可选字段；时间是 RFC 3339 字符串。

**跨域**：所有响应统一带 `Access-Control-Allow-Origin: *`，`OPTIONS` 预检直接返回 204，放行 `GET, POST` 与 `Content-Type, Content-Length`。这是给浏览器端（`webApp`）用的，见 [ADR 0004](adr/0004-browser-target.md)。不带凭据，所以用 `*`；上传仍需接收方在设备上确认。

### 4.4.1 浏览器分享（Download API）

- 分享服务是**独立的明文 HTTP** 服务，端口由系统分配，URL 形如 `http://<lan-ip>:<port>/`。主服务保持 HTTPS，见 [ADR 0003](adr/0003-download-api.md)。
- `GET /` 是分享页（文件列表 + 下载链接）；PIN 设置时先出 PIN 表单。
- `info` 中不含 `port` / `protocol`，文件项不含 `metadata`（与官方 §5.2 一致）。主端口返回同一份 JSON。
- `download` 字段随分享会话变化：有会话为 `true`，否则 `false`，切换时补发一次 announce。
- PIN 复用设置里的接收 PIN：`/`、`prepare-download`、`download` 都要带 `?pin=`，错误回 401。
- 未实现 `Range`，浏览器中断后需要整体重下。

### 4.5 会话与传输

- 接收端同一时刻只允许**一个**会话（Pending 或 Active），第二个返回 409。
- `prepare-upload` 决定接受哪些文件：响应 `files` 只包含被接受文件的 `fileId -> token`；全部拒绝返回 403 `Rejected`；没有文件（消息已被消费）返回 204。
- 超时对齐：接收端等待用户确认的上限是 `PREPARE_DECISION_TIMEOUT_MS`（60s），因此发送端 `prepare-upload` 的请求超时必须**大于**该值（实现为 60s + 15s）。两者取反会导致接收方还没点「接受」，发送方已 `timeout` 失败。
- 每个文件独立 token（UUID v4），上传时校验 sessionId + fileId + token + 发送方 IP。
- 上传超量必须失败；结束后 `set_len(written)` 截断旧内容；开启校验时边写边算 SHA-256，不匹配返回 422 并删除残留，允许同 token 重试 3 次。
- 取消：发送方 `POST /cancel?sessionId=`；接收方取消也调用该接口通知对端。服务端收到未知 session 的 cancel 时上抛 `CancelReceived(ip, sessionId)`，由 UI 校验 IP。
- 文本消息：单个 `text/*` 文件 + `preview` 内嵌正文；接收端识别为消息，不落盘，写历史。
- `metadata.modified` 落盘时还原（文件系统存储与 Android MediaStore；SAF provider 不保证支持）；`accessed` 不用。
- 接收文件夹时保留 `fileName` 里的相对目录；Android 未选 SAF 目录时，MediaStore 的 `RELATIVE_PATH` 也带上子目录。

### 4.6 文件名校验

- `fileName` 可含 `/` 表示目录层级；逐段清洗：去掉 `..`、`.`、空段、Windows 非法字符、保留设备名，限制长度。
- 重名自动追加 ` (n)`，保留扩展名。
- 任何情况下不得写出目标根目录。

---

## 5. 数据与持久化

全部使用 kotlinx.serialization JSON，Okio 写文件，原子替换（`.tmp` → rename）。

| 文件 | 内容 |
|---|---|
| `settings.json` | 设置项（见下） |
| `security.json` | 私钥 PEM、证书 PEM、指纹 |
| `history.json` | 接收历史（最多 500 条） |
| `favorites.json` | 收藏设备 |

设置项：`alias / deviceType / deviceModel / port / https / multicastGroup / discoveryTimeoutMs / networkWhitelist / networkBlacklist / quickSave / quickSaveFromFavorites / receivePin / saveToHistory / createChecksums / verifyChecksums / theme / locale / destination / showToken`。

`showToken` 是 `/show` 接口的随机令牌，首次启动生成并固定；不通过 UI 修改。

- Android 默认保存目录：`Context.getExternalFilesDir("received")`；用户可通过 SAF 选择持久化目录。
- 桌面默认保存目录：`<系统下载目录>/Boxlet`（可用设置覆盖）。下载目录按系统记录解析，不硬编码 `~/Downloads`：Windows 读 shell 的「下载」known folder（`FOLDERID_Downloads`，支持用户重定向到其它盘，如 `D:\Downloads`），Linux 读 `xdg-user-dirs` 的 `XDG_DOWNLOAD_DIR`，macOS 用 `~/Downloads`；解析失败才退回 `~/Downloads/Boxlet`。
- Android 设置文件位于 `filesDir`；桌面位于 `%APPDATA%/Boxlet`（Windows）、`~/.config/Boxlet`（Linux/macOS）。

---

## 6. UI 规范

### 6.1 Android（Compose M3）

- 结构：`Scaffold + NavigationBar`，四个目的地：发送 / 接收 / 历史 / 设置；接收请求用 `ModalBottomSheet` 或全屏对话框。
- 主题：`MaterialTheme`，默认启用 dynamicColor（Android 12+），支持浅色/深色/跟随系统；强调色可关闭。
- 组件：`ElevatedCard`、`ListItem`、`FilledTonalButton`、`OutlinedTextField`、`LinearProgressIndicator`、`SegmentedButton`。
- 交互：点击设备卡片 → 底部弹层（发送文件 / 发送文本）；返回键遵循系统行为；所有耗时操作有可见状态。
- 前台服务：应用启动即常驻（`foregroundServiceType="connectedDevice"`），保证接收端口不被随手回收；通知栏显示空闲端口 / 传输进度 / 等待确认，节流 500ms。Android 13+ 首次启动请求 `POST_NOTIFICATIONS`。
- 实时通知：Android 16+（API 36）用 Live Updates —— `Notification.ProgressStyle` + `setRequestPromotedOngoing(true)` + `setShortCriticalText("45%")`，传输中的进度通知会被系统提升为状态栏胶囊 / 锁屏卡片（即各家所谓「灵动岛」的标准实现）。只在真有进度时请求提升，空闲通知保持普通形态。具体行为：
  - 多文件：每段一个文件，宽度按字节占比；已完成段绿色、未完段灰，进度条用 `setProgress` 填充。
  - 等待对方接受（发送侧 `WAITING_FOR_ACCEPT`）：用 `setProgressIndeterminate(true)` 的不确定进度，胶囊显示「等待」，而不是无进度的普通通知。
  - 完成态：收到 `COMPLETED` 后胶囊显示「完成」并提升，停留 3 秒再退回空闲（完成键随 session 跟踪，避免进度条卡在 100%）。
  - 「等待确认接收」走独立高优先级通道 `boxlet-attention`（IMPORTANCE_HIGH，弹窗 + 锁屏），同一个请求只发一次，确认/拒绝后取消；常驻通知不再显示该请求，避免错过。
  - API < 36 回退为 `NotificationCompat` 的常驻进度通知（不确定进度用 `setProgress(0,0,true)`）；ColorOS / HyperOS 等厂商岛没有公开申请接口，只能依赖系统白名单。
- 图库保存：MediaStore 按 MIME 分流 —— 图片 →「图片/Boxlet」、视频 →「视频/Boxlet」、其它 →「下载/Boxlet」；选中 SAF 目录时仍以 SAF 为准。
- 分享接收：`ACTION_SEND` / `ACTION_SEND_MULTIPLE` 进入 `MainActivity`（`singleTask`，`onNewIntent` 同样处理），`EXTRA_STREAM` / `ClipData` / `data` 三种来源都认；弹窗选设备后按需把 URI 转成 `OutgoingFile`。纯文本分享不处理。
- 触控目标 ≥ 48dp；文本支持系统字号缩放；不使用仅靠颜色传达状态。
- 权限：`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`ACCESS_LOCAL_NETWORK`（Android 17+ 运行时权限预留）。

### 6.2 桌面（Fluent）

- 结构：`NavigationView(Left)` + Mica 背景，四项：发送 / 接收 / 历史 / 设置；底部显示版本与端口。
- 主题：`FluentTheme(colors = lightColors()/darkColors(accent))`，跟随系统深浅色；强调色 `#0078D4`。
- 组件：`NavigationView`、`AccentButton`、`Button`、`TextField`、`ListItem`、`InfoBar`、`ProgressBar`、`Card`、`ContentDialog`。
- 桌面交互：单击设备 → Fluent `ContentDialog`（发送文件 / 发送文本）、AWT 文件选择器、窗口最小尺寸 920×640。
- 系统托盘：常驻图标（代码绘制，无二进制资源），关闭窗口只收进托盘；托盘菜单提供「显示主窗口 / 退出」，双击图标唤起窗口。
- 单实例：启动时读 `settings.json` 的 `port` 与 `showToken`，向 `/api/localsend/v2/show` 发一次请求（HTTPS 用宽松信任，仅限 127.0.0.1）；成功则唤起已有实例后退出，避免两个进程抢端口。
- 拖拽发送：窗口挂 AWT `DropTarget`（`javaFileListFlavor`），文件夹按 `collectFolder` 展开保留相对路径；只有一台设备时直接发，否则弹设备选择框。
- 字体与间距遵循 Fluent：正文 14sp，标题 20/28，控件圆角 4dp。
- 折叠/窄窗口适配与拖拽发送列入 M2。

### 6.3 状态与错误

- 所有状态来自 `app-shared` 的单一 `UiState`，UI 不自行保存业务状态（仅保存局部输入/展开状态）。
- 错误分三类显示：字段级（输入框 supportingText）、页面级（InfoBar/Snackbar）、会话级（传输卡片内的错误文本）。
- 网络错误文案必须给出下一步动作（重试/检查防火墙/检查同一 Wi-Fi）。

---

## 7. 并发与线程模型

- 所有 IO 在 `Dispatchers.IO`；UI 只消费 `StateFlow`。
- 每个发送/接收会话拥有独立 `SupervisorJob`，取消会话即取消该 Job。
- 服务端每条连接一个协程；全局并发连接上限 64（进程内信号量）。
- 共享可变状态只用 `StateFlow.update` / `Mutex`，禁止裸 `var` 跨协程访问。
- 进度节流：UI 状态更新 ≥ 100ms 一次，避免高频重组。

---

## 8. 安全规范

1. 默认 `https = true`；关闭时必须二次确认。
2. 指纹固定不可关闭（除非用户显式关闭 https）。
3. 接收 PIN 每 IP 3 次失败锁定 60 秒；错误响应不泄露 PIN 是否正确之外的信息。
4. 路径穿越防护在 core 层实现，UI/存储层不得假设文件名安全。
5. 不记录完整文件内容、PIN、私钥；日志只记录 IP、别名、文件名、状态码。
6. Web 下载页使用可选客户端证书，浏览器可访问；页面不提供目录浏览，只能下载当前会话文件。

---

## 9. 桌面原生演进方案

核心诉求：桌面端将来可能用 WinUI 3 / Avalonia / Tauri 重写，网络能力不能绑死在 Compose 里。

已落实的设计：

1. `core` 不依赖 Compose，`app-shared` 只依赖协程与序列化；二者可在任何 JVM 桌面壳中直接调用。
2. `engine-host` 提供**无头引擎进程**：
   - 启动参数：`--data-dir <path>`；
   - 控制协议：stdin/stdout 换行分隔 JSON（JSON Lines），请求 `{"id":"1","command":"send.files","params":{...}}`，响应 `{"id":"1","ok":true,"result":{...}}`，事件 `{"event":"state","data":{...}}`；
   - 该进程独占端口与证书，UI 进程只做展示与文件选择。
3. 原生壳也可以直接实现 §4 协议，与 Boxlet / LocalSend 互通；`docs/ENGINE-IPC.md` 是唯一契约。

命令集（v1）：`status`、`devices.scan`、`send.files`、`send.text`、`receive.accept`、`receive.reject`、`transfer.pin`、`transfer.cancel`、`settings.get`、`settings.set`、`favorites.list`、`favorites.add`、`favorites.remove`、`history.list`、`history.clear`、`shutdown`。

选择该方案的原因：进程边界使原生 UI 与 JVM 引擎解耦，避免 JNI/COM 互操作；同时保留直接嵌入 core 的高性能选项。

---

## 10. 工程规范

- 包名：`dev.boxlet.core`、`dev.boxlet.shared`、`dev.boxlet.android`、`dev.boxlet.desktop`、`dev.boxlet.engine`。
- 文件命名：一个文件一个主类型；扩展名 `.kt`；测试 `*Test.kt`。
- 公开 API 必须有 KDoc；内部实现默认 `internal`。
- 禁止 `GlobalScope`；禁止在协程里吞异常（至少记录日志）。
- 提交前必须：`./gradlew :core:test :androidApp:assembleDebug :desktopApp:run` 至少编译通过。
- 构建命令：
  ```bash
  ./gradlew :desktopApp:run          # 桌面
  ./gradlew :androidApp:assembleDebug
  ./gradlew :engine-host:run --args="--data-dir ./build/engine"
  ```

---

## 11. 当前里程碑与已知限制

已实现（M1）：

- UDP 多播发现 + register 应答 + `/24` 扫描回退 + `announce = false` 回退应答（IPv4）。
- 自签名证书、指纹固定、可选客户端证书、HTTP/HTTPS。
- v2 register / info / prepare-upload / upload / cancel / show，含 204 / 422 / 429 语义。
- 发送文件 / 多文件 / 文件夹（保留相对路径）/ 文本消息、接收确认、单会话互斥、422 重试、取消。
- 历史与收藏、设置持久化、PIN。
- 浏览器分享（Download API）：主端口 + 独立明文端口 + 分享页 + PIN。
- SP/1 浏览器接入：顺序多文件上传、进程内断点续传、`device-event` 增量推送、长度校验后才 commit。
- Android 前台服务 + 通知进度；桌面系统托盘 + 单实例转发（`/show`）。
- Android 图库保存（MediaStore 图片 / 视频 / 下载分流）、系统分享 Intent 接收；桌面拖拽发送。
- Android M3 四页 UI；桌面 Fluent 四页 UI；无头 engine-host。

暂不实现（M2+）：

- IPv6 多播与 link-local scoped 地址。
- `Range` 请求：分享页与主端口都是整文件返回，大文件中途断开需整体重下。
- SP/1 的跨设备转发（`peerId`）、bridge → 浏览器的下行流、跨进程续传；v1 浏览器只能发给 bridge 所在设备。
- 桌面开机启动、快捷键。
- WebRTC。

### 11.1 已验证行为

- `:core:jvmTest`：证书指纹格式、register/info/upload、错误指纹握手失败、422 状态映射、双向 TLS 端到端 256 KiB 传输与 SHA-256 校验。
- `:androidApp:assembleDebug`、`:desktopApp:run`、`:engine-host:installDist` 均通过。
- 桌面端在真实局域网中通过 UDP 多播发现并成功注册了两台运行官方 LocalSend 的 Android 设备（HTTPS + 指纹校验）。
- Android 真机（OnePlus PLK110 / Android 17 / arm64）与桌面端在默认端口 `53318` 下双向多播发现：桌面看到「Rapid Puma · OnePlus PLK110」，真机看到「Frozen Marmot · Windows 11」。
- 桌面引擎 → 真机：2 MiB 文件 HTTPS 上传，接收端 `verifyChecksums` 通过，落盘 SHA-256 与源文件逐字节一致；真机历史记录写入正确路径。
- 真机 → 桌面引擎：SAF 选择 441 KiB 图片上传完成，接收端校验通过。
- 浏览器分享：`:core:jvmTest` 4 个用例（分享页、`prepare-download` 结构、二进制流、PIN 401）；`engine-host` 的 `share.start` 实测首页 200、`prepare-download` 返回的 `info` 不含 port/protocol、`download` 取回 2 MiB 文件 SHA-256 与源一致、`share.stop` 后端口关闭。
- 浏览器端（`webApp`）：Kotlin/Wasm 编译与 webpack 打包通过，无头 Edge 实测页面渲染正常；HTTP 模式目标返回 `Access-Control-Allow-Origin: *`，`OPTIONS` 预检 204。真实上传仍需在浏览器里手动点一次（自动化工具不在当前环境）。

> 端口策略：默认 `53318`，刻意与官方客户端 `53317` 错开，便于同机共存；需要与官方客户端互通时把双方端口统一为 `53317`，详见 [ADR 0001](adr/0001-boxlet-default-port.md)。

任何 M2 功能必须新增 ADR（`docs/adr/NNNN-*.md`）后再动手。
