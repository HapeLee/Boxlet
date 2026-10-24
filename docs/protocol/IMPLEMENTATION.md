# BP/2 实现记录

更新：2026-10-25。本文记录代码事实，目标线格式仍以 [BP/2](../BOXLET-PROTOCOL-2.md) 为准。

原生应用与 engine-host 默认使用标准 LocalSend，BP/2 默认关闭；旧默认配置会一次迁移，保留自定义端口与接收偏好。BP/2 的实现和测试保留为内部实验，显式启用后经 TLS/HELLO 验证选择其通道。浏览器仍使用 SP/1。三端不提供 BP/2 开关、端口、状态或指纹配对入口，见 [使用说明](BP2-EXPERIMENTAL.md)。这次切换仅覆盖原生默认配置，不表示全部规范或真机验收完成。

协议已统一更名为 **Boxlet Protocol 2（BP/2）**；代码、文档、发现标识、magic、摘要域、设置与 IPC 一起更新，不保留未发布 SP/2 线格式。仓库所在磁盘目录无需因此改名，旧 LocalSend 扩展的 `sendeeCapabilities/sendeeChecksum` 仍保留原历史字段名。

## 已实现

| 范围 | 代码 | 验证 |
|---|---|---|
| 公共 wire 模型、大小/身份/清单校验 | `core/.../protocol/bp2/Bp2.kt`、`Digests.kt` | Long 数值、十进制溢出、5 GiB offset、路径与分块顺序 |
| 32 字节帧、DATA、BATCH、TCP 增量/WSS 消息边界 | `Frames.kt` | Python 黄金向量；拆包、粘包、逐字节输入、恶意长度、序号、EOF、部分摘要损坏 |
| 严格控制 JSON | `Control.kt` | UTF-8、嵌套重复 key、深度、无效数字与 trailing token |
| manifestHash、contentRoot、prefixRoot | `Digests.kt` | Okio SHA-256；JVM/Wasm 与独立 Python 空文件/块边界/Unicode 向量一致 |
| 原生 TLS 帧通道 | `core/.../transport/bp2/Bp2TlsTransport.kt` | mTLS、服务端 pin、客户端证书要求、加密套件限制、有界 writer、错误身份拒绝、关闭阻塞连接 |
| 双向会话、确认与完成 | `core/.../session/bp2/Bp2Session.kt` | HELLO/ACK、分页 OFFER/ACCEPT、选择接收、拒绝/取消、等待确认期间反向发送、同一会话复用、接收提交后才确认完成 |
| 流式校验与局部修复 | `Bp2FileTransfer.kt`、`Bp2Session.kt` | 源只打开一次、先校验再写入、contentRoot 通过才 commit；坏块 NACK、后续正确块暂存、从发送窗口修复而不重读源 |
| 小文件与大文件调度 | `Bp2Session.kt` | BATCH 最多 8 项、两个大文件交错、协商流数上限；301 项分页清单、混合空文件/小文件/大文件 |
| 累计信用与背压 | `Credit.kt`、`Bp2Session.kt` | 重复 WINDOW_UPDATE 不增信用、重传单独计费、保留 1 MiB 修复空间；传输跨越默认 8 MiB 窗口 |
| 原生发现与证书一致性 | `Bp2DiscoveryService.kt`、`Bp2EngineAdapter.kt` | IPv4 独立 UDP 公告；探测合法公告，TLS/HELLO 验证后加入设备列表；限制同时进行的探测握手，IPC 可按已知端点直连 |
| 实际任务路由 | `DeviceDirectory.kt`、`TransferEngine.kt` | 双通道优先 BP/2、LocalSend-only 自动兼容、BP/2 证书验证失败不偷偷降级；任务固定 adapter 与端点 |
| 现有接收界面、存储与历史 | `Bp2EngineAdapter.kt`、`BoxletApp.kt` | 共用接收确认/选择/取消；逐文件 O(1) 状态更新、100ms 快照；文本记录已校验正文；完成记录真实 adapterId |
| 默认 LocalSend 兼容发现 | `DiscoveryService.kt`、`TransferEngine.kt` | 官方 UDP 53317 与公告 TCP 端口分离；启用 BP/2 后默认 LocalSend TCP 53317、BP/2 TCP 53318 |
| 标准 LocalSend 报文 | `TransferEngine.kt` | 产品默认不公告/协商 ADR 0007 私有摘要尾部，即使旧对端宣告支持也走标准格式；仅底层显式实验标记可启用旧扩展 |
| 独立就绪状态 | `EngineState.protocols` → `UiState.protocols` → IPC | discovery/send/receive 分别报告；端口绑定失败不标记适配器 ready |
| 任务暂停与继续 | `Bp2Session.kt`、`Bp2EngineAdapter.kt`、各端传输卡片、IPC | 双方均可暂停/继续、重复暂停幂等、暂停时反向传输可用、暂停时取消唤醒发送调度；继续使用原连接/原 source |
| 磁盘检查点与源前缀验证组件 | `Bp2CheckpointFile.kt` | 数据/块摘要先 sync 再发布元数据；仅保存 token 摘要并绑定身份/任务/清单/文件与有效期；磁盘 hash journal、独占写锁、恢复验证/截断尾部、源前缀比较；子进程 halt 后恢复 |
| 原生任务续传与持久化结果 | `Bp2ResumeStore.kt`、`Bp2Session.kt`、`FileStorage.kt` | 分页清单/选择持久化、RESUME/READY、认证后重绑流、断线保留检查点；跳过已发布文件，重查完成结果不重复落盘；历史按身份/任务/文件去重 |
| 自动重连与手动恢复 | `Bp2EngineAdapter.kt` | 冻结原端点与协议，三次退避重连；耗尽后保留为 PAUSED，可用原“继续传输”按钮恢复；证书/源前缀/凭证错误不降级重发 |
| 发送端重启恢复 | `Bp2SendJournal.kt`、`Bp2EngineAdapter.kt`、`AndroidSourceResolver.kt` | AES-256-GCM + 身份 RSA-OAEP 封装；持久化冻结任务与显式 source 引用；启动 PAUSED、显式继续；已交付源缺失可跳过，未完成源缺失保留凭证；换身份/篡改/双写拒绝 |
| 显式逐文件重置 | `Bp2Session.kt`、`Bp2ResumeLease.resetFile`、引擎/IPC/各端传输卡片 | 源前缀变化保留 PAUSED；显式 reset-file/file-reset、旧状态绑定、READY 前限制；只清零未发布文件，其他文件进度保留；清零发布后残留旧尾部及确认后断线恢复 |
| 保留任务清理与约束 | `Bp2ResumeStore.kt` | 24 小时有效期；撤销和过期先禁用凭证再清理私有文件；128 个未完成保留任务、256 GiB 预约上限、每任务 64 MiB 元数据限制；已发布目标不被清理 |

