# VMAF v1 integration plan (proposal — NOT implemented this pass)

Status: **proposal only.** Rationale: v1 is a quality/honesty upgrade (banding/chroma), NOT a
compression-increase lever (it is stricter). It must not be silently swapped into the acceptance
gate. This plan meets the directive's bar for introducing a new model.

## Smallest technically-sound path
1. **Upgrade prebuilt libvmaf 3.0.0 → 3.2.0** (arm64 static) — 3.2.0 ships built-in v1 models and
   improved threading. Keeps the `vmaf_model_load(name)` pattern (load `vmaf_v1.0.16` by name), so
   `vmaf_jni.c` changes are minimal.
2. **Content-aware model selection** (new native param): 1080p SDR ≤30fps → `vmaf_v1.0.16_3d0h`;
   1080p 50–60fps → `..._hfr_3d0h`; 4K ≤30 → `..._1d5h_2160`; 4K 50–60 → `..._hfr_1d5h_2160`.
   Phone models (`5d0h`) remain SECONDARY diagnostics only.
3. **Enable CAMBI** feature (encode-side width/height/bitdepth) for banding detection.
4. **10-bit SDR evaluation** path (v1 recommendation) — extend YuvFrameReader to feed 10-bit;
   current path is 8-bit I420 only.
5. **Re-derive thresholds against v1** — its score distribution differs (and 3H 4K models use
   `[0,110]`). Do NOT reuse 95.5/91/84. Calibrate from a labeled pass/fail set.
6. **Versioned metric telemetry** — add metricName, modelName, modelHash, bitDepth, fpsClass,
   preprocessing to DiagnosticsRecorder; keep v0.6.1 available behind a flag for A/B.
7. **Model-selection unit tests** (which model for which w×h×fps).

## Cost estimate (rough)
- APK size: v1 models are compiled into libvmaf 3.2.0 (no extra assets) — static lib grows modestly
  (~hundreds of KB); CAMBI adds CPU.
- Runtime: v1 is faster-threaded but CAMBI adds per-frame cost; net per-probe cost likely similar
  to slightly higher. Thermal impact bounded by the existing 150 s/clip probe budget.
- Memory: comparable; feature buffers grow with CAMBI.

## Expected effect on results (derived, honest)
- Accepted compressions likely **decrease slightly** (v1 catches banding v0 passed).
- Honesty improves: a v0.6.1 "PL Verified" that hides sky/gradient banding would be caught.
- This is the right trade for a "Perceptually Lossless" *promise*, but it does NOT serve the
  "compress more" goal — those are separate axes.

## Controlled validation before shipping
A/B the same matched set with v0.6.1 vs v1 selection; report per-file score deltas and any pass→
fail flips; require a fresh-store on-device run; no threshold claim without it.
