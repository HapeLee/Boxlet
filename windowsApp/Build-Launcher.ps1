# A small GUI bootstrapper using the .NET Framework shipped with Windows 11.
function Build-BoxletLauncher {
    param(
        [Parameter(Mandatory)][string]$OutputPath,
        [Parameter(Mandatory)][string]$Version,
        [Parameter(Mandatory)][ValidatePattern('^\d{1,2}\.\d{1,2}\.\d{1,2}$')][string]$Semver
    )
    $compiler = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
    if (-not (Test-Path -LiteralPath $compiler)) {
        throw 'The Windows .NET Framework C# compiler is required to build the portable launcher.'
    }
    $versionSource = Join-Path ([IO.Path]::GetTempPath()) ("boxlet-launcher-" + [guid]::NewGuid().ToString('N') + '.cs')
    $escapedVersion = $Version.Replace('\', '\\').Replace('"', '\"')
    $attributes = @"
using System.Reflection;
[assembly: AssemblyTitle("Boxlet")]
[assembly: AssemblyProduct("Boxlet")]
[assembly: AssemblyDescription("Boxlet portable launcher")]
[assembly: AssemblyVersion("$Semver.0")]
[assembly: AssemblyFileVersion("$Semver.0")]
[assembly: AssemblyInformationalVersion("$escapedVersion")]
"@
    try {
        [IO.File]::WriteAllText($versionSource, $attributes, [Text.UTF8Encoding]::new($false))
        & $compiler /nologo /target:winexe /optimize+ /platform:anycpu /codepage:65001 /reference:System.Windows.Forms.dll `
            "/win32icon:$(Join-Path $PSScriptRoot 'Assets/AppIcon.ico')" `
            "/win32manifest:$(Join-Path $PSScriptRoot 'app.manifest')" "/out:$OutputPath" `
            (Join-Path $PSScriptRoot 'launcher/Program.cs') $versionSource
        if ($LASTEXITCODE) { throw 'Portable launcher compilation failed.' }
    } finally {
        if (Test-Path -LiteralPath $versionSource) { Remove-Item -LiteralPath $versionSource }
    }
}
