# Repository & build identity (Phase 1)

## Working tree (measured)
- Branch: `fix/downloaded-video-pl-diagnostics`; HEAD `75d2624` (PR #23 head).
- Merge-base vs `origin/main`: `16948e1` (PR #22, already merged).
- PR #23 commits: `f10d968` (bounded adaptive search), `03b8a2f` (undershoot tolerance +
  decaying probe-skip + telemetry), `75d2624` (learning-latched remux label fix).
- Uncommitted before this pass: only untracked `extract_algorithm_evidence.py` at repo root
  (user/earlier-session artifact; left in place, not overwritten). PR #23 state: OPEN, MERGEABLE,
  0 reviews, 1 comment.

## Toolchain (measured, from libs.versions.toml / wrapper)
- Gradle 9.3.1, AGP 9.1.1, Kotlin 2.2.10, Media3 1.5.0, JDK 26 (local), minSdk 24 / targetSdk 36.
- Native: CMake + prebuilt static `libvmaf.a` (arm64-v8a) linked by `vmaf_jni.c` into
  `libcompressorvmaf.so`.

## VMAF implementation identity (implementation fact, from source)
- Bundled **libvmaf API 3.0.0** (`prebuilt/include/libvmaf/version.h`). Runtime `vmaf_version()`
  returns `3.0.0` (confirmed by on-device VMAF_SELFTEST log).
- Model: **`vmaf_v0.6.1`**, loaded by name via `vmaf_model_load` (`vmaf_jni.c:83`) — libvmaf's
  compiled-in model, no external JSON asset shipped.
- Flag: `VMAF_MODEL_FLAG_ENABLE_TRANSFORM` only when `phoneModel=1`. The real probe path
  (`VmafPairScorer.kt:86`) passes `phoneModel=false` → **plain non-phone v0.6.1**, matching the
  threshold calibration (comment at VmafPairScorer.kt:83). No NEG variant, no CAMBI, no chroma
  feature, not HFR-aware (v0 generation).
- Pooling (implementation fact, VmafPairScorer.kt:170): per-frame VMAF over each 1.2 s window,
  then window {mean, p5 = 5th percentile, min}. Up to 3 windows at 20/50/80% (QualityProbePolicy).
- Acceptance thresholds (implementation fact, QualityProbePolicy.kt): window mean ≥ 95.5,
  p5 ≥ 91.0, min ≥ 84.0.

## Capture-to-build binding (measured)
- Capture `batch_20260715_141019` sessionBuildCommit `4f4909e`. `4f4909e` is NOT a local commit
  (`git cat-file` fails) — expected: CI builds PR APKs from a merge ref, exactly as PR #22 showed
  `7e735c8` and the first new-build run showed `0199486`. The installed APK was CI artifact from
  run 29450487379 (PR #23 head `75d2624`), verified installed 2026-07-15 14:05:03 and VMAF self-
  test PASS. Chain of evidence rules out the old APK: the capture carries `REMUX_PREFERRED_BY_
  EVIDENCE` terminals and `probeWindowScores`/`certWindowScores`/`thermalStart` fields that exist
  only in the PR #23 build.
