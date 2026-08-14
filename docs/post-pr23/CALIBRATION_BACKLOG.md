# What else is worth calibrating — PL and HQ

A survey of every tunable constant in the Perceptually Lossless and High Quality paths,
classified by **what evidence actually backs it**, and ranked by expected payoff.

Evidence classes used below:

- **Measured** — derived from a named VMAF suite or device capture.
- **Reasoned** — a defensible argument in the code comments, but no measurement.
- **Unbacked** — a plausible number with no stated derivation.

The point of the classification is that *unbacked constants are not automatically wrong* —
several are fine. They are simply the places where nobody would notice if they were wrong.

---

## Finding 1 — The learning engine is a one-way ratchet (PL) — **IMPLEMENTED**

**Status: was an unbacked consequence of a measured decision. Fixed; see the bottom of this
section for what shipped and one correction to the original diagnosis.**

`SmartPerceptualProfileEngine` adapts in one direction only:

```kotlin
const val SUCCESS_STEP_DOWN = 0.0   // success never lowers the next target
const val FAILURE_STEP_UP  = 0.05   // failure always raises it
```

The zero is deliberate and its rationale is sound. From the code:

> the structural verifier cannot see perceptual damage, so success history must never buy
> a lower target

Backed by a real incident: profile `f030dffc553d` walked 0.90 → 0.60 on structural
successes and VMAF later measured 87.4. Zeroing the step-down stopped that.

**But the premise has since changed, and the constant hasn't.** When it was zeroed, a
"success" could only ever be structural. Today a success can be *pixel-certified* — the
probe ladder proves the ratio before the encode, and `OutputVerifier` + certification
prove the output after. The reason for distrusting success no longer applies to the
successes that carry measured pixel evidence.

**The engine cannot make that distinction, because nobody told it.** `recordVerifiedSuccess`
takes `usedTargetRatio`, `outputToSourceBytesRatio`, `floorRatio`, `measuredOvershootFactor`
— and no certification flag. `grep pixelCertified SmartPerceptualProfileEngine.kt` returns
nothing. The blunt global zero was the only safe option available given what the engine knows.

**Consequence.** Every profile drifts monotonically toward `preferRemux` and never recovers.
Failures ratchet the target up by 0.05 each time; nothing ever brings it back down. A device
that has been running the app for months is strictly more conservative than a fresh install,
with no mechanism to re-earn savings even after the evidence would justify it. If savings
seem to get worse over time, this is the mechanism.

**Proposed change (needs its own PR, tests, and device validation).** Plumb
`pixelCertifiedThisRun` into `recordVerifiedSuccess` and allow a small step-down *only* for
pixel-certified successes:

```
structural-only success -> step down 0.0   (unchanged; the 2026-07-14 rationale stands)
pixel-certified success -> step down ~0.01-0.02, still clamped to floorRatio
```

This preserves the original safety argument exactly — it does not weaken it for the case it
was written about — while letting measured evidence do what measured evidence is for. The
clamp to `floorRatio` and `OutputVerifier` remain the backstops.

**Why this ranks first:** it is the only finding here that compounds. Every other constant is
a fixed offset; this one silently degrades over time.

### What shipped

`PIXEL_CERTIFIED_STEP_DOWN = 0.01`, applied in `recordVerifiedSuccess` only when the caller
passes `pixelCertified = true`. `SUCCESS_STEP_DOWN` stays `0.0` and the parameter defaults to
`false`, so every path that cannot prove what it measured keeps the strict behavior unchanged.
`BatchCompressorViewModel` passes `pixelCertifiedThisRun`, which is set by
`QualityProbePolicy.isPixelCertified` and therefore requires a `Scored` outcome.

Five new tests, and the three original invariant guards
(`verifiedSuccessStoresProfileWithoutSteppingTheTargetDown`,
`repeatedSuccessesNeverLowerTheTargetBelowTheDefault`,
`learningCannotBypassVerificationDecisions`) still pass unmodified.

### Correction to the diagnosis above

The original write-up implied learning could not "re-earn savings" in general. That overstates
it. `recommendedTargetRatio` bounds the learned value below by `max(floorRatio, defaultRatio)`,
so a learned ratio was never able to target below the codec default in the first place — the
ratchet's real effect is that a profile stuck at a *worse-than-default* target after failures
could never return to default.

That makes the fix narrower and safer than first described: it is a **recovery** mechanism, not
an aggressiveness one. Certified successes walk a profile back to the codec default and stop
there, no matter how many accumulate. Targeting below the default remains the exclusive job of
per-clip probe evidence (`pixelProvenRatio`), which is measured per file and never learned.
`certifiedRecoveryHealsTheRatchetButNeverBeatsTheCodecDefault` pins exactly this.

---

## Finding 2 — High Quality is content-blind — **highest payoff on the HQ side**

**Status: unbacked.**

HQ's target is a fixed fraction of source bitrate:

```kotlin
val ratio = 0.72f * fpsScale * heightScale
```

`0.72` has no stated derivation anywhere in the codebase. More importantly, **HQ performs no
quality measurement at all.** A static talking-head clip and a 4K60 handheld grass-and-motion
clip both get 72% of source bitrate, and their perceptual outcomes are nowhere near
equivalent — the first is untouched, the second can visibly fall apart.

This is not a *truthfulness* problem: HQ is labelled lossy, and #38 improved that wording. It
is a *consistency* problem. "High Quality" currently means a fixed bitrate ratio, not a
quality level.

