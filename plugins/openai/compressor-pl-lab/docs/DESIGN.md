# Compressor Perceptual-Lossless Lab design

Status: version-1 architecture implemented and offline-verified on 2026-08-14. Phase 5 remains an evidence gate, so package correctness does not by itself establish a perceptual-lossless `PASS`.

## Boundaries

- Production policy, verifier, encoder, Media3, JNI, and native VMAF code remain authoritative and unchanged by lab operations.
- The canonical Python implementation, schemas, authority matrix, and tests live under `tools/perceptual_lossless_lab/`.
- `plugins/claude/compressor-pl-lab/` and `plugins/openai/compressor-pl-lab/` are independently loadable packages. Each receives a reproducibly generated copy of the runtime, schemas, contracts, and common skill material; a drift test compares generated content byte-for-byte.
- Claude uses Skills, four native subagents, and Claude hooks. OpenAI uses Skills plus four focused role Skills; it has no plugin-level `agents/` component. OpenAI hooks are Codex-only and are not presented as a ChatGPT capability.
- MCP and ChatGPT apps are omitted until a structured adapter is justified and smoke-tested.

## Stable operations

The public CLI exposes one subcommand per operation ID:

| Operation | Purpose | Record owner |
|---|---|---|
| `pl_device_inventory` | Read-only ADB/device and codec inventory | `android-device-lab` |
| `pl_build_install` | Guarded supplied-APK identity verification and update-only install | `android-device-lab` |
| `pl_encode_candidate` | Plan or register a bounded candidate produced by the declared Android adapter | `codec-scientist` |
| `pl_score_pair` | Run the existing fail-closed pair scorer | `perceptual-evaluator` |
| `pl_score_corpus` | Serial, budgeted corpus scoring | `perceptual-evaluator` |
| `pl_hdr_compare` | Compare declared HDR/color evidence without tone-map substitution | `perceptual-evaluator` |
| `pl_pts_compare` | Apply the repository's leading-drop and internal-misalignment rules | `perceptual-evaluator` |
| `pl_run_boundary_suite` | Run the explicit fast or expensive validation slice | `deterministic-core` |
| `pl_optimize_policy` | Rank candidate proposals inside frozen constraints without editing policy | `codec-scientist` |
| `pl_compare_baseline` | Validate and compare hash-linked baseline/candidate evidence | `deterministic-core` |
| `pl_generate_report` | Apply the independent judge and materialize verdict/report records | `regression-judge` |

Every subcommand supports `--help`, requires explicit repository/request/experiment paths, defaults to dry-run, and requires `--execute` before mutation or expensive work. Corpus and optimization requests must declare candidate, worker, duration, and storage budgets; v1 defaults to and enforces one worker.

## Corpus manifest v1

The corpus is strict JSON, not the repository's legacy CSV or numeric-schema manifest. It has exactly `schema_version: "1"` and a nonempty `items` array. Every item has exactly:

```json
{
  "pair_id": "pair-001",
  "source_path": "media/source.mp4",
  "source_sha256": "<64 lowercase hex>",
  "source_size_bytes": 123,
  "output_path": "media/output.mp4",
  "output_sha256": "<64 lowercase hex>",
  "output_size_bytes": 100
}
```

Paths are normalized relative POSIX paths beneath the manifest directory. Sources and outputs must be distinct and disjoint; pair IDs and output paths are unique. The runtime rejects traversal, absolute/private paths, symlinks, junctions/reparse points, hard links, missing/non-regular files, duplicate or unknown fields, and any declared size/hash mismatch. It streams each media file through stable before/handle/after identity checks. The frozen specification stores only the sanitized manifest hash/alias, source-set hash, pair-set hash, and item count; absolute host paths are never durable evidence.

## Process and verdict semantics

Process status is separate from quality verdict. A valid quality `FAIL` is a completed calculation.

| Exit | Meaning |
|---:|---|
| 0 | Valid completed result or valid dry-run |
| 2 | Valid quality `FAIL` when `--gate` was requested |
| 10 | Invalid input/schema/version |
| 11 | Missing prerequisite |
| 12 | Device/package/build/signer mismatch |
| 13 | Incomplete, stale, hash-mismatched, or confounded evidence (`BLOCKED`) |
| 14 | Internal failure |
| 15 | Unsafe overwrite or immutable-record conflict |

The only quality verdicts are `PASS`, `FAIL`, and `BLOCKED`. A metric failure with complete evidence is `FAIL`; absent, stale, incompatible, thermally confounded, or hash-mismatched evidence is `BLOCKED`.

