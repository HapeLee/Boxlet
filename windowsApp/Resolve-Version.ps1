# Keep native builds and packaging on the root Gradle/git-tag version source.
function Resolve-BoxletVersion {
    param([string]$Version)

    $versionRoot = Split-Path $PSScriptRoot -Parent
    $versionArgs = @('-q', 'printBoxletVersion', '--console=plain')
    if ($Version) { $versionArgs += "-PboxletVersion=$Version" }
    Push-Location $versionRoot
    try {
        $versionOutput = & .\gradlew.bat @versionArgs
        if ($LASTEXITCODE) { throw 'Version resolution failed.' }
    } finally { Pop-Location }

    $display = $versionOutput | Where-Object { $_ -cmatch '^BOXLET_VERSION=' } | Select-Object -Last 1
    $semver = $versionOutput | Where-Object { $_ -cmatch '^BOXLET_VERSION_SEMVER=' } | Select-Object -Last 1
    if (-not $display -or -not $semver) { throw 'Gradle did not return the Boxlet version.' }
    [pscustomobject]@{
        Display = $display.Substring('BOXLET_VERSION='.Length)
        Semver = $semver.Substring('BOXLET_VERSION_SEMVER='.Length)
    }
}
