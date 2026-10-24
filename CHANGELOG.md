# 更新记录

## v1.0.0 — 2026-10-25

- 原生应用默认启用 Boxlet Protocol 2（BP/2）；已配对且认证成功的设备优先使用 BP/2，其他设备自动使用默认开启的 LocalSend v2.2 兼容通道。
- BP/2 使用 TCP/TLS，支持双向会话、分页清单、小文件批量传输、多文件调度、边传边校验、坏块修复及任务暂停/继续。普通文件系统支持双方进程重启后续传与源前缀变化后的显式逐文件重传。
- Android、Compose 桌面和 WinUI 提供完整证书指纹配对、撤销与 IP/端口直连。
- 完善 LocalSend 官方发现、取消响应、连接关闭、HTTP 请求边界和标准报文兼容。新增明确的“LocalSend 快速发送（跳过预哈希）”选项，已有校验偏好保留，默认仍启用发送校验。
- 统一 Windows 本地构建、便携包、安装包与引擎的版本来源，整理协议实现与验证文档。

当前边界：Android SAF/MediaStore 接收端持久化续传、BP/2 浏览器 WSS、Quick Share 尚未实现；浏览器继续使用 SP/1。现有回环与独立进程验证不等于 Android/Windows 无线真机验收，也不证明相对官方 LocalSend 客户端有固定速率优势。详见 [BP/2 实现记录](docs/protocol/IMPLEMENTATION.md) 和 [LocalSend 实现记录](docs/protocol/LOCALSEND-IMPLEMENTATION.md)。
