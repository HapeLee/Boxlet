# Build the Boxlet Windows installer (Inno Setup 6) from a self-contained publish folder.
#
# The installer is per-user: it drops the portable folder into %LOCALAPPDATA%\Programs\Boxlet,
# adds Start menu / optional desktop shortcuts and registers an uninstaller that Windows shows
# in "Apps & features". The portable ZIP from publish.ps1 is still the no-install option.
#
# Examples:
#   .\windowsApp\pack.ps1
#   .\windowsApp\pack.ps1 -Version 1.1.0 -JavaHome C:\path\to\temurin-jdk
#   .\windowsApp\pack.ps1 -SkipPublish -Source artifacts\Boxlet-windows-x64-1.1.0-20261022-024934
# 版本由根 Gradle 配置统一解析：显式参数、BOXLET_VERSION、git tag。
param(
    [string]$Version,
    [string]$JavaHome,
    [string]$Source,
    [string]$Iscc,
    [switch]$SkipPublish
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Resolve-Version.ps1')
$requestedVersion = $Version
$buildVersion = Resolve-BoxletVersion -Version $Version
$Version = $buildVersion.Display
$root = Split-Path $PSScriptRoot -Parent
$artifacts = Join-Path $root 'artifacts'
if (-not (Test-Path -LiteralPath $artifacts)) {
    New-Item -ItemType Directory -Path $artifacts | Out-Null
}

# 1. Portable publish (same output publish.ps1 produces, reuse it to keep one code path).
if (-not $Source) {
    if ($SkipPublish) { throw 'Pass -Source together with -SkipPublish.' }
    $publish = Join-Path $PSScriptRoot 'publish.ps1'
    if ($JavaHome) { & $publish -Version $requestedVersion -JavaHome $JavaHome } else { & $publish -Version $requestedVersion }
    if ($LASTEXITCODE) { throw 'Portable publish failed.' }
    $Source = Get-ChildItem -LiteralPath $artifacts -Directory -Filter "Boxlet-windows-x64-$Version-*" |
        Sort-Object LastWriteTime | Select-Object -Last 1 -ExpandProperty FullName
}
$exe = if ($Source) { Join-Path $Source 'Boxlet.Windows.exe' } else { $null }
if (-not $exe -or -not (Test-Path -LiteralPath $exe)) {
    throw "Portable folder not found or incomplete: $Source"
}

# 2. Locate the Inno Setup compiler.
if (-not $Iscc) {
    $candidates = @()
    $command = Get-Command iscc.exe -ErrorAction SilentlyContinue
    if ($command) { $candidates += $command.Source }
    foreach ($folder in @($env:ProgramFiles, ${env:ProgramFiles(x86)}, $env:LOCALAPPDATA)) {
        if ($folder) { $candidates += Join-Path $folder 'Inno Setup 6\ISCC.exe' }
    }
    $candidates += Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe'
    $Iscc = $candidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
}
if (-not $Iscc) {
    throw 'Inno Setup 6 not found. Install it with: winget install --id JRSoftware.InnoSetup -e'
}

# 3. Compile. Paths and version travel through the environment so spaces in the
#    source folder do not get split into separate ISCC arguments.
$isccArgs = @("/O$artifacts", "/FBoxlet-Setup-$Version-x64")
$zhMessages = Join-Path (Split-Path $Iscc -Parent) 'Languages\ChineseSimplified.isl'
if (Test-Path -LiteralPath $zhMessages) { $isccArgs += '/DHAS_ZH' }
$isccArgs += (Join-Path $PSScriptRoot 'installer.iss')

$previousSource = $env:BOXLET_APPSOURCE
$previousVersion = $env:BOXLET_VERSION
$previousSemver = $env:BOXLET_VERSION_SEMVER
$env:BOXLET_APPSOURCE = $Source
$env:BOXLET_VERSION = $Version
$env:BOXLET_VERSION_SEMVER = $buildVersion.Semver
try {
    & $Iscc @isccArgs
    if ($LASTEXITCODE) { throw 'Installer compilation failed.' }
} finally {
    $env:BOXLET_APPSOURCE = $previousSource
    $env:BOXLET_VERSION = $previousVersion
    $env:BOXLET_VERSION_SEMVER = $previousSemver
}

$setup = Join-Path $artifacts "Boxlet-Setup-$Version-x64.exe"
if (-not (Test-Path -LiteralPath $setup)) { throw "Installer not produced: $setup" }
Write-Host "Portable folder: $Source"
Write-Host "Installer: $setup"
