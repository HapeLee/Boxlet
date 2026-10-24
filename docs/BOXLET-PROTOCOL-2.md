# Boxlet Protocol 2（BP/2）

- 版本：2.0，2026-10-23
- 状态：内部实验，产品默认关闭且不提供相关设置；实现已支持普通文件系统双方重启续传。Android 接收端持久化续传、WSS 与完整真机验收待完成，见 [实现记录](protocol/IMPLEMENTATION.md)。
- 本文是 BP/2 的线格式与行为契约；产品规范见 [SPEC](SPEC.md)，决策与迁移见 [ADR 0008](adr/0008-boxlet-primary-protocol.md)。
- “必须 / 不得”为实现要求，“建议”为可经测量调整的策略。本文不声称 BP/2 已完成迁移或实测提速。

2026-10-24 命名统一：正式名称为 **Boxlet Protocol**，当前主版本简称 **BP/2**，替代开发期的 Sendee Protocol / SP/2。未发布的旧线格式不保留回退：magic 为 `BXP2`、发现标识为 `boxlet`、适配器为 `boxlet-bp2`、摘要域为 `boxlet-*-v2`；公共代码类型与设置/IPC 前缀统一为 `Bp2` / `bp2`。旧浏览器 SP/1 与 LocalSend 私有扩展字段仅作为历史实现保留。

## 1. 协议定位

BP/2 的内部实验在启用后优先使用其原生通道；浏览器同协议的 WebSocket 载体仍为目标设计。当前产品统一使用标准 LocalSend v2.2，默认关闭 BP/2，三端不提供协议切换入口。统一发现与路由契约见 [ADR 0009](adr/0009-unified-device-discovery-and-routing.md)。

BP/2 必须支持：双向长连接、分页文件清单、小文件批量传输、多文件交错发送、流式 SHA-256、局部重试、接收端背压、暂停与重连续传。不得要求在正常首次发送前预读整个文件，不生成中间 ZIP，不使用 base64 传文件。

本版不要求：公网打洞、第三方中继、浏览器之间直连、单个文件同时使用多条网络连接、内容去重、自动压缩。原生数据传输统一使用 TCP/TLS，UDP 仅用于设备发现。TCP 丢包仍会影响同一连接的所有流，应用层多路复用不消除这一限制。

## 2. 层次与端口

| 层 | 原生 ↔ 原生 | 浏览器 ↔ 原生 |
|---|---|---|
| 发现 | Boxlet UDP 公告；允许手动地址/二维码 | 扫码或输入地址，由已连接原生端提供设备列表 |
| 连接 | TCP + TLS，默认 TCP 53318 | WSS，默认 TCP 53321，路径 `/boxlet/v2` |
| 协议选择 | TLS 后第一个 BP/2 帧；支持 ALPN 时使用 `boxlet/2` | 必须协商 WebSocket 子协议 `boxlet.v2` |
| 应用层 | 相同的 BP/2 帧与状态机 | 相同的 BP/2 帧与状态机 |

端口是应用默认值，非 IANA 注册服务；必须可配置，并在发现信息/邀请中携带实际端口。原生端不得把任意 TLS 数据当 HTTP 或 LocalSend 请求猜测解析。

一条连接可双向发文件，建议复用 60 秒空闲后关闭。控制帧优先于新数据帧；数据帧不得在 socket 上与其他帧的字节交错。实现必须使用单个有界 writer 队列。

## 3. 设备身份、TLS 与浏览器授权

### 3.1 原生设备

- 使用平台 TLS 实现，优先 TLS 1.3；为项目现有 Android minSdk 保留 TLS 1.2 + ECDHE + AEAD。禁用 TLS 1.0/1.1、静态 RSA 密钥交换和自定义应用层加密。
- 原生双方均出示持久化设备证书。`deviceId` 是证书 DER 的 SHA-256，小写 64 位十六进制；HELLO 中的 ID 必须与实际 TLS 证书一致。
- 不要求手动登记指纹：公告中的证书指纹必须与实际 TLS 证书、HELLO 一致，才向应用报告 BP/2 端点；新任务的发送连接继续固定所选端点的证书。该过程提供加密和连接身份一致性，不提供首次连接的带外身份保证。
- 接收权限统一由现有接收确认、仅收藏设备自动接收和快速保存规则决定。允许传送清单以展示接收请求；获准后才可接收文件数据、创建目标与签发续传凭证。收藏关联证书身份，新身份不能因别名相同继承收藏设备的自动接收权限。
- 不提供单独的指纹配对、设备信任名单或扫码/短码配对流程。证书变化形成新身份，不继承旧收藏的自动接收权限或旧任务续传权限。
- 关闭 TLS 0-RTT 应用数据，防止 OFFER/提交类操作重放。续传始终绑定原设备证书、任务清单和秘密凭证，允许新任务不授予接管已有任务的权限。

