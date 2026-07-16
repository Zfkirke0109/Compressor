# Schema & extractor audit (Phase 3)

## New-build schema (measured from jobs.jsonl, 172 records)
PR #23 records candidate/verification evidence in **flat/compact fields**, NOT nested objects.
The earlier extractor's "0 nested candidate objects" was correct for the OLD schema and is
**not** a defect — the new evidence is in parallel flat fields. Field presence (non-null / 172):

| Field | non-null | form |
|---|---:|---|
| probedRatios | 88 | comma-delimited string ("0.70,0.80,0.90,0.85") |
| pixelProvenRatio | 6 | double |
| probeDetail | 153 | free-text string |
| probeWindowScores | 64 | ";"-delimited "mean/p5/min" |
| certWindowScores | 21 | ";"-delimited "mean/p5/min" |
| thermalStart / thermalEnd | 105 | string bucket |
| plannedTargetRatio / plannedTargetVideoBitrate | 172 / 172 | double / int |
| discardedVideoBitrate | 13 | int |
| fallbackReason | 14 | string |
| terminal, countsAsRealCompression, outputSize, savedBytes, savedPct | 172 | — |

## Extractor built this pass: `pr23_forensic.py`
Distinguishes all 28 states the directive requires (absent vs null vs empty-list vs delimited
string vs malformed; probe not-eligible vs suppressed vs omitted; 1 vs multi candidate; retreat;
bisection; pass; all-fail; proven; encode attempted/failed/completed; cert pass/fail; floor
recovery; accepted/discarded; remux fallback; skip-no-output; terminal; real-compression). It
parses the flat fields directly (does NOT rely on recursive nested-object discovery) and treats
an empty probe list as a distinct state, not "missing telemetry."

Outputs (all under this analysis dir): PER_JOB_ANALYSIS.csv (172 normalized rows, 60+ columns),
PER_JOB_DECISION_TRACES.jsonl (172 readable traces + contradiction flags), PROBE_LADDER_/
RETREAT_RUNG_/BISECTION_/SAME_CODEC_/UNDERSHOOT_/LATCH_TIMELINE/WINDOW_SCORE/THERMAL_/
TERMINAL_ROOT_CAUSES CSVs, MATCHED_BASELINE_COMPARISON.csv, UNMATCHED_JOBS.csv, and the JSON bundle.

## Parser status
`scripts/diagnostics/parse_batch_logcat.py` already accepts all new fields (probedRatios,
pixelProvenRatio, probeDetail, probeWindowScores, certWindowScores, thermalStart/End) — added in
commit 03b8a2f; `test_parse_batch_logcat.py` asserts they survive capture (12/12 pass). **No
parser defect found for PR #23 schema.** structured_ignored_field_count = 0 on this capture,
confirming no unknown fields were dropped.

## Prior-conclusion change
The old extractor's "no candidate evidence" was a *schema-generation* artifact, not a data loss.
With flat-field parsing, the authoritative capture yields full candidate-level evidence for the
88 laddered jobs and window scores for 64. No conclusions from the old extractor were carried
into the PR #23 findings.
