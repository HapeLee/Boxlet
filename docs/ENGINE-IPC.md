# Boxlet Engine IPC（v1）

`engine-host` 是无 UI 的 JVM 进程，独占网络端口、证书与传输状态。原生桌面壳（WinUI 3、Avalonia、Tauri、Qt…）只负责界面、文件选择与本地保存路径，通过 **stdin/stdout 的 JSON Lines** 驱动引擎。

## 1. 启动

```bash
engine-host --data-dir <path>
```

- `--data-dir`：配置、证书、历史、收藏、默认接收目录的根路径；缺省为 `./boxlet-data`。
- 标准输入：命令（每行一个 JSON 对象）。
- 标准输出：响应与事件（每行一个 JSON 对象）。
- 标准错误：仅用于崩溃/诊断输出。
- 标准输入/输出固定 UTF-8，不随 Windows 控制台代码页变化。
- 原生桌面壳可附加 `--desktop`：新配置默认公告为桌面设备，未指定保存目录时使用 `~/Downloads/Boxlet`；现有配置仍优先。
- 收到 `shutdown` 或 stdin EOF 后优雅停止服务并退出。

### 数据布局

```
<data-dir>/settings.json    设备名、端口、HTTPS、PIN、保存目录等
<data-dir>/security.json    PKCS#12 证书（base64）与指纹
<data-dir>/history.json     接收历史
<data-dir>/favorites.json   收藏设备
<data-dir>/received/        未配置保存目录时的默认接收目录
```

## 2. 消息格式

### 请求

```json
{"id": "任意字符串", "command": "send.files", "params": { }}
```

- `id` 由客户端生成，原样回传；事件没有 `id`。
- `params` 可省略。

### 成功响应

```json
{"id": "1", "ok": true, "result": { }}
```

### 错误响应

```json
{"id": "1", "ok": false, "error": {"message": "fingerprint is required"}}
```

### 事件

```json
{"event": "state", "data": { ... }}
```

`state` 在任意状态变化时推送（约 100ms 节流），包含：

| 字段 | 说明 |
|---|---|
| `ready` | 引擎初始化完成 |
| `serverRunning` | 接收服务是否运行 |
| `alias` / `port` / `https` / `fingerprint` | 本机信息 |
| `error` | 当前错误（可被下一次成功操作覆盖） |
| `devices[]` | `fingerprint / alias / deviceType / deviceModel / lastSeen / channels[]` |
| `protocols[]` | 各适配器的 `adapterId / enabled / discoveryReady / sendReady / receiveReady / reason`；默认启用 `localsend-v2` 和 `boxlet-bp2`，各服务独立报告实际就绪状态；已有显式关闭配置保持关闭 |
| `share` | 活动中的浏览器分享：`url / sessionId / fileCount / totalBytes / pinRequired`，无会话时不出现 |
| `pendingReceive` | 待确认的接收请求；`files[]` 含 `id / fileName / size / fileType` |
| `outgoing` / `incoming` | `sessionId / peer / adapterId / status / progress / transferredBytes / totalBytes / error`；adapterId 是实际协议 |

`devices[].channels[]` 包含 `host / port / https / adapterId / identityVerified`。BP/2 通道仅在已登记证书完成 TLS/HELLO 验证后加入，`identityVerified:true`；原有 LocalSend 通道默认 `adapterId:"localsend-v2"`。发现公告本身不能建立 BP/2 信任。原生 BP/2 数据链路仅使用 TCP/TLS，不提供额外 UDP 数据端口、载体开关或路由字段。

`outgoing/incoming.files[]` 包含 `id / fileName / size / fileType / status / bytesTransferred / savedPath / error`，用于逐文件进度及实际落盘路径。历史按稳定完成 ID 去重，重连查询已完成结果不会重复追加记录。

`status` 取值：`preparing`、`waiting_for_accept`、`transferring`、`paused`、`completed`、`failed`、`declined`、`busy`、`pin_required`、`too_many_attempts`、`cancelled`。

## 3. 命令

### status

```json
{"id":"1","command":"status"}
```

返回 `alias / port / https / serverRunning / fingerprint / protocol / deviceCount / bp2Enabled / bp2Port`。`port` 是设置值，实际 LocalSend 监听端口见 `state.port`；`bp2Port` 是实际原生绑定端口，未运行时为 0。`protocol` 保留 LocalSend 兼容版本，不能用它判断任务走哪套协议，应读取任务的 `adapterId`。

### bp2.trust-peer / bp2.untrust-peer / bp2.connect

原生应用与 engine-host 默认启用 BP/2，已有显式关闭设置保持关闭。完整流程见 [BP/2 使用说明](protocol/BP2-EXPERIMENTAL.md)。先通过可信渠道核对双方完整证书指纹，然后在双方分别登记对端：