### 3.2 浏览器

- 浏览器接入默认关闭；开启后由原生端生成至少 256 位随机邀请 token，5 分钟有效、单次使用，通过二维码/本机 UI 交给浏览器。
- WSS 必须先通过浏览器的证书验证。现有无 SAN 的自签名证书和原生指纹固定不能直接等价为浏览器信任；提供可受信证书/受信本地证书环境是浏览器 BP/2 的上线门槛，不得静默降级为明文 WS。
- Token 不放 URL query，不写日志；可放邀请 URL fragment，由受信页面取出后清除，用 HELLO 的 `inviteToken` 发送。首次交换后原生端签发独立的 256 位 `browserToken`，后续 HELLO 用它认证；只存原生端 token 摘要。
- 原生端必须校验 WSS `Origin` 为配置的精确允许源；拒绝任意源、`null` 源和未授权 token。浏览器 ID 是原生端分配的随机 128 位 ID，不以自报 ID 作为认证依据。
- 浏览器不能监听连接。接收能力取决于实际可用的流式文件写入 API：HELLO 不支持 `receive` 时原生端不得向它发送 OFFER。禁止把整个大文件攒进 Blob 来伪装流式接收。
- 原生端持有的浏览器授权可撤销；浏览器身份、授权和续传记录互相绑定。浏览器刷新后丢失文件访问权限时，须重新选择同一源文件才能续传。

## 4. 发现与协议选择

Boxlet 发现使用 UDP 53318，IPv4 组播 `239.255.83.68`，IPv6 链路组播 `ff12::5344`（支持时）。均为本项目私有约定。公告不超过 1400 字节、间隔 5 秒加随机抖动、TTL/hop limit 为 1。设备 20 秒未更新标为离线、90 秒移除。网络切换后重新公告。

```json
{"protocol":"boxlet","major":2,"deviceId":"<64位证书指纹>","alias":"我的电脑","deviceType":"desktop","tlsPort":53318,"wssPort":53321}
```

`wssPort` 未开启时省略；收到公告后以报文源地址构造连接地址，不信任报文自带 host。过滤超长/非法公告、自己的指纹与明显重复公告，限制后续握手频率。局域网多播不可用时允许手动地址或受限子网探测，但探测成功仍需 TLS 身份校验。

协议选择规则：

1. 默认同时启动 BP/2 与 LocalSend 适配器，结果进入统一的“附近设备”列表；用户可以在设置中关闭 LocalSend 兼容，日常发送无需选择协议模式。
2. LocalSend 独立在官方默认 UDP/TCP 53317 发现和监听，沿用其标准组播规则；BP/2 使用本节的独立公告。端口占用只使对应适配器降为受限状态，不中断另一个协议，也不能假装兼容接收已启动。
3. 完整证书指纹经实际 TLS 握手验证一致时，可合并同一设备的两个通道并优先 BP/2；不以别名、IP、未验证的公告指纹或外部协议的短 ID 合并信任。方向授权仍分别保存。
4. 在创建任务前选择目标可用的协议：有可用 BP/2 通道优先 BP/2；仅有 LocalSend 通道时自动 LocalSend。任务创建后固定 adapterId 与 endpoint，PIN/确认/进度/取消均交给同一适配器。
5. 自动选择 LocalSend 设备不等于失败后降级。BP/2 的认证失败、身份变化、用户拒绝、版本不匹配或进行中的传输中断，不得偷偷切到 LocalSend 重发；同一任务不跨协议拼接续传。该限制不要求用户为正常 LocalSend 发送额外开关或确认。

## 5. 通用编码与帧

所有整数按网络字节序编码。JSON 为严格 UTF-8，不允许重复 key、NaN、Infinity。JSON 中的文件大小、偏移、字节数等 64 位量必须使用无符号十进制**字符串**，不得转成 JavaScript/Kotlin `Int`。本版大小与偏移最大值为 `2^63-1`，加法必须检查溢出。计数、32 位 streamId 与枚举版本使用 JSON 数字。

### 5.1 固定 32 字节帧头

| 偏移 | 字节数 | 字段 | 规则 |
|---|---|---|---|
| 0 | 4 | magic | ASCII `BXP2`，hex `42 58 50 32` |
| 4 | 1 | kind | 1 CONTROL，2 DATA，3 BATCH |
| 5 | 1 | flags | 2.0 必须为 0 |
| 6 | 2 | headerLength | 2.0 固定为 32 |
| 8 | 4 | streamId | CONTROL/BATCH 为 0；DATA 为已接受文件的流 ID |
| 12 | 4 | payloadLength | 不包含帧头；必须在分配内存前验证 |
| 16 | 8 | offset | DATA 为文件字节偏移；其他为 0 |
| 24 | 8 | sequence | 每个连接方向从 1 连续递增，计入全部帧 |

