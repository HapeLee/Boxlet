# 版本号与发布

版本号只有**一个来源**：git tag。所有端、所有产物、GitHub Release 和 Telegram 通知
都用同一个值，不再手改 `versionCode` / `packageVersion` / `AppVersion`。

## 1. 版本号规则（对齐 Matter 的 SoftwareVersion）

tag 形式：`vMAJOR.MINOR.PATCH`，或带第 4 段的 `vMAJOR.MINOR.PATCH.TWEAK`
（等价于 Matter 的 `1.2.3+tweak` 写法）。

数值按 Matter Core Spec 的字节打包方式编码，高位到低位依次是
major / minor / patch / tweak，各占 8 位：

```
softwareVersion = (major << 24) | (minor << 16) | (patch << 8) | tweak
```

| 版本 | 十六进制 | 十进制 | 用途举例 |
|---|---|---|---|
| `v0.1.0` | `0x00010000` | 65536 | 第一个开发版 |
| `v1.0.0` | `0x01000000` | 16777216 | 首个正式版 |
| `v1.2.3` | `0x01020300` | 16909056 | 常规功能版 |
| `v1.2.3.4` | `0x01020304` | 16909060 | 同版本的第四次修订 |

- 每个字段 0–255（每字段一个字节）；
- `major` 上限 **125**：Android 的 `versionCode` 是 32 位有符号整数，
  `major = 126` 就会超过 2100000000 的上限，构建时会有明确报错；
- 同一个 `major.minor.patch` 下的正式修订请用第 4 段（tweak）递增。

### 开发构建

不是精确打在 tag 上、或工作区有改动时，展示用的版本号会带后缀，
**数值版本号不变**：

| 场景 | 展示版本 | 数值版本 |
|---|---|---|
| 干净地构建在 `v1.2.3` 上 | `1.2.3` | `0x01020300` |
| `v1.2.3` 之后 7 个提交 | `1.2.3-dev.7+ga1b2c3d` | `0x01020300` |
| 未提交的改动 | `1.2.3-dev.7+ga1b2c3d.dirty` | `0x01020300` |
| 仓库里还没有任何 `v*` tag | `0.1.0+g0efb21a` | `0x00010000` |

正式发布必须打在**干净的 tag** 上（工作区无改动），否则 Release 名称会带 `-dev` 后缀。

### 各端的映射

| 端 | 字段 | 取值 |
|---|---|---|
| Android | `versionName` / `versionCode` | 展示版本 / 数值版本（debug 追加 `-debug` 后缀） |
| Compose 桌面 | `packageVersion` | `major.minor.patch`（MSI/DEB 只接受三段） |
| Windows 原生 | `Version`（FileVersion / InformationalVersion） | 数值版本 |
| Windows 安装包 | `AppVersion`、`VersionInfoVersion` | `major.minor.patch` |
| engine-host | jar `MANIFEST` 的 `Implementation-Version` | 展示版本 |
| 各端 UI | `dev.boxlet.core.BoxletBuild` | 展示版本 + 数值版本（构建期生成） |

## 2. 本地怎么用

```bash
# 查看当前统一版本号
./gradlew -q printBoxletVersion

# 临时覆盖版本号（不依赖 tag），用于本地验证签名包等
BOXLET_VERSION=1.2.3 ./gradlew :androidApp:assembleRelease
./gradlew -PboxletVersion=1.2.3 printBoxletVersion
```

版本号解析在根 [`build.gradle.kts`](../build.gradle.kts)：
优先 `-PboxletVersion`，其次 `BOXLET_VERSION` 环境变量，最后 `git describe --tags --match 'v[0-9]*' --dirty`。
生成的 `dev.boxlet.core.BoxletBuild` 落在 `build/generated/boxlet/kotlin/`，由
`:generateBoxletVersionSource` 负责，不要在源码里手写版本常量。

## 3. 发布流程

```bash
# 1. 在 main 上确认工作区干净
git status

# 2. 打 tag（附注 tag，建议写上这版改了什么）
git tag -a v1.0.0 -m "Boxlet 1.0.0"

# 3. 推送 tag，触发 Release 流水线
git push origin v1.0.0
```

流水线（[`.github/workflows/release.yml`](../.github/workflows/release.yml)）：

1. `prepare` 从 tag 得到版本号，并校验格式；
2. `android` / `windows` / `web` / `engine` 四路并行构建，全部带上同一个 `BOXLET_VERSION`；
3. `release` 汇总产物，创建（或复用）`vX.Y.Z` 的 GitHub Release 并附上生成的更新说明；
4. `notify` 把结果发到 Telegram。

产物：

| 文件 | 内容 |
|---|---|
| `Boxlet-<版本>-android.apk` / `-android-unsigned.apk` | 正式签名 APK / 未配置密钥时的未签名 APK |
| `Boxlet-<版本>-android.aab` | Play 用的 App Bundle（配置了签名密钥时） |
| `Boxlet-<版本>-mapping.txt` | R8 混淆映射，用于反混淆线上崩溃栈 |
| `Boxlet-<版本>-windows-x64-*.zip` | 免安装便携包（含 .NET、Windows App SDK、Java 运行时） |
| `Boxlet-Setup-<版本>-x64.exe` | Inno Setup 安装包 |
| `Boxlet-<版本>-web.zip` | 浏览器端静态站点 |
| `Boxlet-<版本>-engine.zip` | 无头引擎 `engine-host` |

不打算发 Release 时可以先只打 tag 到本地验证，或者用
「Actions → Release → Run workflow」手动输入版本号（不创建 tag）。

## 4. 需要的仓库密钥

在 `Settings → Secrets and variables → Actions` 里配置：

| Secret | 必需 | 用途 |
|---|---|---|
| `TELEGRAM_BOT_TOKEN` | 发通知必需 | @BotFather 创建 bot 后拿到的 token |
| `TELEGRAM_CHAT_ID` | 发通知必需 | 目标会话 id，多个用逗号分隔（私聊 / 群 / 频道；频道需把 bot 设为管理员） |
| `ANDROID_KEYSTORE_BASE64` | 可选 | release keystore 的 base64，配置后才产出已签名 APK/AAB |
| `ANDROID_KEYSTORE_PASSWORD` | 可选 | keystore 口令 |
| `ANDROID_KEY_ALIAS` | 可选 | 签名 key 别名 |
| `ANDROID_KEY_PASSWORD` | 可选 | 签名 key 口令 |

没有配置 Android 签名密钥时流水线不会失败，只产出 `*-unsigned.apk`。
本地签名仍可用仓库根目录的 `keystore.properties`（不进版本库）。

生成 base64（任选其一）：

```powershell
# Windows PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("D:\keys\boxlet-release.jks")) | Set-Clipboard
```

```bash
# Linux / macOS
base64 -w0 boxlet-release.jks      # macOS 用 base64 -i boxlet-release.jks
```

拿 `TELEGRAM_CHAT_ID`：把 bot 拉进目标会话，给它发一条消息，然后
`curl -s "https://api.telegram.org/bot<token>/getUpdates"` 里能看到 `chat.id`。

## 5. Telegram 通知

[`.github/scripts/notify-telegram.sh`](../.github/scripts/notify-telegram.sh) 负责发送：

- 消息内容是 HTML：版本号、成功/失败、仓库、Release 页、本次流水线地址；
- 顺手把构建出的 APK 作为文件发过去（Bot API 单文件上限 50MB，超限的附件会被跳过并打印原因，
  便携包/安装包通常超过这个上限，所以只发链接）；
- 密钥缺失时脚本直接跳过并打印提示，不会让流水线变红。
