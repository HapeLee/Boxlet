# ADR 0009：默认 LocalSend 兼容、统一设备发现与多协议路由

- 状态：已采纳；运行时待实现
- 日期：2026-10-23
- 修订：[ADR 0008](0008-sendee-primary-protocol.md) 原有 LocalSend 默认关闭策略
- 协议：[SP/2](../SENDEE-PROTOCOL-2.md)；第三方协议不使用 SP/2 线格式

## 1. 产品行为

用户选择“发给谁”，应用负责选择协议。默认同时发现 Sendee 和 LocalSend 设备，在同一个附近设备列表展示。设备卡片可有低干扰的 Sendee/LocalSend 来源标识，不要求用户理解端口、帧格式或手动切换协议模式。

| 发现的可用目标 | 默认行为 |
|---|---|
| 仅支持 SP/2 | 用 SP/2 发送 |
| 仅支持 LocalSend | 用标准 LocalSend 发送，无需额外开启模式 |
| 经可信身份关联的 SP/2 + LocalSend 双通道 | 显示一张卡片，优先 SP/2 |
| 同名/IP 相同但不能证明同一身份的多个发现项 | 分别展示并标识来源，不合并信任 |
| 未来已验证可互通的 Quick Share 目标 | 用对应适配器；未实现前不显示为可直传目标 |

LocalSend 兼容默认启用，可在设置中关闭。开启兼容不等于自动接受传输；用户接收确认、PIN、可见性和 quickSave 规则依然适用。SP/2 的配对码、LocalSend PIN、未来第三方验证码分别由各自适配器处理。

## 2. 默认发现和服务生命周期

- `ProtocolRegistry` 默认启动 `sendee-sp2` 和 `localsend-v2`。LocalSend 使用官方组播与 UDP/TCP 53317，SP/2 使用它的独立组播与 UDP/TCP 53318；不靠一个端口猜测两套协议。
- 每个适配器分别报告 `discoveryReady / sendReady / receiveReady / reason`，可以部分可用，不能把一个布尔 `serverRunning` 当作所有协议都已就绪。
- LocalSend 53317 被同机官方客户端占用时，Sendee 主服务继续工作；根据实际绑定结果报告兼容发现/接收受限，不能偷偷换端口后仍声称官方设备可发现和回传。不关闭用户的其他应用，不抢占端口。
- 关闭某个适配器后停止它的公告/监听/探测并移除相应 endpoint；已有 SP/2 endpoint 不受影响。已有任务默认停止新接入并允许有授权的当前任务收尾，明确取消则走其适配器取消流程。
- 全局可见性和接收策略对所有已启用适配器生效；协议专属限制可以更严，不能绕过全局关闭。蓝牙/BLE 扫描等未来能力仅在所需权限和用户设置允许时启动，不因安装应用就持续扫描。

## 3. 统一设备模型

建议模型（设计契约，尚未落成生产类型）：

```text
DiscoveredEndpoint
  endpointKey          adapterId + adapterScopedPeerId + locator
  adapterId            sendee-sp2 | localsend-v2 | quickshare-experimental | ...
  adapterScopedPeerId   协议自身的 ID，不能直接当全局设备 ID
  locator              适配器专属地址/句柄；未必是 host:port
  displayName, deviceType, lastSeen, availability
  identityEvidence     unverified | tls-certificate | protocol-verified | user-linked
  capabilities         send/receive/text/folders/resume/pause/maxFileSize/...

PeerRecord
  localPeerId          Sendee 本地稳定 ID，供收藏与 UI 引用
  endpoints[]          有证据归并的可用通道
  displayName, userLabel
  protocolTrust[]      每协议独立授权、证书与认证状态
```

默认按完整、经实际握手验证的相同证书 DER 指纹关联 SP/2 和 LocalSend HTTPS endpoint，大小写归一化后比较。UDP 自报字段、短 ID、IP、MAC、别名不能单独证明设备相同。跨协议没有可验证证据时保持分开；用户手动关联只合并显示，不把协议 A 的信任转成协议 B 的授权。Quick Share 的轮换/临时 ID 由其适配器管理。

设备离线按 endpoint 更新，不能因为某个发现项过期就删除其他仍在线的通道。收藏以 localPeerId 为主，但重新出现的新身份不能仅因名字一致继承收藏的自动接收权限。

## 4. 适配器契约和路由

`ProtocolAdapter` 负责 start/stop、发现事件、连接/认证、能力验证、发送/接收、进度、取消与支持时的暂停/续传。它暴露统一事件，不把 LocalSend DTO、SP/2 frame 或第三方 SDK 类型传给 UI。

`TransferRouter` 在创建任务前执行：

