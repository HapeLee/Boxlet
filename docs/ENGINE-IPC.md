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
| `share` | 活动中的浏览器分享：`url / sessionId / fileCount / totalBytes / pinRequired`，无会话时不出现 |
| `pendingReceive` | 待确认的接收请求；`files[]` 含 `id / fileName / size / fileType` |
| `outgoing` / `incoming` | `sessionId / peer / status / progress / transferredBytes / totalBytes / error` |

`status` 取值：`preparing`、`waiting_for_accept`、`transferring`、`completed`、`failed`、`declined`、`busy`、`pin_required`、`too_many_attempts`、`cancelled`。

## 3. 命令

### status

```json
{"id":"1","command":"status"}
```

返回 `alias / port / https / serverRunning / fingerprint / protocol / deviceCount`。

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

### settings.get

返回完整 `Settings` JSON。

### settings.set

参数为**要修改的字段**（浅合并），例如：

```json
{"id":"10","command":"settings.set","params":{"alias":"My PC","quickSave":true,"port":53317}}
```

`showToken` 不可通过此命令修改。修改端口 / HTTPS / 多播组 / 网络过滤会重启接收服务。

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