本版接收硬上限：CONTROL 256 KiB，DATA 为 1 MiB + 32 字节，BATCH 为 1 MiB。未知 kind、非零 flags、错误头长、序号不连续或越界长度均发 `PROTOCOL_ERROR` 并关闭连接。sequence 到 `2^63-1` 前重建连接，不允许回绕。

TCP 接收端必须处理拆包、粘包和 EOF 半帧。WSS 上每条 binary message 恰好携带一个 BP/2 完整帧，不发 text 控制消息；RFC 6455 分片可重组，但必须在组装过程中限制总长度。空消息、长度不符、超限消息均拒绝。

### 5.2 CONTROL

payload 是含 `type` 的 JSON 对象。会产生响应的命令携带随机 128 位 `requestId`；响应携带相同 `replyTo`。未知可选字段忽略；未知命令返回 `UNSUPPORTED_MESSAGE`，不得当作成功。重复 requestId 在同一会话必须幂等，不能重复创建文件或重复完成通知。

2.0 不使用隐式“上一条流”的数据归属。streamId 由文件发送端分配：连接发起方用奇数，接受方用偶数，从 1/2 开始，单个连接内不复用。重新连接时重新绑定；transferId/fileId 保持不变。

### 5.3 DATA

payload 是 `chunkSha256[32] || fileBytes`，offset 是该块起始偏移。块大小固定 1 MiB，offset 必须是块大小的整数倍，末块可短；所有非末块必须恰好 1 MiB。摘要仅为当前块原始文件字节的 SHA-256，不含帧头。空文件没有 DATA。

发送端只缓冲当前有界分块，边读边计算；无需预读整个文件。接收端必须在块校验通过后写入文件或放入有界乱序队列。超出声明文件大小、未 ACCEPT 的 streamId、偏移错位均为流错误。

### 5.4 BATCH：小文件合包

最多 8 个记录，必须属于已 ACCEPT 的文件，每条记录是一个完整、非空、不超过 64 KiB 的文件；不允许在 BATCH 中传大文件片段。记录开始和结束之间各占一个活跃流名额，整个批次也必须遵守对端 maxActiveStreams，不能绕过活跃流上限。

payload：`count:u16 || reserved:u16=0 || records...`。

每个 record：`streamId:u32 || offset:u64=0 || length:u32 || sha256[32] || bytes[length]`，固定描述符 48 字节。count 必须 1..8，每个记录必须与对应清单大小一致，总 payload 不超过 1 MiB，记录结束后不得有多余字节。

接收端按记录分别验证与确认；某条记录校验失败只重试该文件，不重传已确认记录。单个记录可用 DATA 重试。空文件用 FILE_FINISH 完成，不制造空块。BATCH 不要求落一个中间归档，仍按原目录结构写入各文件。

## 6. 建连与能力

连接发起方发送 HELLO，接受方回复 HELLO_ACK；双方完成认证前不得 OFFER。

```json
{"type":"hello","requestId":"<32hex>","major":2,"minor":0,"deviceId":"<身份>","alias":"手机","deviceType":"mobile","features":["send","receive","batch","resume"],"limits":{"maxActiveStreams":8,"receiveWindow":"8388608","maxManifestEntries":100000}}
```

HELLO_ACK 返回选定的 major/minor、已认证身份、接受方自己的 features/limits、随机 connectionId。首次邀请浏览器在 HELLO 中省略 deviceId，使用 inviteToken，待 HELLO_ACK 分配浏览器 ID；后续 browserToken 必须绑定这个 ID。major 不同拒绝；minor 取双方较小值。`batch`、`resume` 均为 BP/2 原生实现必备；浏览器可不声明 `receive`，不具备可恢复存储时可不声明 `resume`。

原生 HELLO/HELLO_ACK 可携带 `listenPort`（1–65535），用于认证后登记对端 TCP 监听端点；主机地址取实际 TLS 连接，不能直接信任公告地址。该字段可省略，省略时本次会话仍可用，但不能据此建立新的反向监听端点。当前实验实现声明 `send/receive/batch/pause`；实际存储支持持久化接收与原子发布时才附加 `resume`。具体任务是否可恢复以 ACCEPT_END 是否签发 resumeToken 为准；不支持的 provider、文本任务不签发凭证。这仍不替代完整发布验收。

这些能力描述用于资源和产品功能协商，不是旧版设备回退机制。帧格式、块大小、SHA-256 校验在 2.0 内固定。小版本只能新增可忽略的可选消息/字段；改变帧布局、摘要算法或必备语义必须升级主版本。

