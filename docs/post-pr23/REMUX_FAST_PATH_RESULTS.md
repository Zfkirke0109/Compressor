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

## Status
Implementation + unit tests complete; full suite run recorded below; device benchmark pending the
next user-run batch (no Secure Folder this cycle).
