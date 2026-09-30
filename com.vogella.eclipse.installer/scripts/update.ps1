# Swaps in the new installer once the old one has exited, then starts it.
param(
    [string]$Pids,
    [string]$Root,
    [string]$Next,
    # One installer argument per line, because -File would read arguments like -vm as parameters.
    [string]$ArgumentsFile
)
$ErrorActionPreference = "Stop"
$Pids -split " " | ForEach-Object { Wait-Process -Id $_ -ErrorAction SilentlyContinue }
# The new folder lies in an update folder of its own, which also takes the old installer and is deleted at the end.
$staging = Split-Path -Parent $Next
$old = Join-Path $staging "old"
# Moves are retried because virus scanners hold fresh files.
for ($i = 1; ; $i++) {
    try { Move-Item -Path $Root -Destination $old; break }
    catch { if ($i -ge 30) { exit 1 }; Start-Sleep -Seconds 1 }
}
try { Move-Item -Path $Next -Destination $Root }
catch { Move-Item -Path $old -Destination $Root; exit 1 }
$arguments = @(Get-Content -Encoding UTF8 $ArgumentsFile | ForEach-Object { '"' + $_.Replace('"', '') + '"' })
Start-Process -FilePath (Join-Path $Root "eclipse.exe") -ArgumentList $arguments
Remove-Item -Recurse -Force $staging
Remove-Item -Force $ArgumentsFile, $PSCommandPath
