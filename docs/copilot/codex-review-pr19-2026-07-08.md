# Codex Review PR #19

## Summary

- ACCEPTED: `OutputVerifier` never compares source duration or frame count to output duration/frame count, so a truncated output can pass PL verification and replacement if metadata/size/bitrate heuristics line up (`OutputVerifier.kt:114-116`, `OutputVerifier.kt:253-267`, `OutputVerifier.kt:292-296`).
- ACCEPTED: measured video bitrate is inferred from total output bytes divided by output duration, so short duration or non-video bytes can inflate the verifier’s effective video bitrate (`OutputVerifier.kt:174-200`).
- ACCEPTED: the audio “stream copied” decision is only codec/channel/sample-rate plus missing bitrate; it does not prove packet identity (`OutputVerifier.kt:180-192`, `OutputVerifier.kt:211-218`).
- ACCEPTED: FPS and HDR checks are metadata-only: FPS allows tolerance and HDR only checks transfer/standard/range, not frame timestamps, frame count, or HDR static/dynamic metadata (`OutputVerificationFormatter.kt:26-34`, `OutputVerifier.kt:137-147`, `OutputVerifier.kt:442-444`).
- REJECTED: commit `95143e4` does not weaken the batch fallback/replacement path; it adds a debug-gated CBR request path and keeps OutputVerifier/remux fallback as final gates (`ExperimentalEncoderControls.kt:36-59`, `BatchCompressorViewModel.kt:631-669`, `BatchCompressorViewModel.kt:1556-1566`).

## 1. Can PR #19 falsely pass “Smart Perceptually Lossless”?

- ACCEPTED: Missing duration/frame-count verification can falsely pass PL and replacement. `VerificationInput` carries source duration, and `readFileProbe` reads output duration, but `playable` only requires output duration `> 0` and the PL gate never compares source/output duration or frame count (`OutputVerifier.kt:44-54`, `OutputVerifier.kt:114-116`, `OutputVerifier.kt:253-267`, `OutputVerifier.kt:388`). A 60s source could produce a 5s output with matching resolution/FPS metadata/HDR/audio shape; if its measured bitrate and size checks pass, `replacementSafe` becomes true because the truncated file is strictly smaller (`OutputVerifier.kt:285-296`). Batch fallback then does not remux because fallback only triggers when `!report.verified || !report.replacementSafe` or oversize (`PerceptualLosslessVerifier.kt:4-12`), and replacement can proceed when `verification.replacementSafe` is true (`BatchCompressorViewModel.kt:731-741`, `BatchCompressorViewModel.kt:1556-1566`).

- ACCEPTED: The measured-bitrate fallback can be gamed by total-size inflation or short output duration. When output track bitrate is absent, verifier computes total bitrate as `outputSize * 8000 / outputDurationMs`, subtracts only the inferred audio share, then coerces video bitrate to at least half of total bitrate (`OutputVerifier.kt:174-200`). Because duration parity is not checked, a short output can look high-bitrate; because bytes are total file bytes, non-video payload/overhead can also inflate the effective video bitrate. PL then accepts that inferred value against the policy floor (`OutputVerifier.kt:205-208`, `OutputVerifier.kt:253-263`).

- ACCEPTED: The “stream copied” audio heuristic can falsely accept re-encoded or degraded audio. The verifier treats audio as stream-copied when output audio bitrate is missing and codec/channel/sample-rate match (`OutputVerifier.kt:180-192`). That immediately passes `audioBitratePass` (`OutputVerifier.kt:211-218`) and satisfies critical-field completeness if audio shape is exposed (`OutputVerifier.kt:237-246`). Scenario: AAC 48 kHz stereo is re-encoded but hides `KEY_BIT_RATE`; verifier reports it as “stream copied” without checking packet identity (`OutputVerifier.kt:340-345`).

