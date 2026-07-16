# Statistical analysis (Phase 12)

Sample sizes are small in several strata — reported honestly, no over-fitting, no certainty
claims from handfuls.

## Accepted compressions (n=9, measured)
- savedPct: min 9%, median **40.7%**, max 43%. Bimodal: a cluster at 41–43% (5 files, all
  `video/avc` 720p/1080p 24fps, proven ratio 0.65 via bisection) and a low cluster 9–26%.
- Weighted savings (Σsaved/Σsource over compressed) = **19.7%** — the big-percentage files are
  smaller, so byte-weighted savings is lower than the median. Honest headline is bytes: 718 MB.
- All 9 are H.264→HEVC, SDR, ≤1080p, ≤30fps. **Overfit warning:** zero compressions came from
  HEVC/AV1 sources, 4K, or 60fps in this dataset — savings generalization beyond H.264≤1080p is
  unproven here.

## Undershoot delivered/requested (n=5 this run — UNDER-POWERED)
mean 0.9495, median 0.947, sd 0.041, range 0.885–0.987. Consistent with the richer OLD-run
sample (084112, n=11, mean 0.887, sd 0.019). Combined direction: QTI HEVC VBR delivers ~0.88–0.95
of requested. The 0.15 tolerance covers both samples' worst case (0.885 ⇒ 0.115 undershoot < 0.15).
**Do not re-tune tolerance from n=5**; the OLD n=11 remains the calibration basis.

## Probe-rejected window scores (n=39 measured-fail jobs, measured)
- Rejected-window pooled: median mean 90.0, median p5 79.2, median min 78.7 (bars 95.5/91/84).
- Worst-window gap below bar: median 5.7 (mean), 11.9 (p5), 5.5 (min); p90 mean-gap 29.9.
- Near-miss (all worst-windows within ~1–2 pts of every bar): **1/39**. Clear-fail: 38/39.
- Conclusion: the rejection distribution sits far below the bars with a long tail — **no evidence
  of threshold miscalibration**; a threshold relaxation would admit visibly degraded frames.

## Latch suppression (measured)
- 77 jobs probe-suppressed; 26 of them in classes that verified a compression (defect).
- 83/83 suppressions inherited prior-run store state (cross-run persistence).
- Per-class: 3 classes account for the 26 winnable-denied (720p24 lt10m: 9; 720p30 lt10m: 16;
  1080p30 25-50m: 1). Concentrated, not diffuse — consistent with coarse-bucket over-grouping.

## Transitions old→new (paired, n=172, measured)
Improved 9, regressed 0, unchanged 163. A sign test on saved-bytes deltas (9 positive, 0 negative,
163 ties) is trivially significant for direction but the effect is small in count; the honest
statement is "monotone improvement, no regressions," not a precise effect size.

## HFR stratum (n=18 60fps + 1 120fps, measured)
0 compressed, 13 already-optimized, 4 skip. Under-powered and confounded (60fps here are high-
bitrate camera clips, plausibly genuinely efficient). Cannot conclude the non-HFR v0.6.1 model
causes false rejection from this sample.