标准库测试向量生成器同时生成 `bp2-vectors.json` 和 commonTest 的 Kotlin fixture。公共测试运行在 JVM 与真正的 Wasm/Node 环境。

Windows IPC 冒烟测试会启动两个独立引擎进程，在临时数据目录、不登记配对的情况下连接、发送中文文件名/小文件/空文件；另进行两轮 256 MiB 传输，分别中途停止接收进程与发送进程，使用原身份、数据目录和接收端点重启后恢复任务，核对最终 SHA-256、没有重复文件及历史；发送端重启先停在 PAUSED，显式继续后完成。再进行一轮 256 MiB 发送端重启后修改源前缀的测试，确认任务先暂停，显式逐文件重置后 SHA-256 与当前源一致，文件/历史仍无重复。最后关闭进程；整个过程不登记配对。测试同时覆盖原有分享、SP/1 bridge 与并发 UTF-8 IPC。它证明本机跨进程与双方分别重启恢复可用，不代表 Android ↔ Windows 无线吞吐、整机断电或官方 LocalSend 客户端真机验收。

## 剩余工作与当前边界

- 原生普通文件系统的双方重启续传已接入实际任务。发送端需显式可重开 source 引用；临时授权/无引用 source 保持原进程内恢复能力。Android 持久读取授权文档/目录的 source resolver 已接入并编译通过，授权撤销、provider 离线与真机进程恢复仍待设备验收。
- 仅在私有检查点目录与目标目录不重叠、且文件系统支持同卷硬链接原子发布时启用接收续传并公告 `resume`；跨卷或不支持的文件系统继续使用现有接收路径。Android SAF/MediaStore 不公告接收持久化续传，不默认增加中转拷贝。文本或包含文本的任务目前不签发续传凭证。
- WSS 载体、浏览器可信证书、发送/条件接收与文件权限恢复。现有 WSS 消息 codec 不等于浏览器 BP/2 已上线。
- 已移除单独的图形配对、指纹名单与相关 IPC，接收权限使用现有确认/收藏/快速保存规则；不再规划另一套扫码/短码配对。发现仍须经 TLS/HELLO 验证，证书身份继续用于收藏匹配与续传绑定。
- IPv6 原生发现、真实 Android/Windows 局域网与后台场景验证。
- 大清单的磁盘索引、慢磁盘/低内存压力验证。清单分页传送，但最多 100000 项的元数据仍保存在内存，尚非常数内存清单。
- 空目录传输、逐文件取消/暂停等最终规范要求。当前会话只接受文件和文本；任务级暂停已可用。源前缀不一致保留暂停并等待用户选择恢复原内容或仅重传该文件，已实现前缀变化后的显式 `reset-file`；文件大小变化、清单修改及传输中逐文件重置尚不支持。
- 完成结果可在重连中查询，已完成文件不会重发；跨进程历史通过稳定 completionId 去重。目前没有独立的历史 outbox 管理界面；磁盘/provider 及断电持久性仍需进一步验证。
- 完整 UI 统一目录模型与适配器独立开关。实际发送由 TransferRouter 选择，但现有 Device 模型仍承担应用快照，尚未全部替换为 PeerRecord。
- 原生默认已切回标准 LocalSend，三端暂不提供 BP/2 设置。BP/2 与旧摘要扩展保留为内部测试代码，旧扩展必须单独显式开启，关闭 BP/2 不会自动启用。浏览器迁移尚未完成。
- Quick Share 仍仅研究规划，不在当前可用协议列表。

