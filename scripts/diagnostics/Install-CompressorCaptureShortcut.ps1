#Requires -Version 5
<#
.SYNOPSIS
  Create a Desktop shortcut that launches the autonomous Galaxy Compressor capture.

.DESCRIPTION
  Opt-in convenience only. Creates "Galaxy Compressor Capture.lnk" on the current user's Desktop
  pointing at Start-CompressorCapture.cmd. Requires no administrator rights, installs nothing into
  startup, and starts no capture on its own -- double-clicking the shortcut is the only trigger.
  Remove it with Remove-CompressorCaptureTask.ps1 -Shortcut (or just delete the .lnk).

.PARAMETER Environment
  Optional default profile passed to the launcher (Auto, Normal, or SecondaryProfile).
#>
[CmdletBinding()]
param(
    [ValidateSet("Auto", "Normal", "SecondaryProfile")]
    [string]$Environment = "Auto"
)
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$launcher = Join-Path $PSScriptRoot "Start-CompressorCapture.cmd"
if (-not (Test-Path -LiteralPath $launcher)) {
    throw "Launcher not found next to this script: $launcher"
}

$desktop = [Environment]::GetFolderPath("Desktop")
$linkPath = Join-Path $desktop "Galaxy Compressor Capture.lnk"

$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($linkPath)
$shortcut.TargetPath = $launcher
if ($Environment -ne "Auto") { $shortcut.Arguments = $Environment }
$shortcut.WorkingDirectory = $PSScriptRoot
$shortcut.Description = "Autonomous Galaxy Compressor batch capture ($Environment)"
$shortcut.Save()

Write-Host "Created shortcut: $linkPath"
Write-Host "Double-click it, wait for CAPTURE ARMED, then run a batch. No AI session required."