HELLO、认证各限时 10 秒；闲置 15 秒发送 PING，30 秒未收到任何对端帧视为断线。正在等待用户确认时仍须处理心跳；确认默认 60 秒超时，可明确回复 `PENDING` 延长但不能无限隐式占用资源。

## 7. 清单、接收确认与状态机

transferId/fileId/requestId 均为 CSPRNG 生成的 128 位值，JSON 表示为小写 32hex；每个 fileId 在一个 transferId 内唯一。传输包含文件、空目录与文本消息：

1. `offer-begin`：transferId、entryCount、totalBytes（字符串），允许附带最多 4 KiB 的整体说明。
2. `offer-page`：transferId、从 0 连续的 pageIndex、entries（最多 256 项，且整个 CONTROL 不超过 256 KiB）。
3. entry：`fileId / kind / path / size / mime / modified / preview / streamId`。kind 为 `file|directory|text`；发送方为 file/text 分配本连接唯一的 streamId，directory 大小为 0 且不绑定流；text 大小不超过 64 KiB，内容仍走 DATA/BATCH，preview 最多 4 KiB，不能代替实际内容。
4. `offer-end`：transferId、manifestHash；接收端验证完整清单、配额、路径与哈希后，才展示确认或执行已授权 quickSave。
5. `accept-page`：transferId、pageIndex、accepted（最多 256 项的 `fileId/streamId` 映射，原样确认发送方的流 ID，directory 不带流 ID）；不接受项须带理由。接收端必须拒绝 streamId 重复/奇偶错误或清单外 ID。
6. `accept-end`：transferId、decisionId。所有 ACCEPT 页在此之前只有暂定效力，发送方收到此消息后才可发送数据。全拒绝用 REJECT，不创建目标文件。
7. DATA/BATCH、ACK/NACK、WINDOW_UPDATE：流式发送、验证和调度。
8. `files-finish`：transferId、items（最多 64 项的 fileId、size、contentRoot）。接收端全部块已验证且连续写完，再验证 root、关闭临时文件、发布最终目标。
9. `files-done`：对应每项明确成功或失败。发送端必须收到成功才把该文件标为完成；不得把 socket 已写出或 ACK 当作最终完成。

10. `transfer-finish` / `transfer-done`：汇总已完成、已拒绝、失败项，结果为 `completed|partial|failed|cancelled`。清单全部成功才为 completed；有成功且有拒绝/失败为 partial，无成功且失败为 failed，显式取消为 cancelled。全拒绝在 REJECT 阶段结束。目录只在所有相关路径验证后创建；文本成功表示交付消息层，不默认保存成文件。

同一小文件调度组（最多 8 个文件，可包含空文件）应合并发送 `files-finish`，避免每个文件单独等待一轮完成往返。发送前登记整组待完成项，接收 `files-done` 时按 fileId 匹配，不能依赖结果顺序；分组不会合并或放宽每文件的校验、持久化与发布成功条件。组内发布 I/O 失败应逐文件返回 `failed/COMMIT_FAILED`，发送端仍记录其余成功文件，随后终止失败任务。
正常路径：

```text
DISCONNECTED → TLS/WSS → HELLO/AUTH → READY
READY → OFFERING → WAITING_ACCEPT → TRANSFERRING → VERIFYING → COMMITTING → COMPLETED
                                       ↕ PAUSED
TRANSFERRING → DISCONNECTED → RECONNECT/AUTH → RESUME → TRANSFERRING
任何非终态 → CANCELLED / FAILED
```

同一连接可有多个 transfer，但共享连接级资源上限。每个方向默认最多 1 个发送任务，其他任务排队；接收确认与反向发送不得阻塞网络读取循环。

## 8. 规范化清单与文件摘要

### 8.1 manifestHash

按清单页顺序、每页 entry 顺序连接以下二进制编码，再计算 SHA-256：

`ASCII("boxlet-manifest-v2\0") || entries...`

每个 entry：`kind:u8 || fileId[16] || size:u64 || S(path) || S(mime) || S(modified) || S(preview)`。

kind 的 file/directory/text 分别为 1/2/3；`S(s) = UTF8字节数:u32 || UTF8(s)`，缺省字符串为空。modified 使用 UTC RFC3339、固定毫秒格式 `YYYY-MM-DDTHH:mm:ss.SSSZ` 或空。字符串不做隐式 Unicode 归一化；文件名冲突由接收端单独处理。manifestHash 在 OFFER_END 前一遍增量计算，不依赖 JSON key 顺序或空白。

### 8.2 contentRoot

文件摘要使用有序块摘要，避免为了普通整文件 SHA-256 而重读续传前缀：

