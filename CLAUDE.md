# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Fork of JoshAtticus/Compressor: a native Kotlin/Compose Android video compressor, extended for Samsung Galaxy S23 Ultra batch workflows with a measured "Perceptually Lossless" (PL) mode. Package `compress.joshattic.us`; applicationId `io.github.zfkirke0109.galaxycompressor`; minSdk 24, target 36; native code is arm64-v8a only.

## Commands

```sh
./gradlew :app:assembleDebug                      # debug APK (builds the native VMAF libs via CMake)
./gradlew :app:testDebugUnitTest                  # all JVM unit tests (what CI runs)
./gradlew :app:testDebugUnitTest --tests 'compress.joshattic.us.quality.QualityProbePolicyTest'
./gradlew :app:testDebugUnitTest --tests '*ItemPipelineTest.anEncodeAtOneHundredPercentIsNotACompletedItem'
./gradlew :app:compileDebugAndroidTestKotlin      # instrumentation tests compile; running them needs a device
python3 scripts/ci/verify_native_alignment.py app/build/outputs/apk/debug/*.apk   # 16 KB page-size gate (CI)
```

Python suites are self-running (no pytest) and import by module name, so run each from its own directory, as CI does:

```sh
for t in $(git ls-files '*/test_*.py'); do (cd "$(dirname "$t")" && python3 "$(basename "$t")"); done
```

`requirements-test.txt` (numpy) is only needed by `research/hdr_measurement_basis`. Lint has a known pre-existing error baseline, so a `lintDebug` failure is not necessarily caused by your change. CI (`.github/workflows/android-ci.yml`) runs build, unit-tests and diagnostics-tests; `pre-commit.ci` always errors because the repo has no `.pre-commit-config.yaml`.

Device work: `scripts/device/run-device-checks.sh CLIP.mp4 [ANDROID_USER]` runs `PipelineDeviceTest`; batches run in a secondary profile (user 150, Secure Folder). `docs/DEVICE_VALIDATION.md` has the procedure.

## Architecture

Two screens: `MainActivity`/`CompressorViewModel` (single file, upstream) and `BatchMainActivity`/`BatchCompressorViewModel` (the fork's batch pipeline, where nearly all work happens).

**Batch pipeline** (`BatchCompressorViewModel`): `runBatch` → `processItem` → `ItemPipeline` running four stages: `planItem` (codec choice, PL plan, probe ladder, size gate) → `produceOutput` (Media3 `Transformer` encode via `compressOne`, or `MediaExtractor`/`MediaMuxer` stream copy for Remux Only, then `Mp4MetadataRemuxer` rewrites metadata) → `verifyOutput` (`OutputVerifier` structural checks, then `certifyPixels`, then the PL verdict/fallback) → `finalizeItem` (terminal classification, record, optional original replacement). A stage that ends the item returns false after setting a terminal state. Row progress goes through `PhaseReporter`/`ItemProgressModel` bound to an `AttemptToken`. Never write progress fields directly; stale callbacks must be ignored. Methods in this file must stay under ART's 10,000-code-unit compile limit (a 215K-unit coroutine once aborted the process), so keep stages as separate suspend functions.

**Quality measurement** (`quality/`): `PerceptualQualityProber.runLadder` probes candidate ratios on short windows planned by `ProbeWindowPlanner` (each window after a keyframe lead-in that is decoded but not scored). `VmafPairScorer` pairs frames by PTS (`PtsAligner`) and scores with native libvmaf `vmaf_v0.6.1` (`VmafNative`, `phoneModel = false`); `VmafNativeV1` is shadow-only telemetry. `QualityProbePolicy` holds the PL bar (window mean ≥ 95.5, p5 ≥ 91, min ≥ 84, ≥ 12 frames) and the probe-selection margins (+0.5/+1.25/+1.0, probe only). `CertificationDecision` types an outcome (passed / measured failure / insufficient / unavailable / misaligned). `MeasuredOvershoot` and `BatchQualityBitratePolicy.predictedPerceptualLosslessBytes` drive the size gate. `ResolvedEncodePlan` is the single source for the encoder request, the estimate and the plan log.

**Truth layer**: `OutputVerifier` is the only source of a "verified" verdict; `FinalAcceptance` separates the final verdict from the structural one; `BatchTerminalResult` + `BatchTerminalAccounting` decide what counts as a real compression and saved bytes. `KeepOriginalMessages` words every keep-original by its basis (measured / learned / heuristic / cannot be measured).

**Learning** (`SmartPerceptualProfileEngine`): local SharedPreferences profiles keyed by technical buckets only; learns only from evidence about bits (`LearningEvidencePolicy`, `CertificationFailure`). It picks targets; it never decides verification.

**Diagnostics**: `DiagnosticsRecorder` writes schema-versioned JSONL (`files/diagnostics/<batchId>/session.jsonl`, also logcat tag `CompressorDiag`) with hashed source ids, never names or paths; `DiagLog` mirrors decision lines to `decisions.log`. The in-app export is one ZIP. `scripts/diagnostics/parse_session_jsonl.py <zip|jsonl> [--batch <id>]` summarises a batch; captures hold many batches from several builds, so always name the batch. `make_replay_fixture.py` + `B169ObservationalReplayTest` replay recorded decisions through production Kotlin.

## Rules specific to this repo

- `docs/PERCEPTUALLY_LOSSLESS.md` defines the PL claim, its gates, the two scoring protocols (app sampled windows vs offline whole-clip `measure_quality.py`) and the ABX validation procedure. Read it before touching thresholds, scoring or labels.
- Do not lower PL thresholds, margins or safety floors to get more compressions; change them only with measured evidence (device captures, ABX). Only measured evidence may say "would visibly lose quality"; unavailable or insufficient evidence keeps the original without that claim.
- Remux Only never uses `Transformer`. PL never silently changes resolution, FPS, HDR/colour or audio; AAC audio is passed through bit-exact and proven by packet comparison.
- Experiments (`EncoderExperiments`: B-frames, safer-rung retry) stay opt-in until a device run validates them.
- JVM unit tests cannot construct `android.net.Uri`; use `TestUris.placeholder` (backed by `android.net.PlaceholderUri` in the test source set). Real transcoding can only be tested on a device.
- `.claude/skills/` holds project skills (Media3 PL invariants, Compose UI safety, logcat/device validation, Gradle CI debugging, security/privacy review, repo safety); load the relevant one before working in its area.
