---
name: pl-benchmark
description: Run or resume a declared, bounded Compressor baseline-versus-candidate experiment under a frozen specification, producing separate artifact, evaluation, device, comparison, verdict, and report records. Use only when a valid preexisting baseline lineage and declared host prerequisites exist.
---

# PL benchmark

Apply the bundled constitution. Never loosen frozen constraints after seeing candidate results.

## Require the exact request and existing baseline

Require an explicit Compressor repository, lab-owned experiment, and version 1 request with exactly `schema_version`, `workflow_id`, `run_id`, `created_at`, `experiment_spec_sha256`, and `payload`. Require `workflow_id: "pl-benchmark"`; the run ID must match the frozen specification.

Require `payload.initial_evidence` to contain exactly five content hashes: `calibration_sha256`, `corpus_sha256`, `baseline_sha256`, `baseline_artifact_sha256`, and `baseline_evaluation_sha256`. All five records must already exist in this experiment/run/specification. The calibration must be complete and cover the exact frozen corpus, boundary artifact, and full corpus-evaluation set; the baseline, artifact, and evaluation links must validate. Dry-run does not create substitute evidence.

Require `payload.step_payloads` to contain exactly the eight canonical steps:

1. `pl_device_inventory`: full ADB/serial/user/package/firmware payload.
2. `pl_build_install`: full ADB/AAPT/apksigner/APK, serial/user/package, expected build/signer/firmware, duration, and storage payload.
3. `pl_encode_candidate`: candidate/source/logical-output/configuration, `android-media3` adapter, duration, and storage payload.
4. `pl_score_pair`: source/output/pair, FFmpeg/FFprobe, `vmaf_v0.6.1`, duration, and storage payload.
5. `pl_hdr_compare`: only `reference` and `candidate`; the workflow injects the preceding evaluation hash.
6. `pl_pts_compare`: only `reference_pts_us` and `candidate_pts_us`; the workflow injects the preceding evaluation hash.
7. `pl_compare_baseline`: `{}`; the workflow injects the immutable comparison lineage.
8. `pl_generate_report`: `{}`; the workflow injects the complete judge/report lineage.

Use the exact field and nested-object definitions in `runtime/schemas/v1/requests`. Do not caller-inject future record hashes.

## Run and resume

1. Dry-run before mutation or expensive work:

   `python -B "${CLAUDE_PLUGIN_ROOT}/scripts/pl_lab.py" workflow benchmark --repository "<repo>" --request "<request.json>" --experiment "<dir>"`

   Dry-run validates the frozen specification and preexisting baseline chain, request schemas, budgets, and planned lineage without writing evidence or touching the device.
2. After explicit confirmation, repeat with `--execute`. The workflow runs serially: read-only inventory, guarded update-only install, candidate encode, pair/HDR/PTS evaluation, baseline comparison, independent verdict, and report rendering. Use each role only for its authorized record types.
3. Resume with the identical request, experiment, specification, and gate setting. Valid completed steps are reused only when their resolved operation-request hashes and immutable outputs still validate. Retry the first incomplete step; never overwrite evidence.

Device work requires the exact serial and Android user/profile plus package, APK, build, signer, firmware, duration, and storage identities. Never pick a fallback target. The Android Media3 encode step remains fail-closed until a tested adapter is registered, so a truthful run may stop `BLOCKED` before scoring; never simulate the candidate artifact.

Only this workflow may apply the final judge and generate a report. Complete evidence is required for `PASS` or `FAIL`; missing, stale, cross-spec, identity-mismatched, unavailable, or thermally confounded evidence is `BLOCKED`. Without `--gate`, a complete quality `FAIL` is a successful calculation. Use `--gate` only on this command to make that final `FAIL` exit 2; `BLOCKED` exits 13.

For a final verdict claim, end with exactly four fields: `PL_STATUS: PASS` (or `FAIL`), `PL_EXPERIMENT: validation/pl-lab/<run-id>`, `PL_REPORT: records/report/<report-sha256>.json`, and `PL_REPORT_SHA256: <report-sha256>`. For unavailable evidence, use exactly `PL_STATUS: BLOCKED` and `PL_MISSING: <non-path evidence description>`. Do not add extra `PL_*` fields or absolute paths.

Native invocation: `/compressor-pl-lab:pl-benchmark`.