`SHA256(ASCII("boxlet-file-v2\0") || fileSize:u64 || chunkSize:u32 || chunkHash[0] || ... || chunkHash[n-1])`

chunkSize 固定 1048576，n=`ceil(fileSize/chunkSize)`，空文件 n=0。摘要按块序号排列，不按实际到达顺序。contentRoot 不等于普通文件 SHA-256，界面和导出不得把它标成“文件 SHA-256”。所有 JSON 摘要为小写 64hex，DATA/BATCH 内为原始 32 字节。

块摘要提供局部校验，contentRoot 绑定块顺序、总长度与完成声明，manifestHash 绑定文件身份和路径。TLS 提供传输认证；摘要不能替代 TLS。

## 9. ACK、背压、乱序与调度

### 9.1 接收窗口

- 连接每个方向独立记账。初始接收窗口由该方向接收方 HELLO 声明，默认 8 MiB，可选 2..32 MiB；所有流共享，不是每个文件各占一份窗口。
- DATA 按 fileBytes 长度扣信用；BATCH 按其全部文件字节之和扣信用。帧头/摘要另有硬长度、队列与帧率上限。发送端在发帧前必须拥有整个帧所需的数据信用。
- `window-update` 的 `creditTotal` 是从本次连接建立起累计允许发送的数据字节数（十进制字符串），初值为 receiveWindow，只能递增。发送端累计发出的数据（包括重试）不得超过它。累计值消除重复控制消息导致重复加信用的问题。
- 正常数据在验证并写入连续输出后才归还信用；验证失败的数据被丢弃后也归还对应信用，让重试能继续。仍留在内存等待缺口的块不归还信用。读取 socket/浏览器 bufferedAmount 降低不代表接收端已处理。
- 不得仅因磁盘变慢而追加信用。窗口限制覆盖接收队列、乱序块和在途数据；解析缓冲/存储缓冲单独有界，连接数据内存预算上限为窗口加固定 8 MiB，另加有上限的元数据页。
- 为避免缺块重试死锁，新数据帧不得把当前剩余信用降至 1 MiB 以下；这 1 MiB 留给重试，重试帧可以使用。存在 NACK 时先处理重试，再安排新数据。正常 ACK/窗口汇总必须在等待修复缺块时继续运行。

### 9.2 确认与局部重试

ACK 对一个 stream 给出 `verifiedRanges`（块序号的半开区间）、`writtenOffset`，可选 `durableOffset`，全部可重复累计报告。ranges 必须合并、最多 64 个，额外部分分消息发送。

- verified：摘要已通过，接收端仍持有数据或已经写入；它不是崩溃恢复承诺。
- written：已按序交给输出文件，不一定可在断电后恢复。
- durable：已完成第 10 节的持久化检查点，才可用于跨进程恢复。

接收端建议每处理 1 MiB 或 10 ms（任一先到）汇总 ACK/窗口，文件结束与暂停立即汇总。发送端不逐块等待 ACK，可以用满窗口；完成、窗口与磁盘背压相互独立。

NACK：`streamId / chunkIndex / reason`，摘要错误用 `CHECKSUM_MISMATCH`。发送端优先重发缺块，每块最多发送 3 次（含首次），超过后该文件失败；不得悄悄关闭校验。

缺块后的正确块可以保留在共享窗口内的乱序队列；顺序输出（含不可 seek 的 SAF）等缺块修复后连续写出。不得在缺块处无限缓冲，必须保留至少一个 1 MiB 块的调度机会给重试；必要时停止该文件后续帧。已验证重复块可丢弃并重发 ACK，每次实际接收帧消耗的信用各归还一次；不能按 chunk 去重而漏还重传帧信用，也不能因重复 ACK 多还信用。错误偏移/冲突重复数据应终止对应流。

### 9.3 默认调度

- 每连接最多 8 个活跃文件流，其中大于 1 MiB 的最多 2 个；其他按需等待，不为每个文件创建常驻协程。
- 不超过 64 KiB 的文件走 BATCH，最多 8 项、最多等待 2 ms 即发送；不得为了凑满批次无限等待。
- 其他文件按块加权轮转：控制/重试优先；每调度 4 个小文件块后至少给可发送的大文件 1 块，防止大量小文件饿死大文件。
- 等待某组文件的块 ACK 或 `files-done` 时，其他可发送流继续调度；等待发布的文件仍占用对端流名额，大于 1 MiB 的文件也继续占用两个大文件名额之一，直到最终结果到达。待完成工作数量受上述流上限约束，源句柄在读完后即可关闭。
- 网络写入只有一个 writer；磁盘读取与块哈希 worker 默认最多 2 个，共享有界缓冲池，不复制整个文件、不创建无界发送任务。
- 同一 DATA/BATCH 产生的相邻 ACK/NACK 和累计窗口更新可以共用一次 writer flush，仍保留独立帧与连续 sequence；不得等待后续 DATA 才发送确认或归还信用。一次控制写入组最多 64 帧、帧头与 payload 合计不超过 256 KiB，发送前完成整组校验。
- 分别记录网络吞吐、写入吞吐和排队时长。活跃流数可在 1..8 调整，但不能突破对端 limits 和 2 个大文件上限；窗口扩大只由接收端在内存上限内决定。

