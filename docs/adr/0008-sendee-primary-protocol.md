# ADR 0008：SP/2 为主协议，LocalSend 为默认启用的兼容层

- 状态：已采纳；实现待迁移
- 日期：2026-10-23
- 规范：[Sendee Protocol 2](../SENDEE-PROTOCOL-2.md)
- 替代：ADR 0005 的未来主协议方向；ADR 0007 的 Sendee 私有扩展长期定位

> 同日修订：按用户要求，LocalSend 从“默认关闭、手动开启”调整为“默认启用、统一发现、自动按目标协议发送”。统一路由与后续 Quick Share 扩展见 [ADR 0009](0009-unified-device-discovery-and-routing.md)。协议隔离不等于让用户手动切模式。

## 背景与决定

项目尚未对外发布，不承担旧版 Sendee 兼容成本。现有原生主链路是 LocalSend v2.2，浏览器使用 SP/1；ADR 0007 虽实现流式校验，仍寄生在 LocalSend 上传接口上。继续加私有字段会把自有协议与兼容模式混在一起。

统一采用 SP/2：原生 TLS 长连接，浏览器 WSS，同一套带 streamId/offset 的帧、分页清单、BATCH 小文件、分块 SHA-256、接收信用、暂停和续传。版本直接升至 2，避免与现有 SP/1 隐式流归属混淆。LocalSend 作为默认启用、可独立关闭的兼容适配器，与 SP/2 一起发现设备；选中 LocalSend 设备即自动使用其协议。

## 关键取舍

- 正常首次发送只读一次源文件；分块摘要与 contentRoot 不要求整文件预哈希。续传时为了避免源文件变化拼接旧内容，默认重读校验已保留前缀。
- 默认一条加密连接，多流调度，不先引入 QUIC 或单文件多连接。TCP 队头阻塞仍存在，以真实测试决定后续扩展。
- 用 BATCH 减少小文件的应用请求次数，不先压缩或落临时 ZIP；目录结构与逐文件结果保留。
- ACK、written、durable、FILE_DONE 分开，接收信用限制全部流共享的内存。不能用浏览器本地 bufferedAmount 代替远端背压。
- 默认开启加密与完整性校验。浏览器 WSS 的证书信任是明确的实现依赖，不能靠原生证书指纹规则或明文降级绕过。
- 主协议不兼容 SP/1/早期 Sendee；LocalSend 适配器保持标准格式，不承载 Sendee 私有扩展。

## 实施顺序与退出条件

| 阶段 | 交付 | 退出条件 |
|---|---|---|
| A | commonMain 的 SP/2 模型、帧编解码、摘要算法、独立测试向量 | JVM/Wasm 的线格式与摘要逐字节一致，边界/坏帧测试通过 |
| B | 原生 TLS transport、认证发现、单文件/双向会话、临时文件提交 | Android ↔ Windows 大文件校验与取消通过；继续明确标记未默认切换 |
| C | 分页清单、BATCH、公平调度、信用、状态索引 | 大清单与慢磁盘测试内存有界，小文件实测与旧路径公平对比 |
| D | 暂停、检查点、续传、幂等完成 | 断线/重启/源变化/丢失最终回复的恢复通过 |
| E | 浏览器 WSS 可信证书接入、发送与条件接收 | >2 GiB 与浏览器文件 API 权限恢复通过；不可接收时准确公告能力 |
| F | 默认主链路切换、统一设备目录与路由、默认启用的 LocalSend 适配器、旧代码清理 | SP/2 全部验收基线通过后一次性迁移原生/浏览器；LocalSend 默认发现/发送/接收、双协议去重及端口冲突测试通过 |

阶段 F 时移除 ADR 0007 私有 capability/尾摘要、预哈希回退分支；标准 LocalSend 的预哈希仅留在兼容适配器。阶段 F 前不能只改版本常量/README 就声称运行时已换协议，也不能提前删除仍在使用的链路。

建议代码边界：`core/protocol/sp2` 放公共 wire 模型/codec，`core/transport` 放 TLS/WSS 载体，`core/session` 放传输/流控/续传，`core/compat/localsend` 放标准适配器；统一 `DeviceDirectory`、`ProtocolRegistry`、`TransferRouter` 连接各适配器，平台提供受限 source/destination 能力，UI/IPC 依赖统一任务模型。此为后续重构目标，现有目录无需在文档阶段空搬。

## 当前事实

2026-10-23：只完成上述协议制定；运行时仍为 LocalSend v2.2 + ADR 0007 扩展、SP/1 浏览器上传。SP/2 尚未完成任何阶段的运行时切换。本 ADR 不等同于协议已上线。
