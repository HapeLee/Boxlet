# Boxlet Windows 原生客户端

C# / Microsoft.UI.Reactor `0.1.0-preview.16`，实际控件和窗口均为 WinUI 3。
Mica、窗口圆角、最小化/最大化/还原和标题栏按钮由 Windows / Windows App SDK 处理。
没有 AWT 透明窗、窗口 region 裁剪或手写 Win32 WndProc。

## 启动

**普通用户请使用便携发布包，解压整个目录后双击 `Boxlet.Windows.exe`。不需要安装 .NET SDK、.NET Runtime、Windows App SDK 或 Java。**
下面的 `run.ps1` 是从源码开发/构建使用的入口，不是普通用户启动方式。

要求 Windows 11 22H2+、.NET 10 SDK、JDK 21；支持 x64。Reactor 目前为预览版，依赖版本已固定。
`run.ps1` 支持 `BOXLET_DOTNET_HOME`、`%LOCALAPPDATA%\BoxletBuildTools\dotnet`、PATH 上的 .NET 10 SDK，以及已有 BuildTools 目录内的独立 SDK。
脚本兼容 Windows PowerShell 5.1 和 PowerShell 7，可用 `.\windowsApp\run.ps1 -Diagnose` 查看 SDK 查找结果。

先从托盘退出旧 Compose 桌面端，然后在仓库根目录运行：

```powershell
.\windowsApp\run.ps1
# 构建并执行真实 JVM 引擎的 IPC 集成测试
.\windowsApp\run.ps1 -BuildOnly -Test
# Release 构建
.\windowsApp\run.ps1 -Configuration Release -BuildOnly
```

默认可执行文件：
`windowsApp/bin/x64/Debug/net10.0-windows10.0.26100.0/win-x64/Boxlet.Windows.exe`。

关闭窗口会收进系统托盘；点击托盘图标显示，右键可退出。
最小化按钮使用原生任务栏最小化。再次启动唤起已有窗口。
构建前退出应用，或调用正在运行版本的 `Boxlet.Windows.exe --exit` 优雅退出并停止引擎。
窗口动画遵循 Windows 自身的动画设置，未修改系统设置。

## 架构与数据

`WinUI/Reactor → EngineClient (UTF-8 JSON Lines) → engine-host → app-shared → core`。
构建自动复制引擎 JAR 到输出目录的 `engine/lib`，引擎作为无控制台子进程启动。
Java 优先使用应用目录的 `runtime/bin/java.exe`，开发构建再查找 `BOXLET_JAVA_HOME`、`JAVA_HOME` 和 PATH。
应用退出会发送 `shutdown`，超时才结束子进程。

配置、证书、收藏和接收历史沿用 `%APPDATA%\Boxlet`，与 Compose 端兼容。
默认保存目录为 `~/Downloads/Boxlet`。旧端和新端应轮流运行，避免共享端口与配置文件。

已接入发送（文件/媒体/文本/文件夹/应用包/剪贴板）、拖放、多文件队列、传输进度/取消、接收确认、发送 PIN、收藏、历史、设置、浏览器分享和浏览器接入。
文本与文件目前分别选择；选择文本会替换文件队列。

## 分发

当前使用无需安装的 unpackaged 模式，不需要开启 Windows 开发者模式。两种产物都自包含 .NET、Windows App SDK、引擎与 OpenJDK 运行时，用户机器上不需要装任何东西。

### 便携包（免安装）

`publish.ps1` 生成完整便携目录和 ZIP。请分发整个 ZIP，不能只分发 exe。每次输出到新的 `artifacts/Boxlet-windows-x64-时间戳` 目录。
默认从 Gradle 工具链中选择 Eclipse Temurin JDK，也可通过 `-JavaHome` 指定允许再分发的 x64 OpenJDK。运行时的许可证保留在 `runtime/legal`。

```powershell
.\windowsApp\publish.ps1
# 指定打包使用的 OpenJDK
.\windowsApp\publish.ps1 -JavaHome C:\path\to\temurin-jdk
```

### 安装包（带卸载）

`pack.ps1` 在便携目录的基础上用 Inno Setup 6 打出单文件安装包 `artifacts/Boxlet-Setup-<版本>-x64.exe`：

- 按用户安装到 `%LOCALAPPDATA%\Programs\Boxlet`，默认不需要管理员权限；
- 创建开始菜单快捷方式，桌面图标可选；
- 在「设置 → 应用 → 已安装的应用」里注册卸载项，卸载程序是安装目录下的 `unins000.exe`；
- 升级安装会先调用 `Boxlet.Windows.exe --exit` 让旧实例优雅退出，再覆盖文件；
- 卸载结束会询问是否一并删除 `%APPDATA%\Boxlet` 里的配置、证书、收藏与接收历史（静默卸载时不询问，默认保留）。

需要先装编译工具（只需一次）：`winget install --id JRSoftware.InnoSetup -e`。
Inno Setup 不自带简体中文词条，`Languages\ChineseSimplified.isl` 存在时安装向导才是中文，否则回落英文。

```powershell
# 打包便携 + 安装包（默认版本 1.0.0）
.\windowsApp\pack.ps1

# 指定版本与打包用的 OpenJDK
.\windowsApp\pack.ps1 -Version 1.1.0 -JavaHome C:\path\to\temurin-jdk

# 复用已有的便携目录，只重打安装包（调试 installer.iss 时最快）
.\windowsApp\pack.ps1 -SkipPublish -Source artifacts\Boxlet-windows-x64-20261022-024934

# ISCC.exe 不在默认搜索路径时手动指定
.\windowsApp\pack.ps1 -Iscc "D:\Tools\Inno Setup 6\ISCC.exe"
```

脚本与配置：`publish.ps1`（便携）、`installer.iss`（Inno Setup 安装脚本）、`pack.ps1`（便携 + 安装一条龙）。

旧 Compose 桌面端（`desktopApp`）仍可用 `.\gradlew.bat :desktopApp:packageMsi` 单独出 MSI，但两个客户端不要同时运行。

`tests/EngineSmoke` 使用独立临时配置和随机端口，验证并发请求关联、中文 UTF-8 设置、错误响应、2 MiB 文件分享的 SHA-256、bridge 启停和优雅退出，不修改个人配置。
窗口视觉效果和动画由使用者手动验收。