浏览器额外限制 WebSocket `bufferedAmount`（建议 2 MiB），同时遵守协议信用。使用标准 WebSocket API 的 bufferedAmount + 可取消的调度轮询，不依赖不存在的低水位事件接口。后续以 Worker 减少 UI/后台定时器影响，不能宣称所有浏览器后台都保持前台吞吐。

## 10. 暂停、续传与源文件一致性

### 10.1 暂停和取消

`pause` 按 transferId 或 fileId 停止安排新 DATA，允许已发送的有界在途块到达；收到 `paused` 后保持心跳、临时文件和续传凭证。`continue` 恢复，不重新 OFFER。

`pause`、`continue` 与响应均携带 transferId，响应分别为 `paused`、`continued`，通过 `replyTo` 对应原 requestId。暂停是对应任务的共享状态，任一端均可发起暂停或继续；幂等重复请求仍须确认。暂停不撤回已发送帧，不重置信用，块修复、ACK/窗口、PING、取消与反向任务继续处理。支持此操作的端点声明 `pause` 能力；当前实现支持任务级操作，逐文件范围仍待实现。

`cancel` 必须幂等，终止对应范围并删除尚未发布的临时文件/凭证；已经完成的文件保留。取消一个文件不取消其他文件。`cancelled` 后迟到的数据不得重新创建临时文件。

### 10.2 续传凭证和检查点

支持恢复的任务在 ACCEPT_END 中返回随机 256 位 resumeToken，绑定已认证原生证书身份或 browserToken、transferId、manifestHash。接收端磁盘只存 token 摘要，不把文件路径当授权。发送端如需跨重启恢复，必须加密保存原始凭证、冻结的身份/端点/清单与显式可重开 source 引用；source 引用与平台授权仅用于本机恢复，不进入线格式。默认 24 小时有效，配额清理、取消或撤销授权后失效；完成后只可查询同一结果，不再授权写入新内容，结果保留至记录过期。

断线期间的取消使用 `cancel-resume`，携带 transferId、manifestHash、resumeToken；接收端先核对 TLS 身份与凭证，再撤销保留任务并回复 `cancelled`。重复取消可以幂等确认。接收端本机取消不需要通过网络取回明文 token。清理不得删除已发布的最终目标。

接收端建议累计新增 8 MiB 或 1 秒时做一次检查点：先 flush/force 临时文件，再原子持久化按序块摘要及 durableOffset；顺序不可颠倒。只在平台确实支持持久化临时输出与重新打开时声明 durableOffset。启动恢复必须检查元数据/临时文件是否匹配，异常时从更早的有效检查点或 0 开始，不依据临时文件长度直接推断可续偏移。

不可恢复的文件 provider 仍可支持同进程重连（writtenOffset），但不得宣称跨重启续传。不得为了支持所有 SAF provider 而默认增加一次全文件中间拷贝；需要中转时显式告知存储开销。

### 10.3 重连流程

1. 重建 TLS/WSS 并认证原身份；新连接的 sequence、窗口从头开始，旧连接必须失效，不能两条连接同时写一个 transfer。
2. `resume` 携带 transferId、manifestHash、resumeToken。接收端校验授权和配额后用 `resume-page` 分页返回 fileId、已完成状态、可恢复的连续 offset、prefixRoot，最后发 `resume-end`。每页最多 256 项且不超过 CONTROL 上限。

   线字段为 `resume-page.items[] = {fileId,offset,prefixRoot?,completedRoot?}`。待恢复项带 prefixRoot，已发布项带 completedRoot 且 offset 等于文件大小；已发布项不重开源文件或重复发布。resume-page/resume-end 的 replyTo 对应原 resume.requestId。READY 使用 `resume-ready-page.items[] = {fileId,streamId,offset,prefixRoot?}`，最后 `resume-ready-end`；ACCEPT 的 replyTo 对应这个 READY_END 的 requestId。页索引从 0 连续，不能混入原选择以外的文件或复用旧连接流 ID。
