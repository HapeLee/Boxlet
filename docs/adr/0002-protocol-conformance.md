# ADR 0002：LocalSend v2.2 协议一致性审计

- 状态：已采纳
- 日期：2026-10-21

## 背景

Boxlet 的报文格式照 `localsend/protocol` 的 v2.2 规范实现，但代码是独立写的，规范里那些“不强制、由实现自行决定”的地方容易走偏。真机联调后做了一次逐项对照，重点是**会不会误伤合法对端**，而不是功能多少。

对照基准：`https://github.com/localsend/protocol` 的 `README.md`（v2.2）。

## 审计结果

### 已对齐

| 项 | 规范 | Boxlet |
|---|---|---|
| 多播报文 | `alias / version / deviceModel / deviceType / fingerprint / port / protocol / download / announce` | 同 |
| `register` 请求 | 含 `port` / `protocol` | 同 |
| `register` 响应 | **不含** `port` / `protocol` | 同（`InfoResponse` 不含这两字段） |
| `prepare-upload` | `POST ?pin=`，`{info, files}` → `{sessionId, files{id→token}}` | 同，且只对接受的文件发 token |
| `upload` | `POST ?sessionId&fileId&token`，裸二进制，无响应体 | 同，另按 IP + token 校验 |
| `upload` 422 | `sha256` 不匹配 | 同，删除残留并允许 3 次重试 |
| `cancel` | `POST ?sessionId`，无响应体 | 同，`sessionId` 可缺省 |
| `info` | `GET`，仅调试 | 同（额外接受 `POST`，更宽松） |
| 状态码 | `204/400/401/403/409/422/429/500` | 同 |
| `deviceType` 未知值 | 实现需能处理，回退 `desktop` | 枚举 + `coerceInputValues`，回退默认值 |
| `sha256` 大小写 | 十六进制 | 比较前统一大写 |
| 文本消息 | 单 `text/*` 文件 + `preview` | 同，不落盘、写历史 |

### 本次修正

1. **HTTPS 下 `fingerprint` 必须以证书为准**
   规范明确：HTTPS 模式下 `/register`、`prepare-upload.info` 里的 `fingerprint` 字段“ignored”。原实现拿它和客户端证书指纹比对：
   - `onRegister` 不一致时**静默忽略注册** → 对端永远进不了设备列表；
   - `onPrepareUpload` 不一致时直接 **403** → 对端发不了文件。

   改为：HTTPS 且拿到客户端证书时，用**证书指纹**覆盖报文里的值，再写设备列表；HTTP 模式仍用报文值（此时没有证书可依据）。同一规则应用到 `/register` 响应的解析：客户端以公告里的指纹（TLS 已固定）作为设备身份，不再要求响应回填相同值。

2. **只响应 `announce = true` 的公告**
   规范：`announce = false` 是对他人公告的 UDP 回退应答，不应再触发 `register`。原实现不区分，会重复注册。

3. **`prepare-download` / `download` 的 403 语义**
   原来返回 `503` 口吻的 “Web download not initialized.”，但本机公告里 `download` 恒为 `false`。改为按协议回 403 `Rejected`，文案说明该设备未启用 Web 下载。

### 明确保留的偏差

| 偏差 | 现状 | 理由 |
|---|---|---|
| 默认端口 | `53318`，非 `53317` | 同机与官方客户端共存，见 [ADR 0001](0001-boxlet-default-port.md) |
| Download API 的监听位置 | 主端口 + 独立明文端口各一份 | 浏览器不信任自签名证书，明文端口给浏览器页用（ADR 0003）；主端口按协议服务，`download` 字段与实际响应一致 |
| 发现回退 | `/24` 全段扫描（并发 50），不是“遍历本机所有 IP 逐个 register” | 语义等价且覆盖更全；官方 legacy 模式的实际效果取决于本机接口数量 |
| `upload` 409 | 不返回 | 会话互斥在 `prepare-upload` 阶段已用 409 拦住第二个会话，旧 token 上传按 403 处理更准确 |
| `Range` | 未实现 | 分享页与主端口的 `download` 都整文件返回，见 SPEC §11 |
| IPv6 | 未实现 | 常量与接口预留，见 SPEC §11 |

> 2026-10-21 补记：审计时的两项“未实现”已经补齐 —— Download API 在主端口与分享端口都服务
> （同一会话、同一 PIN），`announce = false` 的多播回退应答也已实现
> （`DiscoveryService.announceFallback()`，对端 register 失败时触发）。

## 后果

正面：

- 与官方客户端互通不再依赖“对端把 `fingerprint` 填成证书指纹”这一实现细节。
- 发现流程不再因 UDP 回退应答产生重复注册。
- 能力声明（`download`）与实际响应一致，对端不会先拿到 `true` 再吃 403。

负面 / 遗留：

- HTTPS 下设备身份改由证书决定，意味着**自签名证书重新生成后会被当成新设备**（本来也是这个语义，只是现在更严格地以证书为唯一依据）。
- 仍未实现的能力只剩 IPv6 与 `Range`，开工前需要新的 ADR。

## 参考

- `localsend/protocol` v2.2：`README.md`（Defaults / Discovery / Upload API / Download API / Additional APIs / Status Codes）
- 本仓库对应实现：`core/src/jvmSharedMain/kotlin/dev/boxlet/core/net/ApiServer.kt`、`.../engine/TransferEngine.kt`、`.../protocol/Models.kt`