- ACCEPTED: FPS verification can falsely pass small changes or nominal-metadata lies. The verifier falls back from retriever FPS to extractor `KEY_FRAME_RATE` (`OutputVerifier.kt:64-72`, `OutputVerifier.kt:561-566`), and comparison allows ±0.5 fps, ±1.0 fps, or ±2.5 fps depending on source rate (`OutputVerificationFormatter.kt:26-34`). PL only requires that comparison to be `MATCH` (`OutputVerifier.kt:253-257`), so 60 -> 59 fps or 120 -> 117.5 fps can pass, and no timestamp/frame-count audit catches actual dropped frames.

- ACCEPTED: HDR verification only proves the color triple, not complete HDR survival. Track probing records `KEY_COLOR_TRANSFER`, `KEY_COLOR_STANDARD`, and `KEY_COLOR_RANGE` only (`OutputVerifier.kt:442-444`). PL checks those three values (`OutputVerifier.kt:137-147`, `OutputVerifier.kt:253-259`), and `colorMatches` is exact only for those integers (`OutputVerifier.kt:482-485`). Scenario: output keeps PQ/BT.2020/limited flags but loses HDR10 static metadata, dynamic metadata, or profile information; verifier still labels HDR/color as OK.

- REJECTED: Equal-or-larger PL replacement is blocked by the new strict-smaller gate. PL `replacementSafe` now requires `perceptuallyLosslessVerified && outputWithinTolerance && strictlySmaller` (`OutputVerifier.kt:285-296`), and replacement exits early when `replacementSafe` is false (`BatchCompressorViewModel.kt:1556-1566`). The raw verdict can still say “Perceptually Lossless Verified” inside the 3% tolerance (`OutputVerifier.kt:320-367`), but the batch path remuxes when `replacementSafe` is false (`PerceptualLosslessVerifier.kt:4-12`, `BatchCompressorViewModel.kt:631-669`).

## 2. Can Media3 1.5.0 ignore requested bitrate or bitrateMode?

- REJECTED as a truth-label defect. The repo pins Media3 `1.5.0` (`gradle/libs.versions.toml:11`) and uses Media3 Transformer dependencies (`app/build.gradle.kts:71-74`). The batch encoder request sets bitrate and bitrateMode through `VideoEncoderSettings` (`BatchCompressorViewModel.kt:1166-1179`), but the code explicitly says the request is not a guarantee and OutputVerifier judges the real output (`BatchCompressorViewModel.kt:1148-1150`). It also logs Media3’s reported average bitrate and encoder name after completion (`BatchCompressorViewModel.kt:1187-1195`).

- REJECTED for the experiment path silently falling back. The experiment enables CBR only in debuggable builds and only when a hardware encoder reports CBR support (`ExperimentalEncoderControls.kt:36-59`, `BatchCompressorViewModel.kt:1056-1057`). When enabled, `setEnableFallback(!useCbrCeiling)` disables fallback (`BatchCompressorViewModel.kt:1162-1168`), so rejection should become an export error and the PL path falls back to remux (`BatchCompressorViewModel.kt:586-609`).

- Hypothesis, not repo-verified: Media3 internal bitrate clamping and “closest supported format” fallback behavior are not source-citable from this repo. The app comments assume that behavior (`BatchCompressorViewModel.kt:1162-1165`), but there is no Media3 source file checked into the repo to cite.

## 3. Is a custom `Codec.EncoderFactory` viable on Media3 1.5.0?

- REJECTED as a current PR defect. The app already has a custom `Codec.EncoderFactory` in `CompressorViewModel`: it overrides audio/video creation, tries CBR first, then VBR, and passes the factory into `Transformer.Builder` (`CompressorViewModel.kt:775-802`). The PR’s batch path does not use a raw-`MediaFormat` custom `DefaultCodec`; it uses `DefaultEncoderFactory.Builder` (`BatchCompressorViewModel.kt:1166-1179`).

