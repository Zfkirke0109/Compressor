---
name: pl-batch-capture-analysis
description: Analyse a Compressor diagnostics capture (the in-app "Everything" ZIP, session.jsonl, decisions.log) from a device batch run — counting real compressions and savings, explaining rejections, comparing runs, and turning findings into fixes. Use whenever the user uploads a diagnostics ZIP or log, asks "review my latest run/logs", mentions a batch number (b165, b169, …), a job id (job_xxxxxxxxxxxx), or asks why files were skipped, kept, or not compressed.
---

# Analysing a Compressor batch capture

Device captures are the ground truth; code comments are hypotheses. Every number you report must come from a command you ran on the capture.

## Steps

1. **Pick the right batch.** An export holds many batches from several builds, and two exports often overlap (identical shared entries). Never analyse "the latest" by default: a High Quality batch can follow the PL one. List sessions, then name one:
   ```sh
   python3 scripts/diagnostics/parse_session_jsonl.py EXPORT.zip            # lists sessions, reports newest
   python3 scripts/diagnostics/parse_session_jsonl.py EXPORT.zip --batch batch_<id>
   ```
   Check mode, build tag, and whether it completed (`session_summary`) or was cancelled.
2. **Baseline totals**: real compressions and saved bytes (only `countsAsRealCompression` with a smaller kept output), terminal counts, and `contradictory acceptance` (must be 0 on schema ≥ 3).
3. **Explain every non-win class** from job records plus `runs/<batch>/decisions.log` (grep the job id): measured probe rejection (how far below the bar, which gate binds, highest rung tried), certification failure, size gate (learned vs measured overshoot), damaged/unparseable source (`media3Input`), structural failure (`failedChecks`), above scoring limits.
4. **Compare runs** by job id, not by position, and say they are observational: learned state (`learnedStateIdentity` / snapshot sha256), order, thermal and charge differ. Job ids hash URIs, not content.
5. **Re-measure the calibration inputs**: `probe->cert` drift, marginal passes, overshoot prediction error.
6. **Separate** confirmed defects (reproduced in code + capture), opportunities (with the evidence and what is unobserved), and speculation. For "projected" gains count only recoveries shown by deterministic replay or direct prior evidence.

## Rules

- Do not lower thresholds, margins or floors to raise the pass count; do not count a copy, remux or retained original as a compression.
- Flag, never trust, records whose verdict fields contradict their terminal (pre-schema-3 captures).
- Replay decisions through production Kotlin (`B169ObservationalReplayTest` pattern, fixture via `scripts/diagnostics/make_replay_fixture.py`), not a Python re-implementation.
- End with what only the phone can confirm, and the exact commands (`docs/DEVICE_VALIDATION.md`).
