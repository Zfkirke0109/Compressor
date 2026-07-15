#Requires -Version 5
<#
.SYNOPSIS
  Remove everything the opt-in persistence installers created.

.DESCRIPTION
  Unregisters the "GalaxyCompressorCapture" Scheduled Task (if present) and deletes the Desktop
  shortcut (if present). Safe to run repeatedly; missing items are ignored. Removes only what the
  installers create -- never touches the app, the device, or captured data.
#>
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$taskName = "GalaxyCompressorCapture"
if (Get-Command Get-ScheduledTask -ErrorAction SilentlyContinue) {
    $existing = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    if ($null -ne $existing) {
        Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
        Write-Host "Removed scheduled task '$taskName'."
    } else {
        Write-Host "No scheduled task '$taskName' to remove."
    }
} else {
    Write-Host "ScheduledTasks module unavailable; skipping task removal."
}

$linkPath = Join-Path ([Environment]::GetFolderPath("Desktop")) "Galaxy Compressor Capture.lnk"
if (Test-Path -LiteralPath $linkPath) {
    Remove-Item -LiteralPath $linkPath -Force
    Write-Host "Removed shortcut: $linkPath"
} else {
    Write-Host "No Desktop shortcut to remove."
}
