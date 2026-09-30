<#
.SYNOPSIS
Downloads the vogella Eclipse installer and runs it, with its window or headless.
The install-eclipse-local.ps1 launcher runs this script from the latest GitHub release.

.DESCRIPTION
The installer extracts an Eclipse application zip into the target folder if that folder is missing or
empty, and installs or updates the latest version of a set of features in it.
This script only checks Java, fetches and caches the installer zip, and starts it: with -Headless it runs
in the console, otherwise it opens the installer window.
Options that are not given fall back to the installer's defaults; without -InstallDir the installer uses
%LOCALAPPDATA%\Programs\<name>.
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
# Empty values are rejected, so an empty variable in a calling script cannot select a default that -Clean deletes.
param(
    # URL or local path of the installer zip; by default the latest GitHub release.
    [ValidateNotNullOrEmpty()][string]$InstallerUrl = "https://github.com/vogellacompany/eclipse-installer/releases/latest/download/eclipse-installer-win32.win32.x86_64.zip",
    [ValidateNotNullOrEmpty()][string]$InstallDir,
    [switch]$Headless,
    [ValidateNotNullOrEmpty()][string]$ApplicationUrl,
    # Application name shown by the installer.
    [ValidateNotNullOrEmpty()][string]$Name,
    [ValidateNotNullOrEmpty()][string[]]$Repositories,
    [ValidateNotNullOrEmpty()][string[]]$Features,
    # Downloads and the extracted installer are kept here and reused on the next run.
    [ValidateNotNullOrEmpty()][string]$CacheDir = (Join-Path $env:LOCALAPPDATA "eclipse-installer"),
    [ValidateNotNullOrEmpty()][string]$JavaHome,
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
        $folder = $JavaHome.TrimEnd("\")
        $java = @((Join-Path $folder "bin\java.exe"), (Join-Path $folder "java.exe")) | Where-Object { Test-Path $_ } | Select-Object -First 1
        if (-not $java) { throw "No java.exe in $folder or $folder\bin" }
    } else {
        $command = Get-Command java.exe -ErrorAction SilentlyContinue
        if (-not $command) { throw "No Java on the PATH, install Java 21 or newer or pass -JavaHome" }
        $java = $command.Source
    }
    # Asks Java itself, because java.exe on the PATH can be a shim such as Oracle's javapath folder.
    $properties = Invoke-Native { & $java -XshowSettings:properties -version 2>&1 } | ForEach-Object { "$_" }
    $jdkLine = $properties | Where-Object { $_ -match '^\s*java\.home = (.+)$' } | Select-Object -First 1
    $versionLine = $properties | Where-Object { $_ -match '^\s*java\.specification\.version = (.+)$' } | Select-Object -First 1
    if (-not $jdkLine -or -not $versionLine) { throw "Cannot read the Java version of $java" }
    $jdk = ($jdkLine -replace '^\s*java\.home = ', '').Trim()
    $version = ($versionLine -replace '^\s*java\.specification\.version = ', '').Trim()
    $major = if ($version.StartsWith("1.")) { [int]$version.Split(".")[1] } else { [int]($version.Split(".")[0]) }
    if ($major -lt 21) { throw "Java $version ($java) is too old, the installer needs Java 21 or newer; pass -JavaHome" }
    Write-Host "Using Java $version in $jdk"
    return $jdk
}

# A short key of the installer URL, so archive and extracted installer belong to exactly one URL.
function Get-Key([string]$Text) {
    $hash = [Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($Text))
    return (($hash[0..7] | ForEach-Object { $_.ToString("x2") }) -join "")
}

# Returns the local installer zip, downloading it only if the server has a newer one.
function Get-Installer([string]$Source) {
    if (Test-Path $Source) { return (Resolve-Path $Source).Path }
    $file = Join-Path $CacheDir ("installer-" + (Get-Key $Source) + ".zip")
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
        } else {
            Remove-Item -Force $partial -ErrorAction SilentlyContinue
        }
    } else {
        # Without curl.exe, HttpWebRequest asks for the zip only if the release is newer than the cached one.
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        $request = [Net.HttpWebRequest]::Create($Source)
        if (Test-Path $file) { $request.IfModifiedSince = (Get-Item $file).LastWriteTime }
        try {
            $response = $request.GetResponse()
        } catch [Net.WebException] {
            $status = $_.Exception.Response
            if ($status -and [int]$status.StatusCode -eq 304) { return $file }
            throw "Download of $Source failed: $($_.Exception.Message)"
        }
        try {
            $out = [IO.File]::Create($partial)
            try { $response.GetResponseStream().CopyTo($out) } finally { $out.Dispose() }
        } finally {
            $response.Dispose()
        }
        (Get-Item $partial).LastWriteTime = $response.LastModified
        Move-Item -Force $partial $file
        Write-Host "Downloaded $Source"
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
    $path = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Location)
    if (-not (Test-Path $path)) {
        # Two or more letters before the colon, so C:\ is taken as a path and not as a scheme.
        if ($Location -match '^[A-Za-z][A-Za-z0-9+.-]+:') { return $Location }
        throw "Not found: $path"
    }
    # "!" separates the archive from its entry in a jar: URI, so a literal one must be escaped.
    $uri = ([Uri]$path).AbsoluteUri.Replace("!", "%21")
    if ($Repository -and (Test-Path -PathType Leaf $path)) {
        if ($path -notmatch '\.(zip|jar)$') { throw "$path is neither a folder nor a zipped p2 update site" }
        return "jar:$uri!/"
    }
    return $uri
}

$jdk = Resolve-Jdk
New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null

$zip = Get-Installer $InstallerUrl
$installerDir = Join-Path $CacheDir ("installer-" + (Get-Key $InstallerUrl))
# Records which archive the folder came from, so a failed extraction is retried and a newer archive replaces it.
$marker = Join-Path $installerDir ".extracted-from"
$item = Get-Item $zip
$stamp = "$($item.FullName) $($item.LastWriteTimeUtc.Ticks) $($item.Length)"
$extracted = if (Test-Path $marker) { (Get-Content -Raw $marker).Trim() } else { "" }
if ($Clean -or $extracted -ne $stamp -or -not (Test-Path (Join-Path $installerDir "eclipsec.exe"))) {
    Expand-Installer $zip $installerDir
    Set-Content -Path $marker -Value $stamp -NoNewline
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

if ($Headless) {
    Invoke-Native { & (Join-Path $installerDir "eclipsec.exe") -vm (Join-Path $jdk "bin\java.exe") @arguments }
    # Run by the launcher, exit would end the launcher's session, which exits with $LASTEXITCODE itself.
    if ($MyInvocation.MyCommand.CommandType -eq "ExternalScript") { exit $LASTEXITCODE }
    return
}
# A GUI executable returns right away, so the window stays open after this script ends.
& (Join-Path $installerDir "eclipse.exe") -vm (Join-Path $jdk "bin\javaw.exe") @arguments
$global:LASTEXITCODE = 0
