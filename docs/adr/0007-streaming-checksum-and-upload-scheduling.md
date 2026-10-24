# ADR 0007：流式校验与多文件调度

- 状态：已实现
- 日期：2026-10-23

> 后续定位：本文保留 BP/2 迁移前的私有扩展设计。原生应用当前默认使用标准 LocalSend，BP/2 默认关闭。旧尾摘要扩展只供底层显式开启 legacyStreamingChecksumEnabled 的测试/基准协商，关闭 BP/2 不会自动启用它。最终清理范围见 [ADR 0008](0008-boxlet-primary-protocol.md)，当前行为见 [LocalSend 实现记录](../protocol/LOCALSEND-IMPLEMENTATION.md)。

设计时原生互传使用 LocalSend v2 API。标准 LocalSend 的可选 SHA-256 位于 prepare-upload 元数据中，提供该摘要时需要预读文件。本 ADR 增加显式协商的扩展，并保留标准上传格式。

## 流式 SHA-256

1. 原生引擎在 register/info 响应的 `sendeeCapabilities` 中公告 `sha256-trailer-v1`。
2. 开启“发送时计算 SHA-256”时，发送端通过所选上传地址的 info API 探测能力，HTTPS 继续使用相同的证书指纹固定策略。失败、缺字段或不支持时回退到 LocalSend 预哈希。
3. 双方支持时，prepare-upload 文件项带 `sendeeChecksum: "sha256-trailer-v1"`，省略预计算的 `sha256`。普通文本消息保持现有处理。
4. 上传 body 为恰好 `size` 字节的文件数据，加上 32 字节原始 SHA-256。Content-Length 是 `size + 32`；元数据大小、进度和保存内容均只计算文件字节。发送端边读边计算，只打开源文件一次。
5. 接收端边写边计算，读取尾部摘要比较后才 commit 或发完成事件。流式扩展必须校验尾部摘要，即使关闭了传统接收校验开关。摘要不匹配时 abort 并返回 422，继续复用最多三次的重试；截断、缺少摘要或多余字节均失败且不 commit。
6. 关闭发送校验时不探测此能力，使用普通 LocalSend body。对未支持扩展的接收端，仍提前计算 SHA-256 并发送标准裸文件 body。

此扩展不是 HTTP trailers，也不是 SP/1，不能未经能力协商发送给旧设备。它用于传输完整性校验；HTTPS 仍负责传输加密与对端身份验证。

## 多文件调度

- 不再为每个文件创建协程。预哈希使用最多两个 worker，每个最多使用 1 MiB 读缓冲，取消时逐块检查。
- 上传按文件大小升序安排，减少小文件等待。包含至少两个不超过 1 MiB 的文件时最多四个 worker，否则保持最多两个 worker。
- 大于 1 MiB 的文件另受两路信号量限制，避免四路大文件争抢存储。单个文件仍是一条流。
- 复用已有的 HTTP keep-alive、PIN、选择性接收、取消和每文件重试。不打包文件、不改变接收端目录结构。

收益主要是新版双方省去一次完整源文件读取，以及小文件有更多请求并行机会。没有引入单文件多连接分块或批量打包协议；真实吞吐取决于网络、存储、CPU 和文件分布。
