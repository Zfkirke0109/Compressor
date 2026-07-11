#Requires -Version 5
# Device-free unit tests for the capture supervisor's pure completion logic. Dot-sources the
# supervisor with -LibraryOnly so no ADB call, capture directory, or capture loop runs.
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "Start-CompressorBatchCapture.ps1") -LibraryOnly

$failures = 0
function Assert([bool]$Condition, [string]$Name) {
    if ($Condition) {
        Write-Host "  PASS $Name"
    } else {
        Write-Host "  FAIL $Name"
        $script:failures++
    }
}

function Rec([string]$batchId, [string]$type, [long]$ts, $selected = $null) {
    $o = [pscustomobject]@{ batchId = $batchId; type = $type; timestampMs = $ts }
    if ($null -ne $selected) { $o | Add-Member -NotePropertyName selectedCount -NotePropertyValue $selected }
    return $o
}

Write-Host "Try-ReadDiagnostic"
$parsed = Try-ReadDiagnostic '07-11 01:00:00.000  I CompressorDiag: {"batchId":"b1","type":"session_start","selectedCount":3}'
Assert ($null -ne $parsed -and [string]$parsed.batchId -eq "b1" -and [int]$parsed.selectedCount -eq 3) "parses a real CompressorDiag line"
Assert ($null -eq (Try-ReadDiagnostic "07-11 01:00:00.000 I SomethingElse: not json")) "ignores non-diagnostic lines"
Assert ($null -eq (Try-ReadDiagnostic "I CompressorDiag: {truncated")) "returns null on malformed JSON instead of throwing"

Write-Host "Find-NewestUnfinishedSession"
# Two unfinished sessions in the buffer -> the newest (by timestamp) is resumed, not the older.
$newest = Find-NewestUnfinishedSession @(
    (Rec "old" "session_start" 100 1),
    (Rec "new" "session_start" 200 2)
)
Assert ($null -ne $newest -and [string]$newest.batchId -eq "new") "resumes the newest unfinished session"

# A session that already has a summary is finished and must never be mistaken for a new run.
$noneUnfinished = Find-NewestUnfinishedSession @(
    (Rec "done" "session_start" 100 1),
    (Rec "done" "session_summary" 150)
)
Assert ($null -eq $noneUnfinished) "a completed buffered session is not resumed"

# Newest overall is finished, older is still unfinished -> resume the older UNFINISHED one.
$olderUnfinished = Find-NewestUnfinishedSession @(
    (Rec "a" "session_start" 100 1),
    (Rec "b" "session_start" 300 1),
    (Rec "b" "session_summary" 350)
)
Assert ($null -ne $olderUnfinished -and [string]$olderUnfinished.batchId -eq "a") "skips a finished newer session for an unfinished older one"

Assert ($null -eq (Find-NewestUnfinishedSession @())) "empty buffer yields no session"

Write-Host "Test-LegacyCompressorLine"
Assert (Test-LegacyCompressorLine "07-11 01:00:00.000  I CompressorBatch: started batch") "detects legacy CompressorBatch output"
Assert (Test-LegacyCompressorLine "1.0  I CompressorVerification: verdict ok") "detects legacy CompressorVerification output"
Assert (-not (Test-LegacyCompressorLine '1.0 I CompressorDiag: {"type":"session_start"}')) "structured CompressorDiag is not legacy"
Assert (-not (Test-LegacyCompressorLine "1.0 I ActivityManager: something")) "unrelated system log is not legacy Compressor"
Assert (-not (Test-LegacyCompressorLine "")) "empty line is not legacy"

Write-Host "Test-SessionMatchesEnvironment"
$user0 = [pscustomobject]@{ type = "session_start"; batchId = "b"; androidUserId = 0 }
$user150 = [pscustomobject]@{ type = "session_start"; batchId = "b"; androidUserId = 150 }
$noUser = [pscustomobject]@{ type = "session_start"; batchId = "b" }
Assert (Test-SessionMatchesEnvironment $user0 "Auto") "Auto accepts user 0"
Assert (Test-SessionMatchesEnvironment $user150 "Auto") "Auto accepts secondary user"
Assert (Test-SessionMatchesEnvironment $noUser "Auto") "Auto accepts unknown user"
Assert (Test-SessionMatchesEnvironment $user0 "Normal") "Normal accepts user 0"
Assert (-not (Test-SessionMatchesEnvironment $user150 "Normal")) "Normal rejects secondary user"
Assert (-not (Test-SessionMatchesEnvironment $noUser "Normal")) "Normal rejects unknown user (cannot prove user 0)"
Assert (Test-SessionMatchesEnvironment $user150 "SecondaryProfile") "SecondaryProfile accepts nonzero user"
Assert (-not (Test-SessionMatchesEnvironment $user0 "SecondaryProfile")) "SecondaryProfile rejects user 0"
Assert (-not (Test-SessionMatchesEnvironment $noUser "SecondaryProfile")) "SecondaryProfile rejects unknown user"

if ($failures -gt 0) {
    Write-Host "FAILED: $failures assertion(s)"
    exit 1
}
Write-Host "All capture-state-machine tests passed."
