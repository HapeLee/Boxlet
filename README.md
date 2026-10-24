# Boxlet

当前版本：**v1.0.0**。变更与验证边界见 [更新记录](CHANGELOG.md)。

局域网文件互传客户端。原生应用默认启用 **Boxlet Protocol 2（BP/2）**，同时兼容 LocalSend v2.2：统一发现附近设备，已配对 Boxlet 优先使用 BP/2，其他设备自动使用 LocalSend。

> Android、Compose 桌面与 WinUI 的“设置 → Boxlet Protocol 2”提供开关、状态、本机指纹复制、对端指纹登记/撤销及直接 IP 连接。双方须通过可信渠道核对完整指纹并分别添加对方；未配对的发现公告不会自动获得信任。已有显式关闭设置保持关闭。普通文件系统支持双方重启续传；Android 接收端 SAF/MediaStore 恢复与 WSS 待完成，浏览器仍使用 SP/1。见 [使用说明](docs/protocol/BP2-EXPERIMENTAL.md)、[BP/2 规范](docs/BOXLET-PROTOCOL-2.md) 和 [ADR 0008](docs/adr/0008-boxlet-primary-protocol.md)。

> 默认 LocalSend 兼容、自动路由及未来 Google Quick Share 的扩展边界见 [ADR 0009](docs/adr/0009-unified-device-discovery-and-routing.md)；Quick Share 尚处研究规划，不是已支持能力。

> 2026-10-25：**Boxlet Protocol 2（BP/2）** 已支持双向会话、分页清单、BATCH、流式校验、坏块修复、暂停、有界自动重连续传，以及源前缀变化后的显式逐文件重传。两个独立引擎进程通过 256 MiB 传输中分别重启接收端、发送端后的续传、最终 SHA-256 与文件/历史去重验证；双通道优先 BP/2，仅 LocalSend 设备自动兼容。见 [使用说明](docs/protocol/BP2-EXPERIMENTAL.md) 和 [实现记录](docs/protocol/IMPLEMENTATION.md)，原生数据链路统一使用 TCP/TLS，UDP 仅用于设备发现；尚无无线吞吐提速结论。

