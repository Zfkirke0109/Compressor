# v1 Optuna study — adversarial review findings

Condensed from a full multi-agent review (2026-07-19) of the externally-run v1
Optuna calibration. The v1 candidate tightened the windows to 95.6 / 91.2 / 84.4
and raised the probe bits-per-pixel floor 0.03 → 0.069. **Verdict: reject the
candidate; make no production change.** The findings below are why, and they are
what `scripts/run_study_v2.py` is built to avoid.

## Reproducibility

The v1 result reproduces bit-exactly (best value, all four parameters, all 500
trials). Reproducibility was never the problem — the evaluation was.

## The evaluation had no holdout

`run_optuna.py` used `GroupKFold`, but evaluated the **same fixed thresholds** on
every fold and averaged. Because confusion counts are additive over rows, the
mean fold loss equals the full-dataset loss ÷ 5 plus a fold-constant penalty —
i.e. in-sample optimization with zero generalization control. Reproduced
empirically: best_value −2.7 = −0.25 × 54 / 5 to machine precision.

## Three of four parameters are unidentified

Exhaustive evaluation of the full grid (15,101,526 combinations) shows the
tied-optimal set is a box, not a point:

| Parameter | v1 value | Identifiability |
|---|---|---|
| `WINDOW_MEAN_MIN` | 95.6 | Only identified parameter, and only to the interval (95.5, 95.6] |
| `WINDOW_P5_MIN` | 91.2 | Arbitrary — [91.0, 92.2] all tie; **zero** rows between current and candidate |
| `WINDOW_MIN_MIN` | 84.4 | Arbitrary — [84.0, 85.8] all tie; **zero** rows between |
| `PROBE_MIN_SOURCE_BITS_PER_PIXEL` | 0.069 | **Completely unidentified** — objective flat over [0.015, 0.1014]; every positive has bpp ≥ 0.1015, above the whole search range. TPE sampler noise |

## The whole improvement is one artifact row

Current vs candidate differ on exactly **one** of 412 rows: `b6b45b359e84`
(batch_20260715_193710, mean floor exactly 95.5). Its "false accept" under
current policy exists only in the offline data because logged window floors are
rounded to one decimal (95.5) while production compares the **unrounded** score —
the device itself rejected that source five times across three builds. The study
optimized against a logging-rounding artifact.

## The data is contaminated and duplicated

- 76% of rows (38 of 54 positives) carry VMAF scores from **before** the PTS
  frame-pairing fix (commit `4d50c03`, 2026-07-16), which produced both false
  skips and false accepts. Five training positives are proven-false (133776.mp4,
  `nameHash 4260fa33af75`).
- 3.8× duplication: 104 of 108 sources appear in multiple capture batches.
- `SKIPPED_WOULD_DEGRADE` negatives mix genuine measured failures with
  misalignment rejections and cert-unavailable-sub-default artifacts.

## The bpp floor would blind exploration for no benefit

Zero label-0 rows below bpp 0.069 pass the current window floors, so the raise
suppresses **no** observed false accepts. Meanwhile the raw job history holds 110
verified `TRANSCODED_SMALLER` wins below 0.069 (74 unique sources, ~5.8 GB
saved) that the study never saw. Raising the floor would permanently deny probing
— and therefore counterfactual evidence — to that entire band. Correct
conclusion for the band: *unidentified, keep 0.03* — not *raise*.

## Takeaway

The v1 tooling was well-intentioned (grouping, asymmetric FA cost, honest
warnings) but the evidence base — contaminated, duplicated, two-day, in-sample —
cannot support any threshold change. The bottleneck is the **data**, not the
optimizer. See `NEXT_ROUND_INSTRUMENTATION.md`.
