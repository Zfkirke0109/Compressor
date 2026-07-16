# Capture-integrity report (Phase 2)

Capture: `batch_20260715_141019`. Classification: **AUTHORITATIVE.**

## Reconciliation (all measured, all consistent)
| Check | Value |
|---|---|
| manifest selected / processed / captured unique | 172 / 172 / 172 |
| session_start selectedCount | 172 |
| session_summary processed | 172 |
| jobs.jsonl rows / unique ids | 172 / 172 |
| raw CompressorDiag events | 174 (1 session_start + 172 job + 1 session_summary) |
| sequences | 0..173 contiguous, no gaps, no reordering |
| eventIds | 174 total, 174 unique, 0 duplicates, 0 malformed |
| schemaVersion | uniform (2) |
| jobs terminals vs aggregate.json by_terminal | identical |
| manifest flags | complete=true, partial=false, reconciled=true, incompatibleBuild=false |
| parserExitCode | 0 |
| structured_ignored_field_count | 0 |
| session_summary ordering | after all 172 jobs (sequence 173) |

## Terminal totals (measured)
TRANSCODED_SMALLER 9, REMUX_PREFERRED_BY_EVIDENCE 63, ALREADY_HIGHLY_OPTIMIZED 54,
SKIPPED_WOULD_DEGRADE 43, UNEXPECTED_REMUX 2, OUTPUT_VALIDATION_FAILED 1. Sum = 172.
session_summary: processed 172, failed 1, skipped 43, realCompressions 9, nonCompressions 119,
totalBytesSaved 718,181,603. Sum of per-job savedBytes on real compressions reconciles.

## Anomaly scan (measured)
- Malformed JSON / truncated lines: 0.
- Duplicate/replayed events, reconnect duplication: 0 (single connection, replayLineCount 0).
- Mixed batchIds / legacy records / mixed schema: none.
- Privacy leak scan (raw filenames, /sdcard, content://, DCIM, drive paths in job records): **0**.
- Decision-path contradictions across 172 traces: **0** (see PER_JOB_DECISION_TRACES.jsonl).

## Documented limitation (not an integrity defect)
This is a **rerun of the same 172-file set** previously run as `batch_20260715_103237` on the
prior new build. The on-device SharedPreferences learning store persisted between runs, so the
probe-skip latch entered this run pre-armed (83/83 suppressions inherited prior-run state). The
capture is internally complete and authoritative; the *learning state* is not fresh. A clean
measurement of first-encounter probing behavior requires a fresh store (see NEXT_VALIDATION_PLAN).