1. 获取目标可用 endpoint，检查适配器就绪和任务所需能力；未知能力由对应协议握手确认，不能直接相信公告。
2. 对兼容的候选按协议偏好选择：可用 SP/2 优先；目标只有 LocalSend 时自动 LocalSend。未完成研究的 Quick Share 不进入候选列表。
3. 某 endpoint 明确不支持请求内容时返回可解释原因；不能静默丢文件、把文本变成空文件，或承诺不支持的断点续传。普通文件发送不要求对端支持 SP/2 专有能力。
4. 创建绑定 `adapterId + endpointKey + authenticatedIdentity` 的任务，完成该协议的 PIN/配对/接收确认后发送。
5. 该任务的所有进度、回复、取消和重试都由同一适配器处理。接收侧同样把协议内会话映射为统一任务，历史记录保留真实 protocolId。

正常自动选协议和失败后偷偷降级是两回事：仅有 LocalSend 的目标直接使用 LocalSend；SP/2 认证失败、身份改变、用户拒绝、版本冲突后不自动改协议重发。进行中的 SP/2 offset/token/checkpoint 不能用于 LocalSend。用户明确重试另一通道时创建新任务，提示原任务哪些文件已成功，避免重复发送。

协议错误映射到统一错误类别，同时保留协议专属 code 供诊断。UI 只展示实际支持的暂停/恢复等操作；外部系统交接仅报告 `handedOff`，没有可验证的结果回调时不能伪报 `completed`。

## 5. LocalSend 的边界

LocalSend 适配器是正式的默认兼容能力，但不决定 Sendee 主协议的帧格式、发现、版本或文件摘要。它保持 LocalSend v2.2 标准报文及安全策略；SP/2 的分块、BATCH、contentRoot 和续传不发给 LocalSend 设备。

SP/2 迁移后删除 ADR 0007 的私有 capability/尾摘要；标准 LocalSend 仍可按设置预哈希。端口 53318 上现有的过渡 LocalSend 服务，只在迁移完成时改为真正 SP/2，不在文档阶段改常量冒充已经完成兼容。

## 6. Google Quick Share（原 Nearby Share）研究路线

Quick Share 作为独立的未来适配器研究，不包装成 SP/2，不要求目标安装 Sendee。Google 的 Nearby Connections API 提供应用间近距离发现和数据交换；接入该 API 本身不足以证明与系统 Quick Share 产品互通，必须独立验证产品级发现、握手和文件交付。

目前官方资料确认 Quick Share 支持 Android/Windows 文件传输；Google 的 `nearby` 开源仓库含 `connections` 与 `sharing` 代码，但仓库说明它不是正式受支持的 Google 产品。这里将它作为研究线索，不据此承诺稳定 SDK 或跨端互通。

后续按以下顺序验证：

1. 调查目标平台的系统共享入口/受支持集成方式和官方公开源码。区分“跳转系统分享面板”和“Sendee 列表中直接发现并发送给 Quick Share 目标”，两者分别标注。
2. 做隔离 PoC，验证 Android ↔ Windows 至少一个发送与一个接收方向，记录 Bluetooth/BLE/Wi-Fi、权限、设备可见性、身份验证、服务依赖与传输完成回调的实际要求。
3. 只有发现和产品级真实互传均通过，才将适配器标记为 experimental；未经确认的手机不能只因为检测到 BLE 广播就显示“Quick Share 可发送”。
4. 验证取消、拒绝、同名目标、断线、系统后台/省电行为、文件权限和大文件，再决定进入默认发现；先保持实验开关关闭，不影响 SP/2/LocalSend 稳定路径。
5. 平台不可用时报告 unavailable；不要伪装可用，不静默安装服务、登录账号、开启系统无线开关或改第三方可见性设置。

不在本 ADR 中承诺研究周期、官方兼容性或未来吞吐。其他近距离分享协议可以按相同适配器边界扩展，不因预留接口就对 UI 宣称支持。

资料核查日期：2026-10-23。

- [Google：Quick Share Android/Windows 使用说明](https://support.google.com/android/answer/13801258?hl=en)
- [Google：Nearby Connections API](https://developers.google.com/nearby/connections/overview)
- [Google Nearby 开源仓库](https://github.com/google/nearby)

## 7. 验收与当前状态

迁移验收增加：默认发现两类设备；仅 LocalSend 设备无需切模式可发送/接收；双协议同身份去重与未验证同名不合并；端口占用部分就绪；关闭/重启适配器；跨协议权限不继承；错误、PIN、取消和历史正确归属；SP/2 失败不偷偷跨协议重发。未来 Quick Share 另有 PoC 退出条件，不混入已通过能力列表。

本次只修订架构与协议文档。运行时仍是原有 LocalSend + ADR 0007、SP/1；统一多适配器发现、自动路由、SP/2 与 Quick Share 均未因此实现。实现进度以 [ADR 0008](0008-sendee-primary-protocol.md) 的阶段和代码验收为准。