- Hypothesis for future vendor-key work: a raw-`MediaFormat` factory would need careful lifecycle handling because any extra `MediaCodec` created for probing must be released. The diagnostics code models this correctly: it creates a codec only to read vendor parameters and releases it in `finally` (`EncoderCapabilityDiagnostics.kt:137-162`). Future injection must also preserve Transformer cancellation/release behavior; current batch cancellation only cancels the Transformer (`BatchCompressorViewModel.kt:1225-1229`).

## 4. Are `KEY_VIDEO_QP_*` keys reachable through public Media3 1.5.0 APIs?

- REJECTED as current-code reachable behavior. The batch path only sets `setBitrate` and `setBitrateMode` on `VideoEncoderSettings.Builder` (`BatchCompressorViewModel.kt:1168-1172`). The older single-video path also only sets bitrate and CBR/VBR mode (`CompressorViewModel.kt:745-766`). No current source path injects `KEY_VIDEO_QP_*`.

- REJECTED as device-proven from checked-in evidence. The repo has diagnostics that log `FEATURE_QpBounds` (`EncoderCapabilityDiagnostics.kt:101-105`) and an experiment comment saying S23 probing found it false (`ExperimentalEncoderControls.kt:15-18`), but the actual S23 log output is not checked into the repo. Treat “false on all S23 Ultra hardware encoders” as a hypothesis unless paired with collected diagnostics.

## 5. Does commit `95143e4` weaken truth labels, remux fallback, replacement safety, or HDR/FPS/audio verification?

- REJECTED: The experimental CBR control is debug-gated and capability-gated (`ExperimentalEncoderControls.kt:34-59`). It changes how PL encode attempts are requested, not how outputs are judged (`ExperimentalEncoderControls.kt:20-26`, `BatchCompressorViewModel.kt:1148-1179`).

- REJECTED: Export failures still fall back to remux for PL attempts (`BatchCompressorViewModel.kt:586-609`), and verification failures or non-replacement-safe PL outputs still fall back to remux (`BatchCompressorViewModel.kt:631-669`, `PerceptualLosslessVerifier.kt:4-12`).

- REJECTED: Replacement safety is stronger after `95143e4`. `OutputVerifier` requires strict smaller output for PL replacement (`OutputVerifier.kt:288-296`), and `replaceOriginalSafely` refuses to write when `verification.replacementSafe` is false (`BatchCompressorViewModel.kt:1556-1566`).

- REJECTED: Overshoot learning affects planning/remux preference, not truth labels. The plan uses learned overshoot only for pre-encode near-optimal prediction (`BatchCompressorViewModel.kt:1056-1068`), prediction clamps overshoot to 1.0..2.0 (`BatchQualityBitratePolicy.kt:320-338`), and learning keeps target ratios clamped to policy floors/max ratio (`SmartPerceptualProfileEngine.kt:136-141`, `SmartPerceptualProfileEngine.kt:170-180`, `SmartPerceptualProfileEngine.kt:198-213`).

- REJECTED with caveat: `95143e4` does not newly weaken HDR/FPS/audio verification; the accepted verifier weaknesses above are in `OutputVerifier`’s current verification model, not specifically caused by the CBR experiment. The commit’s verifier hunk hardens replacement by adding `strictlySmaller` (`OutputVerifier.kt:288-296`), while HDR/FPS/audio checks remain the existing metadata/heuristic checks (`OutputVerifier.kt:137-147`, `OutputVerificationFormatter.kt:26-34`, `OutputVerifier.kt:180-192`).
```
[2026-07-09T06:31:47.099Z] Turn completion inferred after the main thread finished and subagent work drained.

[2026-07-09T06:31:47.908Z] Final output
I could not write `C:\Users\zachk\Compressor\docs\copilot\codex-review-pr19-2026-07-08.md` because this session’s filesystem sandbox is read-only and approvals are disabled. I did not run Gradle. Full report content follows.

