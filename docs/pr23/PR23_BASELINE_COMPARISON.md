# PR #23 vs baselines — matched 172-file comparison (Phase 7)

All three runs are the **same 172 files** (100% jobId overlap; matched by stable salted jobId,
never by sequence). Per-file rows in MATCHED_BASELINE_COMPARISON.csv.

| | 084112 (old 7e735c8) | 103237 (new 0199486) | **141019 (auth 4f4909e)** |
|---|---:|---:|---:|
| Real compressions | 0 | 8 | **9** |
| Bytes saved | 0 | 607.4 MB | **718.2 MB** |
| UNEXPECTED_REMUX | 14 | 33 | **2** |
| REMUX_PREFERRED_BY_EVIDENCE | n/a | n/a | **63** |
| ALREADY_HIGHLY_OPTIMIZED | 73 | 64 | 54 |
| SKIPPED_WOULD_DEGRADE | 84 | 84 | 43 |
| Encoder failures | 4 | 1 | 1 |
| Elapsed (min) | 35.0 | 64.1 | 66.5 |

## Transitions 084112 → 141019 (measured, counts)
- 37 ALREADY_HIGHLY_OPTIMIZED → ALREADY_HIGHLY_OPTIMIZED
- 31 SKIPPED_WOULD_DEGRADE → REMUX_PREFERRED_BY_EVIDENCE
- 30 SKIPPED_WOULD_DEGRADE → SKIPPED_WOULD_DEGRADE
- 23 ALREADY_HIGHLY_OPTIMIZED → REMUX_PREFERRED_BY_EVIDENCE
- 17 SKIPPED_WOULD_DEGRADE → ALREADY_HIGHLY_OPTIMIZED
- 13 ALREADY_HIGHLY_OPTIMIZED → SKIPPED_WOULD_DEGRADE
- 9 UNEXPECTED_REMUX → REMUX_PREFERRED_BY_EVIDENCE  (label fix)
- **5 UNEXPECTED_REMUX → TRANSCODED_SMALLER**  (undershoot tolerance + floor recovery)
- **3 SKIPPED_WOULD_DEGRADE → TRANSCODED_SMALLER**  (retreat rung / bisection)
- 1 OUTPUT_VALIDATION_FAILED → TRANSCODED_SMALLER
- 2 SKIPPED_WOULD_DEGRADE → UNEXPECTED_REMUX

## Interpretation (derived)
- **9 improved, 0 regressed, 163 unchanged** vs the old build. Real, monotone improvement in
  bytes saved with no measured regression.
- The big REMUX_PREFERRED_BY_EVIDENCE bucket (63) is mostly *relabeling* — the same up-front
  learning-latch remuxes that the old build split between SKIPPED/ALREADY_OPTIMIZED/UNEXPECTED.
  It is **not** new compression and is not counted as such.
- **Confound:** 141019 vs 103237 (both new build) is NOT a clean A/B — the learning store
  persisted between them (83/83 latch inheritance). The +1 compression / +110 MB from 103237 to
  141019 cannot be attributed cleanly to code; it reflects learning drift + label reclassification.
  The trustworthy comparison is **old-build 084112 (fresh-ish) → 141019**, which is unambiguous:
  0 → 9 compressions, 0 → 718 MB.

## Runtime economics (derived)
- 66.5 min / 172 files = 23.2 s/file average; 718 MB / 66.5 min = **10.8 MB saved per minute**.
- The +31.5 min vs old build is probe/encode cost. The known-winnable latch fix will add some
  probe time on winnable classes but is bounded by the same 150 s/clip probe budget.