**Proposed change.** The app already owns a complete quality-targeting apparatus — probe
ladder, VMAF windows, certification — and HQ never touches any of it. Point it at HQ with a
relaxed threshold set:

```
PL:  mean >= 95.5 / p5 >= 91.0 / min >= 84.0   (transparency)
HQ:  mean >= ~93  / p5 >= ~88  / min >= ~80    (stated, honest quality floor)
```

HQ then means "the smallest file that still meets a stated quality bar" instead of "72% of
whatever you had". Easy content gets *more* savings than 0.72; hard content gets less and
stops falling apart. The label stays honestly lossy either way.

The HQ numbers above are illustrative and must be calibrated, not pasted — same discipline as
the PL thresholds. Costs one probe ladder per HQ item, so it likely wants the same
1080p-class ladder bar and the certification-only path at 4K.

---

## Finding 3 — Post-CAMBI: a banding threshold now has data coming

**Status: measurement just enabled, not yet calibrated.**

CAMBI is now recorded per certification window (`certBandingDiag`). Once captures exist:

1. Establish the CAMBI distribution of outputs that *passed* VMAF windows. If some passing
   outputs band, that quantifies VMAF's blind spot on this content.
2. Only then consider a gate.

**Calibration caveat that must not be skipped:** CAMBI's default window size is 4K-referenced
(libvmaf documents 63 as "~1 degree at 4k"), so absolute values are not comparable across
resolution classes. A threshold has to be per-resolution-class, or the window size pinned.

---

## Finding 4 — The 4K VMAF model question

**Status: reasoned, unmeasured.** Detailed in `PL_PIXEL_EVIDENCE_GAP.md`.

4K frames are now scored with the 1080p-trained `vmaf_v0.6.1`. Defensible because the offline
harness does the same, so both agree — but whether that shared choice is right for 4K is
open, and the bias direction is unmeasured. Resolve by scoring identical 4K pairs under
`vmaf_v0.6.1` and `vmaf_4k_v0.6.1` offline. If they disagree materially, thresholds and both
harnesses move together — never the on-device scorer alone.

---

## Finding 5 — The bpp gates remain unidentified

**Status: measured, but the measurement could not resolve them.**

`PERCEPTUAL_LOSSLESS_MIN_SOURCE_BITS_PER_PIXEL = 0.08` and
`PROBE_MIN_SOURCE_BITS_PER_PIXEL = 0.03`. The v2 study removed bpp from its search because it
was unidentifiable — no positive example below 0.1015 and a flat objective.
`NEXT_ROUND_INSTRUMENTATION.md` §4-5 already specifies the fix: a corpus with headroom
diversity across the [0.03, 0.069) band, including the 0.97 retreat rung.

Nothing to do here beyond what that document says. **Do not tune these from the existing
captures** — that is precisely the mistake the v1 study made.

---

## Finding 6 — Small unbacked constants (low individual payoff)

Listed for completeness. None is obviously wrong; none has a stated derivation.

| Constant | Value | Note |
|---|---|---|
| `NEAR_MISS_UPWARD_MARGIN` | 2.5 | New in #37. "Kept small so the extra encode is spent only when a pass is plausible" — reasoned, not measured. Captures now record what near-miss shortfalls look like, so this is cheaply checkable. |
| `REFINEMENT_MIN_GAP` | 0.06 | Bisection worth-it threshold. Trades probe encodes for savings resolution; measurable from probe traces. |
| `MIN_COMPARED_FRAMES_PER_WINDOW` | 12 | Minimum frames for a window to count. At 30fps a 1.2s window yields ~36, so this only bites on short/sparse windows. |
| `PERCEPTUAL_LOSSLESS_SIZE_TOLERANCE` | 0.03 | Allowed growth before rejection. |
| `MIN_PERCEPTUAL_LOSSLESS_PREDICTED_SAVINGS` | 0.005 | Explicitly a noise threshold, not a worthiness bar — documented as such. Fine. |
| `FAILURE_STEP_UP` | 0.05 | Ratchet size. Interacts directly with Finding 1; calibrate together, not separately. |
| HQ floors | 12/8/5/2.5/1 Mbps | Resolution-keyed. `HighQualityRetryPolicy` shows they can eat the entire saving on 4K (a 13.49 Mbps source clamps to 12 Mbps = 89% of source). |

---

## What NOT to touch

- **The PL window thresholds (95.5 / 91.0 / 84.0).** Already studied properly. The v2 verdict
  is *no change*, current production sits inside the cross-fold consensus box, and it stays
  tied-optimal in 100% of bootstrap resamples. Re-tuning these from the same captures would
  repeat the v1 error.
- **The HDR gate.** Blocked on the measurement basis in `research/hdr_measurement_basis/`, and
  then on the five conditions listed in its README.
- **Safety floors** (`HDR_120_RATIO_FLOOR` etc., absolute 4K ≥ 48 Mbps / 8K ≥ 100 Mbps). These
  are safety-critical by design and require explicit sign-off to move.

---

## Suggested order

1. ~~**Finding 1** — plumb pixel certification into the learning engine.~~ **Done.**
2. **Device-validate PR #41** — now doubly important: Finding 1's recovery path only engages for
   pixel-certified successes, and on 4K profiles those depend entirely on the certification path
   this PR introduces. Until the device run happens, 4K profiles keep the old structural
   behavior in practice.
3. **Finding 3** — capture round with CAMBI now recording; cheap, since the data collects itself.
4. **Finding 2** — HQ target-quality mode. Largest user-visible win, largest amount of work.
5. **Findings 4-6** — resolve alongside whichever capture round is already running.
