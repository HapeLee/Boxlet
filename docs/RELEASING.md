# 版本号与发布

版本号只有**一个来源**：根目录 [`version.properties`](../version.properties)。所有端、所有产物、GitHub Release 和 Telegram 通知
都用同一个值，不再手改 `versionCode` / `packageVersion` / `AppVersion`。

## 1. 版本号规则

与 Matter 相同，通过四个属性维护版本：

```properties
VERSION_MAJOR=1
VERSION_MINOR=0
VERSION_PATCH=0
VERSION_SUFFIX=0
```

`VERSION_SUFFIX=0` 表示正式版；`1` 表示预发布。正式版 tag 为 `vMAJOR.MINOR.PATCH`，
预发布 tag 为 `vMAJOR.MINOR.PATCH-beta.N`，序号由已有 tag 的最大值加一，首次为 `beta.1`。
主、次、修订每段 0–99，不带前导零，且不能全为 0。不使用第 4 段：
同一版本内的修订直接递增 patch，`1.2.99`（`10299`）之后就是 `1.3.0`（`10300`）。

数值用**十进制拼接**，每段固定 2 位：

```
versionCode = major * 10_000 + minor * 100 + patch
```

| 版本 | versionCode | 说明 |
|---|---|---|
| `v0.1.0` | `100` | 第一个开发版 |
| `v1.0.0` | `10000` | 首个正式版 |
| `v1.2.3` | `10203` | 常规功能版：拼起来正好是 `1 02 03` |
| `v1.2.99` | `10299` | 下一次要为 `1.3.0` |

- 每段 0–99（固定 2 位十进制），最大值 `999999`，远低于 Android 的
  `versionCode` 上限 2100000000，不可能溢出；
- 数值随版本单调递增：`1.2.99`（`10299`）< `1.3.0`（`10300`）；
- 代价是 patch 只有 99 个位置。真到那一步，升 minor 即可，不需要第 4 段。

> 历史：最初按 Matter 的 `SoftwareVersion` 做 8 位位打包
> （`(major<<24)|(minor<<16)|(patch<<8)|tweak`，`v1.0.0` → `16777216`），
> 后来又试过 4 段十进制（`v1.0.0` → `1000000`）。位打包是固件/Windows
> `FILEVERSION` 的常规做法，但 Android 的 `versionCode` 只需要单调递增，
> 十进制拼接对人更直观，最终定为现在的三段拼接。
> `v0.0.1` 那个验证 Release 是位打包时代的产物（`versionCode = 256`），
> 之后所有版本都比它大，升级不受影响。

### 开发构建

本地始终从配置文件取基础版本。正式版配置下，不在对应 tag 上或工作区有改动时，展示用的版本号会带后缀，
**数值版本号不变**：

| 场景 | 展示版本 | versionCode |
|---|---|---|
| 干净地构建在 `v1.2.3` 上 | `1.2.3` | `10203` |
| `v1.2.3` 之后 7 个提交 | `1.2.3-dev.7+ga1b2c3d` | `10203` |
| 未提交的改动 | `1.2.3-dev.7+ga1b2c3d.dirty` | `10203` |
| 配置 `1.2.3`，尚无对应 tag（共 7 个提交） | `1.2.3-dev.7+ga1b2c3d` | `10203` |
| 配置 `1.2.3`、`VERSION_SUFFIX=1`，本地构建 | `1.2.3-beta.local` | `10203` |
| 同一预发布配置，CI 已有 `v1.2.3-beta.2` | `1.2.3-beta.3` | `10203` |

Release workflow 计算好版本后，通过 `BOXLET_VERSION` 注入四端，不带本地开发后缀。
这里只沿用 Matter 的配置文件与 beta 规则，**保留 Boxlet 的十进制 versionCode**，不改为提交数。
同一基础版本的 beta 与正式版共用数值编号；需要更大的 Android `versionCode` 时必须递增主、次或修订号。

### 各端的映射

