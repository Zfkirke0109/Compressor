# Compressor Perceptual-Lossless Lab — OpenAI

This repo-contained, unpublished OpenAI plugin exposes native Skills, a deterministic Codex-local CLI, versioned evidence schemas, and Codex hooks. It declares no MCP server, app, or plugin-level agent component; repo-local packaging does not imply that model interactions are local or private.

## Host-native invocation

In Codex, invoke:

- `$compressor-pl-lab:pl-calibrate`
- `$compressor-pl-lab:pl-benchmark`
- `$compressor-pl-lab:pl-investigate`

In ChatGPT, type `@` and select the corresponding bundled Skill. Claude slash commands are not valid OpenAI invocations.

Python 3.10 or newer must be on `PATH`; verify it read-only with `python --version`. The Skill guides the session; the bundled CLI performs deterministic local work. Resolve `scripts/pl_lab.py` from the loaded package. From the Compressor repository root, a checkout-local invocation has this exact shape:

```powershell
python -B "plugins\openai\compressor-pl-lab\scripts\pl_lab.py" workflow calibrate --repository "<repo>" --request "<request.json>" --experiment "<dir>"
python -B "plugins\openai\compressor-pl-lab\scripts\pl_lab.py" workflow benchmark --repository "<repo>" --request "<request.json>" --experiment "<dir>"
python -B "plugins\openai\compressor-pl-lab\scripts\pl_lab.py" workflow investigate --repository "<repo>" --request "<request.json>" --experiment "<dir>"
```

Omit `--execute` for the required dry-run. Add it only after reviewing the plan and identities. Only benchmark accepts `--gate`.

## Surface boundary

A trusted Codex local-plugin session can run the repo-contained CLI and its configured hooks against an accessible Compressor checkout. ChatGPT can use the Skill guidance and inspect files the user supplies, but it does not execute Codex hooks and does not gain access to a local repository, Android device, Python, or host binaries by loading this plugin. In ChatGPT, any local, device-backed, or baseline claim without supplied admissible evidence remains `BLOCKED`.

The four role Skills preserve codec-scientist, perceptual-evaluator, Android-device-lab, and regression-judge responsibilities. OpenAI plugin packages do not carry Codex custom-agent TOML definitions. Role Skills are orchestration guidance; the core enforces declared producer-role/type compatibility, hash-linked output contracts, and deterministic judging, but a producer label does not cryptographically authenticate a human or agent. The package declares no MCP or app capability because none is implemented and smoke-tested.

## Named-workflow request contract

Every named request is strict version 1 JSON. Unknown or missing fields are rejected. Common top-level fields are `schema_version`, `workflow_id`, `run_id`, `created_at`, and `payload`; `created_at` is a UTC timestamp. The workflow builds each operation envelope, so values under `payload.step_payloads` are raw operation payload bodies, not full operation requests.

| Workflow | Additional top-level field | Exact `payload` keys | Canonical steps |
|---|---|---|---|
| `pl-calibrate` | `definition` | `step_payloads` | `pl_run_boundary_suite`, `pl_score_corpus` |
| `pl-benchmark` | `experiment_spec_sha256` | `initial_evidence`, `step_payloads` | `pl_device_inventory`, `pl_build_install`, `pl_encode_candidate`, `pl_score_pair`, `pl_hdr_compare`, `pl_pts_compare`, `pl_compare_baseline`, `pl_generate_report` |
| `pl-investigate` | `experiment_spec_sha256` | `target_verdict_sha256`, `step_payloads` | `pl_compare_baseline`, `pl_run_boundary_suite`, followed by workflow publication of an `investigation` record |

Calibration `definition` must match `runtime/schemas/v1/spec-freeze-request.schema.json`: it declares `corpus`, `metrics`, `pts`, `hdr`, `tolerances`, `device_requirements`, `budgets`, and the four repository-authority `constraints`. The runtime resolves and verifies the live repository authority; the caller cannot supply different operators or numeric thresholds.

Benchmark `initial_evidence` must contain exactly `calibration_sha256`, `corpus_sha256`, `baseline_sha256`, `baseline_artifact_sha256`, and `baseline_evaluation_sha256`. The complete calibration must hash-link the frozen corpus, its full corpus-evaluation set, and the boundary artifact. All records must already exist in the experiment, share the requested run/specification, and form a valid baseline lineage. Dry-run does not manufacture them.

The exact step-template fields are:

| Step | Fields supplied under `step_payloads` |
|---|---|
| `pl_run_boundary_suite` | `mode`; expensive mode also requires `max_candidates`, `max_workers`, `max_duration_seconds`, `max_storage_bytes` |
| `pl_score_corpus` | `corpus_manifest_path`, `ffmpeg_executable`, `ffprobe_executable`, `model_id`, `max_candidates`, `max_workers`, `max_duration_seconds`, `max_storage_bytes` |
| `pl_device_inventory` | `adb_executable`, `device_serial`, `android_user`, `package_id`, `expected_firmware_sha256` |
| `pl_build_install` | `adb_executable`, `apksigner_executable`, `aapt_executable`, `apk_path`, `device_serial`, `android_user`, `package_id`, `expected_build_identity`, `expected_signer_sha256`, `expected_firmware_sha256`, `max_duration_seconds`, `max_storage_bytes` |
| `pl_encode_candidate` | `candidate_id`, `source_path`, `output_logical_path`, `requested_configuration`, `adapter_id`, `max_duration_seconds`, `max_storage_bytes` |
| `pl_score_pair` | `source_path`, `output_path`, `pair_id`, `ffmpeg_executable`, `ffprobe_executable`, `model_id`, `max_duration_seconds`, `max_storage_bytes` |
| `pl_hdr_compare` | `reference`, `candidate`; the workflow injects `input_evaluation_sha256` |
| `pl_pts_compare` | `reference_pts_us`, `candidate_pts_us`; the workflow injects `input_evaluation_sha256` |
| `pl_compare_baseline` | `{}`; the workflow injects the immutable lineage |
| `pl_generate_report` | `{}`; the workflow injects the complete report lineage |

