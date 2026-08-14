# Perceptual compression

## Mode boundary

`Remux Only` uses extractor/muxer stream copy. Smart Perceptually Lossless is an encode attempt whose output is accepted only after structural and pixel-quality evidence. High Quality and Storage Saver are intentionally lossy and must remain labeled that way.

The policy authority is `app/src/main/java/compress/joshattic/us/BatchQualityBitratePolicy.kt`; adaptive target selection is in `SmartPerceptualProfileEngine.kt`; final structural acceptance is in `OutputVerifier.kt`. Read those exact files before proposing a policy change.

## Candidate rules

- Treat source media as immutable.
- Preserve source resolution, FPS, HDR/color, and audio for a PL candidate.
- Respect the policy's source-bits-per-pixel gate, target-ratio floors, maximum ratio, absolute bitrate floors, and meaningful-savings checks. Extract current values from source; do not copy remembered values into a new policy.
- A learned profile can make a candidate safer or prefer remux. It cannot reward a structural-only success as perceptual proof.
- Record requested and actual codec configuration. Hardware bitrate overshoot is evidence, not permission to reinterpret the target.

Use `pl_optimize_policy` only to create bounded candidate specifications inside a frozen experiment specification. Production policy edits require separate explicit approval.