| 端 | 字段 | 取值 |
|---|---|---|
| Android | `versionName` / `versionCode` | 展示版本 / 数值版本（debug 追加 `-debug` 后缀） |
| Compose 桌面 | `packageVersion` | `major.minor.patch`（MSI/DEB 只接受三段） |
| Windows 原生 | `Version`（FileVersion / InformationalVersion） | 数值资源取三段版本，信息版本保留开发后缀 |
| Windows 安装包 | `AppVersion` / `VersionInfoVersion` | 展示版本 / `major.minor.patch` |
| engine-host | jar `MANIFEST` 的 `Implementation-Version` | 展示版本 |
| 各端 UI | `dev.boxlet.core.BoxletBuild` | 展示版本 + 数值版本（构建期生成） |

## 2. 本地怎么用

```bash
# 查看当前统一版本号
./gradlew -q printBoxletVersion

# 临时覆盖版本号（不依赖 tag），用于本地验证签名包等
BOXLET_VERSION=1.2.3 ./gradlew :androidApp:assembleRelease
./gradlew '-PboxletVersion=1.2.3' printBoxletVersion
```

版本号解析在根 [`build.gradle.kts`](../build.gradle.kts)：
优先 `-PboxletVersion`，其次 `BOXLET_VERSION` 环境变量，最后读取 `version.properties`。
Git 只提供提交信息、开发后缀和已发布的 tag，不再决定基础版本。
生成的 `dev.boxlet.core.BoxletBuild` 落在 `build/generated/boxlet/kotlin/`，由
`:generateBoxletVersionSource` 负责，不要在源码里手写版本常量。

Windows 的 `run.ps1`、`publish.ps1` 与 `pack.ps1` 经 `Resolve-Version.ps1` 调用同一个 Gradle 解析任务；`-Version` 覆盖值也会传给引擎构建，避免界面、引擎和安装包版本不一致。

## 3. 发布流程

1. 修改 `version.properties`，修复递增 `VERSION_PATCH`，功能更新递增 `VERSION_MINOR`；
2. 提交并推送到 GitHub；
3. 打开 **Actions → Release → Run workflow**，选择对应分支并运行，无需填写版本号。

构建成功后，流水线自动在本次源码提交上创建 tag 与 GitHub Release。
正式版 tag 已存在时，在构建前失败并提示先升版本，不覆盖已发布版本。
当前 GitHub 远端只有 `v0.0.1`，配置中的 `1.0.0` 可以作为下次正式版本。
本地另有尚未推送的 `v1.0.0` tag，因此本地预览可能提示已存在；CI 以检出的远端 tag 为准。

预发布只需把 `VERSION_SUFFIX` 改为 `1`，同一入口会依次发布 `beta.1`、`beta.2` 等，
GitHub Release 标记为 pre-release；发布正式版时改回 `0`。

仍支持推送 tag 触发发布，但 tag 必须与该提交的 `version.properties` 基础版本和正式／预发布类型一致。
失败的 tag 流水线可直接重跑，已存在的对应 Release 会替换同名产物。

流水线（[`.github/workflows/release.yml`](../.github/workflows/release.yml)）：

1. `prepare` 检出完整历史，调用 `printBoxletReleaseVersion`，从配置计算版本并校验 tag；
2. `android` / `windows` / `web` / `engine` 四路并行构建，全部带上同一个 `BOXLET_VERSION`；
3. `release` 汇总产物，创建对应 tag 的 GitHub Release，并附上生成的更新说明；
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

Windows ZIP 解压后从顶层 `Boxlet.exe` 启动，依赖、语言资源、引擎和运行时都在 `app/`，须完整保留。打包前可运行 `windowsApp/tests/LauncherSmoke.ps1`（PowerShell 7）验证移动目录、中文路径、参数转发和 `--exit`；CI 与 Release 的 Windows 构建已接入该检查。

不打算发 Release 时，运行普通 CI 或本地构建即可。`printBoxletReleaseVersion` 只计算版本和校验 tag，
不会创建 tag 或发布；手动执行「Actions → Release → Run workflow」会实际构建并发布。

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
