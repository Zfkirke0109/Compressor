# Why "true PL" was unreachable above 1080p

Investigation into the recurring complaint that Smart Perceptually Lossless rarely
reaches a genuine, pixel-proven verdict on real phone footage — and what this change
does and does not fix.

## Summary

Three independent things stop a clip from reaching pixel-proven Perceptually Lossless.
Only one of them was an engineering defect. This change fixes that one.

| # | Gate | Applies to | Verdict |
|---|---|---|---|
| A | Pixel scoring capped at 1080p-class geometry | every 4K/1440p source | **Defect — fixed here** |
| B | HDR is excluded from all pixel scoring | 4K HDR camera clips | Correct; needs research, not a code tweak |
| C | Source bits-per-pixel below the transparency gate (0.08) | efficient HEVC camera clips | Correct; this is physics |

## A. The pixel-evidence gap (fixed)

`VmafPairScorer.MAX_COMPARE_PIXELS` was `1920 * 1088`, and
`QualityProbePolicy.isPixelScoreableGeometry` derived from it. That single predicate gated
`probeEligible` in `BatchCompressorViewModel.buildPerceptualLosslessPlan`, and
`probeEligible` in turn gated *both* the probe ladder *and* post-encode certification.

For any source above 1080p the consequence was total:

1. No probe ladder ran, so no lower ratio could ever be pixel-proven.
2. **Certification never ran either.** `pixelCertifiedThisRun` stayed `false`, so QUAL-001's
   `withCertificationBasis(false)` downgraded the verdict to
   `PERCEPTUALLY_LOSSLESS_STRUCTURAL_ONLY` — "structural checks only, pixels not sampled".

So a 4K clip could not earn the pixel-proven label *at any bitrate, on any device, ever*.
Not because it failed a measurement, but because the measurement was never attempted.
`VerificationLabelHonestyTest` even documents "source above the VMAF geometry cap" as an
expected reason for the qualified wording — the honesty machinery was working correctly on
top of a measurement that was silently disabled.

The cap's own comment justified it as "memory/time", not as a validity limit. Those are two
very different costs, and they apply to the two uses very differently:

- **Certification** scores three ~1.2 s windows of an output that already exists.
- **The probe ladder** additionally *encodes* a real clip per rung, up to four rungs × three
  windows, and scores every one — which at 4K does not fit in
  `PerceptualQualityProber.TOTAL_BUDGET_MS`.

Collapsing both into one geometry predicate meant the cheap, high-value half was paying the
expensive half's bill.

### The fix

- `MAX_COMPARE_PIXELS` raised to `3840 * 2176` (4K-class, covers portrait). 8K still returns
  `Unavailable`: it needs two simultaneous 8K hardware decoders and ~50 MB per decoded frame.
- Queue depth is now derived from frame size against a fixed **64 MB** byte budget rather than
  a fixed count of 4. 1080p keeps 4-deep queues (unchanged); 4K drops to 2, holding peak
  in-flight frames at ~50 MB instead of ~100 MB.
- `isProbeLadderGeometry` is a new, strictly narrower predicate holding the ladder at
  1080p-class. Above it the plan keeps its default/learned ratio and relies on certification.
- `pixelCertifiable` is a new plan field, separate from `probeEligible`, and is now the only
  gate on certification.

### Scoring stays at native resolution

Deliberately **not** downscaling 4K to 1080p before scoring. The authoritative offline harness,
`scripts/diagnostics/measure_quality.py`, states as a contract that it "never rescales or
frame-rate-converts either input" and scores with plain `vmaf_v0.6.1`. Every threshold in
`QualityProbePolicy` was calibrated against that harness. Downscaling here would hide exactly
the high-frequency coding artifacts the thresholds exist to catch — an invisible loosening of
the bar disguised as a performance optimization.

### Open question: is `vmaf_v0.6.1` the right model at 4K?

This change scores native 4K frames with the **1080p-trained** default model. That is off-label:
Netflix's guidance for 4K material is `vmaf_4k_v0.6.1`, trained on 4K displays at 1.5x height.
`vmaf_jni.c` loads `vmaf_v0.6.1` only (optionally with `VMAF_MODEL_FLAG_ENABLE_TRANSFORM` for the
phone variant, which `VmafPairScorer` deliberately leaves off because the transform maps scores
upward).

What makes the current choice defensible is *consistency*, not model-correctness: the offline
harness does exactly the same thing, so on-device and offline scores remain comparable and the
calibrated thresholds keep their meaning. Whether that shared choice is the right one for 4K is
unresolved, and the bias direction has not been measured. Do not assume it is conservative.

