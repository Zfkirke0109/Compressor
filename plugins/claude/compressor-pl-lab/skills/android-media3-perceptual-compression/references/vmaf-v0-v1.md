# VMAF v0 and v1

## Current authority

The on-device scorer is `quality/VmafPairScorer.kt`; acceptance rules are in `quality/QualityProbePolicy.kt`; PTS pairing is in `quality/PtsAligner.kt`; the native bridge is `app/src/main/cpp/vmaf_jni.c`. The JNI source identifies libvmaf and its built-in model. The host diagnostic scorer is `scripts/diagnostics/measure_quality.py`.

## Evidence rules

- Bind scorer version, model identity, model asset hash when applicable, phone-model transform flag, pixel format, dimensions, window selection, aggregation, and source hashes.
- Do not silently substitute a model, rescale, change frame rate, tone-map, or use a different metric implementation.
- Threshold names, operators, and values are a fixed semantic mapping extracted from authoritative source before candidate results exist.
- VMAF v0 is the retained compatibility/calibration lineage. A v1 experiment needs its own declared model/configuration and baseline; do not compare it directly to v0 as though provenance were unchanged.
- Empty output, too few aligned frames, unavailable model/native library, decode failure, or ambiguous provenance is `BLOCKED`. Complete measured values below any frozen threshold are `FAIL`.

The current probe policy includes mean, lower-percentile, minimum-frame, and minimum-compared-frame constraints. Always re-extract the exact values rather than relying on prose.