`step_payloads` must contain exactly the steps for that workflow. Nested objects and value constraints are authoritative in `runtime/schemas/v1/requests`. In v1, every supplied `max_workers` is exactly `1`.

## Execution, resume, and truthful limits

- Dry-run validates repository authority, request schemas, budgets, and all preexisting evidence it needs. It writes no experiment evidence and performs no device mutation.
- `--execute` runs the declared steps serially. Corpus scoring, pair scoring, encoding, device installation, and expensive boundary work occur only when explicitly requested; hooks never launch them.
- Codex hooks are bounded host guardrails, not the evidence authority. A host interruption/timeout may discard a decision; concurrent relevant edits request an explicit retry. Use the CLI, immutable evidence store, schemas, and deterministic judge for the final gate. ChatGPT does not execute these hooks.
- Resume by re-running the same named command with the same request, experiment, specification, and benchmark gate setting. Completed immutable steps are reused only when their resolved operation-request hashes and output records still validate. The first missing, mismatched, blocked, or confounded step is retried or reported; nothing is overwritten.
- `pl-calibrate` first publishes a pending calibration, then boundary and corpus-evaluation evidence, and only then a superseding complete calibration linked to every required record. The runnable host scorer requires `model_source: "host_libvmaf_builtin"`, binding the authority-captured scorer and its exact VMAF selector; unrelated asset/native model identities fail closed. Calibration issues no overall quality verdict or report.
- `pl-benchmark` is the only workflow that invokes the independent judge and report renderer. Its Android Media3 candidate encode remains fail-closed until a tested adapter is registered; absence of that adapter is `BLOCKED`, not simulated output.
- `pl-investigate` recomputes an existing same-lineage `FAIL` or `BLOCKED` verdict from live evidence, runs only comparison plus the declared boundary suite, and publishes an investigation record. It does not replace the target verdict or generate a new report.

Missing or incompatible evidence and unavailable declared adapters are `BLOCKED`. A valid, complete constraint violation is `FAIL`. Device/package/build/signer mismatch is an identity error, never a fallback-device selection.

## Deterministic operations

Run `python -B "<resolved-script>" --help` to discover the public surface. Every direct operation requires `--repository`, `--request`, and `--experiment`, and defaults to dry-run:

`pl_device_inventory`, `pl_build_install`, `pl_encode_candidate`, `pl_score_pair`, `pl_score_corpus`, `pl_hdr_compare`, `pl_pts_compare`, `pl_run_boundary_suite`, `pl_optimize_policy`, `pl_compare_baseline`, and `pl_generate_report`.

Exit 0 means dry-run or a valid completed calculation, including quality `FAIL` without a gate. Exit 2 is an explicitly gated final benchmark `FAIL`; 10 invalid input; 11 unavailable runtime prerequisite; 12 device identity mismatch; 13 incomplete/confounded evidence or another honest `BLOCKED`; 14 internal failure; and 15 immutable publication conflict.

## Disposable Codex load and hook trust

Use a fresh temporary `CODEX_HOME` and add the repository root as the marketplace source. Run this from outside any other plugin repository so project configuration cannot influence the smoke test:

```powershell
$repo = (Resolve-Path "<repo>").Path
$env:CODEX_HOME = Join-Path ([IO.Path]::GetTempPath()) ("codex-pl-smoke-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $env:CODEX_HOME | Out-Null
Push-Location ([IO.Path]::GetTempPath())
try {
    codex plugin marketplace add $repo --json
    codex plugin list --marketplace compressor-local --available --json
    codex plugin add compressor-pl-lab@compressor-local --json
} finally {
    Pop-Location
}
```

This exercises only the disposable home. Review the discovered command hooks in `/hooks` before trusting them; Codex skips untrusted hooks, and changed hook content requires review again. Do not normalize bypassing hook trust as routine use.

## Stop completion protocol

For a final Codex benchmark verdict, use exactly `PL_STATUS: PASS` (or `FAIL`), `PL_EXPERIMENT: validation/pl-lab/run-id`, `PL_REPORT: records/report/<report-sha256>.json`, and `PL_REPORT_SHA256: <report-sha256>`. A truthful blocked workflow uses exactly `PL_STATUS: BLOCKED` and `PL_MISSING: Android device access` (or another non-path evidence description). Extra `PL_*` fields and absolute paths are rejected. Calibration and investigation do not emit benchmark verdict trailers. ChatGPT does not execute this Stop hook.
