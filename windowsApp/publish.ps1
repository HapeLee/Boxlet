# Build a portable x64 release with .NET, WinUI and an OpenJDK runtime.
# 版本由根 Gradle 配置统一解析：显式参数、BOXLET_VERSION、git tag。
param([string]$JavaHome, [string]$Version)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Resolve-Version.ps1')
$buildVersion = Resolve-BoxletVersion -Version $Version
$engineVersionArgs = @()
if ($Version) { $engineVersionArgs += "-PboxletVersion=$Version" }
$Version = $buildVersion.Display
$root = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'Resolve-DotNet.ps1')
. (Join-Path $PSScriptRoot 'Build-Launcher.ps1')
$dotnet = Resolve-BoxletDotNet
if (-not $JavaHome) {
    $gradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
    $jdk = Get-ChildItem -LiteralPath (Join-Path $gradleHome 'jdks') -Directory -Filter 'eclipse_adoptium*' -ErrorAction SilentlyContinue |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin/jlink.exe') } | Select-Object -First 1
    if ($jdk) { $JavaHome = $jdk.FullName }
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/jlink.exe'))) {
    throw 'Pass -JavaHome with an x64 OpenJDK JDK directory containing bin/jlink.exe. Use a JDK whose license permits redistribution.'
}
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$destination = Join-Path $root "artifacts/Boxlet-windows-x64-$Version-$stamp"
$payload = Join-Path $destination 'app'
Push-Location $root
try {
    & .\gradlew.bat @engineVersionArgs :engine-host:installDist --console=plain
    if ($LASTEXITCODE) { throw 'Engine build failed.' }
    & $dotnet publish windowsApp/Boxlet.Windows.csproj -c Release -p:Platform=x64 "-p:Version=$Version" -r win-x64 --self-contained true -o $payload --nologo
    if ($LASTEXITCODE) { throw 'Publish failed.' }
    if (-not (Test-Path -LiteralPath (Join-Path $payload 'Boxlet.Windows.pri'))) {
        throw 'Published WinUI resource index is missing.'
    }
    & (Join-Path $JavaHome 'bin/jlink.exe') --module-path (Join-Path $JavaHome 'jmods') --add-modules java.se,jdk.crypto.ec,jdk.unsupported,jdk.zipfs --strip-debug --no-header-files --no-man-pages --output (Join-Path $payload 'runtime')
    if ($LASTEXITCODE) { throw 'Bundled Java runtime creation failed.' }
    Build-BoxletLauncher -OutputPath (Join-Path $destination 'Boxlet.exe') -Version $Version -Semver $buildVersion.Semver
    @"
Boxlet $Version - Windows x64 portable release

Extract the entire folder, then open Boxlet.exe.
No .NET SDK, .NET Runtime, Windows App SDK or Java installation is needed.
Windows 11 22H2 or newer is required.
Keep the app folder beside Boxlet.exe. It contains all application and runtime files.
Close hides to the notification-area tray. Right-click the tray icon to exit.
Do not run this and the old Compose desktop client at the same time.

Third-party notices: see app/runtime/legal and app/ThirdPartyNotices.* where provided.
"@ | Set-Content -LiteralPath (Join-Path $destination 'START-HERE.txt') -Encoding ascii
    $archive = "$destination.zip"
    Compress-Archive -Path $destination -DestinationPath $archive -CompressionLevel Optimal
    Write-Host "Portable folder: $destination"
    Write-Host "Release archive: $archive"
} finally { Pop-Location }