启用 BP/2 时，默认旧接收端口 53318 会让给 BP/2，LocalSend HTTP(S) 监听改为 53317；自定义端口保持配置值，冲突由 `protocols[]` 报告。LocalSend 端口配置为 0 的隔离测试模式不公告 BP/2。修改 BP/2 设置会重启引擎并中断当前连接；已持久化的发送任务在重新启用 BP/2 后恢复为暂停。

## 可重复验证

```powershell
python scripts/protocol_bp2_vectors.py --check
.\gradlew.bat :core:jvmTest :core:wasmJsNodeTest
.\gradlew.bat :androidApp:compileDebugKotlin :desktopApp:compileKotlin :webApp:compileKotlinWasmJs :engine-host:installDist
.\windowsApp\run.ps1 -BuildOnly -Test
```

2026-10-24 原生载体实验已撤回：同一 Windows/JVM 回环、相同 BP/2 校验和持久化工作下，64 MiB 文件中位吞吐 TCP/TLS 为 315.03 MiB/s，候选 QUIC 实现为 78.59 MiB/s，小文件接近。该次测量仅作为本项目选型记录，不代表无线环境或其他实现；按用户要求已移除候选载体及其额外依赖，统一维护 TCP/TLS。

检查点使用应用私有目录与磁盘摘要日志，验证过程每次最多读取一个块；不会把整个文件或全部块摘要装入内存。元数据保留两个已发布代次，未发布尾部在恢复时截断；最终文件用硬链接排他发布，之后解除临时链接，不额外复制整文件。清单/选择仍有上限地驻留内存，不能据此宣称全部元数据为常数内存。为避免同时保留大量 source 句柄，恢复时在 READY 前验证前缀，并在真正发送所用的源句柄上再次验证；正常首次发送仍只打开/读取一次源。

发送端恢复记录在私有 `bp2-send/` 目录内，以本机 TLS 身份的 RSA-OAEP（SHA-256/MGF1-SHA-256）封装随机 AES-256-GCM 密钥；凭证、清单、端点和 source 引用均被认证加密，不写入明文日志/IPC，不保存源字节。记录先 sync 再发布代次，清除发布空记录后删除旧代次；损坏时隔离，不自动回退重放。独占文件锁禁止两进程同时写同一目录。每应用保留一个发送任务，24 小时到期；恢复启动不读源、不自动连接、不把记录中的端点直接加入发现。显式继续仍须重新认证对端、查询完成结果并验证未完成源的保留前缀。暂时失去源/权限或已保留前缀变化时保留暂停；前缀变化允许明确选择逐文件从头重传，源大小变化仍需新任务。该密钥来自应用身份，并非独立的操作系统密钥库。


保留通用的已授权恢复接管：先核对原身份、任务、清单、凭证和有效期，才能关闭对应旧活动会话，等旧写入者退出后重新获取排他租约。错误凭证不能驱逐旧会话。发送记录中的未知旧载体标记会被拒绝并隔离，不隐式解释成 TCP 任务。

当前默认迁移：`Settings.bp2Enabled` 默认 false，应用默认 LocalSend 端口 53317。SettingsRepository 将缺少内部迁移标记的旧配置一次切回 LocalSend，旧默认端口 53318 改为 53317，其他自定义端口、接收/收藏与校验偏好保留。迁移完成后显式开启的开发实验不在每次启动时重置。三端已移除 BP/2 设置区，WinUI 启动冲突检查按迁移后的有效端口执行。

