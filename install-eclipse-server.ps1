<#
.SYNOPSIS
Downloads the vogella Eclipse installer and runs it, with its window or headless.
The install-eclipse-local.ps1 launcher runs this script from the latest GitHub release.

.DESCRIPTION
The installer extracts an Eclipse application zip into the target folder if that folder is missing or
empty, and installs or updates the latest version of a set of features in it.
This script only checks Java, fetches and caches the installer zip, and starts it: with -Headless it runs
in the console, otherwise it opens the installer window.
Options that are not given fall back to the installer's defaults.
Java 21 or newer must be on the PATH, or JavaHome has to point at it (the JDK folder or its bin folder).

.EXAMPLE
.\install-eclipse-local.ps1
Opens the installer window, using the latest installer release.

.EXAMPLE
.\install-eclipse-local.ps1 -InstallerUrl .\eclipse-installer-win32.win32.x86_64.zip
Opens the window of a locally built installer.

.EXAMPLE
.\install-eclipse-local.ps1 -InstallDir C:\eclipse\sdk -Headless -Clean
Installs from scratch in the console, reusing the cached downloads.

.EXAMPLE
.\install-eclipse-local.ps1 -InstallDir C:\eclipse\sdk -Headless -Repositories C:\sites\egit-site.zip,https://vogellacompany.github.io/eclipse-themes/
Installs from a zipped p2 update site next to a remote one.
#>
[CmdletBinding()]
param(
    # URL or local path of the installer zip; by default the latest GitHub release.
    [string]$InstallerUrl = "https://github.com/vogellacompany/eclipse-installer/releases/latest/download/eclipse-installer-win32.win32.x86_64.zip",
    [string]$InstallDir,
    [switch]$Headless,
    [string]$ApplicationUrl,
    # Application name shown by the installer.
    [string]$Name,
    [string[]]$Repositories,
    [string[]]$Features,
    # Downloads and the extracted installer are kept here and reused on the next run.
    [string]$CacheDir = (Join-Path $env:LOCALAPPDATA "eclipse-installer"),
    [string]$JavaHome,
    # Deletes the installation before installing; downloaded zips are kept.
    [switch]$Clean
)

$ErrorActionPreference = "Stop"

# Runs a native command without turning its stderr (curl's progress meter, launcher messages) into terminating errors.
function Invoke-Native([scriptblock]$Command) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { & $Command } finally { $ErrorActionPreference = $previous }
}

