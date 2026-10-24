#Requires -Version 7.0
param([string]$Launcher)
$ErrorActionPreference = 'Stop'
$windowsRoot = Split-Path $PSScriptRoot -Parent
. (Join-Path $windowsRoot 'Build-Launcher.ps1')
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('boxlet-launcher-test-' + [guid]::NewGuid().ToString('N'))
$testRoot = [IO.Path]::GetFullPath($testRoot)
New-Item -ItemType Directory -Path $testRoot | Out-Null

function Assert-OwnedPath([string]$Path) {
    $full = [IO.Path]::GetFullPath($Path)
    if (-not $full.StartsWith($testRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Test path escaped its private directory: $full"
    }
}

function Run-Launcher([string]$Directory, [string[]]$Arguments, [int]$ExpectedExit) {
    $info = [Diagnostics.ProcessStartInfo]::new((Join-Path $Directory 'Boxlet.exe'))
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
    $info.WorkingDirectory = $testRoot # Deliberately different from the package directory.
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::Start($info)
    try {
        if (-not $process.WaitForExit(15000)) { $process.Kill($true); throw 'Launcher did not exit.' }
        if ($process.ExitCode -ne $ExpectedExit) { throw "Unexpected launcher exit: $($process.ExitCode), expected $ExpectedExit" }
    } finally { $process.Dispose() }
}

try {
    $package = Join-Path $testRoot 'original package'
    $payload = Join-Path $package 'app'
    New-Item -ItemType Directory -Path $payload | Out-Null
    if ($Launcher) { Copy-Item -LiteralPath $Launcher -Destination (Join-Path $package 'Boxlet.exe') }
    else { Build-BoxletLauncher -OutputPath (Join-Path $package 'Boxlet.exe') -Version '1.0.0' -Semver '1.0.0' }

    $probe = Join-Path $testRoot 'Probe.cs'
    @'
using System;
using System.IO;
using System.Linq;
using System.Text;
class Probe {
    static int Main(string[] args) {
        string[] values = new[] { Environment.CurrentDirectory }.Concat(args.Skip(1)).ToArray();
        File.WriteAllLines(args[0] + ".tmp", values.Select(value => Convert.ToBase64String(Encoding.UTF8.GetBytes(value))).ToArray());
        File.Move(args[0] + ".tmp", args[0]);
        return 17;
    }
}
'@ | Set-Content -LiteralPath $probe -Encoding utf8
    $compiler = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
    & $compiler /nologo /target:winexe /reference:System.Core.dll "/out:$(Join-Path $payload 'Boxlet.Windows.exe')" $probe
    if ($LASTEXITCODE) { throw 'Probe compilation failed.' }

    # Relocate before launching, including spaces and non-ASCII characters in the package path.
    $relocated = Join-Path $testRoot '搬家后的 Boxlet'
    Assert-OwnedPath $package
    Assert-OwnedPath $relocated
    Move-Item -LiteralPath $package -Destination $relocated
    $payload = Join-Path $relocated 'app'
    $values = @('', 'hello world', '中文路径\文件.txt', 'quote"inside', 'space and trailing slash\', '\\server\a b\', 'a\"b')
    foreach ($exiting in @($false, $true)) {
        $record = Join-Path $testRoot ("result-$exiting.txt")
        $expected = @(if ($exiting) { '--exit' }) + $values
        Run-Launcher $relocated (@($record) + $expected) $(if ($exiting) { 17 } else { 0 })
        $deadline = [DateTime]::UtcNow.AddSeconds(10)
        while (-not (Test-Path -LiteralPath $record)) {
            if ([DateTime]::UtcNow -gt $deadline) { throw 'Child process did not write its argument report.' }
            Start-Sleep -Milliseconds 20
        }
        $actual = @(Get-Content -LiteralPath $record | ForEach-Object { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($_)) })
        if ($actual[0] -ne $payload -or $actual.Count -ne $expected.Count + 1) { throw 'Working directory or argument count changed.' }
        for ($index = 0; $index -lt $expected.Count; $index++) {
            if ($actual[$index + 1] -cne $expected[$index]) { throw "Argument $index was not forwarded exactly." }
        }
    }

    $broken = Join-Path $testRoot 'missing app'
    New-Item -ItemType Directory -Path $broken | Out-Null
    Copy-Item -LiteralPath (Join-Path $relocated 'Boxlet.exe') -Destination $broken
    Run-Launcher $broken @('--exit') 1
    Write-Host 'PASS: relocated launcher, Unicode paths, working directory, exact argument forwarding, --exit wait/exit code, incomplete package.'
} finally {
    $resolved = (Resolve-Path -LiteralPath $testRoot).Path
    if ($resolved -ne $testRoot -or -not (Split-Path $resolved -Leaf).StartsWith('boxlet-launcher-test-')) {
        throw 'Refusing to clean an unexpected test directory.'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
