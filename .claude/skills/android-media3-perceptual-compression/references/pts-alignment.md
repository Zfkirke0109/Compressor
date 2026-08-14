# PTS and frame alignment

`quality/PtsAligner.kt` is the runtime authority. It pairs reference and distorted frames by presentation timestamp using an adaptive tolerance derived from observed frame gaps, with a repository-defined floor and bounded leading drops.

## Rules

- Normalize both streams to the declared timebase before comparison.
- Preserve ordering and duplicates as evidence; do not silently sort or deduplicate malformed input.
- Apply the same streaming state transitions, adaptive tolerance, and maximum-drop rule as Kotlin.
- A leading mismatch can be dropped only within the declared bound. An internal mismatch, non-monotonic timeline, exhausted side, or excess drops is incomplete alignment and blocks pixel scoring.
- Bind alignment implementation/source hash and configuration in the experiment specification.

Use `pl_pts_compare` for fixtures and parity tests. A successful alignment proves identity of the compared timeline, not image quality by itself.
