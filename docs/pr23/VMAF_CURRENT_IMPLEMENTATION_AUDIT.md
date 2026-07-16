# VMAF implementation audit + v1 investigation (Phases 9, 13)

## Current implementation (implementation fact, from source)
| Property | Value | Source |
|---|---|---|
| Library | libvmaf API 3.0.0 (real Netflix libvmaf, static arm64) | version.h; runtime vmaf_version() |
| Model | `vmaf_v0.6.1` (v0 generation) | vmaf_jni.c:83 |
| Transform | none in probe path (phoneModel=false) | VmafPairScorer.kt:86 |
| NEG | not enabled | no neg model/flag in vmaf_jni.c |
| CAMBI / banding | NOT present in v0.6.1 | model definition |
| Chroma features | NOT present in v0.6.1 | model definition |
| HFR awareness | NO (v0 motion, no 5-frame HFR window) | model definition |
| Bit depth | 8-bit I420 frames fed via YuvFrameReader | YuvFrameReader.kt |
| Sampling | up to 3× 1.2 s windows at 20/50/80% | QualityProbePolicy.probeWindows |
| Pooling | window mean + p5 + min | VmafPairScorer.kt:170 |
| Acceptance | mean≥95.5, p5≥91, min≥84 per window | QualityProbePolicy |

**Verdict: real libvmaf, but the OLD v0.6.1 model, non-HFR, no banding/chroma, 8-bit.**

## External primary-source facts (Phase 9, reverified 2026-07-15)
- Latest libvmaf release: **v3.2.0** (2024-06-20 tag; "Initial public release for VMAF v1").
  Source: github.com/Netflix/vmaf/releases. App is on API 3.0.0 → behind.
- **VMAF v1** announced June 2026 (Netflix TechBlog "VMAF v1: Good Is Not Good Enough"): adds
  banding + colour awareness, drops VIF, faster threading. Model family `vmaf_v1.0.16` (SDR).
  4K variants: 1.5H `[0,100]` (default) and 3H `[0,110]` (different range — thresholds not
  directly comparable).
- HDR v1 model: not yet publicly released (per directive; consistent with release notes).

## Implication for the user's hypothesis (derived)
VMAF v1's banding/chroma awareness makes it **stricter** on banding-prone content → it would
**reject more** re-encodes, not fewer. Therefore the outdated model is **not** the cause of the
high remux rate. Swapping to v1 is a *quality-honesty* upgrade (a v0.6.1 "pass" can hide banding
in skies/gradients that v1 would catch), not a compression-increase lever.

## What a faithful v1 integration requires (NOT done — proposal)
libvmaf 3.0.0 already supports v1 models *if* the model JSON + CAMBI feature extractor are
available in the build. To claim "VMAF v1" honestly the app must:
1. Upgrade prebuilt libvmaf 3.0.0 → 3.2.0 (built-in v1 models) OR ship `vmaf_v1.0.16` JSON assets.
2. Select the model by content: 1080p-3d0h / 4K-1d5h_2160 / HFR variants for 50-60fps.
3. Enable CAMBI (encode-side width/height/bitdepth params) for banding.
4. Prove feature-extractor availability at runtime (not just JSON present).
5. Add versioned telemetry: metric name, model path+hash, bit depth, fps class, preprocessing.
6. 10-bit SDR evaluation path (v1 recommendation) — currently 8-bit only.
7. Re-derive thresholds against v1 (its score distribution differs; do NOT reuse 95.5/91/84).
8. Estimate APK-size/mem/CPU/thermal impact; keep v0.6.1 available for controlled comparison.

Cost/benefit: high effort, primarily improves *safety* (catches artifacts v0 misses), likely
*reduces* accepted compressions. Recommend as a separate, controlled workstream, not bundled
with the latch fix.

## Cross-validation status (data gap)
An offline libvmaf-v1 cross-check of the 9 accepted outputs vs sources was NOT run this pass:
the bundled dev ffmpeg carries its own libvmaf/model set (not necessarily v1.0.16), and pulling
+ scoring all pairs with the exact v1 model matrix is a separate task requiring the official v1
model assets. The on-device certification scores (certWindowScores) ARE recorded per accepted
job and are strongly passing (e.g. p5 93–100, min 89–95 on the 0.65-ratio winners) — but under
v0.6.1, so banding sensitivity is unproven. This is logged in DATA_GAPS.md.
