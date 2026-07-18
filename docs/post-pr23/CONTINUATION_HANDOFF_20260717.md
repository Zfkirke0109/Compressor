# Compressor continuation handoff — 2026-07-17 (UltraCode session)

Durable shared-reality record for the next Claude/Codex/human session. No secrets, no keystore
paths, no passwords. The signer SHA-256 below is a public certificate fingerprint (already in CI).

## Current integrated state (UPDATED 2026-07-18 — all of this session's work is MERGED)
- **origin/main = `5d48e94`**. Merge order this session: #28 `54857cf` (signing, CI-only) ->
  handoff `af9fa74` -> **#29 PERF-001 `f046c0e`** -> **#30 `bf15a61`** (manifest comment API 34->35)
  -> **#31 QUAL-001 `5d48e94`**.
- Merged & integrated: #22-#25 (PL pipeline + pairing fix), #26 (SAFE-001), #27 (QUAL-002),
  #28 (signing), **#29 (PERF-001, device-validated)**, #30, **#31 (QUAL-001)**.
- **Open PRs:** only **#17** (DRAFT, AV1 follow-up — PRESERVE, untouched). Nothing else pending.
- **PERF-001 device evidence (S23 Ultra / Android 16, API 36):** FGS `isForeground=true`
  `types=0x00002000` (mediaProcessing — confirms the API-35 threshold fix), `galaxycompressor:batch`
  PARTIAL wake lock held, ongoing notification, **survived background + screen-off 10 min during a
  real 182-video batch**, clean release at completion. Signer verified == expected shared identity.
  Device migrated off the old pre-#28 debug key (uninstall+install, owner-authorized); learning store
  backed up and restored (8329 B) -> future CI builds now update in place cleanly.
- **QUAL-001 (#31) is DONE** — the design below was implemented as specified (keyed on
  `certOk && certScores != null`, never on `probeEligible`). Kept for historical context only.
