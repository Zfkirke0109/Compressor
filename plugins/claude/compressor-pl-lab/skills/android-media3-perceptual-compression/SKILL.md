---
name: android-media3-perceptual-compression
description: Apply Compressor's non-negotiable perceptual-lossless semantics when work touches Remux Only, Smart Perceptually Lossless, codec or bitrate policy, VMAF, OutputVerifier, HDR/color, PTS alignment, MediaCodec/Media3, experiment evidence, or user-facing quality claims.
---

# Compressor perceptual-lossless constitution

Use these invariants before changing policy, evaluating an encode, or reporting a result.

## Non-negotiable semantics

- `Remux Only` is the only zero-loss mode. It is stream copy and must not enter a Transformer encode path.
- `Perceptually Lossless` is a conservative lossy re-encode. `Verified` is allowed only for the exact output measured by the verifier and required pixel-quality pipeline.
- Preserve resolution, frame rate, HDR/color identity, audio, duration, and frame-count parity. Missing or inconclusive evidence is `BLOCKED`, never an inferred pass.
- A valid, complete measurement that violates a frozen constraint is `FAIL`. `PASS` means every predeclared constraint passed. Execution success is separate from quality verdict.
- Honest remux or skip with zero savings is preferable to a misleading compression claim. Do not report positive savings for a same-size or larger output.

## Evidence and authority

- Freeze corpus, metric/model versions, alignment and HDR configuration, device conditions, budgets, numeric tolerances, and thresholds before evaluating a candidate.
- Thresholds and verifier rules come from repository code or a hash-linked calibration record. A candidate may propose a change but cannot activate it. Any approved change requires a new specification and baseline.
- Baseline and candidate artifacts, evaluations, device evidence, comparison, and verdict are separate immutable, content-addressed records. Corrections supersede; they never rewrite history.
- Codec authors, evaluators, and device collectors cannot approve their own evidence. Only the deterministic judge applies frozen constraints to a complete lineage.
- Missing, stale, cross-spec, hash-mismatched, self-approved, cyclic, or confounded evidence forces `BLOCKED`.

## Safety

- Never replace source media, silently tone-map HDR, cap FPS, weaken audio, cross Android users/profiles, or expose raw device serials/private paths.
- AV1 remains explicit opt-in. Do not assume advertised codec support equals a measured working path.
- Device mutation requires an explicit serial, user/profile, package/build/signer identity, experiment directory, and execute flag. Never uninstall, clear data, reboot, root, or alter unrelated settings.
- Local learning may select a target only inside repository-defined limits; it cannot lower safety floors, bypass verification, or issue a verdict.

## Load only the reference needed

- Compression mode or bitrate-policy work: [perceptual-compression.md](references/perceptual-compression.md).
- VMAF scoring, models, thresholds, or provenance: [vmaf-v0-v1.md](references/vmaf-v0-v1.md).
- MediaCodec, Media3, encoder controls, or requested-versus-actual configuration: [mediacodec.md](references/mediacodec.md).
- HDR, color, tone mapping, or metadata parity: [hdr-color.md](references/hdr-color.md).
- Timestamps, frame identity, timebases, or dropped-frame behavior: [pts-alignment.md](references/pts-alignment.md).
- Corpus, calibration, execution budgets, resume, or device collection: [experiment-corpus.md](references/experiment-corpus.md).
- Baseline comparison, verdicts, redaction, or reports: [baseline-reporting.md](references/baseline-reporting.md).

For repeatable lab work, route to `pl-calibrate`, `pl-benchmark`, or `pl-investigate`. Do not replace their versioned requests with improvised shell pipelines.
