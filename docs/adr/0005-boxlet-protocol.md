# ADR 0005：自定义传输协议 SP/1

- 状态：SP/1 实现记录保留；未来主协议方向已由 [ADR 0008](0008-boxlet-primary-protocol.md) 与 [BP/2](../BOXLET-PROTOCOL-2.md) 替代
- 日期：2026-10-21

> 2026-10-23：本文记录 SP/1 的历史目标与实现，不作为新主协议的线格式或安全要求。BP/2 改用显式 streamId 帧、接收信用、强制块校验及 TLS/WSS；原 SP/1 明文接入不代表目标协议默认行为。

## 背景

LocalSend v2.2 只解决“原生客户端之间互通”，浏览器从一开始就不在它的设计目标里：HTTP 一问一答、每个文件单独 POST、没有多路复用、没有断点续传，浏览器既不能监听也不能多播。

我们自己的场景是「桌面 + 手机 + 浏览器」三类节点，目标是**局域网内尽可能快**。因此把 LocalSend 降级为可选兼容层（可以后来做成开关），主链路换成自己的协议。

## 目标与非目标

目标：

- 同一份协议覆盖原生与浏览器；浏览器是完整的收发节点。
- 吞吐优先：千兆局域网内接近磁盘/网卡上限，而不是被协议开销卡住。
- 一条连接多文件并发、可取消、可断点续传。
- 浏览器侧能流式落盘，不把整个文件读进内存。

非目标（v1）：

- 跨网段 / 公网打洞（需要 WebRTC/TURN，另立 ADR）。
- 浏览器之间直连（局域网内经中继即可）。
- 与 LocalSend 的双向互通（保留为兼容层，不在主路径）。

## 传输层

| 通道 | 用途 | 说明 |
|---|---|---|
| WebSocket (`ws://`, binary) | 浏览器 ↔ 任一原生节点 | 浏览器唯一能主动建立的双向字节流 |
| 裸 TCP + 同帧格式 | 原生 ↔ 原生（后续） | 省掉 WS 掩码与分片开销，本轮先只实现 WS |

浏览器不能监听，所以它**始终是连接发起方**；原生节点提供 `BridgeServer`，浏览器连上后由 bridge 代理发现（多播结果下发）与转发。

## 消息格式

WS 上只分两类消息，**数据面没有帧头**：

| WS 消息 | 内容 |
|---|---|
| `text` | 控制消息，JSON，用 `type` 字段区分 |
| `binary` | 纯 payload，归属于最近一次 `data-start` 指定的流 |

这样浏览器收到 binary 可以直接 `Blob.slice()` / `write()` 落盘，不需要把 1 MiB 数据搬进 Kotlin 的 ByteArray（wasm 里那次转换是纯开销，也最容易踩坑）。控制面是 JSON，抓包就能读懂。

控制消息（`type` 字段）：

| type | 字段 |
|---|---|
| `hello` | `alias` / `deviceType` / `version` / `pairCode` |
| `devices` | `devices[]`：`id / alias / deviceType / host / port / online` |
| `device-event` | `device` / `removed` |
| `offer` | `files[]`：`id / name / size / mime / offset`、`pin` |
| `accept` | `accepted[]`、`resumeFrom{}`、`token` |
| `reject` | `reason` |
| `data-start` | `streamId`（后续 binary 都归这条流）、`fileId` |
| `data-end` | `streamId`、`bytes` |
| `ack` | `streamId`、`bytes`（每 32 块一次） |
| `done` | `streamId`、`bytes` |
| `cancel` | `streamId`、`reason` |
| `ping` / `pong` | 无 |

v1 一次只跑一条流（`streamId` 单调递增，由 `data-start` 建立），多文件按顺序传。多流并发等带宽实测后再加。

## 性能决策