## Evidence and immutability

- An experiment directory is explicit and must be new or contain the lab ownership marker.
- Each run is created under `runs/<run-id>/`; completed runs are never overwritten. Resume requires the original request and experiment-specification hashes.
- Experiment specifications are frozen and content-addressed before candidate evaluation.
- Corpus, artifact, baseline, candidate, evaluation, device, comparison, verdict, calibration, investigation, and report records use distinct version-1 schemas and producer roles.
- Immutable records are atomically written under `records/<type>/<sha256>.json`. Corrections create a new record with `supersedes_sha256`.
- The deterministic judge accepts only complete schema-valid chains whose declared links match recalculated hashes. Candidate authors and evaluators cannot produce verdict records under the authority matrix.
- Role labels and Claude tool restrictions provide partial host enforcement; producer identity is declarative rather than cryptographically authenticated. Hashes, schemas, immutable storage, and the judge are the technical enforcement layer.

## Safety

- No operation deletes/replaces source media, uninstalls, clears app data, reboots, roots, crosses Android users, changes unrelated settings, or weakens policy thresholds.
- Device-changing operations require serial, Android user, package ID, expected build identity, expected signer fingerprint, experiment directory, and `--execute`.
- External processes use argument arrays without a shell, bounded timeouts, and sanitized outputs.
- Reports use logical paths and hashes only. Raw device serials and private absolute paths are rejected by redaction tests.
- The fast edit hook matches resolved sensitive paths and runs only verifier truth tables, policy boundaries, identity/alignment fixtures, and package/schema/script validation. Device, corpus, encode, and full boundary suites remain explicit.
- Stop hooks honor `stop_hook_active` and block at most once. They permit honest `BLOCKED` handoffs and require a current hash-linked report for `PASS`, `improved`, `optimized`, or `complete` PL claims.
- Hooks are bounded host guardrails, not the evidence authority: a host interruption or timeout can discard a hook decision. The PostToolUse outer timeout covers all three bounded inner checks, Stop has a separate artifact-rehash budget, and concurrent relevant edits fail closed for an explicit retry. The CLI, immutable store, schemas, and deterministic judge remain authoritative.

## Native workflows

- `pl-calibrate` validates the corpus/metric prerequisites, extracts thresholds only from one repository-authority snapshot, publishes a pending calibration, runs the boundary and serial corpus steps, and then publishes a superseding complete calibration linked to the exact frozen corpus, every canonical pair evaluation, and the boundary artifact. It never updates production policy or issues an overall verdict.
- Host corpus and pair scoring accept only the repository scorer's authenticated `vmaf` contract. The frozen model identity is domain-bound to the captured scorer bytes, schema version, and exact built-in libvmaf selector; an unrelated caller model file or fabricated metric/version fails closed. Direct dry-runs validate live media, tools, model provenance, and immutable lineage without writing evidence.
- `pl-benchmark` consumes an already frozen experiment specification plus a complete calibration and preexisting baseline/corpus lineage, stages the bounded candidate chain, resumes only on matching hashes, requires one measurement-pipeline identity across calibration/baseline/candidate evaluations, and invokes the judge only after all required records exist.
- `pl-investigate` consumes a specific `FAIL` or `BLOCKED` chain, identifies stale/missing/confounded evidence, and emits a bounded non-mutating diagnosis.

Claude invocation is namespaced slash-Skill syntax. Codex invocation is the namespaced `$skill` syntax. ChatGPT uses the Skills picker; it has no fabricated slash alias and does not execute Codex hooks.

## Known version-1 limitations

- Stable before/open-handle/after identity checks reject ordinary symlink, reparse-point, hard-link, persistent-change, and hash-mismatch attacks. They do not close an adversarial writable-parent junction swap on Windows because Python does not expose a component-by-component reparse-safe directory-handle traversal here.
- Media and tool hashes are verified before and after bounded execution, but a privileged concurrent writer could perform an `A -> B -> A` pathname swap wholly inside that interval. Closing that residual window requires an OS-handle-bound execution/input strategy or execution from a verified immutable tool-owned copy.
- Plugin payload generation captures one canonical byte map, atomically replaces each file, checks both packages byte-for-byte, and detects a changed source snapshot. It is not a cross-package filesystem transaction; interruption can leave a detectable partial/mixed payload, which must be repaired by rerunning the generator and its `--check` gate.
- The Android Media3 encode adapter remains intentionally unregistered until a device-backed implementation is tested. `pl_encode_candidate` therefore returns `BLOCKED` instead of manufacturing media or treating a host encode as equivalent.
