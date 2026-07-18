<#
.SYNOPSIS
    Runs the Compressor test suites and saves durable, inspectable results locally.

.DESCRIPTION
    Runs the JVM unit tests (and optionally assembleDebug / lintDebug), parses the JUnit XML into a
    per-class + overall summary, and copies everything into a timestamped folder under
    validation\test-runs\ (gitignored, so results never end up in a commit).

    Also runs the Python diagnostics parser tests, which are easy to forget because they live outside
    Gradle.

    Exit code is non-zero when any suite fails, so it can gate a commit.

.EXAMPLE
    .\scripts\diagnostics\Run-CompressorTests.ps1
    .\scripts\diagnostics\Run-CompressorTests.ps1 -IncludeLint
    .\scripts\diagnostics\Run-CompressorTests.ps1 -UnitTestsOnly
#>
[CmdletBinding()]
param(
    # Also run lintDebug. Off by default: the repo carries a known pre-existing lint-error baseline,
    # so a lint failure is NOT necessarily caused by the change under test.
    [switch]$IncludeLint,
    # Skip assembleDebug (faster loop when you only care about test outcomes).
    [switch]$UnitTestsOnly,
    # Skip the Python parser tests.
    [switch]$SkipPythonTests,
    [string]$OutputRoot = 'validation\test-runs'
)

$ErrorActionPreference = 'Continue'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $repoRoot

$stamp   = Get-Date -Format 'yyyyMMdd_HHmmss'
$outDir  = Join-Path $repoRoot (Join-Path $OutputRoot $stamp)
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$gitSha    = (git rev-parse --short HEAD 2>$null)
$gitBranch = (git rev-parse --abbrev-ref HEAD 2>$null)
$dirty     = if ((git status --porcelain 2>$null)) { 'DIRTY (uncommitted changes)' } else { 'clean' }

Write-Host "Compressor test run $stamp" -ForegroundColor Cyan
Write-Host "  branch $gitBranch @ $gitSha ($dirty)"
Write-Host "  results -> $outDir"

# ---------------------------------------------------------------- Gradle
$tasks = @('testDebugUnitTest')
if (-not $UnitTestsOnly) { $tasks += 'assembleDebug' }
if ($IncludeLint)        { $tasks += 'lintDebug' }

Write-Host "`n[1/3] gradlew $($tasks -join ' ') ..." -ForegroundColor Cyan
$gradleLog = Join-Path $outDir 'gradle.log'
& .\gradlew @tasks --console=plain *>&1 | Tee-Object -FilePath $gradleLog | Out-Null
$gradleExit = $LASTEXITCODE
$gradleOk = ($gradleExit -eq 0)
Write-Host ("  gradle: " + $(if ($gradleOk) { 'SUCCESS' } else { "FAILED (exit $gradleExit)" })) `
    -ForegroundColor $(if ($gradleOk) { 'Green' } else { 'Red' })

# ---------------------------------------------------------------- Parse JUnit XML
Write-Host "`n[2/3] parsing JUnit results ..." -ForegroundColor Cyan
$resultsDir = Join-Path $repoRoot 'app\build\test-results\testDebugUnitTest'
$classes = @()
$total = 0; $failed = 0; $errored = 0; $skipped = 0

if (Test-Path $resultsDir) {
    Copy-Item (Join-Path $resultsDir '*.xml') -Destination $outDir -ErrorAction SilentlyContinue
    Get-ChildItem $resultsDir -Filter '*.xml' | ForEach-Object {
        try {
            [xml]$x = Get-Content $_.FullName
            $s = $x.testsuite
            if ($null -ne $s) {
                $t = [int]$s.tests; $f = [int]$s.failures; $e = [int]$s.errors; $k = [int]$s.skipped
                $total += $t; $failed += $f; $errored += $e; $skipped += $k
                $classes += [pscustomobject]@{
                    Class    = ($s.name -split '\.')[-1]
                    Tests    = $t
                    Failures = $f
                    Errors   = $e
                    Skipped  = $k
                }
                # Surface the actual failure text so the log is useful on its own.
                foreach ($tc in $s.testcase) {
                    if ($tc.failure -or $tc.error) {
                        $msg = if ($tc.failure) { $tc.failure.message } else { $tc.error.message }
                        Add-Content (Join-Path $outDir 'failures.txt') "$($s.name).$($tc.name)`n    $msg`n"
                    }
                }
            }
        } catch { Write-Warning "could not parse $($_.Name): $_" }
    }
} else {
    Write-Warning "no JUnit results at $resultsDir (did compilation fail?)"
}

