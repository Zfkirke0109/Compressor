---
name: android-media3-perceptual-compression
description: Work on the Compressor app's video compression pipeline — Remux Only, Smart Perceptually Lossless, High Quality, or Storage Saver modes, Media3 Transformer usage, OutputVerifier, or SmartPerceptualProfileEngine (the local learning engine). Use whenever the user mentions perceptual lossless, remux, HEVC/H.264/AV1 codec selection, HDR (PQ/HLG/BT.2020), bitrate policy, FPS preservation, output verification, or the "truth"/honesty rules around compression labels — even if they just ask to "tweak the compression settings" or "the size reduction number looks wrong." This skill encodes hard safety and honesty invariants for this codebase; consult it before touching BatchQualityBitratePolicy.kt, OutputVerifier.kt, SmartPerceptualProfileEngine.kt, or anything that decides an encode target or a verification verdict.
---

# Compressor: Smart Perceptually Lossless Pipeline

## Why these rules are strict

This app makes user-facing claims like "Perceptually Lossless Verified" and "Remux Verified — 0.0 MB saved." Those labels are a promise to the user that nothing was silently degraded — no dropped frames, no tone-mapped HDR, no resampled audio, no fake savings number. Getting this wrong doesn't just produce a worse compression ratio, it means the app lied to someone about what happened to their video. Every rule below exists to prevent a specific way that lie could happen. Treat them as invariants, not preferences — if a change would let one of these be violated, that change is wrong even if it improves compression ratio or code simplicity.

## Core truth rules (permanent — do not relax these without the user explicitly overriding in this conversation)

1. **True zero-loss = Remux Only, and only Remux Only.** Remux Only means `MediaExtractor` + `MediaMuxer` stream copy — bytes pass through unmodified except container repackaging. It must never invoke Media3 `Transformer`. If you find Remux Only touching Transformer, that's a bug, not a feature.
2. **Perceptually Lossless is not mathematically lossless — it's a *verified conservative re-encode*.** The word "Perceptually" is load-bearing. It may only be labeled "Verified" when `OutputVerifier` (or `PerceptualLosslessVerifier`) has actually proven safety on that specific output — not because the encode succeeded, not because it looks fine, not because a similar clip verified before.
3. **A smaller output is only accepted when verification proves resolution, FPS, HDR/color, and audio all survived intact.** If verification can't prove it, the code must fall back to Remux Only and label it honestly (e.g., "Remux Fallback Kept"), even though that means reporting less (or zero) savings.
4. **Never silently cap FPS, tone-map HDR to SDR, change resolution, or degrade audio.** "Silently" is the key word — a mode that explicitly offers a lossy FPS cap (High Quality / Storage Saver) is fine as long as it's labeled as lossy; Perceptually Lossless and Remux Only must never do these things at all, labeled or not.
5. **Never claim "PL Verified" unless `OutputVerifier` actually returned that verdict for this file.** Don't infer it, don't assume it from a similar past run, don't let a learned profile shortcut the check.
6. **If a source is already near-optimal, say so and skip re-encoding — don't report fake savings.** A remux that saves 0.0 MB is a correct, honest result, not a failure to compress.

## Device/codec-specific rules (S23 Ultra and similar HEVC HDR hardware)

- For 4K60/4K120 HEVC HDR sources, prefer HEVC-first encoding with conservative bitrate floors — this hardware's own camera encoder is often already close to optimal, so aggressive targets are more likely to fail verification and waste an encode.
- AV1 is **opt-in only**. Never let Auto-mode default to AV1 for Samsung HDR camera clips — hardware AV1 encode support is inconsistent and the failure mode (silent quality loss or verification failure after a slow encode) is worse than just not offering it by default.
- Block H.264 HDR paths that are known-unsafe in Smart PL — H.264 doesn't carry HDR metadata as reliably as HEVC, so a PL encode down that path is more likely to silently lose HDR information without verification catching it in every case. If in doubt, prefer the safer HEVC path or fall back to remux.
- If Samsung camera HEVC output is already near the hardware encoder's efficiency ceiling, Smart PL should prefer Remux Only and report honest zero/near-zero savings rather than burning an encode that will likely fail verification anyway.

## `SmartPerceptualProfileEngine` (local learning engine) — context from PR #19

This engine exists to make repeated encodes on the same device/content class smarter over time, without ever weakening the truth rules above.

- **Local-only.** No upload, no network call, no internet permission. Stored in app SharedPreferences.
- **Profile key is technical buckets only** — manufacturer/model/SDK, encoder mime, source codec, resolution bucket, FPS bucket, HDR bucket, bitrate buckets. No private paths, no location, no per-file identifiers. If you see a change that would let a file path, GPS coordinate, or filename leak into a stored profile key, that's a privacy regression — stop and flag it (see [[android-security-privacy-review]]).
- **Adjusts target ratios cautiously.** Small steps down after a verified success (e.g., 0.95 → 0.93), steps up after a failed/discarded attempt. Two consecutive near-max-ratio failures mark the profile "remux-preferred"; a later verified success clears that flag.
- **Clamped above safety floors, always.** Known floors from this codebase: HDR 120fps ≥ 0.90, HDR 4K60 ≥ 0.80, SDR 120fps ≥ 0.88 (120fps floors are intentionally stricter than 60fps — don't collapse these into one ">50fps" bucket), plus absolute floors (8K ≥ 100 Mbps, 4K ≥ 48 Mbps). A corrupted or tampered stored value must not be able to push a recommendation below its floor — the clamp has to be enforced at read time, not just at write time.
- **Never bypasses verification.** The learning engine only ever picks a *target*, it does not and must not decide "verified." `OutputVerifier` is the only source of truth for that word, always, for every encode it touches.

## Validation to run before calling any of this done

- 4K60 HEVC HDR source around ~120 Mbps (the realistic Samsung camera case) — confirm honest remux-preferred behavior when re-encoding wouldn't help.
- 4K120 or slow-motion/high-FPS source — confirm FPS is preserved end to end, not silently capped.
- A lower-bitrate SDR/H.264 source that has genuine headroom — confirm PL can actually verify and produce real savings here (this is the case that should succeed, not just fail safely).
- High Quality label — confirm it's presented as lossy, with real savings.
- Storage Saver label — confirm it's presented as lossy with a visible-loss warning.
- Confirm the remux fallback path reports itself honestly ("Remux Fallback Kept" or equivalent), not as a PL success.
- Confirm no path reports non-zero "savings" when the output is actually the same size (or larger) than the source.

If you can't run these against a real device in the current session, say so explicitly in your report rather than asserting they pass — see [[android-kotlin-pr-continuation]] on honest status reporting.

## Escalation — stop and ask the user when

- A proposed change would let any core truth rule above be relaxed, even temporarily or "just for this case."
- A bitrate floor or clamp would need to change — floors are safety-critical and shouldn't move without explicit sign-off.
- You're unsure whether a given Samsung/OEM codec quirk is a genuine app bug or a hardware limitation — check [[android-logcat-device-validation]] first.

## Anti-patterns

- Don't "simplify" by having Remux Only and Perceptually Lossless share an encode path — they are architecturally different (stream copy vs. Transformer) on purpose.
- Don't report a verification verdict you didn't actually observe from `OutputVerifier` in this session.
- Don't widen a safety floor to make more sources "pass" PL — that inverts the whole point of the floor.
- Don't let AV1 become a default anywhere in the Samsung HDR path just because it compresses better on paper.
