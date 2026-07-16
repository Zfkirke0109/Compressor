# PR #23 forensic evaluation — executive findings

Authoritative capture: `validation/captures/batch_20260715_141019` (batch_1784136791853), build
`4f4909e` (CI merge-ref of PR #23 head `75d2624`), app 1.6.1, Normal profile (user 0), 172
selected = 172 processed = 172 captured, parserExitCode 0, complete/reconciled/not-partial/not-
incompatible. **Integrity: AUTHORITATIVE.** 0 malformed records, 0 duplicate/missing sequences,
0 privacy leaks, 0 decision-path contradictions across 172 jobs.

Every number below is **directly measured** from the capture unless tagged (derived) or
(external). Evidence categories are kept separate throughout.

## Bottom line

PR #23 **did increase real compression and did not weaken any safety invariant** — but a
**proven latch defect** is suppressing measured trials on files that could compress, and the
same-set rerun is **confounded by cross-run learning persistence**.

| Metric (measured) | old 084112 (7e735c8) | authoritative 141019 (4f4909e) |
|---|---:|---:|
| Real compressions | 0 | **9** |
| Bytes saved | 0 | **718.2 MB** |
| Median savings (compressed) | — | **40.7%** |
| Weighted savings (compressed) | — | 19.7% |
| UNEXPECTED_REMUX | 14 | **2** (label fix works: 63 now REMUX_PREFERRED_BY_EVIDENCE) |
| Encoder failures | 4 | 1 |
| Elapsed | 35.0 min | 66.5 min |
| Per-file transitions vs 084 | — | 9 improved / **0 regressed** / 163 unchanged |

## The user's hypothesis — tested and split

**CONFIRMED (measured defect):** the decaying probe-skip latch denied probes to **26 files in
three profile classes that ALSO produced verified compressions** — e.g. `video/avc|720p|24|sdr|
lt10m` verified 3 compressions (one at 42%) yet latch-suppressed 9 files in the same class. The
latch groups by coarse technical buckets, but compressibility is **content-dependent within a
bucket**, so class-level suppression denies winnable per-file trials. This is the "learning latch
suppressing opportunities" the user suspected. **This is a proven, patched defect** (see below).

**CONFOUND (measured):** **83 of 83 latch suppressions inherited state from the prior identical-
set run** `batch_20260715_103237`. The SharedPreferences learning store persisted, so this rerun
under-probed relative to a fresh store. The 9-vs-8 delta between the two new-build runs is not a
clean measurement — a fresh-store run would probe more.

**REFUTED (falsification, as requested):** the VMAF window thresholds are **not** too strict.
Of 39 probed-and-failed files with recorded scores, **38 are clear-fail** (median rejected p5 =
79.2 vs the 91 bar; worst-window gaps of 5–12 points), only 1 near-miss. Lowering thresholds
would accept visibly degraded output. Files that were actually probed genuinely degrade.

**REFUTED (external + derived):** "outdated VMAF model causes over-conservatism" — the app does
use an outdated model ((external) latest libvmaf v3.2.0; app bundles API 3.0.0 / model
`vmaf_v0.6.1`, the v0 generation), but VMAF v1 (June 2026) is **more discerning** (adds banding/
chroma awareness, drops VIF) → it would reject *more*, not compress more. The model is a
correctness/honesty upgrade, **not** the over-conservatism lever.

## Root-cause census (measured, all 172)

| Root cause | count | correct behavior? |
|---|---:|---|
| probe_suppressed_by_latch | 77 | **PARTLY — 26 in winnable classes = defect; rest genuine** |
| every_candidate_failed_measured_quality | 55 | correct (38/39 clear-fail) |
| accepted_real_compression | 9 | success |
| efficient_after_probe | 9 | correct |
| hdr_remux_only_policy | 9 | correct (safety invariant) |
| genuinely_starved_below_noise | 8 | correct (bpp < 0.03) |
| codec_downgrade/efficient_no_probe | 2 | correct (AV1→HEVC etc.) |
| structural / floor / encoder | 3 | correct (honest fallback) |

## Change made this pass (evidence-proven, minimal)

`SmartPerceptualProfileEngine`: **known-winnable exemption** — a profile class that has ever
verified a compression is never latch-suppressed (`everCompressed` flag, set on
`recordVerifiedSuccess`, checked first in `shouldSkipProbes`). Recovers the 26 denied-winnable
probes and generalizes. Only ever ADDS probes; never lowers a quality bar (OutputVerifier +
sampled certification remain the sole verdict). Regression test added. No threshold, floor,
ladder, or VMAF-model change.

## Proposed, NOT done (require separate controlled work)

1. **VMAF v0.6.1 → v1.0.16 model** — correctness upgrade (catches banding v0 misses), large
   integration (ship model assets, prove feature compatibility, versioned telemetry). Would
   likely reduce, not increase, accepted compressions. See VMAF_V1_INTEGRATION_PLAN.md.
2. **HFR-aware model for 50/60fps** — (measured) 18 60fps files, 0 compressed, 13 already-
   optimized; weak evidence of false rejection in THIS set, but v0.6.1 is non-HFR.
3. **Fresh-store validation run** — required to de-confound the latch persistence (see
   NEXT_VALIDATION_PLAN.md).