$unitOk = ($total -gt 0 -and $failed -eq 0 -and $errored -eq 0)
$classes | Sort-Object Class | Format-Table -AutoSize | Out-String | Write-Host
Write-Host ("  unit tests: $total total, $failed failures, $errored errors, $skipped skipped") `
    -ForegroundColor $(if ($unitOk) { 'Green' } else { 'Red' })

# ---------------------------------------------------------------- Python parser tests
$pyOk = $true
if (-not $SkipPythonTests) {
    Write-Host "`n[3/3] python diagnostics parser tests ..." -ForegroundColor Cyan
    $pyLog = Join-Path $outDir 'python-tests.log'
    Push-Location (Join-Path $repoRoot 'scripts\diagnostics')
    $env:PYTHONIOENCODING = 'utf-8'
    & python -m unittest test_parse_batch_logcat *>&1 | Tee-Object -FilePath $pyLog | Out-Null
    $pyOk = ($LASTEXITCODE -eq 0)
    Pop-Location
    Write-Host ("  parser tests: " + $(if ($pyOk) { 'OK' } else { 'FAILED' })) `
        -ForegroundColor $(if ($pyOk) { 'Green' } else { 'Red' })
} else {
    Write-Host "`n[3/3] python parser tests skipped" -ForegroundColor DarkGray
}

# ---------------------------------------------------------------- Summary
$allOk = $gradleOk -and $unitOk -and $pyOk
$summary = [ordered]@{
    timestamp    = $stamp
    branch       = $gitBranch
    commit       = $gitSha
    workingTree  = $dirty
    gradleTasks  = ($tasks -join ' ')
    gradleOk     = $gradleOk
    unitTests    = [ordered]@{ total = $total; failures = $failed; errors = $errored; skipped = $skipped; ok = $unitOk }
    pythonParserTestsOk = $pyOk
    overallOk    = $allOk
    classes      = $classes
}
$summary | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $outDir 'summary.json')

$md = @"
# Compressor test run $stamp

- branch: ``$gitBranch`` @ ``$gitSha`` ($dirty)
- gradle tasks: ``$($tasks -join ' ')`` -> $(if ($gradleOk) { 'SUCCESS' } else { "FAILED (exit $gradleExit)" })
- unit tests: **$total total, $failed failures, $errored errors, $skipped skipped**
- python parser tests: $(if ($pyOk) { 'OK' } else { 'FAILED' })
- **overall: $(if ($allOk) { 'PASS' } else { 'FAIL' })**

| Class | Tests | Failures | Errors |
|---|---|---|---|
$(($classes | Sort-Object Class | ForEach-Object { "| $($_.Class) | $($_.Tests) | $($_.Failures) | $($_.Errors) |" }) -join "`n")

Artifacts in this folder: ``gradle.log``, ``summary.json``, per-class JUnit ``*.xml``$(if (Test-Path (Join-Path $outDir 'failures.txt')) { ", ``failures.txt``" }).
"@
$md | Set-Content (Join-Path $outDir 'summary.md')

Write-Host "`n================ $(if ($allOk) { 'PASS' } else { 'FAIL' }) ================" `
    -ForegroundColor $(if ($allOk) { 'Green' } else { 'Red' })
Write-Host "saved: $outDir"
if (-not $allOk -and (Test-Path (Join-Path $outDir 'failures.txt'))) {
    Write-Host "`n--- failures ---" -ForegroundColor Red
    Get-Content (Join-Path $outDir 'failures.txt') | Select-Object -First 40 | Write-Host
}
if (-not $allOk) { exit 1 } else { exit 0 }
