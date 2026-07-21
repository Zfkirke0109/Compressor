# Perceptual-lossless threshold calibration (offline research)

Offline calibration tooling for the three VMAF window thresholds that gate
perceptually-lossless acceptance in
[`QualityProbePolicy.kt`](../../app/src/main/java/compress/joshattic/us/quality/QualityProbePolicy.kt):
`WINDOW_MEAN_MIN`, `WINDOW_P5_MIN`, `WINDOW_MIN_MIN` (current production 95.5 / 91.0 / 84.0).

**This is research tooling, not a production-policy updater.** Its output is
evidence to be reviewed, not constants to paste into the app. No study result
changes production without the frozen VMAF harness, boundary/regression clips,
JVM tests, and fresh Galaxy S23 Ultra device validation — and never without an
explicit, separately-reviewed change.

## Why this exists

An earlier Optuna study (the "v1" study, run externally on Colab) produced a
candidate that tightened all three windows and more than doubled the probe
bits-per-pixel floor (0.03 → 0.069). A full adversarial review
([`REVIEW_FINDINGS.md`](REVIEW_FINDINGS.md)) reproduced that result bit-exactly
and then **rejected it**: the evaluation was in-sample, three of four parameters
were unidentified plateau points, and the single row driving the whole
improvement was a logging-rounding artifact. This directory is the corrected
study plus a plan for the data the *next* round actually needs.

## Contents

```
scripts/prepare_dataset_v2.py   dataset hygiene + dedup -> model_rows_v2.csv
scripts/run_study_v2.py         exhaustive grid + nested holdout + bootstrap
scripts/build_corpus.py         build a bpp-diverse test corpus + offline VMAF labels
scripts/test_build_corpus.py    unit tests for the corpus builder's pure logic
scripts/build_smoke_notebook.py generate the Colab ffmpeg/libvmaf smoke test
notebooks/Compressor_Optuna_V2.ipynb   Colab driver
REVIEW_FINDINGS.md              why the v1 candidate was rejected
NEXT_ROUND_INSTRUMENTATION.md   what to change before the next capture round
```

## Building a test corpus

`build_corpus.py` addresses the root cause the review surfaced: the existing
captures can't identify the thresholds because they lack headroom diversity. It
cuts short, non-overlapping segments from a set of master videos and re-encodes
each across a bits-per-pixel ladder (emphasizing the disputed [0.03, 0.069) band)
and codec mix, producing controlled SOURCE clips. Its `label` phase re-encodes
each clip perceptually-lossless-style and scores it with the repo's authoritative
VMAF harness ([`measure_quality.py`](../../scripts/diagnostics/measure_quality.py))
to attach a ground-truth "compressible?" label — treating any encoder/measurement
failure as *unlabeled*, never as a quality-negative.

```
python scripts/build_corpus.py plan  --masters <dir>              # dry-run size/spread estimate
python scripts/build_corpus.py build --masters <dir> --out <dir>  # needs ffmpeg + ffprobe
python scripts/build_corpus.py label --out <dir>                  # needs ffmpeg w/ libvmaf
```

Needs `ffmpeg`/`ffprobe` (and libvmaf for `label`) on PATH — none are bundled. The
pure planning/accounting logic is covered by `test_build_corpus.py`; the
ffmpeg-dependent paths are integration-only. See `NEXT_ROUND_INSTRUMENTATION.md`
§5 for why the corpus, not the optimizer, is the lever.

### Smoke-testing the ffmpeg paths

Unit tests cannot reach the encode/VMAF paths. `build_smoke_notebook.py` emits a
self-contained Colab notebook (both tools embedded as base64) that acquires an
ffmpeg with libvmaf, generates synthetic masters, runs plan → build → label, and
asserts the build path, the label path, and the `measure_quality` coupling:

```
python scripts/build_smoke_notebook.py --out Compressor_Corpus_SmokeTest.ipynb
```

Upload the result to Colab and Runtime → Run all. Re-run it after changing
`build_corpus.py`. Its first real execution (2026-07-21) caught three bugs static
review had missed — including one that recorded failed measurements as
quality-negatives — so treat a green run as a release gate for this tool, not a
formality.

The study reads a capture bundle's `all_jobs_normalized.csv` (produced from
device logcat by
[`scripts/diagnostics/parse_batch_logcat.py`](../../scripts/diagnostics/parse_batch_logcat.py)).
Capture data itself is not committed (it lives under the git-ignored
`validation/`).

## Running it

Local:

```
python scripts/prepare_dataset_v2.py --data <bundle>/all_jobs_normalized.csv --out <bundle>
python scripts/run_study_v2.py       --data <bundle>/model_rows_v2.csv       --output <bundle>/results_v2
```

Colab: open `notebooks/Compressor_Optuna_V2.ipynb`, Runtime → Run all, upload the
capture bundle when prompted. No GPU; the search is a deterministic exhaustive
grid (no Optuna dependency, no seed sensitivity — only the bootstrap is seeded).

## What v2 fixes (each maps to a review finding)

| v1 problem | v2 fix |
|---|---|
| GroupKFold averaged fixed thresholds over all folds → in-sample evaluation | Nested holdout: select on train folds, score on the held-out fold |
| 94 trials tied; TPE reported one arbitrary plateau point | Exhaustive grid reports the entire tied-optimal box + cross-fold consensus |
| bpp floor unidentifiable (no positive below 0.1015; flat objective) | bpp removed from the search; `PROBE_MIN_SOURCE_BITS_PER_PIXEL` stays 0.03 |
| 3.8× row duplication (412 rows / 108 sources) | Dedup to one row per `nameHash` |
| 76% of rows predated the PTS pairing fix; 5 proven-false positives | Post-fix re-measures supersede pre-fix rows; known-false positives excluded |
| Negatives mixed measured fails with misalignment / cert-unavailable artifacts | Only measured rejections count as negatives |
| False-accept cost tradeable; asymmetric permissiveness penalty | FA=0 hard constraint; ties broken toward minimal change, never rewarded |
| Optimizer forced to emit a point candidate | `NO_CHANGE_RECOMMENDED` is a first-class, expected outcome |

On the existing captures the v2 verdict is **no change** (current production sits
inside the cross-fold consensus box; pooled held-out FA=0; bootstrap keeps
current production tied-optimal in 100% of resamples). That is the correct answer
for this input distribution — see `NEXT_ROUND_INSTRUMENTATION.md` for why the
data, not the optimizer, is the bottleneck.