3. offset 必须按块对齐，只有整个文件已验证完成时可以等于非对齐 fileSize。同进程可用仍保有的 writtenOffset；重启后只用 durableOffset。
4. prefixRoot=`SHA256(ASCII("boxlet-prefix-v2\0") || fileSize:u64 || chunkSize:u32 || retainedChunkCount:u64 || retainedChunkHashes...)`。恢复偏移 0 时也按此式计算空前缀。
5. 发送端默认重读**已保留前缀**、重新计算块摘要并比较 prefixRoot；不能只凭大小、修改时间或首尾一块判断文件没变。只有持有真实不可变快照时才能省略该步骤。它只发生在续传，不增加正常首次发送的预读。
6. 校验一致后发送端重新分配本连接 streamId，通过 `resume-ready-page`（每页最多 256 项的 fileId、streamId、offset、prefixRoot）和 `resume-ready-end` 提交；接收端返回 ACCEPT_PAGE/ACCEPT_END 原样确认，之后只发送剩余块。信用使用新连接已有的 WINDOW_UPDATE 记账，不因 resume 重复增加。前缀不一致时进入 PAUSED，保留凭证与其他文件进度；用户可恢复原内容后继续，或明确选择使用当前内容从头重传该文件。只有声明 `reset-file` 能力的对端支持后者：发送 `reset-file` 请求接收端清空对应临时文件，得到 `file-reset` 确认后绑定新流从 0 发；不得混合新旧内容。
7. 最终 contentRoot 仍包含完整文件所有块。源文件读取长度不符、检测到源被改写时报告 `SOURCE_CHANGED`，停止该文件，不能把混合内容标作成功。平台无法提供文件快照时，应用不承诺一次发送对应某个瞬间的磁盘快照。

`reset-file` 只能在认证并完成 RESUME/RESUME_END 后、第一条 READY_PAGE 之前执行，携带 `transferId / fileId / offset / prefixRoot`；offset 与 prefixRoot 必须匹配接收端刚返回的状态。它只作用于原选择中的未发布文件，不能修改清单、文件大小、目标或已发布文件（包含已有发布意图的文件）。接收端先持久化 offset=0、verifiedRoot=null 的新元数据代次，再截断数据与块摘要日志并 sync，完成后用 `file-reset` 回复相同 transferId/fileId、offset="0" 和空前缀根；replyTo 必须对应 reset-file.requestId。随后 READY 使用新状态与本连接的新流 ID。已有清零状态的重连可直接续传；重置确认丢失不允许恢复到旧前缀。

源大小变化、读取长度异常或冻结清单需要变化时创建新任务，不能以 reset-file 绕过原 ACCEPT 范围。重启恢复不保留用户尚未确认完成的重置意图，仍需先继续校验再明确选择；已完成清零则由接收端持久状态证明。

对断线自动重连最多 3 次，退避 0.5/1/2 秒并加抖动；之后进入可手动恢复的 PAUSED，不删除已保留进度。身份验证失败不自动重试或降低安全级别。

## 11. 文件系统与资源约束

- 相对 path 使用 `/`，UTF-8 总长最多 4096 字节、每段最多 255 字节；禁止绝对路径、盘符、空段、`.`、`..`、NUL、反斜杠和符号链接条目。平台非法名称需映射并报告；目录穿越不得用“自动修正后继续”掩盖。
- 打开输出时再次确认实际目标位于授权目录内，不能只检查字符串前缀；防止目录中已有 symlink/reparse point 把写入导向目录外。
- 同名/大小写折叠/平台名称归一化冲突，接收端统一分配最终名称并返回映射；不得无提示覆盖已有文件。
- 输出先写 `.boxlet-part` 或平台 pending 项，验证/关闭后发布。目录缓存以 transfer 为范围，避免每个文件重复查找 SAF 父目录；元数据和 provider 操作必须有并发限制。
- 磁盘空间、总字节数、清单项数、待确认任务数在 ACCEPT 前检查并保留配额，传输期间继续检查；默认每 transfer 最多 100000 项。大清单分页入索引/临时库，不强制全量常驻 UI 状态。
- 进度按 fileId O(1) 更新，增量维护总字节与剩余文件数；UI 合并到 100 ms 更新，不在每个数据块重建整个文件列表。提交/失败/取消事件立即发布。
- FILE_DONE 表示平台 commit 成功、最终目标可用。不得用普通 ACK 承诺断电持久性；平台无法保证原子发布时必须记录并暴露失败结果。

## 12. 错误与幂等

ERROR 包含 `code / scope(connection|transfer|file) / transferId? / fileId? / requestId? / message / retryable`，不得泄露本机绝对路径、token 或证书私钥。