1. **大块传输**：DATA 建议 1 MiB（上限 2 MiB）。块太小会被系统调用与帧头开销吃掉。
2. **不做逐块 ACK**：默认每 32 块或流结束时回一个累计 ACK；发送端维护窗口（默认 8 MiB）即可，避免局域网 RTT 成为瓶颈。
3. **不压缩、不 base64**：局域网带宽远高于压缩速度，CPU 花在压缩上只会更慢。
4. **`TCP_NODELAY` + 1 MiB socket buffer**：避免 Nagle 把 1 MiB 块拆成小包等 ACK。
5. **校验可选**：默认只校验长度；开启“完整性优先”才逐文件 SHA-256，且在校验时用流式 HashingSink，不额外读盘。
6. **直接写盘**：接收端用 `FileChannel` / `FileSystemWritableFileStream` 顺序写，不经过内存缓冲层。
7. **多流并发**：多个文件共用一条连接、按 `streamId` 交错发送，块调度用轮转，避免大文件饿死小文件。
8. **背压**：浏览器侧用 `bufferedAmount` + `bufferedAmountLowThreshold`；原生侧用阻塞写（socket 缓冲满自然阻塞），不做无界队列。

## 发现

浏览器不能多播，所以：

- 原生节点继续用 UDP 多播发现彼此（现有实现保留）。
- 浏览器连上 bridge 后收到 `DEVICES` 全量列表，之后只收 `DEVICE_EVENT` 增量。
- 浏览器要主动发给「另一个浏览器」时，OFFER 经 bridge 转发，两段各用自己的连接。

## 安全

- bridge 默认**关闭**，用户显式开启；开启后只监听局域网。
- 连接后必须发 `HELLO` 携带配对码（一次性 token 或设置里的 PIN），不匹配直接断开。
- 接收仍需在目标设备上确认（或 quickSave）；`ACCEPT` 里的 token 绑定这条流，其他连接不能用。
- 明文 WS 仅限可信局域网；后续可加 TLS，但不作为默认（速度与证书信任成本）。

## 后果

正面：

- 浏览器成为完整节点，不再是“只能往一个方向扔文件”的残废客户端。
- 一条连接传多个文件，进度/取消/续传都是协议原生能力。
- 原生侧还能继续用 LocalSend 兼容层，不影响已有互通。

负面：

- 需要自己实现 WebSocket 服务端（握手 + 掩码 + 分片 + 背压），这是额外的一块基础设施。
- 两套协议并存，测试与文档成本上升；LocalSend 兼容层必须明确标记为「兼容模式」。
- 断点续传要求两端都记录 offset，Web 端刷新页面会丢会话（v1 只做单次连接内的续传）。

## 实现现状（2026-10-21）

- 已实现：`hello` 配对码校验、`devices` 首次全量 + `device-event` 增量、`offer` / `accept`
  （含 `resumeFrom`）、`data-start` / `data-end` / `ack` / `done` / `ping`-`pong`、`cancel`；
  1 MiB 分块、8 MiB 发送窗口、`data-end` 长度校验（不匹配不 commit，回 `cancel`）。
- 断点续传：bridge 在进程内保留未完成的半截文件（TTL 10 分钟），同一个文件
  （id + 文件名 + 大小）带 `offset > 0` 重新 `offer` 时回 `resumeFrom`；浏览器以服务端回的
  值为准（没有半截文件时回 0，必须从头发）。跨进程 / 跨设备续传没有做。
- 未实现：`peerId` 跨设备转发（方案见 [ADR 0006](0006-sp1-cross-device-relay.md)）、
  bridge → 浏览器的下行流（浏览器仍不能接收），`offer.pin` 的逐次校验（配对码已在 `hello`
  校验），多流并发（v1 仍是一次一条流）。
- 尚未做接收确认：bridge 对 `offer` 直接接受并落盘，没有原生端那种「用户点接受」的环节。

## 参考

- 实现：`core/src/jvmSharedMain/kotlin/dev/boxlet/core/sp/`（帧编解码、BridgeServer）
- Web 端：`webApp/src/wasmJsMain/kotlin/dev/boxlet/web/`
- 兼容层：`core/.../net/`（LocalSend v2.2，见 ADR 0002）
