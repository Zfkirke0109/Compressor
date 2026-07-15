[CmdletBinding()]
param(
    [string]$Serial,
    # Which Android profile's structured session to bind to. Auto binds to the first new session from
    # any user; Normal only binds to user 0; SecondaryProfile only binds to a nonzero user (Secure
    # Folder is a nonzero user, but a nonzero user is not PROVEN to be Secure Folder from public APIs).
    [ValidateSet("Auto", "Normal", "SecondaryProfile")]
    [string]$Environment = "Auto",
    [switch]$RecoverCurrentSession,
    [int]$WaitForBatchMinutes = 0,
    [int]$MaxOfflineMinutes = 0,
    [int]$IdleMinutes = 0,
    # After legacy (non-structured) Compressor activity is first observed, wait at most this long for a
    # structured session_start before declaring the installed build incompatible. 0 disables the guard.
    # It never interrupts a genuinely active structured session -- it only applies while still unbound.
    [int]$SessionDetectionTimeoutSeconds = 180,
    # If the installed build is incompatible (legacy-only), capture human-readable tags and finish by
    # inactivity instead of stopping. Explicitly marked non-authoritative / partial in the manifest.
    [switch]$AllowLegacyFallback,
    [switch]$NoParse,
    [switch]$IncludeHumanReadableTags,
    [switch]$ClearLogcat,
    # Dot-source with -LibraryOnly to load the pure helper functions below for unit tests, without
    # touching ADB, creating a capture directory, or entering the capture loop.
    [switch]$LibraryOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# --- Pure helpers (no ADB, no side effects) so the completion logic can be unit-tested. ---
function Try-ReadDiagnostic([string]$Line) {
    $marker = "CompressorDiag: "
    $index = $Line.IndexOf($marker, [System.StringComparison]::Ordinal)
    if ($index -lt 0) { return $null }
    $json = $Line.Substring($index + $marker.Length).Trim()
    try { return $json | ConvertFrom-Json -ErrorAction Stop } catch { return $null }
}

# True when a raw logcat line is legacy (human-readable) Compressor output, NOT a structured
# CompressorDiag record. Used to detect that Compressor is running but the installed build predates
# the structured-diagnostics integration, so we can warn instead of waiting silently forever.
function Test-LegacyCompressorLine([string]$Line) {
    if ([string]::IsNullOrEmpty($Line)) { return $false }
    if ($Line.IndexOf("CompressorDiag:", [System.StringComparison]::Ordinal) -ge 0) { return $false }
    foreach ($tag in @(
            "CompressorBatch:",
            "CompressorEncoderPlan:",
            "CompressorLearning:",
            "CompressorVerification:",
            "CompressorCodecCaps:")) {
        if ($Line.IndexOf($tag, [System.StringComparison]::Ordinal) -ge 0) { return $true }
    }
    return $false
}

# Whether a session_start diagnostic is eligible for the requested -Environment. Auto accepts any
# session. Normal requires the reported Android user 0. SecondaryProfile requires a nonzero user. A
# session_start that does not report androidUserId (an older structured build) cannot be confirmed as
# a specific profile, so it only qualifies under Auto -- it is skipped for Normal/SecondaryProfile.
function Test-SessionMatchesEnvironment($Diagnostic, [string]$Environment) {
    if ($Environment -eq "Auto") { return $true }
    $hasUser = $false
    $userId = -1
    if ($null -ne $Diagnostic -and ($Diagnostic.PSObject.Properties.Name -contains "androidUserId")) {
        $raw = $Diagnostic.androidUserId
        if ($null -ne $raw -and "$raw" -match '^\d+$') { $hasUser = $true; $userId = [int]$raw }
    }
    if (-not $hasUser) { return $false }
    switch ($Environment) {
        "Normal" { return ($userId -eq 0) }
        "SecondaryProfile" { return ($userId -ne 0) }
        default { return $true }
    }
}

function Find-NewestUnfinishedSession([object[]]$Records) {
    $starts = @{}
    $completed = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($diagnostic in $Records) {
        $id = [string]$diagnostic.batchId
        if ([string]::IsNullOrWhiteSpace($id)) { continue }
        if ($diagnostic.type -eq "session_start") {
            $starts[$id] = $diagnostic
        } elseif ($diagnostic.type -eq "session_summary") {
            $null = $completed.Add($id)
        }
    }
    return @(
        $starts.Values |
            Where-Object { -not $completed.Contains([string]$_.batchId) } |
            Sort-Object { [long]$_.timestampMs } -Descending
    ) | Select-Object -First 1
}

if ($LibraryOnly) { return }

if ($ClearLogcat -and $RecoverCurrentSession) {
    throw "-ClearLogcat cannot be combined with -RecoverCurrentSession."
}

$adbCommand = Get-Command adb.exe -ErrorAction SilentlyContinue
if ($null -eq $adbCommand) {
    throw "adb.exe was not found on PATH. Install Android platform-tools first."
}
$adb = $adbCommand.Source
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$parser = Join-Path $PSScriptRoot "parse_batch_logcat.py"
$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$outputDir = Join-Path $repoRoot "validation\captures\batch_$stamp"
$null = New-Item -ItemType Directory -Force -Path $outputDir
$rawLog = Join-Path $outputDir "session.logcat.txt"
$captureLog = Join-Path $outputDir "capture.log"
$manifestPath = Join-Path $outputDir "manifest.json"
$writer = [System.IO.StreamWriter]::new($rawLog, $false, [System.Text.UTF8Encoding]::new($false))
$writer.AutoFlush = $true

$captureStarted = [DateTimeOffset]::UtcNow
$waitDeadline = if ($WaitForBatchMinutes -gt 0) { $captureStarted.AddMinutes($WaitForBatchMinutes) } else { $null }
$requestedSerial = $Serial
$activeBatchId = $null
$sessionStart = $null
$sessionSummary = $null
$stopReason = $null
$lastDeviceSerial = $null
$offlineSince = $null
$lastRecordAt = $null
$clearPending = [bool]$ClearLogcat
$seenLines = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
$jobIds = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
$connectionCount = 0
$replayCount = 0
$targetDeviceIdentity = $null
$initialBufferSnapshotTaken = $false
$readyAnnounced = $false
$preexistingBatchIds = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
$legacyActivitySeenAt = $null
$sessionDetectionDeadline = $null
$legacyActivityDetected = $false
$sessionDetectionTimedOut = $false
$legacyFallbackActive = $false
$waitingAnnounced = $false

function Write-CaptureLog([string]$Message) {
    $line = "[{0}] {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Message
    [System.IO.File]::AppendAllText($captureLog, $line + [Environment]::NewLine)
    Write-Host $line
}

function Test-AdbSerial([string]$Candidate) {
    if ([string]::IsNullOrWhiteSpace($Candidate)) { return $false }
    $state = & $adb -s $Candidate get-state 2>$null
    return $LASTEXITCODE -eq 0 -and ($state | Select-Object -First 1) -eq "device"
}

function Get-OnlineSerials {
    $result = @()
    foreach ($line in (& $adb devices 2>$null)) {
        if ($line -match '^(\S+)\s+device(?:\s|$)') {
            $result += $Matches[1]
        }
    }
    return @($result | Sort-Object -Unique)
}

function Get-MdnsEndpoints {
    $endpoints = @()
    foreach ($line in (& $adb mdns services 2>$null)) {
        if ($line -match '_adb-tls-connect\._tcp\s+(\d{1,3}(?:\.\d{1,3}){3}:\d+)\s*$') {
            $endpoints += $Matches[1]
        }
    }
    return @($endpoints | Sort-Object -Unique)
}

function Get-DeviceIdentity([string]$Candidate) {
    if (-not (Test-AdbSerial $Candidate)) { return $null }
    $identity = (& $adb -s $Candidate shell getprop ro.serialno 2>$null | Select-Object -First 1).Trim()
    if ([string]::IsNullOrWhiteSpace($identity)) { return $null }
    return $identity
}

function Resolve-AdbSerial {
    if (-not [string]::IsNullOrWhiteSpace($requestedSerial)) {
        if (Test-AdbSerial $requestedSerial) { return $requestedSerial }
        if ($requestedSerial -match '^\d{1,3}(?:\.\d{1,3}){3}:\d+$') {
            $null = & $adb connect $requestedSerial 2>$null
            if (Test-AdbSerial $requestedSerial) { return $requestedSerial }
        }
        # Once the first transport identifies the physical phone, follow that identity across
        # Samsung wireless-debugging IP/port rotation instead of waiting forever on a stale socket.
        if (-not [string]::IsNullOrWhiteSpace($targetDeviceIdentity)) {
            foreach ($endpoint in @(Get-MdnsEndpoints)) {
                $null = & $adb connect $endpoint 2>$null
                if ((Get-DeviceIdentity $endpoint) -eq $targetDeviceIdentity) {
                    return $endpoint
                }
            }
        }
        return $null
    }

    $online = @(Get-OnlineSerials)
    $tcp = @($online | Where-Object { $_ -match '^\d{1,3}(?:\.\d{1,3}){3}:\d+$' })
    if ($tcp.Count -eq 1) { return $tcp[0] }
    if ($tcp.Count -gt 1) {
        throw "Multiple TCP ADB devices are online. Re-run with -Serial <serial>."
    }
    if ($online.Count -eq 1) { return $online[0] }

    $endpoints = @(Get-MdnsEndpoints)
    if ($endpoints.Count -gt 1) {
        throw "Multiple wireless ADB endpoints were discovered. Re-run with -Serial <serial>."
    }
    if ($endpoints.Count -eq 1) {
        $null = & $adb connect $endpoints[0] 2>$null
        if (Test-AdbSerial $endpoints[0]) { return $endpoints[0] }
    }
    return $null
}

function Get-BufferedDiagnostics([string]$DeviceSerial) {
    $records = @()
    $buffer = & $adb -s $DeviceSerial logcat -d -v "epoch,usec" "CompressorDiag:V" "*:S" 2>$null
    if ($LASTEXITCODE -ne 0) { return @() }
    foreach ($line in $buffer) {
        $diagnostic = Try-ReadDiagnostic $line
        if ($null -ne $diagnostic) { $records += $diagnostic }
    }
    return @($records)
}

function Start-LogcatProcess([string]$DeviceSerial) {
    $tags = if ($IncludeHumanReadableTags) {
        @(
            "CompressorBatch:V",
            "CompressorEncoderPlan:V",
            "CompressorLearning:V",
            "CompressorVerification:V",
            "CompressorCodecCaps:V",
            "CompressorDiag:V",
            "*:S"
        )
    } else {
        # Privacy mode still SUBSCRIBES to legacy tags so an old, structured-diagnostics-free build
        # can be detected (and warned about) instead of causing a silent wait. Legacy lines are used
        # only for detection here; the write guard below keeps them out of the structured raw log.
        @(
            "CompressorDiag:V",
            "CompressorBatch:V",
            "CompressorEncoderPlan:V",
            "CompressorLearning:V",
            "CompressorVerification:V",
            "CompressorCodecCaps:V",
            "*:S"
        )
    }

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $adb
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($argument in @("-s", $DeviceSerial, "logcat", "-v", "epoch,usec") + $tags) {
        $null = $startInfo.ArgumentList.Add($argument)
    }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    $null = $process.Start()
    return $process
}

Write-CaptureLog "Output directory: $outputDir"
Write-CaptureLog "Waiting for an ADB device before arming capture."

try {
    while ($null -eq $stopReason) {
        if ($null -ne $waitDeadline -and [DateTimeOffset]::UtcNow -ge $waitDeadline -and $null -eq $activeBatchId) {
            $stopReason = "wait_timeout"
            break
        }

        $deviceSerial = Resolve-AdbSerial
        if ($null -eq $deviceSerial) {
            if ($null -eq $offlineSince) {
                $offlineSince = [DateTimeOffset]::UtcNow
                Write-CaptureLog "ADB device offline; waiting for wireless debugging to return."
            }
            if ($MaxOfflineMinutes -gt 0 -and [DateTimeOffset]::UtcNow -ge $offlineSince.AddMinutes($MaxOfflineMinutes)) {
                $stopReason = "offline_timeout"
                break
            }
            Start-Sleep -Seconds 3
            continue
        }

        $offlineSince = $null
        $lastDeviceSerial = $deviceSerial
        $connectionCount++
        if ([string]::IsNullOrWhiteSpace($targetDeviceIdentity)) {
            $targetDeviceIdentity = Get-DeviceIdentity $deviceSerial
        }
        if ($clearPending) {
            & $adb -s $deviceSerial logcat -c | Out-Null
            $clearPending = $false
            Write-CaptureLog "Logcat cleared by explicit request."
        }
        if (-not $initialBufferSnapshotTaken) {
            $bufferedDiagnostics = @(Get-BufferedDiagnostics $deviceSerial)
            foreach ($record in $bufferedDiagnostics) {
                if (-not [string]::IsNullOrWhiteSpace([string]$record.batchId)) {
                    $null = $preexistingBatchIds.Add([string]$record.batchId)
                }
            }
            if ($RecoverCurrentSession) {
                $recoverable = Find-NewestUnfinishedSession $bufferedDiagnostics
                if ($null -ne $recoverable) {
                    $activeBatchId = [string]$recoverable.batchId
                    $sessionStart = $recoverable
                    Write-CaptureLog "Recovering unfinished batch $activeBatchId ($($recoverable.selectedCount) selected)."
                }
            }
            $initialBufferSnapshotTaken = $true
        }
        Write-CaptureLog "Attached to $deviceSerial (connection $connectionCount)."
        $process = Start-LogcatProcess $deviceSerial
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $readTask = $process.StandardOutput.ReadLineAsync()
        if (-not $readyAnnounced) {
            Write-CaptureLog "Target environment: $Environment"
            Write-CaptureLog "CAPTURE ARMED - start one Compressor batch now. You may close every AI app; capture keeps running."
            if (-not $IncludeHumanReadableTags) {
                Write-CaptureLog "Privacy mode: structured CompressorDiag records only."
            }
            $readyAnnounced = $true
        }
        if (-not $waitingAnnounced -and $null -eq $activeBatchId) {
            Write-CaptureLog "WAITING FOR STRUCTURED SESSION - listening for a CompressorDiag session_start."
            $waitingAnnounced = $true
        }

        try {
            while ($null -eq $stopReason -and -not $process.HasExited) {
                if ($null -ne $waitDeadline -and $null -eq $activeBatchId -and
                    [DateTimeOffset]::UtcNow -ge $waitDeadline) {
                    $stopReason = "wait_timeout"
                    break
                }
                # An old build that never emits a structured session_start must not hang the recorder
                # forever. Once legacy Compressor activity has been seen, give the structured session a
                # bounded grace period, then declare the build incompatible (or fall back).
                if ($null -eq $activeBatchId -and $null -ne $sessionDetectionDeadline -and
                    [DateTimeOffset]::UtcNow -ge $sessionDetectionDeadline) {
                    $sessionDetectionTimedOut = $true
                    if ($AllowLegacyFallback) {
                        $legacyFallbackActive = $true
                        $activeBatchId = "legacy-fallback"
                        $lastRecordAt = [DateTimeOffset]::UtcNow
                        Write-CaptureLog "INSTALLED BUILD MAY LACK STRUCTURED DIAGNOSTICS - falling back to legacy human-readable capture (non-authoritative)."
                    } else {
                        $stopReason = "incompatible_build"
                        Write-CaptureLog "INCOMPATIBLE COMPRESSOR BUILD - STRUCTURED DIAGNOSTICS NOT DETECTED. Galaxy Compressor activity was seen, but this installed APK did not emit the required CompressorDiag session record. Install the corrected build (or re-run with -AllowLegacyFallback for a non-authoritative capture)."
                        break
                    }
                }

                if ($readTask.Wait(1000)) {
                    $line = $readTask.Result
                    if ($null -eq $line) { break }
                    $readTask = $process.StandardOutput.ReadLineAsync()
                    if (-not $seenLines.Add($line)) {
                        $replayCount++
                        continue
                    }

                    $diagnostic = Try-ReadDiagnostic $line
                    if ($null -eq $activeBatchId) {
                        # Not a structured record: if it is legacy Compressor output, start (once) the
                        # bounded session-detection countdown so an old build is reported, not silently
                        # awaited. Non-Compressor noise is ignored.
                        if ($null -eq $diagnostic) {
                            if ((Test-LegacyCompressorLine $line) -and $null -eq $legacyActivitySeenAt) {
                                $legacyActivitySeenAt = [DateTimeOffset]::UtcNow
                                $legacyActivityDetected = $true
                                if ($SessionDetectionTimeoutSeconds -gt 0) {
                                    $sessionDetectionDeadline = $legacyActivitySeenAt.AddSeconds($SessionDetectionTimeoutSeconds)
                                }
                                Write-CaptureLog "LEGACY COMPRESSOR ACTIVITY DETECTED - waiting up to ${SessionDetectionTimeoutSeconds}s for a structured session_start."
                            }
                            continue
                        }
                        if ($diagnostic.type -ne "session_start") { continue }
                        # The initial on-device buffer snapshot is the arrival boundary. This avoids
                        # assuming the PC and phone wall clocks are synchronized.
                        if ($preexistingBatchIds.Contains([string]$diagnostic.batchId)) { continue }
                        if (-not (Test-SessionMatchesEnvironment $diagnostic $Environment)) {
                            Write-CaptureLog "Ignoring session $($diagnostic.batchId) from androidUserId=$($diagnostic.androidUserId) (Environment=$Environment)."
                            continue
                        }
                        $activeBatchId = [string]$diagnostic.batchId
                        $sessionStart = $diagnostic
                        $lastRecordAt = [DateTimeOffset]::UtcNow
                        $writer.WriteLine($line)
                        Write-CaptureLog "SESSION DETECTED - capturing batch $activeBatchId ($($diagnostic.selectedCount) selected, androidUserId=$($diagnostic.androidUserId))."
                        continue
                    }

                    if ($null -ne $diagnostic -and [string]$diagnostic.batchId -ne $activeBatchId) {
                        continue
                    }
                    # Privacy invariant: in structured mode the raw log holds ONLY structured records.
                    # Legacy lines (subscribed for detection, or captured during -AllowLegacyFallback)
                    # are written only when a human-readable capture was explicitly requested.
                    if ($null -ne $diagnostic -or $IncludeHumanReadableTags -or $legacyFallbackActive) {
                        $writer.WriteLine($line)
                        $lastRecordAt = [DateTimeOffset]::UtcNow
                    }
                    if ($null -ne $diagnostic -and $diagnostic.type -eq "job") {
                        $null = $jobIds.Add([string]$diagnostic.id)
                        Write-Host ("Captured item {0}: {1}" -f $jobIds.Count, $diagnostic.terminal)
                    }
                    if ($null -ne $diagnostic -and $diagnostic.type -eq "session_summary") {
                        $sessionSummary = $diagnostic
                        $stopReason = "session_summary"
                        break
                    }
                }

                # Idle completion is a fallback only: for -AllowLegacyFallback (no structured summary is
                # ever coming) or for an explicit -IdleMinutes safety net on a bound structured session.
                $idleWindow = if ($legacyFallbackActive -and $IdleMinutes -le 0) { 3 } else { $IdleMinutes }
                if ($idleWindow -gt 0 -and $null -ne $activeBatchId -and
                    ($legacyFallbackActive -or $jobIds.Count -gt 0) -and
                    $null -ne $lastRecordAt -and [DateTimeOffset]::UtcNow -ge $lastRecordAt.AddMinutes($idleWindow)) {
                    $stopReason = "legacy_idle_timeout"
                    break
                }
            }
        } finally {
            if (-not $process.HasExited) {
                # Stop only the logger launched by this script; never touch the app or ADB server.
                $process.Kill()
                $process.WaitForExit()
            }
            $stderr = $stderrTask.Result.Trim()
            if (-not [string]::IsNullOrWhiteSpace($stderr)) {
                Write-CaptureLog "logcat ended: $stderr"
            }
            $process.Dispose()
        }

        if ($null -eq $stopReason) {
            Write-CaptureLog "Capture transport detached; reconnecting without clearing the device buffer."
            Start-Sleep -Seconds 2
        }
    }
} finally {
    $writer.Dispose()
}

$parserExitCode = $null
if (-not $NoParse -and (Test-Path -LiteralPath $rawLog) -and (Get-Item -LiteralPath $rawLog).Length -gt 0) {
    $python = Get-Command python.exe -ErrorAction SilentlyContinue
    if ($null -eq $python) { $python = Get-Command python -ErrorAction SilentlyContinue }
    if ($null -eq $python) {
        Write-CaptureLog "Python not found; raw capture retained but parsing skipped."
    } else {
        & $python.Source $parser $rawLog --out $outputDir
        $parserExitCode = $LASTEXITCODE
    }
}

$selectedCount = if ($null -ne $sessionStart) { [int]$sessionStart.selectedCount } else { 0 }
$processedCount = if ($null -ne $sessionSummary) { [int]$sessionSummary.processed } else { 0 }
# When parsing is enabled, "complete" REQUIRES the parser to have succeeded: a structured summary
# with reconciled counts is not enough if the parser rejected the capture. -NoParse waives only the
# parser requirement, never the structured reconciliation.
$parseOk = if ($NoParse) { $true } elseif ($null -ne $parserExitCode) { $parserExitCode -eq 0 } else { $false }
$reconciled = $stopReason -eq "session_summary" -and
    $selectedCount -eq $processedCount -and
    $processedCount -eq $jobIds.Count
$complete = $reconciled -and $parseOk
# Idle inference is a fallback only and can never certify completeness; the manifest says so.
$completionSource = switch ($stopReason) {
    "session_summary" { "session_summary_record" }
    "legacy_idle_timeout" { if ($legacyFallbackActive) { "legacy_idle_inference" } else { "idle_inference" } }
    "incompatible_build" { "incompatible_build" }
    default { $stopReason }
}
$incompatibleBuild = $stopReason -eq "incompatible_build" -or $legacyFallbackActive
$partial = (-not $complete) -and (($jobIds.Count -gt 0) -or $legacyFallbackActive)
$failureReason = if ($complete) { $null } elseif ($stopReason -eq "incompatible_build") { "incompatible_installed_build_no_structured_diagnostics" } elseif (-not $reconciled) { $stopReason } elseif (-not $parseOk) { "parser_failed" } else { $stopReason }
# Surface the privacy-safe build/profile identity the session reported, so the manifest records which
# environment produced the data without any device-side query.
$sessionProfileKind = if ($null -ne $sessionStart -and ($sessionStart.PSObject.Properties.Name -contains "profileKind")) { $sessionStart.profileKind } else { $null }
$sessionAndroidUserId = if ($null -ne $sessionStart -and ($sessionStart.PSObject.Properties.Name -contains "androidUserId")) { $sessionStart.androidUserId } else { $null }
$sessionPackageName = if ($null -ne $sessionStart -and ($sessionStart.PSObject.Properties.Name -contains "packageName")) { $sessionStart.packageName } else { $null }
$sessionAppVersionName = if ($null -ne $sessionStart -and ($sessionStart.PSObject.Properties.Name -contains "appVersionName")) { $sessionStart.appVersionName } else { $null }
$sessionAppVersionCode = if ($null -ne $sessionStart -and ($sessionStart.PSObject.Properties.Name -contains "appVersionCode")) { $sessionStart.appVersionCode } else { $null }
$sessionBuildCommit = if ($null -ne $sessionStart -and ($sessionStart.PSObject.Properties.Name -contains "buildCommit")) { $sessionStart.buildCommit } else { $null }
$parsedArtifacts = if (-not $NoParse -and $parserExitCode -eq 0) {
    [ordered]@{
        jobsFile = Join-Path $outputDir "jobs.jsonl"
        summaryFile = Join-Path $outputDir "summary.csv"
        aggregateFile = Join-Path $outputDir "aggregate.json"
    }
} else { $null }
$manifest = [ordered]@{
    schema = 1
    captureStartedUtc = $captureStarted.ToString("o")
    captureEndedUtc = [DateTimeOffset]::UtcNow.ToString("o")
    deviceSerial = $lastDeviceSerial
    targetDeviceIdentity = $targetDeviceIdentity
    environment = $Environment
    batchId = $activeBatchId
    stopReason = $stopReason
    completionSource = $completionSource
    complete = $complete
    partial = $partial
    reconciled = $reconciled
    incompatibleBuild = $incompatibleBuild
    legacyActivityDetected = $legacyActivityDetected
    sessionDetectionTimedOut = $sessionDetectionTimedOut
    legacyFallbackActive = $legacyFallbackActive
    sessionProfileKind = $sessionProfileKind
    sessionAndroidUserId = $sessionAndroidUserId
    sessionPackageName = $sessionPackageName
    sessionAppVersionName = $sessionAppVersionName
    sessionAppVersionCode = $sessionAppVersionCode
    sessionBuildCommit = $sessionBuildCommit
    failureReason = $failureReason
    selectedCount = $selectedCount
    processedCount = $processedCount
    capturedUniqueJobCount = $jobIds.Count
    connectionCount = $connectionCount
    reconnectCount = [Math]::Max(0, $connectionCount - 1)
    replayLineCount = $replayCount
    parseAttempted = (-not $NoParse)
    parserExitCode = $parserExitCode
    rawLog = $rawLog
    structuredRawLog = $rawLog
    parsedArtifacts = $parsedArtifacts
    sessionSummary = $sessionSummary
}
[System.IO.File]::WriteAllText(
    $manifestPath,
    ($manifest | ConvertTo-Json -Depth 8),
    [System.Text.UTF8Encoding]::new($false)
)

Write-CaptureLog "Stopped: $stopReason. Complete=$complete. Results: $outputDir"
if (-not $complete -or ($null -ne $parserExitCode -and $parserExitCode -ne 0)) { exit 2 }