小文件调度补充：同组最多 8 项的完成请求合并为一条 `files-finish`，无需逐文件等待一轮往返；回复可以乱序，必须逐文件收到成功才报告完成。空文件也参加分组完成，但不生成空 DATA/BATCH。组内发布 I/O 失败会逐项返回 `COMMIT_FAILED`，其余成功文件正常报告，发送任务终止；后续任务仍可复用连接。测试覆盖 17 项以 8/8/1 完成、部分选择、乱序回复、实际接收端首项落盘失败及全空文件；每文件落盘、摘要和发布语义不变。这是控制消息次数优化，不构成相对 LocalSend 或无线吞吐提升的测量结论。

发送流水线补充：块 ACK 与最终发布确认在受流数限制的待完成工作中等待，其他流继续发送，不再由某个文件的确认延迟暂停整个发送调度。等待 `files-done` 仍占用总流名额及大文件名额，超过 1 MiB 的活跃文件最多两个；大文件源句柄读完即关闭。结构化协程随任务一起退出，取消会终止所有待完成工作，不留下每文件常驻任务。测试用对端故意扣留 ACK/完成回复，验证其他文件可以推进、对端只允许两个流时仍受限、允许八流时仍只保留两个大文件、取消退出与源句柄关闭。

小文件调度会为尚未启动的非 BATCH 数据预留流名额，防止慢确认占满全部流后，新小批次不断抢占释放的名额，让大文件长时间等待。重连拿到完整、已验证的恢复快照后，先同步远端已完成结果，再验证未完成源的前缀；即使源变化导致 READY 前暂停，已发布文件也保持完成状态，不依赖先前 `files-done` 是否送达。

控制写入补充：DATA 的窗口归还与 ACK/NACK、同一 BATCH 的各文件 ACK/NACK 与一次窗口归还共用一次有界 writer flush，线格式不变。填补缺口时合并多个累计窗口更新为最后的累计值，不增加信用。每组最多 64 帧和 256 KiB；无效/超限组在入队前拒绝，不发送部分控制或消耗序号。断线测试按小文件已真实发布后再断线；续传流量只计算重连段，避免把先前窗口内尚未处理的 TLS 字节误当成整文件重发。

## 同存储协议对照测量

手动运行 `.\gradlew.bat :core:bp2Benchmark`，报告输出到 `core/build/reports/bp2-protocols.json`。测量使用同一 JVM 的两个真实 TLS 引擎、同一组源、相同接收 sink、逐文件 `fd.sync` 和原子改名，BP/2 与标准 LocalSend v2.2 适配器交替测量各三次，每种工作负载分别预热。兼容通道不协商旧私有扩展。计时覆盖发送动作到完成结果，包含预哈希、握手、协议准备和发布；独立整文件 SHA-256 校验在计时后执行。

报告包括大文件、小文件和混合任务的全部样本、中位数、范围、源打开次数及读取字节数，并标记明显波动。正常首次 BP/2 每源打开/读取一次，开启 SHA-256 的标准 LocalSend 兼容路径预哈希加发送共两次，测量时逐项断言这些计数。两侧都不启用续传日志和历史，以保持存储工作一致；这与默认持久化接收路径存在差异。该测量比较本项目的两个适配器，不是官方客户端、无线网络或 Android 实机结果。当前回环测量仍显示小文件路径有待优化，不能据此宣称 BP/2 所有工作负载更快，也不能将波动样本当作稳定提速比例。

样本之间停止引擎并等待其协程退出，拒绝让旧连接与下一轮重叠。测量期间发现并修复 LocalSend HTTP 服务停止时仅关闭监听、已接收连接仍等待读超时的问题；现在停止服务同时关闭活跃及等待 handler 名额的连接，完成回调也覆盖协程尚未启动即取消的情况。测试覆盖 keep-alive 空闲连接和超过 64 个名额的未完成 TLS 握手。性能测量与 Android/桌面编译分步执行，避免构建争用污染回归下限；原有吞吐阈值保持不变。

`core:jvmTest` 仍强制执行原有性能门槛：先依赖 `core:throughputTest`，每个吞吐测试类在新的 JVM 中完成自身预热与测量，再运行功能测试，隔离其他 TLS/会话用例对堆、JIT 和共享线程池的影响。性能结果位于 `core/build/test-results/throughputTest`，不能仅统计 `jvmTest` 目录就漏掉这部分结果。
