# Phase 1 — keep-original remux fast path (perf/remux-keep-original-fast-path)

## What changed
- **`BatchMaterialization.kt` (new):** `MaterializedOutput` sealed model (`ReusedSource` /
  `GeneratedFile`), `OriginalReuseBlockReason` enum, `OriginalReusePolicy.evaluate(...)` — the
  single audited, pure, fail-closed guard — and `retentionReport(...)` (honest verification
  record for a retained original; `replacementSafe=false` always).
- **`BatchTerminalResult.kt`:** `BatchTerminalInput.retainedOriginalNoOutput`; classifier maps a
  readable retained original to the existing keep-original terminals (ALREADY_HIGHLY_OPTIMIZED /
  REMUX_PREFERRED_BY_EVIDENCE) and an unreadable source to OUTPUT_VALIDATION_FAILED (fail closed).
  Never a compression; never replacement-eligible.
- **`BatchCompressorViewModel.kt`:** fast-path block at the up-front keep-original decision.
  Guards audited with REAL probes at decision time: `ContentResolver.getType` (container MIME,
  never extension) + `openFileDescriptor("r")` (readability). Eligible → no cache file, no
  MediaExtractor→MediaMuxer copy, no copy-verification; original surfaced as the result
  (`outputUri = sourceUri`), savedBytes 0, message "Already optimal — original retained (no copy
  written)". Blocked → **unchanged full remux** with the guard name recorded
  (`originalReuseBlockReason`).
- **Telemetry:** `materializationMode` (REUSED_SOURCE/GENERATED_FILE), `originalReuseBlockReason`,
  `copyAvoidedBytes` on every job record; parser + tests updated.

## Guards (full remux always retained for)
explicit Remux Only • any privacy-stripping mode • unknown/non-MP4-family containers (probed
MIME) • unreadable source • any distinct-output requirement • any non-keep-original decision.

## Test coverage vs the 11 required checks (honest accounting)
| # | Requirement | Coverage |
|---|---|---|
| 1 | keep-original + PRESERVE_ALL writes no copy | code path (no cacheOutputFile/remux call) + policy unit test; device confirmation next run |
| 2 | original SHA-256 unchanged | by construction (never opened for write) + device check next run |
| 3 | result references source URI | fast-path sets outputUri=sourceUri; unit-level via classifier/report tests |
| 4 | savedBytes 0, terminal correct | `retainedOriginalIsKeepOriginalTerminalNeverCompression` (unit) |
| 5 | privacy-strip still full remux | `anyPrivacyStrippingModeForcesTheFullRemux` (unit) |
| 6 | explicit Remux Only still full remux | `explicitRemuxOnlyAlwaysProducesARealRemux` (unit) + fast path gated on `quality != REMUX_ONLY` |
| 7 | failed-encode fallback still verified output | untouched code path (fallback remux runs `remuxOnlyOne` as before) |
| 8 | incompatible containers still normalize | `containerCompatibilityIsProbedNeverAssumed` (unit) |
| 9 | cancellation: no partials, source safe | fast path performs no writes; covered by construction + existing cancellation handlers |
| 10 | counts/ordering deterministic | record emitted exactly once per item via recordDiagnosticJob before return |
| 11 | latch + cooldown tests stay green | full suite run (see below) |
Additional: retention report honesty (`retentionReportIsHonestAndNeverReplacementSafe`),
unreadable-source fail-closed (classifier unit test).
**Deferred to device validation:** SAF permission revocation mid-batch, process death, share-flow
of reused source, leak scanning of descriptors — listed in DEVICE_VALIDATION_PLAN items.

## Benchmark
Baseline (measured, capture batch_20260715_193710): 79 remux items = 40.3 min; worst 267 s for a
0-byte-savings 1 GB-class file; remux time vs size r=0.80 (copy I/O dominated).
Fast-path projection: eligible keep-original items drop to ~1–3 s (two ContentResolver probes +
record). Acceptance target (≥80% median reduction) is asserted for the NEXT device run — no
on-device claim is made until measured. The benchmark comparison = same 172-file corpus, count of
REUSED_SOURCE records × copyAvoidedBytes vs the 193710 per-item elapsedMs for the same jobIds.

## Hostile audit (post-implementation, repo-wide) — consumer/flow safety table

