# Perceptually Lossless: definition, gates, and how to validate the claim

This document says what the app means when it labels an output "Perceptually Lossless", what
evidence it requires before it says so, and how that claim is to be validated with people rather
than with one number.

## 1. The definition

An output is *perceptually lossless* for a viewing condition **V** when a normal-sighted viewer,
watching the output and the source under **V**, cannot reliably tell which is which.

- **V for this project:** the Galaxy S23 Ultra's own display at its native resolution, held at a
  normal hand-held distance (about 5 screen heights, the condition VMAF's phone models are built
  for), indoors, at the brightness the user normally uses, played at native frame rate with the
  device's own player. Audio through the device's speakers or wired/Bluetooth headphones at a
  normal listening level.
- **"Cannot reliably tell"** means: in a forced-choice test the viewer does no better than chance
  at a stated confidence (Section 4).
- The definition is about the *whole output* — video and audio — but the two are validated by
  separate paths and reported separately (Section 3).

Everything else in the pipeline is a *proxy* for this definition. Objective metrics are gates that
decide whether a file may carry the label; they do not define it.

## 2. The objective gates (what the app checks today)

The video verdict requires **all** of:

1. **Structural parity** (`OutputVerifier`): resolution, frame rate, frame count, duration,
   rotation, HDR/colour metadata, audio codec/channels/sample rate, and metadata survive intact;
   the output is strictly smaller than the source. A failure here is terminal.
2. **Sampled pixel measurement** (`VmafPairScorer`, libvmaf 3.0.0, model `vmaf_v0.6.1`, luma,
   8-bit, native resolution, no rescaling): three 1.2 s windows of the *final output* are decoded
   and paired frame-for-frame with the source by presentation time. Every window must pass
   `mean ≥ 95.5`, `5th percentile ≥ 91`, `minimum frame ≥ 84` (`QualityProbePolicy`).
   The same windows, at the same positions, are used by the probe ladder that chose the bitrate,
   so probe and certification are comparable frame for frame.
3. **Measured evidence only.** A window that could not be decoded or aligned is "unavailable",
   never a pass and never a rejection of the content. Only a scored window may reject a file as
   "would visibly lose quality", and only a scored window may certify it.

### 2.0 How the probe ladder chooses a ratio

The ladder encodes the three windows at each candidate ratio and scores them against the source.
It does **not** select a ratio the moment it clears the bar above. A full encode lands slightly
below its probe on the same frames: in b165, 63 windows scored on both showed the full encode
lower by (10th percentile / worst) 0.52 / 1.16 on the mean, 1.25 / 1.76 on the 5th percentile and
0.89 / 3.62 on the minimum. Seven of 23 certified encodes failed, every one after a probe that had
cleared some gate by less than 0.5.

So a rung is **selected** only when it clears the bar by the 10th-percentile drift
(`QualityProbePolicy.PROBE_SELECTION`: +0.5 mean, +1.25 5th percentile, +1.0 minimum). A rung that
clears the bar but not the margin is *marginal*: the ladder tries the next, safer rung. If no rung
clears the margin, the highest marginal rung is still attempted, labelled "by only X, below the
selection margin; certification decides", and certification judges the real output against the
unchanged bar. The margin changes which encode is attempted, never what is accepted. The session
summariser prints the drift and the marginal attempts of every capture (`probe->cert`,
`marginal passes`), so the margin is re-measured each run rather than trusted.

