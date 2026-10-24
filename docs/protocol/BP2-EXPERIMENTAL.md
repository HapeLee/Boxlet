# 原生 BP/2 使用说明

Boxlet Protocol 2（BP/2）原生会话已经接入实际发送、接收确认、存储、历史、任务级暂停/继续，以及普通文件系统的双方重启续传。原生应用与 engine-host 默认启用 BP/2；已有显式关闭配置保持关闭。Android 接收端恢复与 WSS 尚未完成，范围见 [实现记录](IMPLEMENTATION.md)。LocalSend 默认保持启用，发送时由引擎按设备通道自动选择。

原生 BP/2 数据链路统一使用 TCP/TLS；UDP 仅用于设备发现。

## 图形界面配对

Android、Compose 桌面与 WinUI 均可打开“设置 → Boxlet Protocol 2”：

1. 确认“优先使用 BP/2”已开启，并检查状态是否就绪。
2. 分别复制两端的本机指纹，通过当面或其他可信渠道核对完整 64 位指纹。
3. 在双方分别添加对端指纹。修改信任会重启接收服务，请在无活动传输时操作。
4. 返回设备列表刷新；双方认证成功后自动优先选择 BP/2。自动发现不可用时，在已配对设备下填写 `192.168.1.20` 或 `host:port` 直接连接，默认端口 53318；IPv6 使用 `[地址]:端口`。
5. “撤销配对”会移除信任并关闭现有连接。BP/2 认证失败不会自动换协议重发。

BP/2 不使用 LocalSend 接收 PIN，而是由双方指纹授权并继续使用原有接收确认/快速保存策略。设置中的 LocalSend 校验与 HTTPS 开关只控制兼容链路，BP/2 加密与完整性校验始终开启。浏览器仍使用 SP/1，尚未迁移为 BP/2。

## 通过引擎 IPC 启用

先构建并分别启动两端引擎。每个进程使用自己的数据目录；以下 JSON 每条独占一行，写入对应进程 stdin，响应与状态事件从 stdout 读取。完整命令见 [Engine IPC](../ENGINE-IPC.md)。

```powershell
.\gradlew.bat :engine-host:installDist
.\engine-host\build\install\engine-host\bin\engine-host.bat --data-dir D:/Boxlet-Test/A
```

1. 双方查询身份，并通过当面或其他可信渠道核对完整 64 位证书指纹。身份来源是本机 `status`，不能把未验证的 UDP 公告直接当成配对授权。

   ```json
   {"id":"identity","command":"status"}
   ```

2. 默认已启用 BP/2；若之前显式关闭，可重新开启。默认 LocalSend 接收端口由 53318 调整为 53317，BP/2 监听 53318；需确保两台设备都运行更新后的引擎。

   ```json
   {"id":"enable","command":"settings.set","params":{"bp2Enabled":true}}
   ```

3. 双方分别登记已核对的对端指纹。命令会持久化信任并重启服务；若有活动任务，会随重启中断。

   ```json
   {"id":"trust","command":"bp2.trust-peer","params":{"fingerprint":"对端的完整64位十六进制证书指纹"}}
   ```

4. 登记后通常由 IPv4 多播发现。也可以直接连接对端局域网 IP；`port` 省略时为 53318。

   ```json
   {"id":"connect","command":"bp2.connect","params":{"host":"192.168.1.20","port":53318,"fingerprint":"对端的完整64位十六进制证书指纹"}}
   ```

   `started:true` 表示已启动连接。等待 `state.devices[].channels[]` 出现 `adapterId:"boxlet-bp2"` 与 `identityVerified:true` 才表示 TLS/HELLO 验证成功；失败原因通过 `state.error` 返回。

5. 使用原有发送、接收命令，不需要指定协议。

   ```json
   {"id":"send","command":"send.files","params":{"fingerprint":"对端指纹","files":[{"path":"D:/Boxlet-Test/photo.jpg"}]}}
   {"id":"accept","command":"receive.accept"}
   ```

   接收命令发给接收方，仅在出现 `pendingReceive` 后操作。`receive.accept.params.fileIds` 可选择部分文件，`receive.reject` 拒绝，`transfer.cancel` 取消。`outgoing/incoming.adapterId` 与 `history.list` 表明实际协议。双通道优先 BP/2，仅 LocalSend 通道使用 LocalSend；BP/2 认证失败不会换协议重发。

传输期间可在双方传输卡片点击“暂停传输/继续传输”，或使用 IPC：

```json
{"id":"pause","command":"transfer.pause","params":{"direction":"send"}}
{"id":"continue","command":"transfer.continue","params":{"direction":"send"}}
```

接收方操作时 direction 为 `receive`。主动暂停保留连接与进度，仍可取消和反向发送。接收端为普通文件系统且实际签发续传凭证时，断线会自动重连三次；耗尽后任务显示为暂停，使用同一“继续传输”按钮或命令恢复。接收进程重启后，在原数据目录、原接收目录和原端点重新运行即可恢复；发送端使用原身份与数据目录重启后，可恢复为同一暂停任务，需点击“继续传输”或发送 continue 命令才重新连接与读源。已交付文件不重开或重发；未完成源暂时缺失/权限失效时保留暂停，恢复源与读取权限后再继续。

发送端状态加密存放在应用数据目录的 `bp2-send/`，不复制源文件；支持普通文件路径，以及 Android 已持久化读取授权的文档/目录。临时分享授权和不可重开 source 仍仅支持进程内恢复。Android 权限恢复尚未真机验收。

接收端状态存放在应用数据目录的 `bp2-receive/`。支持续传要求其与接收目录位于可硬链接发布的同一文件系统，且两目录不重叠；否则沿用原有接收路径，不签发凭证。Android SAF/MediaStore 和文本任务目前不提供接收续传。源文件保留前缀变化会暂停任务，各端传输卡片提供“使用当前内容从头重传”按钮，只清零对应的未发布文件；也可恢复原内容后直接继续。其他文件保留进度，已发布文件不会重发。源大小变化仍需取消后创建新任务。

保留任务默认 24 小时过期；未完成任务最多 128 个，预约字节数默认最多 256 GiB，每任务清单元数据上限 64 MiB。启动及每分钟清理过期/撤销记录，保留已发布文件。取消断线任务会删除本机保留状态；若对端仍离线，远端状态需待重新连接处理或过期清理。

## 配置与撤销

`settings.get` 返回：

| 字段 | 默认值 | 含义 |
|---|---|---|
| `bp2Enabled` | `true` | 原生 BP/2 开关；已有显式关闭配置保持关闭 |
| `bp2Port` | `53318` | 原生 TLS 监听端口 |
| `bp2TrustedPeers` | `[]` | 已明确授权的完整证书指纹 |

`status.bp2Port` 返回实际绑定端口，`0` 表示未运行；`protocols[]` 分别报告发现/发送/接收就绪状态。将 LocalSend `port` 和 `bp2Port` 均设为 `0` 可隔离同机多进程测试，然后通过 `status.bp2Port` 手动连接；该模式不进行 BP/2 多播公告。

```json
{"id":"revoke","command":"bp2.untrust-peer","params":{"fingerprint":"对端指纹"}}
{"id":"disable","command":"settings.set","params":{"bp2Enabled":false}}
```

撤销会持久化并重启，关闭现有 BP/2 连接。关闭 BP/2 后恢复旧开发链路和原端口配置，LocalSend 继续可用。首次发送无需整文件预哈希，BP/2 的块校验和最终摘要始终启用，与 LocalSend 的 `createChecksums` 设置独立。