| Consumer / flow | Generated-file behavior | Reused-source behavior | Safe? | Evidence |
|---|---|---|---|---|
| `candidateFiles` failure cleanup (VM:657, 1326, 1329) | cache files deleted on failure | never added — fast path adds nothing to the set | ✅ | `candidateFiles +=` exists only in full-remux/encode paths (870/876/924/1133) |
| `clearBatchCache()` (VM:2738) | deletes `cacheDir/batch_compressed_videos/*` | source URI is not in the app cache dir | ✅ | path-scoped deletion only |
| `clear()`/`clearBatchState()` (VM:414/429) | resets state list; cache cleanup only | no per-item file ops | ✅ | body inspection |
| failure-path `outputFile.delete()` (VM:~1115) | deletes generated cache file | no `outputFile` exists; fast path returns earlier | ✅ | control flow |
| `replaceOriginalSafely` | gated on `verification.replacementSafe` | unreachable (early return) AND diagnostics record replacement blocked | ✅ | control flow + `replacementSafe=false` |
| share-all (`shareCompressedOutputs`, Activity:848–870) | shares from `outputPath` | **excluded** (`outputPath == null`) | ✅ conservative | Activity:850 `mapNotNull` |
| `saveAllCopiesToGallery` (VM:1496) | copies `File(outputPath)` | **excluded** | ✅ | VM:1498 |
| `hasOutputs` (VM:175) | counts `outputPath != null` | retained items don't count as outputs | ✅ honest | body |
| learning updates (`recordVerifiedSuccess`/`recordFailure`) | encode/cert paths only | unreachable — fast path returns before all learning calls | ✅ | control flow |
| batch accounting (`BatchTerminalAccounting`) | savings for real compressions | keep-original terminals → savedBytes 0 | ✅ | unit test (mixed-batch determinism) |
| diagnostics | `OutputVerificationReport` verdicts | typed `RetainedSourceValidation`; verdict "Original Retained … not output-verified"; `materializationMode=REUSED_SOURCE` | ✅ after audit fix | this branch |
| process restoration | nothing persisted (in-memory StateFlow) for ANY item | identical — no worse | N/A (equal) | architecture |
| `outputUri` readers | none exist today (write-only field) | value `sourceUri` is inert but honest state | ✅ | repo-wide grep |

## Audit corrections applied (post-PR-open)
1. **Removed the fabricated `OutputVerificationReport`** for retained sources — replaced with the
   typed `RetainedSourceValidation` (readable/playable-at-decision-time, size, normalized MIME,
   timestamp). Diagnostics derive verdict/verified from it; `verification=null`; item UI
   `verificationReport=null`; a `require()` guard forbids a job carrying both. No "unchanged"
   verification fields are synthesized anywhere.
2. **MIME normalization**: trim + strip parameters + lowercase before the allow-list compare
   (`"video/mp4; codecs=avc1"` now eligible); null/blank/param-only/octet-stream still fail
   closed. Compatibility is never inferred from a filename extension.
3. **Share/save honesty correction**: retained items are **excluded** from share-all and
   save-all (both key on `outputPath != null`). The earlier claim that retained results are
   shareable via the app was wrong and is withdrawn — the original remains exactly where the
   user keeps it; the app simply reports honest retention. (A per-item share affordance for
   retained sources would be new UI work, deliberately out of scope for this audit-fix PR.)
4. **Session-scoped results documented**: batch results do not survive process death for ANY
   item type (in-memory state). Retained sources therefore need no durable-permission machinery
   beyond the batch's existing read grants; the read-open check at decision time is the guard.

## MEASURED device benchmark (2026-07-16, S23 Ultra, build 2f4ae52 / CI e75d480)
Matched per-jobId comparison, capture batch_20260716_071913 (fast path) vs batch_20260715_193710
(pre-fast-path), 172/172 jobIds matched, 70 keep-original pairs:
- keep-original elapsed: median **19.4 s -> 0.06 s** (p90 57.3 -> 8.2 s; max 151.6 -> 12.8 s)
- **per-item median reduction 96.8%** — acceptance target (>=80%) MET; 50/63 pairs >= 80%
- total on matched pairs: 29.6 -> 2.3 min (**27.3 min saved**); whole-batch wall ~32 min for 176
  files (previous build: ~81 min for 172)
- largest file: 1,883 MB retained in **0.01 s** (was 151.6 s); 93 REUSED_SOURCE overall,
  **17.8 GB of copying avoided**, all savedBytes = 0
- guards verified on device: Remux Only -> 0 reused (21/21 GENERATED_FILE); privacy strip ->
  0 reused (blockReason PRIVACY_STRIP_REQUIRED fired); 3 reference sources SHA-256 byte-identical
  pre/post, including after an abrupt mid-batch process kill
- retained-items-never-compression asserted across both captures: PASS
- documented gap: graceful in-app cancel (session_cancelled) not exercised this cycle (code path
  untouched by the PR; abrupt-kill was the harsher source-safety test)
Analysis script: validation/captures/batch_20260716_071913/matched_benchmark.py (local, not
committed with captures). Full evidence in PR #24 comments.

## Status
COMPLETE AND MERGED (PR #24 -> main, 2026-07-16). Implementation + audit fixes + unit tests +
clean validation + device validation + measured benchmark all recorded above and in PR #24.
Remaining follow-ups: graceful in-app cancel device check (fold into a future run); RUNTIME_
EVALUATION.md committed alongside this update. Secure Folder validation still deferred by owner.
