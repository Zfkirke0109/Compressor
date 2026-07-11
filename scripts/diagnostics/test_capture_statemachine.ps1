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

if ($failures -gt 0) {
    Write-Host "FAILED: $failures assertion(s)"
    exit 1
}
Write-Host "All capture-state-machine tests passed."