| code | 行为 |
|---|---|
| UNSUPPORTED_VERSION / PROTOCOL_ERROR | 关闭连接，不降级 |
| AUTH_REQUIRED / AUTH_FAILED / TRUST_CHANGED | 停止传输，要求身份/授权处理 |
| REJECTED / BUSY / QUOTA_EXCEEDED / NO_SPACE | 清楚报告，保留与删除策略按是否已有有效检查点决定 |
| INVALID_PATH / INVALID_MANIFEST / INVALID_STREAM | 拒绝对应清单或流，绝不写清单外路径 |
| CHECKSUM_MISMATCH | NACK，最多 3 次；超过 FILE_FAILED |
| SOURCE_CHANGED / RESUME_EXPIRED / RESUME_MISMATCH | 暂停或显式重置对应文件，不拼接不一致内容 |
| IO_ERROR / COMMIT_FAILED | 不发送 FILE_DONE 成功；保留可恢复临时文件或按平台清理 |
| CANCELLED | 幂等确认，不重建被取消输出 |

完成记录必须按 peerIdentity + transferId + fileId + contentRoot 幂等保留至续传记录过期；FILE_DONE 丢失后重发 FILE_FINISH 返回同一结果，不能再次落盘、改名或追加历史。TRANSFER_DONE 丢失后重连查询结果，不盲目重传已完成文件。

## 13. LocalSend 兼容层

LocalSend 使用原始 `/api/localsend/v2/*`、v2.2 DTO、官方发现组与端口，保留标准 SHA-256 预哈希、PIN、接收确认及取消行为。它不支持 BP/2 帧、BATCH、contentRoot 或 BP/2 续传。

兼容层不得公告或发送 `sendeeCapabilities`、`sendeeChecksum` 等项目私有字段，不拼接 ADR 0007 的摘要尾部。浏览器 Download API 可以保留原行为，但不能作为 BP/2 能力的证明。

兼容适配器默认启用，可独立关闭；监听端口、发现服务与帧编解码独立，发现结果和任务进入统一设备目录与路由器。UI 共用任务/文件状态、存储与历史服务，不因协议切换页面。协议专属 PIN/配对不能互相替代。BP/2 业务代码不得通过 `LocalSendHandler` 或 LocalSend DTO 定义自身契约。

## 14. 验收基线

设计阶段固定向量见 [`protocol/bp2-vectors.json`](protocol/bp2-vectors.json)，覆盖 CONTROL、DATA、BATCH、空文件、块边界、多块 contentRoot/prefixRoot 和带 Unicode 的规范化清单。生成器为 [`scripts/protocol_bp2_vectors.py`](../scripts/protocol_bp2_vectors.py)，`--check` 校验文件可复现。这些是线格式设计夹具，不代表任何生产 codec 或跨端实现已经通过验收。

发布 BP/2 前必须满足：

1. 帧黄金向量跨 JVM/Android/Wasm 解码一致；拆包、粘包、边界长度、序号、恶意长度和 WSS 分片均覆盖。
2. 同一组文件在 TLS 与 WSS 路径得到相同 manifestHash/contentRoot，包含空文件、Unicode、>2 GiB、混合目录、大清单与同名冲突。
3. BATCH 部分成功/失败、丢失完成回复、乱序重复 ACK、丢失检查点、源文件前缀变化、授权撤销、浏览器刷新、平台不支持 seek 均有明确测试。
4. 大于窗口的数据持续发送时内存不随文件大小增长；磁盘阻塞不会造成无界队列；缺块重试不会耗尽信用而死锁；暂停/取消不阻塞反向控制。
5. 对 1 GiB 大文件、10000 × 4 KiB 小文件、100 × 8 MiB 混合文件分别测准备时间、首个文件完成时间、总耗时、吞吐、CPU、峰值内存、协议开销与重连恢复字节数。
6. 前后对比使用同设备/网络/存储/加密/校验条件，区分热缓存与冷缓存，Android 使用 release/profileable 构建；没有真机数据前不承诺速度倍数。
7. 默认同时发现和接收 BP/2/LocalSend，选中仅支持 LocalSend 的设备能直接发送；双协议设备可验证去重并优先 BP/2；端口冲突、禁用适配器、认证失败和跨协议重试不串用身份、帧或续传状态。

## 15. 标准参考

本文的 BP/2 帧、端口与摘要树是 Boxlet 自有设计；TLS 与 WebSocket 使用标准实现：

- [TLS 1.3 / RFC 9846](https://www.rfc-editor.org/info/rfc9846/)：加密传输与握手，不自造密码协议。
- [WebSocket / RFC 6455](https://www.rfc-editor.org/info/rfc6455/)：浏览器载体、Origin 与帧处理；不得混同 WebSocket message 与 TCP read 边界。
- [HTTP/2 / RFC 9113](https://www.rfc-editor.org/info/rfc9113/)：多路复用/流控的参考，不作为 BP/2 的线格式，也不声称应用层分流消除 TCP 队头阻塞。