```json
{"id":"enable","command":"settings.set","params":{"bp2Enabled":true}}
{"id":"trust","command":"bp2.trust-peer","params":{"fingerprint":"对端完整64位十六进制指纹"}}
{"id":"connect","command":"bp2.connect","params":{"host":"192.168.1.20","port":53318,"fingerprint":"对端完整64位十六进制指纹"}}
{"id":"revoke","command":"bp2.untrust-peer","params":{"fingerprint":"对端完整64位十六进制指纹"}}
```

- 登记/撤销接受大小写十六进制指纹，持久化后返回 `updated:true`；不允许登记自身。信任修改会重启引擎，中断当前任务并关闭已有连接。
- `bp2.connect` 要求适配器已启用且指纹已登记，`port` 默认 53318。`started:true` 只表示开始异步连接；等 `state.devices` 中出现已验证 BP/2 通道才表示成功，失败由 `state.error` 报告。
- 启用 BP/2 后，默认 LocalSend TCP 端口改为 53317，原生监听 53318；自定义端口保持配置值。两者冲突由 `protocols[].reason` 报告，不虚报就绪。
- `bp2Enabled / bp2Port / bp2TrustedPeers` 均由 Settings 持久化；默认分别为 `true / 53318 / []`。Android、Compose 桌面、WinUI 均有指纹配对界面；普通文件系统支持双方重启续传，Android SAF/MediaStore 接收续传与 WSS 尚未完成。

### devices.scan

发送多播 announce，并异步执行本地 `/24` 扫描。设备通过 `state` 事件出现。

```json
{"id":"2","command":"devices.scan"}
```

### send.files

```json
{
  "id": "3",
  "command": "send.files",
  "params": {
    "fingerprint": "目标设备指纹",
    "files": [
      {"path": "C:/Users/me/photo.jpg", "name": "photo.jpg"}
    ]
  }
}
```

- `path` 必须是引擎进程可读的本地文件；`name` 可选，用于覆盖展示文件名。
- 文件在命令返回后异步发送；进度通过 `outgoing` 事件推送。
- 自动优先可用 BP/2 通道，只有 LocalSend 通道时使用标准 LocalSend。任务中途不会切换适配器；BP/2 身份失败或拒绝不会自动降级重发。
- 若目标要求 PIN，`outgoing.status` 变为 `pin_required`。原生壳应通过本地 UI 获取 PIN，然后调用：

```json
{"id":"4","command":"transfer.pin","params":{"pin":"1234"}}
```

`outgoing.status` 变为 `pin_required` 后，通过 `transfer.pin` 提交 PIN：

```json
{"id":"4","command":"transfer.pin","params":{"pin":"1234"}}
```

错误 PIN 会再次进入 `pin_required`；取消发送使用 `transfer.cancel`。

### send.text

```json
{"id":"5","command":"send.text","params":{"fingerprint":"...","text":"hello"}}
```

### share.start / share.stop

通过浏览器分享文件：起一个独立的明文 HTTP 服务，返回可直接发给对方的 URL（见 ADR 0003）。

```json
{"id":"s1","command":"share.start","params":{"files":[{"path":"C:/Users/me/photo.jpg"}]}}
```

- `files[].path` 必须是引擎进程可读的本地文件，`name` 可选（用于覆盖展示名）。
- PIN 沿用 `settings.receivePin`：设置了就必须在 URL 上带 `?pin=`。
- 响应：`{"id":"s1","ok":true,"result":{"url":"http://192.168.1.5:49152/","sessionId":"…","fileCount":1,"pinRequired":false}}`。
- 会话存活期间本机 `download` 字段为 `true`，停止后恢复为 `false`。
- 只支持浏览器**下载**，且不支持 `Range`。
- 主端口同时提供 `prepare-download` / `download`（与分享端口同一会话、同一 PIN），对端按公告的 `port` 访问也能下载。

```json
{"id":"s2","command":"share.stop"}
```

### bridge.start / bridge.stop

打开 SP/1 的浏览器接入点。浏览器不能监听、不能多播，由 bridge 代做设备发现与文件落地（见 ADR 0005）。

```json
{"id":"b1","command":"bridge.start","params":{"port":0,"pairCode":"1234"}}
```

- `port` 省略或为 `0` 时由系统分配。
- `pairCode` 省略则复用 `settings.receivePin`；两者都没有时不校验配对码。
- 响应的 `url` 可直接填进 Boxlet Web 页面的「Bridge 地址」：
  `{"id":"b1","ok":true,"result":{"url":"ws://192.168.1.5:53321/","port":53321,"pairCodeRequired":true}}`。
- `state` 事件里的 `bridge` 对象字段相同；未开启时该字段缺省。
- 没有可用局域网 IPv4 地址时返回错误，不会启动。
- 同一时刻只保留一个 bridge，重复 `bridge.start` 会先停掉旧的。
- 浏览器端 v1 只发不收，落盘目录与原生接收一致（`settings.destination` 或 `received/`）。
- 断点续传在 bridge 进程内有效：半截文件保留 10 分钟，`accept.resumeFrom` 给出续传起点；Web 页断线后会自动重连并带 `offset` 重新 `offer`。

