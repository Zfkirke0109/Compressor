# Phase 3 rescoped — the "run faster" lever is wasted encodes, not concurrency (2026-07-17)

## Evidence
Timing breakdown of the fresh 176-file rerun (capture batch_20260716_233032, post-pairing-fix
build, learning store cleared so everything probed). Analysis scripts in
validation/captures/analysis_rerun_20260717/ (timing_breakdown.py, unexpected_remux.py).

Total batch: 36.1 min job-elapsed (+3.9 min thermal cooldown carried).

| terminal | n | time | share |
|---|---|---|---|
| UNEXPECTED_REMUX (full encode -> discarded) | 12 | 12.8 min | 35% |
| SKIPPED_WOULD_DEGRADE (probed, failed) | 82 | 12.1 min | 34% |
| TRANSCODED_SMALLER (the wins) | 14 | 9.6 min | 26% |
| ALREADY_HIGHLY_OPTIMIZED (mostly retained) | 63 | 1.7 min | 5% |

Full encodes = 22.4 min; **12.8 of that (57%) is discarded (UNEXPECTED_REMUX).**

## Why concurrency is the WRONG lever
- One QTI hardware HEVC encoder; parallel Transformer encodes contend for it (serialize or fail).
- Thermal already climbing: 26/26 encode jobs ended non-nominal ("slightly warm"/"warm").
  More parallel encode heat -> more cooldown, net-negative.
- The win is doing LESS encoding, not overlapping it.

## The real lever: eliminate discarded full-encodes (12.8 min, 35%)
Two mechanisms (unexpected_remux.py):

### Bucket 1 — latch skips the probe but NOT the encode (4 files, 9.0 min)
The probe-skip latch (PR #23) fires when a class "measured visible loss at every candidate in 2
consecutive recent ladders". It skips the cheap 1.2s x 3 probe -> but the plan still runs a FULL
encode (e.g. 155916.mp4: 224s on a 25-min 1080x1920 clip), which then fails cert
("output bitrate fell below the verified safety threshold") and is discarded for a remux.
Worst of both: no cheap gate AND a wasted encode. The everCompressed exemption already means only
NEVER-won, repeatedly-failing classes reach this state.

### Bucket 2 — probe unmeasurable, so encode ran anyway (8 files, 3.8 min)
Probe ran, "no candidate ratio passed", but highestCandidateMeasuredRejected was false (the
safest rung was UNMEASURABLE, not measured-rejected), so preferRemux never set -> full encode ->
fail. Includes odd/sub-SD dimensions (624x352, 272x480, 640x360, 272x480) that throw
ERROR_CODE_ENCODING_FAILED on both probe and full encode: the hardware cannot encode these,
yet the full attempt runs and fails anyway.

## Proposed fix (evidence-gated, quality-preserving)
1. **Bucket 2a (zero correctness risk):** when the probe at the SAFEST ratio is UNMEASURABLE due
   to an encoder failure (ERROR_CODE_ENCODING_FAILED), treat the source as encoder-incapable and
   skip straight to remux/skip — the full encode is guaranteed to fail the same way. No
   compressible file is ever lost (the encode could not have produced output).
2. **Bucket 1 (bounded correctness trade-off):** when the probe-skip latch fires for a
   never-won class, skip the full encode too (-> honest remux/skip) instead of encoding-then-
   discarding. The decaying latch still force-re-probes every 3 encounters, so a newly-
   compressible file in that class is caught within 3 batches; everCompressed classes are exempt
   (never latch-suppressed). Trades a bounded, self-healing risk of a delayed compression for
   ~9 min/batch. NEEDS OWNER RISK SIGN-OFF (never silently weaken a quality gate).

Concurrency framework (original Phase 3) is shelved as evidence-contradicted.
