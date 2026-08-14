# Compressor Perceptual-Lossless Lab — Claude Code

This repo-contained, unpublished Claude Code plugin exposes three bounded Skills, four narrow role agents, a deterministic CLI, versioned evidence schemas, and fast edit/Stop hooks. It declares no MCP server; repo-local packaging does not imply that model interactions are local or private.

## Local load and native invocation

Python 3.10 or newer must be on `PATH`; verify it read-only with `python --version`. From the Compressor repository root, an isolated Skills/agents load smoke is:

```powershell
$claudePlugin = (Resolve-Path 'plugins\claude\compressor-pl-lab').Path
claude --bare --plugin-dir $claudePlugin --no-session-persistence --permission-mode plan --tools "Read,Glob,Grep" -p 'List the compressor-pl-lab workflow Skills and role agents; do not execute them.'
```

This process-scoped smoke does not publish the package or edit a personal marketplace. `--bare` intentionally skips hooks, which are validated separately; omit `--bare` only for a reviewed interactive development load. Invoke the Skills with their full Claude namespace:

- `/compressor-pl-lab:pl-calibrate`
- `/compressor-pl-lab:pl-benchmark`
- `/compressor-pl-lab:pl-investigate`

The Skill guides the session; the bundled CLI performs the deterministic work. From the same PowerShell session, the exact CLI shape is:

```powershell
python -B "$claudePlugin\scripts\pl_lab.py" workflow calibrate --repository "<repo>" --request "<request.json>" --experiment "<dir>"
python -B "$claudePlugin\scripts\pl_lab.py" workflow benchmark --repository "<repo>" --request "<request.json>" --experiment "<dir>"
python -B "$claudePlugin\scripts\pl_lab.py" workflow investigate --repository "<repo>" --request "<request.json>" --experiment "<dir>"
```

Omit `--execute` for the required dry-run. Add it only after reviewing the plan and identities. Only benchmark accepts `--gate`.

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
- Hooks are bounded host guardrails, not the evidence authority. A host interruption/timeout may discard a decision; concurrent relevant edits request an explicit retry. Use the CLI, immutable evidence store, schemas, and deterministic judge for the final gate.
- Resume by re-running the same named command with the same request, experiment, specification, and benchmark gate setting. Completed immutable steps are reused only when their resolved operation-request hashes and output records still validate. The first missing, mismatched, blocked, or confounded step is retried or reported; nothing is overwritten.
- `pl-calibrate` first publishes a pending calibration, then boundary and corpus-evaluation evidence, and only then a superseding complete calibration linked to every required record. The runnable host scorer requires `model_source: "host_libvmaf_builtin"`, binding the authority-captured scorer and its exact VMAF selector; unrelated asset/native model identities fail closed. Calibration issues no overall quality verdict or report.
- `pl-benchmark` is the only workflow that invokes the independent judge and report renderer. Its Android Media3 candidate encode remains fail-closed until a tested adapter is registered; absence of that adapter is `BLOCKED`, not simulated output.
- `pl-investigate` recomputes an existing same-lineage `FAIL` or `BLOCKED` verdict from live evidence, runs only comparison plus the declared boundary suite, and publishes an investigation record. It does not replace the target verdict or generate a new report.

Missing or incompatible evidence and unavailable declared adapters are `BLOCKED`. A valid, complete constraint violation is `FAIL`. Device/package/build/signer mismatch is an identity error, never a fallback-device selection.

## Boundaries and deterministic operations

The regression judge has only `Read`, `Glob`, and `Grep`. Other agents require `Bash` or `PowerShell` to call the deterministic CLI, so their no-write boundaries are additionally enforced by operation-to-role/type contracts and evidence validation; Claude agent labels are not cryptographic identities.

Run `python -B "$claudePlugin\scripts\pl_lab.py" --help` to discover the public surface. Every direct operation requires `--repository`, `--request`, and `--experiment`, and defaults to dry-run:

`pl_device_inventory`, `pl_build_install`, `pl_encode_candidate`, `pl_score_pair`, `pl_score_corpus`, `pl_hdr_compare`, `pl_pts_compare`, `pl_run_boundary_suite`, `pl_optimize_policy`, `pl_compare_baseline`, and `pl_generate_report`.

Exit 0 means dry-run or a valid completed calculation, including quality `FAIL` without a gate. Exit 2 is an explicitly gated final benchmark `FAIL`; 10 invalid input; 11 unavailable runtime prerequisite; 12 device identity mismatch; 13 incomplete/confounded evidence or another honest `BLOCKED`; 14 internal failure; and 15 immutable publication conflict.

## Stop completion protocol

Use an exact trailer only for a final benchmark verdict:

```text
PL_STATUS: PASS
PL_EXPERIMENT: validation/pl-lab/run-id
PL_REPORT: records/report/<report-sha256>.json
PL_REPORT_SHA256: <report-sha256>
```

`FAIL` uses the same four fields. A truthful blocked workflow uses exactly `PL_STATUS: BLOCKED` and `PL_MISSING: Android device access` (or another non-path evidence description). Extra `PL_*` fields and absolute paths are rejected. Calibration and investigation do not have a truthful `PASS`/`FAIL` trailer; report their record identities without claiming benchmark completion.
