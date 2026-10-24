; Boxlet Windows 安装包（Inno Setup 6）
;
; 由 pack.ps1 调用（它用环境变量传目录与版本，避免路径里的空格被当成参数分隔）：
;   $env:BOXLET_APPSOURCE = "D:\Project\Sendee\artifacts\Boxlet-windows-x64-20261022-024934"
;   $env:BOXLET_VERSION = "1.0.0"
;   ISCC.exe /O"artifacts" /F"Boxlet-Setup-1.0.0-x64" installer.iss
;
; 安装到当前用户目录（无需管理员），卸载走 Windows「应用和功能」，
; 卸载时会询问是否一并删除 %APPDATA%\Boxlet 里的配置、证书与接收历史。

#ifndef AppSource
  #if GetEnv("BOXLET_APPSOURCE") != ""
    #define AppSource GetEnv("BOXLET_APPSOURCE")
  #else
    #define AppSource "..\artifacts\Boxlet-windows-x64"
  #endif
#endif

#ifndef AppVersion
  #if GetEnv("BOXLET_VERSION") != ""
    #define AppVersion GetEnv("BOXLET_VERSION")
  #else
    ; 没有 BOXLET_VERSION 时用开发基线，正式发布一定由 pack.ps1 / CI 传入
    #define AppVersion "0.1.0"
  #endif
#endif

#if GetEnv("BOXLET_VERSION_SEMVER") != ""
  #define AppNumericVersion GetEnv("BOXLET_VERSION_SEMVER")
#else
  #define AppNumericVersion AppVersion
#endif

#ifndef AppPublisher
  #define AppPublisher "Boxlet"
#endif

[Setup]
; 固定 AppId：升级安装会复用同一条卸载记录，不要改动（左大括号需要写两个）
AppId={{6F1D2B0A-5C4E-4E7B-9E31-6B8C9A3F4D27}
AppName=Boxlet
AppVersion={#AppVersion}
AppVerName=Boxlet {#AppVersion}
AppPublisher={#AppPublisher}
; 安装包 exe 的版本资源，脚本传入的已经是 tag 解析出的数值版本（主.次.修订）
VersionInfoVersion={#AppNumericVersion}

; 便携包本身就是自包含的（.NET + Windows App SDK + JDK 运行时），这里只负责落盘与卸载
DefaultDirName={localappdata}\Programs\Boxlet
DefaultGroupName=Boxlet
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog

ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.22621

OutputBaseFilename=Boxlet-Setup-{#AppVersion}-x64
SetupIconFile=..\design\brand\boxlet.ico
UninstallDisplayIcon={app}\Boxlet.exe
UninstallDisplayName=Boxlet
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
RestartApplications=no
LicenseFile=..\LICENSE

[Languages]
; 简体中文词条不在 Inno Setup 安装包里，pack.ps1 检测到才启用（HAS_ZH），否则回落英文
#ifdef HAS_ZH
Name: "chinesesimplified"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"
#endif
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
; 整个便携目录原样安装：顶层启动器及 app 子目录都不能少
Source: "{#AppSource}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Boxlet"; Filename: "{app}\Boxlet.exe"; WorkingDir: "{app}"
Name: "{group}\{cm:UninstallProgram,Boxlet}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\Boxlet"; Filename: "{app}\Boxlet.exe"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\Boxlet.exe"; Description: "{cm:LaunchProgram,Boxlet}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; 运行时可能在安装目录里留下日志或缓存，卸载时一并清掉
Type: filesandordirs; Name: "{app}"

[Code]
{ 升级安装前让正在运行的实例优雅退出，否则替换文件会失败 }
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ResultCode: Integer;
  Launcher: String;
begin
  Result := '';
  Launcher := ExpandConstant('{app}\Boxlet.exe');
  if not FileExists(Launcher) then
    Launcher := ExpandConstant('{app}\Boxlet.Windows.exe');
  if FileExists(Launcher) then
  begin
    Exec(Launcher, '--exit', '', SW_HIDE,
      ewWaitUntilTerminated, ResultCode);
    Sleep(1200);
  end;
end;

{ 卸载后询问是否删除用户数据：配置、证书、收藏与接收历史都在 %APPDATA%\Boxlet }
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  DataDir: String;
begin
  if CurUninstallStep <> usPostUninstall then
    Exit;
  DataDir := ExpandConstant('{userappdata}\Boxlet');
  if not DirExists(DataDir) then
    Exit;
  if UninstallSilent then
    Exit;
  if MsgBox('是否同时删除 Boxlet 的配置、证书与接收历史？' + #13#10 + #13#10 + DataDir,
    mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
    DelTree(DataDir, True, True, True);
end;
