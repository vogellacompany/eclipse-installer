<#
.SYNOPSIS
Runs the latest vogella Eclipse installer script from GitHub with all arguments passed through.

.DESCRIPTION
Keep this small launcher; the installer script it runs is updated through the GitHub release.
The last downloaded script is cached and used when GitHub cannot be reached.

.EXAMPLE
.\install-eclipse-local.ps1 -InstallDir C:\eclipse\sdk
#>
param(
    [string]$ScriptUrl = "https://github.com/vogellacompany/eclipse-installer/releases/latest/download/install-eclipse-server.ps1",
    # Uses the cached script without looking for a newer one.
    [switch]$NoUpdate
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$cacheDir = Join-Path $env:LOCALAPPDATA "eclipse-installer"
$cached = Join-Path $cacheDir "install-eclipse-server.ps1"
New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null

if (-not $NoUpdate) {
    $partial = "$cached.part"
    try {
        if (Test-Path $ScriptUrl) { Copy-Item $ScriptUrl $partial }
        else { Invoke-WebRequest -Uri $ScriptUrl -OutFile $partial -UseBasicParsing }
        Move-Item -Force $partial $cached
    } catch {
        Remove-Item -Force $partial -ErrorAction SilentlyContinue
        if (-not (Test-Path $cached)) { throw "Cannot download $ScriptUrl and no cached copy exists: $_" }
        Write-Warning "Cannot download $ScriptUrl, using the cached copy: $_"
    }
}
if (-not (Test-Path $cached)) { throw "No cached installer script in $cacheDir, run without -NoUpdate first" }

# Runs in memory, which RemoteSigned does not block; @args keeps named parameters and switches intact.
$code = [IO.File]::ReadAllText($cached, [Text.Encoding]::UTF8)
$global:LASTEXITCODE = 0
& ([scriptblock]::Create($code)) @args
exit $LASTEXITCODE
