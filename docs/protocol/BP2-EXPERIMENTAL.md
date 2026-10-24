# 原生 BP/2 使用说明

Boxlet Protocol 2（BP/2）原生会话已经接入实际发送、接收确认、存储、历史、任务级暂停/继续，以及普通文件系统的双方重启续传。原生应用与 engine-host 默认关闭 BP/2，统一使用标准 LocalSend；BP/2 仅供内部开发实验显式启用。Android 接收端恢复与 WSS 尚未完成，范围见 [实现记录](IMPLEMENTATION.md)。LocalSend 默认保持启用，发送时由引擎按设备通道自动选择。

原生 BP/2 数据链路统一使用 TCP/TLS；UDP 仅用于设备发现。

## 日常互传

Android、Compose 桌面与 WinUI 在设备列表中选择目标并发送，接收方确认即可。开启“仅收藏设备自动接收”后，收藏设备可自动接收，其他设备仍弹出确认；开启“快速保存”则自动接受所有传输。收藏匹配设备的证书身份，不按别名判断。

BP/2 不使用 LocalSend 接收 PIN，也不再提供手动指纹配对或另一份信任名单。TLS、块校验和最终摘要始终开启；公告指纹必须与实际 TLS 证书和 HELLO 一致，但不提供首次连接的带外身份保证。续传仍绑定原证书、清单和秘密凭证。

三端均不提供 BP/2 设置区、开关、端口或状态项，关于页仅显示 LocalSend。LocalSend 的校验与 HTTPS 开关只控制兼容链路；浏览器仍使用 SP/1。

## 引擎 IPC

先构建并分别启动两端引擎。每个进程使用独立数据目录；以下 JSON 每条独占一行，写入对应进程 stdin，响应与状态事件从 stdout 读取。完整命令见 [Engine IPC](../ENGINE-IPC.md)。

```powershell
.\gradlew.bat :engine-host:installDist
.\engine-host\build\install\engine-host\bin\engine-host.bat --data-dir D:/Boxlet-Test/A
```

以下为开发诊断流程，不属于产品设置。启动后在双方通过 IPC 显式启用；应用已完成一次默认配置迁移，后续显式启用可持久化。无需登记指纹，等待发现完成后使用 `state.devices[].fingerprint` 指定目标：

```json
{"id":"enable","command":"settings.set","params":{"bp2Enabled":true}}
{"id":"send","command":"send.files","params":{"fingerprint":"目标设备ID","files":[{"path":"D:/Boxlet-Test/photo.jpg"}]}}
{"id":"accept","command":"receive.accept"}
```

`receive.accept` 发给接收方，仅在出现 `pendingReceive` 后操作；`params.fileIds` 可选择部分文件，`receive.reject` 拒绝，`transfer.cancel` 取消。双通道优先 BP/2，仅 LocalSend 通道使用 LocalSend；证书验证失败不会换协议重发。实际协议见任务与历史的 `adapterId`。

诊断工具可以用 `bp2.connect` 按已知地址和期望证书连接，参数为 `host / port / fingerprint`。它验证本次连接，不持久化配对；普通界面不需要手动核对或输入指纹。`started:true` 仅表示已开始，等待设备出现 `identityVerified:true` 的 BP/2 通道才表示成功。

传输期间可在双方传输卡片点击“暂停传输/继续传输”，或使用 IPC：

```json
{"id":"pause","command":"transfer.pause","params":{"direction":"send"}}
{"id":"continue","command":"transfer.continue","params":{"direction":"send"}}
```

接收方操作时 direction 为 `receive`。主动暂停保留连接与进度，仍可取消和反向发送。接收端为普通文件系统且实际签发续传凭证时，断线会自动重连三次；耗尽后任务显示为暂停，使用同一“继续传输”按钮或命令恢复。接收进程重启后，在原数据目录、原接收目录和原端点重新运行即可恢复；发送端使用原身份与数据目录重启后，可恢复为同一暂停任务，需点击“继续传输”或发送 continue 命令才重新连接与读源。已交付文件不重开或重发；未完成源暂时缺失/权限失效时保留暂停，恢复源与读取权限后再继续。

发送端状态加密存放在应用数据目录的 `bp2-send/`，不复制源文件；支持普通文件路径，以及 Android 已持久化读取授权的文档/目录。临时分享授权和不可重开 source 仍仅支持进程内恢复。Android 权限恢复尚未真机验收。

接收端状态存放在应用数据目录的 `bp2-receive/`。支持续传要求其与接收目录位于可硬链接发布的同一文件系统，且两目录不重叠；否则沿用原有接收路径，不签发凭证。Android SAF/MediaStore 和文本任务目前不提供接收续传。源文件保留前缀变化会暂停任务，各端传输卡片提供“使用当前内容从头重传”按钮，只清零对应的未发布文件；也可恢复原内容后直接继续。其他文件保留进度，已发布文件不会重发。源大小变化仍需取消后创建新任务。

保留任务默认 24 小时过期；未完成任务最多 128 个，预约字节数默认最多 256 GiB，每任务清单元数据上限 64 MiB。启动及每分钟清理过期/撤销记录，保留已发布文件。取消断线任务会删除本机保留状态；若对端仍离线，远端状态需待重新连接处理或过期清理。

## 配置与任务清理

`settings.get` 返回：

| 字段 | 默认值 | 含义 |
|---|---|---|
| `bp2Enabled` | `false` | 内部实验启用项，无图形设置入口 |
| `bp2Port` | `53318` | 原生 TLS 监听端口 |

`status.bp2Port` 返回实际绑定端口，`0` 表示未运行；`protocols[]` 分别报告发现/发送/接收就绪状态。将 LocalSend `port` 和 `bp2Port` 均设为 `0` 可隔离同机多进程测试，然后通过 `status.bp2Port` 手动连接；该模式不进行 BP/2 多播公告。

```json
{"id":"disable","command":"settings.set","params":{"bp2Enabled":false}}
```

取消保留任务会禁用对应续传凭证；过期任务按原有配额和有效期清理，不影响已发布文件。关闭 BP/2 后继续使用标准 LocalSend，不自动启用旧私有摘要尾扩展。首次发送无需整文件预哈希，BP/2 的块校验和最终摘要始终启用，与 LocalSend 的 `createChecksums` 设置独立。
