# Developer entry point. End users should launch the portable Boxlet.Windows.exe.
param(
    [ValidateSet('Debug', 'Release')][string]$Configuration = 'Debug',
    [switch]$BuildOnly,
    [switch]$Test,
    [switch]$Diagnose
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
. (Join-Path $PSScriptRoot 'Resolve-DotNet.ps1')
. (Join-Path $PSScriptRoot 'Resolve-Version.ps1')
$dotnet = Resolve-BoxletDotNet -Diagnose:$Diagnose
if ($Diagnose) { return }
$buildVersion = Resolve-BoxletVersion
Push-Location $root
try {
    & .\gradlew.bat :engine-host:installDist --console=plain
    if ($LASTEXITCODE) { throw 'Engine build failed.' }
    if ($Test) {
        & $dotnet run --project windowsApp/tests/EngineSmoke/EngineSmoke.csproj
        if ($LASTEXITCODE) { throw 'Engine IPC tests failed.' }
    }
    & $dotnet build windowsApp/Boxlet.Windows.csproj -p:Platform=x64 "-p:Version=$($buildVersion.Display)" -c $Configuration --nologo
    if ($LASTEXITCODE) { throw 'Windows build failed. Exit the running app from its tray menu before rebuilding.' }
    if (-not $BuildOnly) {
        $exe = Join-Path $PSScriptRoot "bin/x64/$Configuration/net10.0-windows10.0.26100.0/win-x64/Boxlet.Windows.exe"
        Start-Process -FilePath $exe -WorkingDirectory (Split-Path $exe) -WindowStyle Normal
    }
} finally { Pop-Location }