Resolving it means scoring the same 4K pairs under `vmaf_v0.6.1` and `vmaf_4k_v0.6.1` offline and
comparing against the window thresholds. If the models disagree materially, the thresholds and
both harnesses have to move together — never the on-device scorer alone.

### Related gap: VMAF is weak on banding

VMAF is known to under-weight contrast banding, which is the signature artifact of re-encoding
smooth gradients (skies, walls, fades) at exactly the conservative bitrates Smart PL targets. A
clip can clear 95.5 / 91.0 / 84.0 and still show visible banding.

CAMBI (Contrast Aware Multiscale Banding Index) is built into libvmaf from 2.2 onward, and
`vmaf_jni.c` already links **libvmaf v3.0.0** — so the feature is compiled into the shipped
binary and simply never registered; the JNI only calls `vmaf_use_features_from_model`. Adding it
is a native change plus its own threshold calibration, so it is a separate piece of work, but it
needs no new dependency.

### Why this cannot weaken a verdict

For newly-covered geometry the change is monotone in the honest direction:

| Certification outcome at 4K | Before | After |
|---|---|---|
| Scored, windows pass | accepted, label qualified as structural-only | accepted, **pixel-proven label** |
| Scored, windows fail | accepted with no evidence | item skipped, original untouched |
| MisalignmentRejected | accepted with no evidence | item skipped, original untouched |
| Unavailable | accepted structurally | accepted structurally (unchanged) |

That last row is why `certificationOutcomePassesWithoutProbeBasis` exists. The ratio-aware
`certificationOutcomePasses` fails closed on `Unavailable` below the default ratio, because
there a sub-default target was justified by probe pixels alone. Above the ladder bar no probe
ever ran — the target came from the codec default plus the learning engine's floor-clamped
ratio — so absent evidence leaves the structural verdict standing, exactly as today. Applying
the ratio-aware rule to these sources would have *withdrawn* acceptances the app currently
grants, on devices where dual 4K decode fails.

`isPixelCertified` still requires `PairScoreOutcome.Scored`, so an `Unavailable` acceptance
can never wear the pixel-proven label.

## B. HDR (not changed — deliberately)

`probeEligible`/`pixelCertifiable` both require `!source.isHdr`, and
`hdrPixelTransparencyUnvalidated` sends non-same-codec HDR straight to stream copy. For a
Samsung 4K HDR camera clip this is the binding constraint, and **this change does not move it.**

That gate is correct as it stands: libvmaf ships no validated PQ/HLG model, and the 2026-07-14
suite has zero pixel-validated HDR pairs. Relaxing it would violate the truth rules in
`android-media3-perceptual-compression` — it needs an HDR measurement basis first, and explicit
sign-off, not a code tweak. It is the single largest remaining lever for anyone shooting mostly
4K HDR.

## C. Bit density (not changed — correct)

`PERCEPTUAL_LOSSLESS_MIN_SOURCE_BITS_PER_PIXEL = 0.08`. Efficient HEVC camera footage often
sits below it, and the honest outcome is a 0.0 MB remux. `research/perceptual_calibration`
already reached this verdict independently: its v2 study says **no change**, and
`NEXT_ROUND_INSTRUMENTATION.md` §5 explains that the corpus, not the thresholds, is the
bottleneck. Nothing here touches these constants.

## Validation status

Run in this session:

- `./gradlew testDebugUnitTest` — **161 tests, 0 failures** (baseline before the change was
  also green).
- New coverage: `QualityProbePolicyTest` (geometry gates, the ladder/scoreable ordering
  invariant, the no-probe-basis certification rules) and `VmafPairScorerQueueBudgetTest`
  (1080p depth unchanged, peak queued bytes bounded at every scoreable geometry).

**Not run in this session — required before this is trusted on real footage:**

- `./gradlew assembleDebug` (needs the NDK toolchain and signing secrets; CI covers it).
- **Galaxy S23 Ultra device validation.** This is the important one. Nothing here has been
  measured on real 4K footage, and the on-device scorer has a documented history of large
  false fails on some content (see `REVIEW_FINDINGS.md`). Specifically confirm:
  - a 4K SDR clip with genuine headroom now reports pixel-proven PL, and the logged
    `certification;` line shows real window scores;
  - dual 4K decode actually succeeds on-device (if it does not, certification returns
    `Unavailable` and behavior is unchanged — verify that, do not assume it);
  - certification time per 4K clip is acceptable inside a batch;
  - 4K HDR still routes to remux, and 8K still reports no pixel evidence;
  - no 4K clip that previously succeeded is now skipped by a *false* measured failure. This
    is the one regression this change can plausibly cause, and only a device run can find it.

Until that capture round exists, treat the 4K certification path as implemented and
unit-tested but unproven on real video.
