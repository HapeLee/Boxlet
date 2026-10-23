# ADR 0006：SP/1 跨设备转发

- 状态：搁置（设计保留，暂不实施）
- 日期：2026-10-21

> 2026-10-21 讨论结论：该能力的实际收益有限——大多数场景下浏览器用户就是把文件交给
> 眼前这台设备；为它引入"双跳带宽、目标端来源显示错位、转发期间不能续传"这些代价不划算。
> 方案留档，等真的出现"浏览器必须发给第三方"的需求再实现。
- 相关：[ADR 0005](0005-boxlet-protocol.md)、[ADR 0002](0002-protocol-conformance.md)

## 背景

SP/1 的 `offer` 里已经留了 `peerId` 字段（"目标节点 id：为空表示发给 bridge 自己"），
但 `BridgeServer.handleOffer` 从来没读过它：浏览器只能把文件发给 bridge 所在的那台设备。
`PeerDevice.id` 就是该设备的 LocalSend 指纹（`TransferEngine.startBridge` 里
`id = device.fingerprint`），所以"发给谁"在数据上是清楚的，缺的是送达通道。

要让浏览器把文件交给局域网里的第三台设备，有两条路：

1. **桥接转发（relay）**：bridge 收下浏览器的字节，再以**本机身份**走 LocalSend
   上传给目标设备。
2. **bridge ↔ bridge 直连**：浏览器 A 的 bridge 打开一条 SP/1 连接，直接写进设备 B 的
   bridge，B 再落盘 / 再转发。

## 方案对比

| | A. 桥接转发（LocalSend 第二跳） | B. bridge ↔ bridge | C. 先落盘再转发 |
|---|---|---|---|
| 是否需要新的发现信息 | 否，`devices` 里已有 host/port/fingerprint | **是**：公告里没有 bridge 端口 | 否 |
| 目标设备是否要开浏览器接入 | 否 | **是**（且要能接受陌生节点的连接） | 否 |
| 目标端确认 | 复用现有接收弹窗 / 快速保存 | 需要新的一套授权模型 | 同 A |
| 传输路径 | 浏览器 → bridge → 目标（两次局域网跳） | 浏览器 → bridge A → bridge B | 同 A，但先写临时文件 |
| 断点续传 | 转发开始后不可续（HTTP body 不能回退） | 可续 | bridge 落盘后可续 |
| 浏览器能否作为接收方 | 不受影响 | 依赖"浏览器接收"（未实现） | 不受影响 |
| 工作量 | 中 | 大 | 中（但占磁盘、首字节延迟高） |

方案 B 的硬伤不在实现，而在协议外的东西：

- 无法告知对端 bridge 端口。`PeerDevice.port` 是 LocalSend 主端口。要补只能在
  LocalSend 公告/`register` 里加自定义字段（官方客户端会忽略未知字段，但这是事实上的
  协议分叉，需要单独 ADR）。
- 信任模型：A 的 bridge 去连 B 的 bridge，用的是谁的配对码？浏览器不知道 B 的配对码；
  若 B 的 bridge 无条件接受，等于任何局域网节点都能往 B 里塞文件。
- 目标浏览器必须能接收 —— 这条本身没做（ADR 0005 的 v1 边界）。

## 决策

**先做 A（流式桥接转发）**，B 暂缓。

理由：A 复用已有的发现、PIN/确认、重试与进度 UI，不需要改 LocalSend 报文，也不要求
目标设备开任何额外东西。用户视角的缺口是"浏览器只能发给这一台"，A 正好补上。

## 协议变化（SP/1）

不改消息集合，只补语义：

1. `offer.peerId` 非空 → 转发模式；为空 → 收到本机（现状）。
2. 转发模式下 `offer.pin` 作为**目标设备**的 PIN 尝试值（现在被忽略）。
3. `accept` 必须等目标设备的 `prepare-upload` 返回 200 之后再发：浏览器在此之前不发数据，
   避免目标拒绝后白传几百 MB。目标侧返回非 200 时直接回 `reject`：
   - 403 → `reject("目标设备拒绝了传输")`
   - 409 → `reject("目标设备正在接收其他传输")`
   - 401 / 429 → `reject("目标设备需要 PIN")`
   - 超时（默认 60 s）→ `reject("目标设备未响应")`
