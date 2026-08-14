---
name: pl-calibrate
description: Validate and freeze a bounded Compressor perceptual-lossless corpus, repository-derived constraints, metric/model pipeline, PTS/HDR configuration, budgets, and provenance before candidate evaluation. Use to establish or reproduce calibration evidence without tuning thresholds from observed candidate results.
---

# PL calibrate

Apply the bundled `android-media3-perceptual-compression` constitution first. Do not issue an overall `PASS` or `FAIL` from calibration.

## Require the exact request

Require an explicit Compressor repository, new or lab-owned experiment directory, and version 1 request with exactly these top-level fields: `schema_version`, `workflow_id`, `run_id`, `created_at`, `definition`, and `payload`. Require `workflow_id: "pl-calibrate"`.

Require `definition` to declare `corpus`, `metrics`, `pts`, `hdr`, `tolerances`, `device_requirements`, `budgets`, and the four authority-bound `constraints` defined by `runtime/schemas/v1/spec-freeze-request.schema.json`. The runtime must resolve live repository authority and reject changed operators, mappings, or thresholds.

Require `payload` to contain only `step_payloads`, with exactly:

- `pl_run_boundary_suite`: `mode`; when `mode` is `expensive`, also require positive `max_candidates`, `max_duration_seconds`, `max_storage_bytes`, and `max_workers: 1`.
- `pl_score_corpus`: `corpus_manifest_path`, `ffmpeg_executable`, `ffprobe_executable`, `model_id`, positive `max_candidates`, `max_duration_seconds`, `max_storage_bytes`, and `max_workers: 1`.

These are raw payload templates. Do not wrap them in operation envelopes or add undeclared steps.

## Run and resume

1. Dry-run the exact request first:

   `python -B "${CLAUDE_PLUGIN_ROOT}/scripts/pl_lab.py" workflow calibrate --repository "<repo>" --request "<request.json>" --experiment "<dir>"`

   Treat dry-run as read-only validation. It may read repository authority, corpus/model bytes, and declared prerequisites, but it writes no experiment evidence and performs no device mutation.
2. After explicit confirmation, repeat the same command with `--execute`. The workflow freezes the content-addressed experiment specification and corpus, publishes a pending calibration, runs `pl_run_boundary_suite` followed by serial `pl_score_corpus` evaluation, and publishes a superseding complete calibration only when the boundary artifact and every frozen corpus pair validate.
3. Resume by re-running the identical execute command with the same request and experiment. Reuse a completed immutable step only when its resolved request hash and outputs still validate; never overwrite or branch a completion.

Fast boundary mode is deterministic and local. Corpus scoring and expensive boundary mode require their declared host tools/assets/adapters and positive budgets. Missing or incompatible corpus/model/runtime evidence, a legacy manifest that does not match schema v1, or an unavailable adapter is honestly `BLOCKED`; an unavailable executable can be a prerequisite error. Calibration produces no benchmark verdict or report.

Do not claim calibration `PASS`, `FAIL`, or overall completion through the benchmark Stop protocol. If evidence is unavailable, use exactly `PL_STATUS: BLOCKED` and `PL_MISSING: <non-path evidence description>`. Otherwise report the immutable calibration record identity without a `PL_STATUS` trailer.

Native invocation: `/compressor-pl-lab:pl-calibrate`.
