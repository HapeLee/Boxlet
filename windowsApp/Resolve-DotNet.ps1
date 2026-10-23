# ASCII source so Windows PowerShell 5.1 and PowerShell 7 parse this identically.
function Resolve-BoxletDotNet {
    param([switch]$Diagnose)
    $candidates = @()
    if ($env:BOXLET_DOTNET_HOME) {
        $configured = [Environment]::ExpandEnvironmentVariables($env:BOXLET_DOTNET_HOME.Trim().Trim('"'))
        if ($configured.EndsWith('.exe', [StringComparison]::OrdinalIgnoreCase)) { $candidates += $configured }
        else { $candidates += Join-Path $configured 'dotnet.exe' }
    }
    foreach ($folder in @('BoxletBuildTools', 'SendeeBuildTools', 'Microsoft')) {
        $candidates += Join-Path $env:LOCALAPPDATA "$folder/dotnet/dotnet.exe"
    }
    $command = Get-Command dotnet.exe -ErrorAction SilentlyContinue
    if ($command) { $candidates += $command.Source }
    foreach ($folder in @($env:ProgramFiles, ${env:ProgramFiles(x86)})) {
        if ($folder) { $candidates += Join-Path $folder 'dotnet/dotnet.exe' }
    }
    Get-ChildItem -LiteralPath $env:LOCALAPPDATA -Directory -Filter '*BuildTools*' -ErrorAction SilentlyContinue |
        ForEach-Object { $candidates += Join-Path $_.FullName 'dotnet/dotnet.exe' }
    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            if ($Diagnose) { Write-Host "Not found: $candidate" }; continue
        }
        try { $sdks = @(& $candidate --list-sdks 2>&1) }
        catch { if ($Diagnose) { Write-Host "Cannot run: $candidate ($_)" }; continue }
        if ($sdks | Where-Object { "$_" -match '^\s*10\.\d+\.\d+\s+\[' }) {
            if ($Diagnose) { Write-Host "Using SDK: $candidate"; $sdks | ForEach-Object { Write-Host "  $_" } }
            return $candidate
        }
        if ($Diagnose) { Write-Host "No .NET 10 SDK: $candidate" }
    }
    throw 'A .NET 10 SDK is required to BUILD from source. Set BOXLET_DOTNET_HOME to its directory, or use a portable release (no SDK needed).'
}