- Preserved local branches: `feat/galaxy-app-icon`, `claude/add-android-workflow-skills`,
  `copilot/fix-pr-18-build-failure`, `feat/batch-foreground-service` (=#29),
  `fix/verification-label-honesty` (empty QUAL-001 starting branch off main, no commits yet).
- Untracked stray (ignored, never committed): `extract_algorithm_evidence.py`.

## Signing status (preserve; verify, never expose)
- Shared identity CN=Zachary Kirke / alias rootlesszachdsp. Expected signer SHA-256:
  `18:45:A5:52:78:82:E7:3E:7E:21:BE:D9:48:4D:A2:A6:CC:B6:BB:31:A6:77:E6:B9:5F:F2:A6:46:48:7E:72:19`.
- #29 CI artifact VERIFIED: SHA-256 `1845a5...487e7219` == expected (normalized). Package
  `io.github.zfkirke0109.galaxycompressor` versionCode 26 / 1.6.1, compileSdk 36. v2 scheme.
- #28 workflow secret names: KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD,
  EXPECTED_SIGNER_SHA256. PERF-001/QUAL-001 touch NO CI/signing file.

## PR #29 — PERF-001 (foreground service + bounded CPU wake lock)
- Branch `feat/batch-foreground-service`. Files: BatchForegroundService.kt (new),
  BatchExecutionGuard.kt (new: idempotent coordinator + sink + Android sink w/ PARTIAL wake lock),
  BatchCompressorViewModel.kt (batchGuard field, begin() first-in-try, end() first-in-finally,
  onCleared()), AndroidManifest.xml (FOREGROUND_SERVICE + *_MEDIA_PROCESSING + *_DATA_SYNC +
  POST_NOTIFICATIONS + WAKE_LOCK; service exported=false type mediaProcessing|dataSync),
  strings.xml (3), BatchExecutionGuardTest.kt (6 tests incl. 32-thread concurrency).
- Adversarial 3-lens review caught 2 REAL should-fix bugs, both fixed: (a) mediaProcessing FGS
  type + permission are **API 35** (Android 15), not 34 -> threshold corrected to >=35 w/ dataSync
  fallback on 29-34; (b) API-35 two-arg onTimeout(startId,fgsType) also overridden (avoids fatal
  RemoteServiceException). Also fixed the begin()/end() try/finally asymmetry. Journal:
  docs/post-pr23/PERF001_DECISION_JOURNAL.md.
- Gates passed: assembleDebug + testDebugUnitTest green (BatchExecutionGuardTest 6/6); 0 new lint
  errors (60 pre-existing baseline on main, CI does not gate lintDebug); CI green on #29; diff is
  exactly the intended files; no secrets/exported surface; signer verified.
- **REMAINING BLOCKER (why #29 is not merged):** on-device lifecycle evidence not yet captured —
  device was asleep/unreachable, and this session's rule is DO NOT auto-install. Next session
  (device awake): rediscover ADB (IP 192.168.1.17, port rotates — `adb mdns services`), pull the
  installed base.apk and apksigner --print-certs to confirm the INSTALLED signer == expected
  (package match + signer mismatch = HARD STOP), then `gh run download` the #29 artifact,
  `adb install -r`, run PL over a few files, background + screen-off, confirm: notification shows,
  batch survives + completes, service stops after. Then merge #29 (squash).

## SHIPPED since the first draft of this file (main = `ad18935`)
#29 PERF-001 (device-validated) · #30 manifest comment · #31 QUAL-001 label honesty ·
#32 regression guards (API-35 threshold + pixel-cert rule extracted to pure, tested policies;
dumpsys gotcha recorded in the android-logcat-device-validation skill) ·
**#33 free-space precheck** · **#34 cache-clear scoping**.
**Every confirmed audit finding rated Medium or above is now shipped.** 152 unit tests, 0 failures.

## NEXT TASK (updated) — remaining backlog, risk order
1. **Instrumented SAF rollback test for SAFE-001** — the one gate SAFE-001 still lacks. The strict
   size rule is unit-tested (`ReplacementSizeCheckTest`) but the actual mid-write rollback (inject
   ENOSPC / a truncated stream, assert the original is restored and the message is honest) has never
   been executed. Highest remaining value on the safety side.
2. **Speed lever (SPEED_SCOPING.md)** — biggest user-visible win left: the probe-skip latch skips the
   cheap probe but still runs a FULL encode it then discards (~9 min of the measured 12.8 min/batch
   wasted-encode total). Requires care: do not let it deny a winnable class (the everCompressed
   exemption exists for exactly that reason).
3. vmaf_jni dist-array release on the `ref==NULL` error branch (Low, native-OOM only).
4. Mp4MetadataRemuxer empty-track early-return release path (Low).
5. Evaluate temp-write + atomic same-volume rename to drop SAFE-001's recovery-copy I/O.
6. Remove dead `MainActivity`/`CompressorViewModel` + `recommendedBatchParallelism` ONLY after
   reference analysis.
7. GitHub Releases update-check page (older backlog item, never started).

## (HISTORICAL — already implemented in #31) QUAL-001 design
Branch `fix/verification-label-honesty` (already created off main). Goal: stop labeling a PL
output "Perceptually Lossless Verified" (implying pixel proof) when pixel scoring did NOT actually
certify it. `OutputVerifier.verify` (OutputVerifier.kt:396) sets that verdict on STRUCTURAL grounds
only, before pixel certification, and doesn't know the cert outcome.

Root truth: an output is pixel-certified ONLY when the ViewModel's certify step returned
`PairScoreOutcome.Scored` AND `certOk` (measured windows passed). NOT `probeEligible` alone — a
probeEligible item whose certify returns `Unavailable` is accepted structurally
(`certificationOutcomePasses(usedRatio>=default, Unavailable)=true`) and would otherwise wear the
full label dishonestly. Minimal correct change:
1. Add `pixelCertified: Boolean = false` to `OutputVerificationReport` (data class in
   OutputVerifier.kt). Do NOT change OutputVerifier's structural verdict logic.
2. In BatchCompressorViewModel, declare `var diagnosticPixelCertified = false` alongside the other
   diagnostic vars; in the certify block set it `true` ONLY where `certOk && certScores != null`
   (i.e. `certOutcome is Scored`). It stays false for `!probeEligible` (no certify) and for
   `Unavailable`-structural accepts.
3. Just before terminal classification (~ViewModel line 1194, after the remux-fallback branch), if
   `verification.verdict == "Perceptually Lossless Verified" && !diagnosticPixelCertified`, replace
   with `verification.copy(verdict = "Perceptually Lossless Verified (structural)", pixelCertified
   = false)`; else if certified `verification.copy(pixelCertified = true)`. Remux/HQ/SS verdicts
   untouched.
4. Thread `pixelCertified` into DiagnosticsRecorder.job(...) as a new job field (schema-additive,
   no new event type) + parse_batch_logcat.py STRUCTURED_JOB_FIELDS + CSV, so logs/analytics agree
   with the UI. UI (BatchMainActivity) already renders `verification.verdict` -> honest for free.
5. Tests: OutputVerifier test asserting `pixelCertified=false` default; a ViewModel-level or
   formatter test proving structural-only never emits the unqualified full label. Do NOT weaken any
   acceptance/cert-fail behavior (cert-fail still SKIPPED_WOULD_DEGRADE).
Consumers to keep consistent (grep already done): OutputVerifier, BatchCompressorViewModel,
DiagnosticsRecorder, BatchMainActivity (UI), SmartPerceptualProfileEngine (learning reads
verified-success; do NOT let the "(structural)" wording break its match — check `recordVerifiedSuccess`
gating on `verification.verified`, which is a Boolean and unaffected by the verdict-string change).

## Confirmed backlog after QUAL-001 (risk order, one focused PR each)
free-space StatFs precheck; scope clearBatchCache to current-batch items; vmaf_jni dist-array
release on ref==NULL error branch; muxer empty-track release path; instrumented SAF rollback test
for SAFE-001; evaluate temp-write + atomic same-volume rename to drop the recovery-copy I/O; remove
dead MainActivity/CompressorViewModel + recommendedBatchParallelism ONLY after reference analysis.
Audit verdicts: docs/post-pr23/AUDIT_TRIAGE.md (OpenRouter NO-GO refuted; Opus findings real).

## Algorithm/reliability/safety backlog (unchanged, evidence-gated)
Speed lever = eliminate discarded encodes (docs/post-pr23/SPEED_SCOPING.md): probe-skip latch runs
a full encode it then discards (~9 min); concurrency is evidence-contradicted. Metric: 62/69 skips
genuine; NEG/v1 gate would reduce coverage. VMAF v1 integration proposal pending. Secure Folder
validation deferred by owner.
