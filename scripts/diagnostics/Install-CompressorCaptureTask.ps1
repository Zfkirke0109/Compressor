#Requires -Version 5
<#
.SYNOPSIS
  Register an OPT-IN, per-user Scheduled Task that can launch the capture at logon.

.DESCRIPTION
  Creates a Scheduled Task "GalaxyCompressorCapture" that runs the capture launcher in the current
  user's interactive session. Safety choices:
    * No administrator rights (registered in the current user context, runs only when this user is
      logged on, RunLevel Limited).
    * DISABLED by default so it never starts a capture silently. Pass -EnableAtLogon to enable the
      logon trigger deliberately; otherwise the task exists but does nothing until you enable it in
      Task Scheduler or re-run with -EnableAtLogon.
  Remove everything it creates with Remove-CompressorCaptureTask.ps1.

.PARAMETER Environment
  Profile passed to the launcher (Auto, Normal, SecondaryProfile).

.PARAMETER EnableAtLogon
  Enable the at-logon trigger now. Omit to register the task disabled (recommended default).
#>
[CmdletBinding()]
param(
    [ValidateSet("Auto", "Normal", "SecondaryProfile")]
    [string]$Environment = "Auto",
    [switch]$EnableAtLogon
)
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$taskName = "GalaxyCompressorCapture"
$launcher = Join-Path $PSScriptRoot "Start-CompressorCapture.cmd"
if (-not (Test-Path -LiteralPath $launcher)) {
    throw "Launcher not found next to this script: $launcher"
}

if (-not (Get-Command Register-ScheduledTask -ErrorAction SilentlyContinue)) {
    throw "The ScheduledTasks module is unavailable on this system; use Install-CompressorCaptureShortcut.ps1 instead."
}

$argument = "/c `"$launcher`""
if ($Environment -ne "Auto") { $argument = "/c `"$launcher`" $Environment" }
$action = New-ScheduledTaskAction -Execute "cmd.exe" -Argument $argument -WorkingDirectory $PSScriptRoot
$trigger = New-ScheduledTaskTrigger -AtLogOn -User ([Security.Principal.WindowsIdentity]::GetCurrent().Name)
# Interactive, current user, no elevation.
$principal = New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable

$existing = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
if ($null -ne $existing) {
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
}
$null = Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Description "Opt-in autonomous Galaxy Compressor capture ($Environment)."

if ($EnableAtLogon) {
    Enable-ScheduledTask -TaskName $taskName | Out-Null
    Write-Host "Registered and ENABLED task '$taskName' (launches capture at your next logon)."
} else {
    Disable-ScheduledTask -TaskName $taskName | Out-Null
    Write-Host "Registered task '$taskName' DISABLED (nothing runs until you enable it)."
    Write-Host "Re-run with -EnableAtLogon to enable the at-logon trigger."
}
Write-Host "Remove it any time: pwsh -File Remove-CompressorCaptureTask.ps1"
