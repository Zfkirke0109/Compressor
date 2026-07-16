# Device validation — PR #24 keep-original fast path (S23 Ultra)

APK: build the audited PR #24 head via CI ("Build PR Debug APK" artifact for this branch's PR)
or locally (`.\gradlew.bat assembleDebug`); install in place: `adb -s <ip:port> install -r <apk>`
(no data/permission clearing). Confirm embedded identity vs the head SHA recorded in the PR.

## Log evidence contract (capture-friendly)
- Structured records (CompressorDiag, captured by `Start-CompressorBatchCapture.ps1`): per-job
  fields `materializationMode` (REUSED_SOURCE | GENERATED_FILE), `originalReuseBlockReason`,
  `copyAvoidedBytes`, `verdict` ("Original Retained (source readable; no copy written; not
  output-verified)"), `savedBytes` (must be 0 for retained), `terminal`.
- Live tags: `adb logcat -s CompressorBatch:I` →
  `keep-original fast path; job=…; materialization=REUSED_SOURCE; copyAvoidedBytes=…` or
  `keep-original fast path blocked; job=…; reason=<OriginalReuseBlockReason>`.

## Procedure (Normal profile; NO Secure Folder this cycle)
1. **Baseline hashes**: pick 3 known already-optimal MP4s incl. one ≥1 GB. For each:
   `adb shell md5sum /sdcard/<path>` (or `sha256sum` if available) + `stat -c%s`.
2. **Arm capture**: `pwsh -File scripts\diagnostics\Start-CompressorBatchCapture.ps1 -Serial <ip:port> -Environment Normal -SessionDetectionTimeoutSeconds 0 -ClearLogcat`.
3. **Eligible retention run**: privacy = Preserve all, mode = Perceptually Lossless, select the
   3 files, run. PASS: each ends "Already optimal — original retained (no copy written)" within
   seconds (large file: no minutes-long remux progress); `materializationMode=REUSED_SOURCE`;
   `savedBytes=0`; no new file in `cacheDir/batch_compressed_videos`
   (`adb shell run-as io.github.zfkirke0109.galaxycompressor ls files/../cache/batch_compressed_videos`).
4. **Byte-identity**: re-hash the 3 sources → identical hashes + sizes. Open each in Gallery,
   seek to middle/end. PASS: plays + seeks.
5. **Result handling**: retained items are EXCLUDED from Share-all/Save-all (by design —
   outputPath is null). Remove/clear the batch list → re-hash sources unchanged. PASS.
6. **Guard paths (each must produce a distinct output / honest failure):**
   a. Explicit **Remux Only** on one file → full remux runs (progress bar, new output,
      `materializationMode=GENERATED_FILE`, output verified).
   b. **Privacy strip** (any non-preserve mode) + PL on an already-optimal file → full remux,
      `originalReuseBlockReason=PRIVACY_STRIP_REQUIRED` on the record.
   c. **Unknown container**: a .webm/.mkv source → existing path (unsupported-container or
      remux), never REUSED_SOURCE.
   d. **Revoked SAF access**: select a file, revoke its provider permission (or delete the file)
      mid-batch → honest failure/`SOURCE_NOT_READABLE`; source (if present) untouched.
   e. **Cancel** during a batch containing retained items → sources unchanged (re-hash).
7. **Learning neutrality**: after the run,
   `adb shell run-as io.github.zfkirke0109.galaxycompressor cat shared_prefs/smart_perceptual_profiles_v3.xml`
   → no `success=` increment and no `everCompressed=true` attributable to retained items
   (compare before/after copies of the file).
8. **Process restoration**: force-stop mid-review of results → app restarts with an empty batch
   list (expected: results are session-scoped for ALL item types; no broken retained entries).

## Pass criteria (all required)
No output generated/copied for eligible items • large-file retention completes in seconds •
source hashes byte-identical • guards b/c/d produce distinct outputs or honest failures •
removal/clear never deletes sources • diagnostics distinguish REUSED_SOURCE vs GENERATED_FILE •
retained savedBytes = 0 everywhere • no learning success from retention.