- **Android**：Jetpack Compose + Material 3，四页式移动端体验；沉浸式边到边 + 液态玻璃顶栏 / 悬浮底栏（`io.github.kyant0:backdrop`，Android 12 及以上）。
- **Windows 原生桌面**：WinUI 3 + Microsoft.UI.Reactor，系统 Mica、圆角、标题栏与窗口动画；复用 JVM 引擎。构建说明见 [`windowsApp/README.md`](windowsApp/README.md)。
- **跨平台桌面**：Compose Multiplatform + [compose-fluent-ui](https://github.com/compose-fluent/compose-fluent-ui)，保留 Linux/macOS 及现有 Windows 版本。
- **核心**：纯 Kotlin/JVM 的协议、证书、发现与传输引擎，不依赖 Compose，可被原生桌面壳复用。
- **无头模式**：`engine-host` 通过 JSON-lines 暴露完整引擎，供 WinUI / Avalonia / Tauri 等原生 UI 接入。
- **浏览器**：`webApp` 用 Kotlin/Wasm + Compose Multiplatform，通过原生节点上的 SP/1 bridge（WebSocket）接入局域网，无需安装即可发文件（受浏览器沙箱限制，只能发、不能收，见 [ADR 0005](docs/adr/0005-boxlet-protocol.md)）。

完整设计、协议细节、版本矩阵与限制见 [`docs/SPEC.md`](docs/SPEC.md)，引擎控制协议见 [`docs/ENGINE-IPC.md`](docs/ENGINE-IPC.md)。

LocalSend 兼容链路的代码边界、取消/连接复用与验证方式见 [实现说明](docs/protocol/LOCALSEND-IMPLEMENTATION.md)。

## 功能

- UDP 多播自动发现 + `/24` 子网扫描回退（IPv4）
- BP/2 始终使用双向 TLS 和流式完整性校验；LocalSend 兼容传输可配置 HTTP(S) 和 SHA-256
- LocalSend 快速发送可跳过预哈希，接收同意后直接上传；默认仍启用发送校验
- 发送文件、多文件、文件夹（保留相对目录结构）、文本消息；接收确认 / 拒收 / 取消
- LocalSend 接收 PIN、每 IP 失败限流（3 次 / 60 秒）；BP/2 通过双向指纹配对授权并保留接收确认
- SHA-256 生成与校验（校验失败自动重传，最多 3 次）
- 浏览器分享：对方无需装客户端，用浏览器打开链接即可下载（独立明文端口 + 可选 PIN）；主端口同时按协议提供 `prepare-download` / `download`
- SP/1 浏览器接入：一条 WebSocket 顺序发多文件、进程内断点续传、设备列表增量推送
- 接收历史、收藏设备、快速保存
- 桌面系统托盘（关闭窗口收进托盘）与单实例转发（再次启动会唤起已有窗口，不再抢端口）
- 桌面拖拽发送：文件或文件夹拖进窗口，只有一台设备时直接发，多台时弹选择框
- Android 前台服务 + 常驻通知（接收端口存活、传输进度、等待确认状态）；Android 16+ 用 Live Updates，传输中的进度通知可被系统提升到状态栏胶囊/锁屏
- Android 图库保存：图片 →「图片/Boxlet」、视频 →「视频/Boxlet」，其它仍进「下载/Boxlet」
- Android 分享接收：系统「分享」文件到 Boxlet 后选设备发送
- Android SAF 目录选择；桌面自定义保存目录
- 默认 BP/2 TCP `53318`，LocalSend HTTP(S) TCP `53317`；两套发现同时工作，兼容官方 UDP `53317` 发现；自定义端口保持配置值，发现不可用时报告受限原因

## 技术栈（2026-10 最新稳定版）

| 组件 | 版本 |
|---|---|
| Gradle / AGP | 9.6.1 / 9.4.0 |
| Kotlin | 2.4.21 |
| Compose Multiplatform | 1.12.2 |
| compose-fluent-ui | 0.1.0 |
| Android Compose BOM / Material 3 | 2026.09.00 / 1.5.0-beta01 |
| 液态玻璃（Android） | io.github.kyant0:backdrop 2.0.1 |
| Kotlin Coroutines / Serialization | 1.11.0 / 1.12.0 |
| OkHttp / Okio | 5.5.0 / 3.18.2 |
| BouncyCastle | 1.86 |
| compileSdk / 应用 minSdk | 37 / 31（核心库 24） |

## 目录结构

```
core/         协议 DTO、证书、UDP 发现、HTTP(S) 服务端/客户端、传输状态机、存储接口
app-shared/   设置/历史/收藏持久化、引擎装配、UiState、业务动作
androidApp/   Compose Material 3 UI、SAF/MediaStore 存储、文件选择
desktopApp/   Compose Fluent UI、AWT 文件选择、桌面存储
windowsApp/   C# / Reactor / WinUI 3 原生 Windows 客户端、引擎 IPC
engine-host/  无头引擎 + JSON-lines 控制协议
webApp/       Kotlin/Wasm + Compose Web，浏览器端 SP/1 客户端（复用 core 的协议与 SP DTO）
docs/         规范与协议文档
```

依赖方向：`androidApp / desktopApp / engine-host → app-shared → core`，`webApp → core(commonMain)`，禁止反向依赖。

## 构建与运行

环境：JDK 21、Android SDK 37（`local.properties` 中配置 `sdk.dir`）。

```bash
# 桌面端（Windows / Linux / macOS）
./gradlew :desktopApp:run

# Android Debug APK
./gradlew :androidApp:assembleDebug
# 产物：androidApp/build/outputs/apk/debug/androidApp-debug.apk

# 核心协议与端到端传输测试
./gradlew :core:jvmTest

# 无头引擎（stdin/stdout JSON lines）
./gradlew :engine-host:installDist
./engine-host/build/install/engine-host/bin/engine-host --data-dir ./boxlet-data

# 浏览器端（Kotlin/Wasm + Compose）
# 首次打包会下载 Node / Yarn / Binaryen 工具链，约 1~2 分钟
./gradlew :webApp:wasmJsBrowserDevelopmentWebpack
# 产物：webApp/build/kotlin-webpack/wasmJs/developmentExecutable/
python -m http.server 8080 --directory webApp/build/kotlin-webpack/wasmJs/developmentExecutable
# 然后在桌面端「设置 → 浏览器接入」开启接入，把显示的 ws:// 地址
# 填进页面的「Bridge 地址」；设了接收 PIN 时，页面的配对码也填同一个值。
```

Windows 下把 `./gradlew` 换成 `gradlew.bat`。

Windows 原生客户端使用 PowerShell 执行 `.\windowsApp\run.ps1`；需要 .NET 10 SDK 和 JDK 21，首次构建会从 NuGet 下载 Windows App SDK / Reactor。使用前从托盘退出旧桌面端。

Windows 分发包：`.\windowsApp\publish.ps1` 生成免安装的便携 ZIP，`.\windowsApp\pack.ps1` 生成带卸载程序的安装包（需先 `winget install --id JRSoftware.InnoSetup -e`），详见 [`windowsApp/README.md`](windowsApp/README.md#分发)。

> 首次在 Windows 启动桌面端时，系统可能弹出防火墙授权。请允许「专用网络」访问，否则其他设备无法发现本机。Android 端请在系统设置中允许本地网络访问，并尽量关闭电池优化。

### 无头引擎快速验证

```bash
printf '{"id":"1","command":"status"}\n{"id":"2","command":"shutdown"}\n' \
  | ./engine-host/build/install/engine-host/bin/engine-host --data-dir ./boxlet-data
```

输出包含 `{"event":"state",...}` 事件与 `{"id":"1","ok":true,...}` 响应。

## 版本号与发布

版本号只有一个来源：git tag `vMAJOR.MINOR.PATCH`。数值按十进制拼接
（`major*10_000 + minor*100 + patch`，`v1.0.0` → `10000`、`v1.2.3` → `10203`），
Android `versionCode`、Windows `FileVersion`、安装包版本、桌面 `packageVersion` 和界面显示的
版本全部由它推导；不在 tag 上的构建带 `-dev.N+gHASH` 后缀。详见
[`docs/RELEASING.md`](docs/RELEASING.md)。

```bash
./gradlew -q printBoxletVersion                                  # 查看当前统一版本号
git tag -a v1.0.0 -m "Boxlet 1.0.0" && git push origin v1.0.0    # 打 tag 即触发发布流水线
```

流水线见 [`.github/workflows/release.yml`](.github/workflows/release.yml)：四端并行构建 →
GitHub Release → Telegram 通知；需要的 Actions 密钥与产物清单同样在
[`docs/RELEASING.md`](docs/RELEASING.md)。

## 互通说明

- 已配对的 BP/2 设备边传边校验，正常首次发送无需预读整个文件；标准 LocalSend 兼容通道在启用发送校验时预哈希，开启“LocalSend 快速发送”则省略整文件摘要。旧私有尾摘要扩展仅在关闭 BP/2 且显式协商时保留，见 [LocalSend 实现记录](docs/protocol/LOCALSEND-IMPLEMENTATION.md)。
- 默认 BP/2 TCP/UDP 端口 `53318`，LocalSend TCP/UDP `53317`；LocalSend 使用多播组 `224.0.0.167`，BP/2 使用独立发现公告。自定义端口与冲突处理见 [使用说明](docs/protocol/BP2-EXPERIMENTAL.md)。
- 浏览器分享走**独立的明文 HTTP 端口**（系统分配，URL 由应用直接给出）：主服务是 HTTPS + 自签名证书，浏览器无法访问，所以 Download API 必须另开明文端口（见 [ADR 0003](docs/adr/0003-download-api.md)）。
- 身份使用 RSA-2048 自签名证书（CN=`LocalSend User`），配对指纹为证书 DER 的 SHA-256 十六进制。
- BP/2 强制双向 TLS，并验证双方已登记的完整指纹。LocalSend 默认开启 HTTPS，其服务端使用可选客户端证书，客户端出示本机证书。
- LocalSend 关闭 HTTPS 后使用明文 HTTP，仅建议可信网络；该开关不影响 BP/2。
- 分享会话期间公告的 `download` 为 `true`，此时主端口的 `prepare-download` / `download` 与分享端口读同一份数据；没有会话时按协议回 403。
- 接收文件默认保存到应用目录；Android 可在设置中选择 SAF 目录，桌面默认 `~/Downloads/Boxlet`。

## 已验证

- v1.0.0 本轮：141 项 JVM/Wasm/共享层测试通过（含原有吞吐门槛），Android Debug/Release、Compose 桌面编译、浏览器生产包、引擎构建通过；Windows 两进程互传、双方分别重启续传、显式逐文件重传、最终 SHA-256 与文件/历史去重通过。协议黄金向量和 Android 设计检查通过。
- 以下为此前版本的界面与真机验证记录；不能替代 v1.0.0 的 Android/Windows 无线与后台场景验收。
- `:core:jvmTest` 通过：证书指纹、register/info/upload、错误指纹拒绝、422 映射、HTTPS 双向 TLS 与 HTTP 端到端 256 KiB 传输 + SHA-256 校验；分享服务 4 个用例（分享页、`prepare-download` 响应、二进制流、PIN 拒绝）；主端口 Download API 4 个用例（`prepare-download`、二进制流、错误 PIN/未知会话、无会话 403）；SP/1 断线重连续传用例（半截文件不发布、`resumeFrom` 续写后内容一致）。
- `:androidApp:assembleDebug` 构建通过。
- `:desktopApp:run` 冒烟启动通过；托盘创建无异常；第二个实例在 7 秒内通过 `/show` 唤起已有窗口后退出，窗口数保持 1。
- 真机（SDK 37）：`am start -a SEND` 冷启动与运行中 `onNewIntent` 都能弹出「发送分享的 N 个文件」，文件名按 `DISPLAY_NAME` 解析；`POST_NOTIFICATIONS` 首次启动正常请求。
- `:engine-host` 状态事件、设置读取、退出指令通过；`share.start` 起明文分享服务，`download` 取回的 2 MiB 文件 SHA-256 与源一致。
- `:webApp:wasmJsBrowserDevelopmentWebpack` 打包通过，无头 Edge 实测页面正常渲染；目标端 `/api/localsend/v2/info` 返回 `Access-Control-Allow-Origin: *`，浏览器可直接跨域读取。
- Android 真机（OnePlus PLK110 / Android 17）与桌面端在默认端口 `53318` 下双向多播发现，并完成双向 HTTPS 传输：桌面 → 真机 2 MiB 文件（落盘 SHA-256 与源一致），真机 → 桌面 441 KiB 图片（SAF 选择）。

## 已知限制

- 仅 IPv4 发现；IPv6 多播常量已预留但未使用。
- 浏览器分享页与主端口的 `download` 都不支持 `Range`，中断后需整体重下。
- 浏览器（SP/1）v1 只能发给 bridge 所在设备，不能接收，也不会被自动发现；跨设备转发经评估暂不做（方案留档在 [ADR 0006](docs/adr/0006-sp1-cross-device-relay.md)）；浏览器下载未实现。
- SP/1 断点续传只在 bridge 进程存活期间有效（半截文件保留 10 分钟）；原生 BP/2 的普通文件系统任务已支持双方重启恢复，详见 [使用说明](docs/protocol/BP2-EXPERIMENTAL.md)。
- Android 已用前台服务 + 常驻通知保活，但厂商省电策略仍可能杀掉进程，建议关闭电池优化。
- 桌面已实现系统托盘、单实例转发与拖拽发送；开机启动、快捷键仍未实现。
- LocalSend 兼容层无断点续传、暂停与 WebRTC。

## License

MIT
