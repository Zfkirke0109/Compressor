# PTS and frame alignment

`quality/PtsAligner.kt` is the runtime authority. It pairs reference and distorted frames by presentation timestamp using a FIXED tolerance — the repository-defined `TOLERANCE_FLOOR_US`, independent of frame rate — with bounded leading drops.

The tolerance is deliberately not derived from observed frame gaps. A correctly aligned pair's skew is bounded by how the streams were addressed, not by how far apart their frames are: the probe clip is cut with `setStartPositionMs(startUs / 1000)`, leaving a constant skew of `-(startUs % 1000)`, at most 999 us at any frame rate. A tolerance that widened with the frame interval reached 20.0 ms at 25 fps and scored half-frame-offset pairs as if they were aligned, which is misalignment reported as quality.

## Rules

- Normalize both streams to the declared timebase before comparison.
- Preserve ordering and duplicates as evidence; do not silently sort or deduplicate malformed input.
- Apply the same streaming state transitions, fixed tolerance, and maximum-drop rule as Kotlin. Do not reintroduce a frame-rate-dependent tolerance.
- A leading mismatch can be dropped only within the declared bound. An internal mismatch, non-monotonic timeline, exhausted side, or excess drops is incomplete alignment and blocks pixel scoring.
- Bind alignment implementation/source hash and configuration in the experiment specification.

Use `pl_pts_compare` for fixtures and parity tests. A successful alignment proves identity of the compared timeline, not image quality by itself.