# Returns the JDK folder of JavaHome (the JDK folder or its bin folder) or of the PATH, refusing anything older than Java 21.
# The installer cannot report this itself: its launcher jar needs Java 21 to load at all.
function Resolve-Jdk {
    if ($JavaHome) {
        $jdk = $JavaHome.TrimEnd("\")
        if ((Split-Path -Leaf $jdk) -eq "bin") { $jdk = Split-Path -Parent $jdk }
        if (-not (Test-Path (Join-Path $jdk "bin\java.exe"))) { throw "No java.exe in $jdk\bin" }
    } else {
        $command = Get-Command java.exe -ErrorAction SilentlyContinue
        if (-not $command) { throw "No Java on the PATH, install Java 21 or newer or pass -JavaHome" }
        $jdk = Split-Path -Parent (Split-Path -Parent $command.Source)
    }
    $release = Join-Path $jdk "release"
    if (Test-Path $release) {
        $match = Select-String -Path $release -Pattern '^JAVA_VERSION="([^"]+)"' | Select-Object -First 1
        if ($match) {
            $version = $match.Matches[0].Groups[1].Value
            $major = if ($version.StartsWith("1.")) { [int]$version.Split(".")[1] } else { [int](($version -split "[.+-]")[0]) }
            if ($major -lt 21) { throw "Java $version in $jdk is too old, the installer needs Java 21 or newer; pass -JavaHome" }
        }
    }
    Write-Host "Using Java in $jdk"
    return $jdk
}

# Returns the local installer zip; $script:InstallerChanged tells whether it is new since the last run.
function Get-Installer([string]$Source) {
    if (Test-Path $Source) {
        # A local zip may have been rebuilt under the same name, so it is always extracted again.
        $script:InstallerChanged = $true
        return (Resolve-Path $Source).Path
    }
    $file = Join-Path $CacheDir ([IO.Path]::GetFileName(([Uri]$Source).AbsolutePath))
    $partial = "$file.part"
    $curl = Join-Path $env:SystemRoot "System32\curl.exe"
    if (Test-Path $curl) {
        # Downloads only if the release is newer than the cached zip.
        $condition = @()
        if (Test-Path $file) { $condition = @("--time-cond", $file) }
        $status = Invoke-Native { & $curl --fail --location --silent --show-error --remote-time @condition --output $partial --write-out "%{http_code}" $Source }
        if ($LASTEXITCODE -ne 0) { throw "Download of $Source failed (curl exit code $LASTEXITCODE)" }
        if ($status -eq "200" -and (Test-Path $partial) -and (Get-Item $partial).Length -gt 0) {
            Write-Host "Downloaded $Source"
            Move-Item -Force $partial $file
            $script:InstallerChanged = $true
        } else {
            Remove-Item -Force $partial -ErrorAction SilentlyContinue
        }
    } elseif (-not (Test-Path $file)) {
        Write-Host "Downloading $Source"
        # The progress bar slows Invoke-WebRequest down by an order of magnitude in Windows PowerShell.
        $ProgressPreference = "SilentlyContinue"
        Invoke-WebRequest -Uri $Source -OutFile $partial -UseBasicParsing
        Move-Item -Force $partial $file
        $script:InstallerChanged = $true
    }
    return $file
}

# Extracts $Zip into $Destination, dropping its single top-level folder, via $Destination.part so an interrupted run leaves nothing half-filled.
function Expand-Installer([string]$Zip, [string]$Destination) {
    $staging = "$Destination.part"
    if (Test-Path $staging) { Remove-Item -Recurse -Force $staging }
    Write-Host "Extracting $Zip"
    Expand-Archive -Path $Zip -DestinationPath $staging
    $root = $staging
    $entries = @(Get-ChildItem -Force $staging)
    if ($entries.Count -eq 1 -and $entries[0].PSIsContainer) { $root = $entries[0].FullName }
    if (Test-Path $Destination) { Remove-Item -Recurse -Force $Destination }
    Move-Item $root $Destination
    if (Test-Path $staging) { Remove-Item -Recurse -Force $staging }
}

# The installer takes URIs only: a Windows path becomes a file: URI, and a zipped p2 update site a jar:file:...!/ URI.
function ConvertTo-Location([string]$Location, [switch]$Repository) {
    # Two or more letters before the colon, so C:\ is taken as a path and not as a scheme.
    if ($Location -match '^[A-Za-z][A-Za-z0-9+.-]+:') { return $Location }
    $path = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Location)
    if (-not (Test-Path $path)) { throw "Not found: $path" }
    $uri = ([Uri]$path).AbsoluteUri
    if ($Repository -and (Test-Path -PathType Leaf $path)) {
        if ($path -notmatch '\.(zip|jar)$') { throw "$path is neither a folder nor a zipped p2 update site" }
        return "jar:$uri!/"
    }
    return $uri
}

$jdk = Resolve-Jdk
New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null

$script:InstallerChanged = $false
$zip = Get-Installer $InstallerUrl
$installerDir = Join-Path $CacheDir ("installer-" + [IO.Path]::GetFileNameWithoutExtension($zip))
if ($script:InstallerChanged -or $Clean -or -not (Test-Path (Join-Path $installerDir "eclipsec.exe"))) {
    Expand-Installer $zip $installerDir
}

# The script has just fetched the latest release, so the installer need not look for one.
$arguments = @("--no-update-check")
if ($InstallDir) { $arguments += @("--target", $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($InstallDir)) }
if ($Headless) { $arguments += "--headless" }
if ($Clean) { $arguments += "--clean" }
if ($ApplicationUrl) { $arguments += @("--application-url", (ConvertTo-Location $ApplicationUrl)) }
if ($Name) { $arguments += @("--name", $Name) }
if ($Repositories) {
    $sites = $Repositories | ForEach-Object { $_ -split "," } | Where-Object { $_.Trim() } |
        ForEach-Object { ConvertTo-Location $_.Trim() -Repository }
    $arguments += @("--repositories", ($sites -join ","))
}
if ($Features) { $arguments += @("--features", ($Features -join ",")) }
if ($PSBoundParameters.ContainsKey("CacheDir")) { $arguments += @("--cache-dir", $CacheDir) }

# No exit: this script runs in the launcher's session, which exits with $LASTEXITCODE.
if ($Headless) {
    Invoke-Native { & (Join-Path $installerDir "eclipsec.exe") -vm (Join-Path $jdk "bin\java.exe") @arguments }
    return
}
# A GUI executable returns right away, so the window stays open after this script ends.
& (Join-Path $installerDir "eclipse.exe") -vm (Join-Path $jdk "bin\javaw.exe") @arguments
$global:LASTEXITCODE = 0