4. 转发模式下 `accept.resumeFrom` 恒为空：第二跳是 HTTP body，开了就不能回退。
   浏览器必须按 0 起送（它本来就只信服务端返回值）。
5. `done` 在"目标侧该文件上传完成"时发；整单失败发 `cancel` 并带上原因。

## 内部实现要点

### 引擎

`TransferEngine` 增加一个内部入口（`send()` 的产品语义不变）：

```kotlin
class RelaySession(
    val accepted: CompletableDeferred<Boolean>,   // 目标接受/拒绝
    fun sinkFor(fileId: String): Sink,            // 浏览器字节写这里
    fun finishFile(fileId: String),               // 该文件写完（关闭管道）
    fun awaitResult(): TransferStatus,
    fun abort(reason: String),
)
fun relay(fingerprint: String, files: List<RelayFile>): RelaySession?
```

- 每个文件一条 `PipedInputStream/PipedOutputStream`（缓冲 4–8 MiB），包成
  `OutgoingFile(openSource = { pipe.source() })`；写满时 `write` 阻塞 → 天然背压，
  bridge 的 ACK 因此也不会跑在目标前面。
- **必须跳过预哈希**：`compress/checksums` 那条路会把整个文件读一遍，管道读不了。
  `sha256 = null`（协议允许省略）。
- **强制上传并发 = 1**。默认 `Protocol.UPLOAD_CONCURRENCY` 是并行的；管道模式下多文件
  并行会互相等对方的数据而死锁（浏览器是顺序发的）。
- `runSend` 参数化：`relayMode`、`onSessionAccepted`、`onFileSent` 三个钩子；失败时把
  管道输入关掉，让浏览器侧写操作抛错并收到 `cancel`。
- 本机 `outgoing` 槽位被占用（正在发给别人）时 `relay` 直接返回 null → bridge 回
  `reject("本机正在传输")`。转发对用户是可见的：本机 UI 会显示"正在发送到 X"。

### bridge

- `handleOffer` 解析 `peerId` → `engine.relay(...)`；等待 `accepted`（60 s）再回 `accept`。
- `DATA` 帧写入对应文件的管道 sink；`DATA_END` 调 `finishFile`。
- 连接断开：转发**不能**像本机接收那样留半截文件（第二跳不可回退），直接 `abort`。
- `cancel` 帧 → `abort` 对应 send（引擎会向目标发 LocalSend `cancel`）。

### 安全

- 转发只在 `pairCode` 非空时允许。否则一个没有任何口令的 bridge + 目标端"快速保存"，
  等于局域网内任何人都能往目标机器写文件，且来源显示为 bridge 所在设备。
- 目标端的确认弹窗是最后一道闸；文档里要写明"快速保存 + 浏览器接入"的风险。

## 已知限制

- 双跳带宽：大文件实际速度约为单跳的一半（同一 Wi-Fi 上收发共享空口）。
- 浏览器进度表示"已交给本机"，不是"目标已落盘"；真正的完成看 `done`。
- 转发开始后不能续传；bridge 进程重启或目标拒绝都需要浏览器从头再发。
- `offer.pin` 只有一次机会：还需要浏览器端补一个 PIN 输入框（当前 Web UI 没有）。

## 测试计划

- `:core:jvmTest`：
  - 转发模式 offer → 假 forwarder 消费管道，校验字节顺序、多文件、`done` 逐文件；
  - 目标拒绝 / 超时 / 本机忙 → `reject` 且不产生落盘；
  - 连接中断 → 管道关闭、引擎取消，不残留 `.part`。
- 手工：桌面开浏览器接入（设配对码）→ 手机浏览器打开 Web 页 → 选一台第三方设备发送 →
  第三方出现确认弹窗 → 接受后文件出现在第三方接收目录。

## 后续（方案 B）

若之后要做真正的 peer 直连，需要先有一份 ADR 解决：bridge 端口如何公告（建议在
`register`/`info` 里加 `spPort`，官方客户端会忽略）、节点间信任（每对设备独立配对码，
或一次性邀请 token）、以及浏览器接收（下行二进制 + File System Access API）。