The encoder request is the same for probes and the full encode: bitrate mode, keyframe interval
(matched to the source's own, 1 to 5 s; see `KeyframeIntervalPolicy`) and the opt-in B-frame
experiment. Media3's default of one keyframe per second spent roughly three times the source's
share of bits on intra frames for every b165 source, all of which had 3 s keyframe intervals.

The thresholds were set from an offline calibration suite on a PC (`scripts/diagnostics/
measure_quality.py`) and have not been changed since. They are deliberately strict; whether they
are *right* for the definition above is exactly what Section 4 is for. **They must not be moved to
make more files pass.** They may be moved only with the evidence Section 4 produces.

### 2.1 What the gates do not measure

- Chroma. `vmaf_v0.6.1` scores luma only. A colour shift that leaves luma intact would pass.
  This is why a tag change such as an untagged SD source acquiring a BT.601 tag is still rejected
  structurally: the scorer could not catch it.
- Banding at 8 bit. CAMBI is recorded per window (`banding[...]`) but is not a gate: no
  calibration exists for it on this content.
- Temporal artefacts longer than a window, and anything outside the three windows.
- Audio (Section 3).

### 2.2 The shadow score

Every certification window is also scored with **VMAF v1** (`vmaf_v1.0.16_5d0h`, libvmaf 3.2.0,
10-bit input by bit replication). This is the model Netflix (June 2026) recommends for phone
viewing at about 5H; it drops VIF, adds CAMBI and chroma awareness, and replaces the v0 phone
polynomial with a viewing-distance model. It is recorded next to the v0.6.1 score for the same
frame pairs (`v1shadow[...]`, and `certV1Scores` in the job record) so that, once Section 4 has
produced ground truth, the two models can be compared on the same frames and the gate re-based on
whichever predicts the human result better. It is not a gate today, and probes do not run it.

## 3. Audio

Audio is never covered by the video gate.

- When the source's audio is AAC, Perceptually Lossless passes it through unchanged. The verifier
  proves this by comparing every packet of the output's audio track with the source's
  (`AudioTrackIdentity`). The record then says **bit-identical copy of the source's compressed
  audio (N packets compared)**. Nothing was decoded or re-encoded; there is nothing to validate
  perceptually.
- When the source's audio is not AAC (Opus in WebM, for instance) the pipeline re-encodes it to
  AAC. That is a second lossy generation. It is labelled **re-encoded, not validated as
  perceptually lossless**, and the word "lossless" is not applied to it. If this path is ever to
  carry the claim, it needs its own listening test (an ABX with the same statistics as Section 4)
  or a validated objective proxy; neither exists in this project today.

## 4. Validating the definition: a 2AFC / ABX procedure

Objective gates can be cross-checked against each other, but only people can validate a claim
about people. The procedure below is small enough to run on one phone in an afternoon and strict
enough to defend.

### 4.1 Material

- **Representative clips**: at least 12 short clips (8–15 s) from the user's own library, chosen
  to span content, not to flatter the pipeline: camera 1080p and 4K, screen recordings, social-media
  downloads at low bit density, dark scenes, fast motion, fine texture, smooth gradients (skies,
  walls), text overlays, and at least two with fades.
- For each clip, the app's Perceptually Lossless output where it produced one, plus two
  **anchors** made offline: a *transparent* anchor (same encoder, 2× the source bitrate; the
  self-check's ceiling) and a *visibly worse* anchor (the same encoder at 0.4× the source
  bitrate). Anchors calibrate the viewer and the statistics: a viewer who cannot separate the
  visibly-worse anchor from the source is not a usable observer for that clip.

### 4.2 Presentation

- **Two-alternative forced choice with a reference (ABX).** The viewer sees A and B (source and
  candidate, order randomised per trial) and then X, a copy of one of them, and must say whether
  X is A or B. Playback in the device's own player, full screen, in condition **V**, with the
  viewer free to replay each of A, B and X as often as wanted.
- Video and audio are tested in **separate sessions**: video with the sound muted; audio with the
  screen blank (only for outputs whose audio was re-encoded; bit-identical audio needs no test).
- Each clip × candidate gets **at least 20 trials** per viewer, interleaved with the anchors, and
  at least **3 viewers** who are not the person who built the pipeline.

### 4.3 Decision rule

- For a candidate to be *validated as perceptually lossless* under **V**, the pooled correct rate
  across viewers must be **statistically indistinguishable from 50 %**: with 60 trials (3 viewers ×
  20), 39 or more correct is significant at p < 0.01 (one-sided binomial), so a candidate with
  ≥ 39/60 is *detected* and fails; below that it is not detected. Report the count and the exact
  binomial p, not just pass/fail.
- A viewer's results on a clip are discarded if that viewer did not detect the visibly-worse anchor
  on the same clip (≥ 15/20).
- Do not pool across clips to rescue a clip: the claim is per file.

### 4.4 What to do with the result

- Every trial's VMAF v0.6.1 window scores (mean/p5/min), v1 shadow scores, CAMBI and the
  per-frame minimum position are already in the diagnostics ZIP. Join them to the ABX outcomes.
- If files that people cannot detect are being rejected by one gate (for example, a single-frame
  minimum), that gate is too strict *for this definition*, and the evidence to relax it is the
  ABX table, not the desire for more compressions. If files people do detect are passing, a gate
  is too loose, and it tightens the same way.
- Prefer the model whose scores separate detected from undetected files best (v0.6.1 vs v1
  phone) as the primary gate, and keep the other as a shadow.
- Independent cross-checks that should agree with the chosen gate: SSIM/MS-SSIM on the same
  paired frames, and an offline libvmaf run on the same windows with explicitly synchronised
  timestamps (`scripts/diagnostics/measure_quality.py`). Disagreement between the on-device
  scorer and the offline scorer on the same frames is a measurement defect and blocks any
  threshold change until it is explained.

## 5. Where measurement can still go wrong, and how each is guarded

| Risk | Guard |
|---|---|
| Frames paired out of time | first-frame origins plus a 4 ms timestamp tolerance; unalignable windows are "unavailable" |
| Probe clip scores encoder warm-up | every probe clip starts at a source keyframe ≥ 2 s before the window; the lead-in is decoded and paired but not scored |
| Decoder output not 8-bit 4:2:0 | any other format fails closed to "unavailable" |
| Rotation/crop mismatch | both sides are converted to display orientation with the crop rectangle applied; a geometry mismatch is "unavailable" |
| A defect in the scorer itself | the self-check: source vs itself and source vs stream copy must score 100 on every frame; its log is exported with the batch it ran next to |
| Probe passes that the full encode then fails | selection margin from measured probe-to-encode drift; re-measured in every capture |
| Probe clip encoded differently from the full encode | one request shape (mode, keyframe interval, B-frames) for both |
| A metric that is not the definition | Section 4 |

## 6. What the label means on screen

- **Perceptually Lossless Verified** — structural parity and sampled pixel measurement both passed
  on this output. Audio is stated separately.
- **Perceptually Lossless — structural checks only (pixels not sampled)** — accepted under the
  rules for sources that cannot be scored; no perceptual proof is claimed.
- **Kept original, no copy written.** — the file was not re-encoded. The message names the basis:
  a measured rejection, a learned class-level decision, a heuristic, or "cannot be pixel-measured
  on this device" (HDR, above 4K, codec downgrade, scorer unavailable). Only a measurement may say
  "visibly".
