# HDR measurement basis (offline research)

Establishes a way to *measure* whether an HDR re-encode is perceptually transparent.
It does not change any production behavior, and on its own it never will.

## The problem this solves

HDR is the binding constraint on Smart Perceptually Lossless for phone camera footage:

- `BatchCompressorViewModel` gates both `pixelCertifiable` and `probeEligible` on
  `!source.isHdr`, and `hdrPixelTransparencyUnvalidated` routes non-same-codec HDR
  straight to stream copy.
- `scripts/diagnostics/measure_quality.py` rejects HDR outright with
  `HDR_OR_WIDE_GAMUT_UNSUPPORTED_FAIL_CLOSED`.

Both are **correct**. VMAF is trained on SDR BT.709; libvmaf ships no validated PQ/HLG
model, and the 2026-07-14 suite has zero pixel-validated HDR pairs. With no measurement,
the only honest action is an exact stream copy.

But "we cannot measure it" is a solvable problem, and it is a different problem from
"it cannot be done". Everything else in this codebase resolves that shape of question
with evidence. This directory supplies the missing evidence channel.

## The metrics, and why these

### ΔE<sub>ITP</sub> — ITU-R BT.2124 (primary)

A standards-defined colour difference for HDR/WCG signals. Two properties make it the
right primary here, and both are things VMAF cannot offer for PQ:

1. **No trained model.** It is defined by the standard, so there is no "is this model
   valid for our content?" question to answer first.
2. **Calibrated in JNDs.** ΔE<sub>ITP</sub> = 1.0 is one just-noticeable difference. That
   gives "perceptually lossless" a defensible operational meaning on this axis, rather
   than an arbitrary score cut-off.

Pipeline, per BT.2100/BT.2124:

```
linear BT.2020 RGB -> LMS -> PQ (ST 2084) -> ICtCp -> (I, T = Ct/2, P = Cp)
ΔE_ITP = 720 * sqrt(dI² + dT² + dP²)
```

### PU21-PSNR (secondary)

PU21 (Mantiuk & Azimi) maps absolute luminance into a perceptually uniform space so
SDR-style metrics become meaningful on HDR signals. It is here as an *independent* axis:
two metrics disagreeing is a finding worth chasing, whereas a single metric is a single
point of failure. It is not a tie-breaker and it is not authoritative.

### Why not tone-map to SDR and run VMAF

Because the tone map becomes part of the measurement. Two encodes that differ only in
HDR highlight detail can tone-map to nearly identical SDR, scoring as transparent while
differing exactly where HDR matters. `measure_quality.py` names this in its own header
as the reason it has no "shared deterministic tone map" — inventing one here would
reintroduce the same problem one layer down.

## Status

**Implemented and verified in-session:**

- `scripts/hdr_metrics.py` — the metric core: ST 2084 PQ transfer functions, BT.2100
  RGB→LMS→ICtCp, ΔE<sub>ITP</sub>, PU21 encoding, PU21-PSNR, and a frame summary that
  reports the tail (p99 / p99.9 / max / fraction ≥ 1 JND), not just the mean.
- `scripts/test_hdr_metrics.py` — **15 tests, all passing** (`python3 test_hdr_metrics.py`).

The tests check identities that follow from the standards, so they catch a transposed
matrix or a swapped constant — the failure mode that otherwise yields plausible numbers
and a wrong conclusion. Notably: neutral grey must give Ct = Cp = 0 *exactly*, and each
RGB→LMS row must sum to 1.

Independent spot-checks against published values:

| Quantity | Computed | Reference |
|---|---|---|
| PQ(100 cd/m²) | 0.50808 | ≈0.5081 |
| PQ(203 cd/m²), BT.2408 HDR reference white | 0.58069 | ≈0.58 |
| PU21 peak at 10,000 cd/m² | 595.394 | ≈595 |

Resulting sensitivity on a 100-nit neutral patch: 1% luminance error ≈ 0.72 JND
(sub-threshold), 5% ≈ 3.53 JND (clearly visible). 1 JND falls near a 1.4% error.

**Extraction layer — implemented and verified:**

- `scripts/hdr_pair_measure.py` — decodes matched windows of two HDR files to linear BT.2020
  RGB and reports per-window ΔE<sub>ITP</sub> and PU21-PSNR as JSON.
- `scripts/test_hdr_pair_measure.py` — **12 tests, all passing**, covering the fail-closed
  validation and the exact ffmpeg invocation.
- `python3 hdr_pair_measure.py --self-test` — synthesizes a PQ/BT.2020 pair with ffmpeg and
  measures it end to end. Identical inputs score **exactly 0.000000**; a deliberately bad
  encode scores far higher. This caught a real bug on its first run: the `scale` filter takes
  `in_color_matrix=bt2020`, not `bt2020nc` (the `-colorspace`/ffprobe spelling for the same
  matrix), which fails with an unhelpful "Undefined constant" error.

Requires `ffmpeg` and `ffprobe` on PATH. The self-test path needs only ffmpeg and falls back to
the `imageio-ffmpeg` static build.

What it refuses to do, deliberately:

- **HLG is rejected, not measured.** Its EOTF is a different curve; applying the PQ curve would
  produce confident, wrong numbers instead of an error.
- **SDR is redirected** to `measure_quality.py`, which has a validated VMAF path.
- **Nothing is rescaled, tone-mapped, or transfer-converted.** A geometry, transfer, or
  primaries mismatch fails the run rather than being papered over, and a test asserts the
  decode command contains no `tonemap` / `zscale` / transfer conversion.
- **The report carries `"verdict": null`.** No calibrated HDR threshold exists yet, so it states
  measurements and refuses to imply a pass.

Window sampling mirrors `QualityProbePolicy.probeWindows` (20/50/80% of the clip, 1.2 s each),
so offline numbers and on-device numbers describe the same parts of a clip.

## What must be true before ANY production change

This tooling measures; it does not authorize. Before a single HDR clip is re-encoded by
Smart Perceptually Lossless:

1. ~~The extraction layer exists and is validated on known pairs.~~ **Done** — but validated
   only against synthetic clips so far, never against real S23 Ultra HDR footage.
2. A corpus of real HDR camera clips is measured across a bitrate ladder, giving a
   ΔE<sub>ITP</sub> distribution for HDR re-encodes at each ratio.
3. A threshold is derived from that distribution — including what fraction of the frame
   may reach 1 JND, since localized banding is the expected HDR failure and a
   frame-mean threshold would miss it entirely.
4. The threshold is validated against subjective checks on a real HDR display. JND units
   are meaningful, but "how many JNDs over how much of the frame is acceptable" is a
   product decision, not a mathematical one.
5. Device validation on the S23 Ultra, and an explicit, separately-reviewed decision to
   move the HDR gate.

Until all five hold, `hdrPixelTransparencyUnvalidated` stays exactly as it is. Relaxing
it earlier would mean claiming "Perceptually Lossless" for HDR on the strength of a
metric nobody has calibrated for this content — the precise failure the truth rules in
`.claude/skills/android-media3-perceptual-compression` exist to prevent.