```json
{"id":"b2","command":"bridge.stop"}
```

### receive.accept / receive.reject

```json
{"id":"6","command":"receive.accept","params":{"fileIds":["id1","id2"]}}
{"id":"7","command":"receive.reject"}
```

`fileIds` 省略或为空表示全部接收。

### transfer.cancel

```json
{"id":"8","command":"transfer.cancel","params":{"direction":"send"}}
{"id":"9","command":"transfer.cancel","params":{"direction":"receive"}}
```

### transfer.pause / transfer.continue

只用于活动中的 BP/2 任务，方向必须为 `send` 或 `receive`；LocalSend 没有此能力时返回错误。发送端或接收端均可操作。

```json
{"id":"pause","command":"transfer.pause","params":{"direction":"send"}}
{"id":"continue","command":"transfer.continue","params":{"direction":"receive"}}
```

返回 `started:true` 表示开始异步操作，等待 `state.outgoing/incoming.status` 变为 `paused/transferring` 才表示对端已确认。主动暂停保留连接与原 source，允许有限在途数据收尾，心跳、修复、取消与反向传输照常处理。对持有接收端续传凭证的任务，断线会自动重连三次，耗尽后 PAUSED 也可通过同一 continue 命令恢复；接收端重启可从磁盘恢复；可重开的本地文件任务在发送端重启后恢复为同一 PAUSED 任务，需显式 continue 才连接和读源。源暂时缺失或读取授权失效时保留为暂停，恢复源与权限后可继续；已保留前缀改变时暂停并标记对应文件的 `restartRequired:true`；可恢复原内容后继续，或使用下述 reset-file 明确重传该文件。源大小变化仍需新建任务。恢复失败由 `state.error` 或任务 error 报告。Android、Compose 桌面和 Windows 原生传输卡片提供相应按钮。

源前缀变化后的显式逐文件重传：

```json
{"id":"reset","command":"transfer.reset-file","params":{"direction":"send","fileId":"state.outgoing.files 中 restartRequired 为 true 的文件 ID"}}
```

仅允许暂停的 BP/2 发送任务，返回 `started:true` 后观察状态事件。此操作明确选择使用当前源内容，认证重连后只重置对应未发布文件，其他文件保留进度。文件 ID 不使用本地路径或文件名；拒绝任意未标记文件、已完成文件和接收方向。重启后先 continue 重新校验，不自动重放上次的重置意图。重置确认后发生断线，可用普通 continue 从接收端已经清零的新状态恢复。

发送端恢复记录位于原应用数据目录的 `bp2-send/`，用本机 TLS 身份密钥封装 AES-256-GCM 密钥，加密保存凭证、冻结端点、清单与显式 source 引用；不保存源内容，不通过 IPC 暴露凭证。记录只保留一个任务，24 小时过期，完成/取消后清除。重启必须使用原身份与数据目录。损坏记录不会回退重放旧代次；应用目录由独占文件锁保护。Android 仅持久读取授权的文档/目录 source 可重建，临时分享授权不承诺跨重启恢复。

### settings.get

返回完整 `Settings` JSON。

### settings.set

参数为**要修改的字段**（浅合并），例如：

```json
{"id":"10","command":"settings.set","params":{"alias":"My PC","quickSave":true,"port":53317}}
```

`showToken` 不可通过此命令修改。修改端口 / HTTPS / 多播组 / 网络过滤 / BP/2 开关、端口或信任集合会重启接收服务，活动任务会中断。

### favorites.list / add / remove

```json
{"id":"11","command":"favorites.list"}
{"id":"12","command":"favorites.add","params":{"fingerprint":"..."}}
{"id":"13","command":"favorites.remove","params":{"fingerprint":"..."}}
```

`favorites.add` 要求该设备当前已在 `devices` 列表中（先扫描）。

### history.list / clear

```json
{"id":"14","command":"history.list"}
{"id":"15","command":"history.clear"}
```

每条接收历史包含 `adapterId`，区分 `localsend-v2` 与 `boxlet-bp2`。旧记录没有该字段时按 `localsend-v2` 读取。

### shutdown

```json
{"id":"16","command":"shutdown"}
```

## 4. 与嵌入 core 的对比

| 方式 | 优点 | 代价 |
|---|---|---|
| 直接依赖 `core` / `app-shared`（JVM） | 零 IPC、可访问全部 Kotlin API | UI 必须是 JVM 技术栈 |
| `engine-host` 子进程 | UI 技术栈完全自由（C#/Rust/JS…），崩溃隔离 | 需要进程管理与 JSON 协议 |

原生壳也可以自行实现 LocalSend v2.2 协议，与本项目/官方客户端互通；此时以 `docs/SPEC.md` §4 为准。
