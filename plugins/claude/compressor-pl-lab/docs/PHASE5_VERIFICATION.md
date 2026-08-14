# Phase 5 verification

Verification date: 2026-08-14. Final quality status: `BLOCKED`.

The implementation, offline contracts, packages, and available-host loading were exercised. Phase 5 is not a package-build synonym: it also requires reproduction of a canonical baseline with matching source, output, corpus, model, code/configuration, and device evidence. Those inputs were not available in this recovered checkout, so no perceptual-quality `PASS`, improvement, optimization, or baseline-reproduction claim is made.

## Verified implementation gates

| Gate | Result |
|---|---|
| Canonical PL-lab full suite | 162 tests passed |
| Explicit fast suite | 88 tests passed |
| Repository quality/parser suite | 33 tests passed |
| Corpus-builder suite | 38 tests passed |
| Capture-state-machine PowerShell suite | Passed every listed case |
| Android unit tests | `testDebugUnitTest` passed; 26 tasks executed |
| Skill structural validation | 12 packaged Skills passed |
| Package tests | 18 tests passed |
| Canonical/package drift check | Passed for both packages |
| Claude manifest/layout | Strict validator passed on Claude Code 2.1.228 |
| OpenAI manifest/layout | Installed preflight passed: skills-only, 8 Skills, 136 entries |
| Codex isolated local load | Passed in a fresh disposable `CODEX_HOME`; namespaced Skill visible |
| Installed Codex Stop wrapper | Unsupported benchmark-success claim was blocked; no bytecode written |
| Claude isolated local load | `BLOCKED`: installed CLI OAuth session expired before plugin loading |
| ChatGPT runtime load | `BLOCKED`: no separate ChatGPT Windows application was installed |

The full suite covers every public operation/help surface, dry-run/execute and exit semantics, strict schemas, domain-separated content hashes, atomic immutable publication, role/type authority, self-approval rejection, evidence DAG/cardinality, calibration lifecycle, judge recomputation, report validation/redaction, ADB argument/identity guards using fakes, bounded process termination, corpus/path/TOCTOU checks, hook path matching/recursion/Stop protocol, and generated-package parity. Device and real-media behavior remains separately gated below.

## Live prerequisite and baseline gate

Read-only inventory found Python 3.14.7, ADB 37.0.0, Claude Code 2.1.228, Codex CLI 0.146.1, and Codex app 26.803.10989.0. It also found:

- no connected ADB target;
- no `ffmpeg` or `ffprobe` executable on `PATH`;
- no strict version-1 corpus manifest plus matching source/output media in the recovered checkout;
- no content-addressed PL experiment containing a complete calibration and canonical baseline/artifact/evaluation lineage;
- no baseline report that can be rehashed and reproduced through `pl-benchmark`;
- no tested Android Media3 encode adapter registered by `pl_encode_candidate`.

The repository's legacy calibration builder writes CSV, while the lab intentionally requires the stricter version-1 JSON media-identity contract. Ignored historical `validation/` captures were not present in the recovered worktree and were not recreated or treated as evidence. Consequently, there is no baseline report path or report hash to report. The baseline reproduction result is `BLOCKED`, not `FAIL`.

## Frozen production tolerances

These values were re-extracted from the exact Kotlin authority snapshot; they were not copied from prose or tuned after observing a candidate.

| Contract | Value | Authority SHA-256 |
|---|---:|---|
| VMAF window mean minimum | 95.5 | `0a5da919146af7b95928e16dac0d09648f2d7cf75addea9ba655e8811489ec41` |
| VMAF window p5 minimum | 91.0 | same `QualityProbePolicy.kt` hash |
| VMAF window minimum-frame minimum | 84.0 | same `QualityProbePolicy.kt` hash |
| Minimum compared frames/window | 12 | same `QualityProbePolicy.kt` hash |
| PL output size growth tolerance | 0.03 | `81dad4c1d7dee0ede5d024ca9d822ab2059d8b14ecfc5ec26d37e83dd71fa573` |
| Duration tolerance | max(500 ms, 2%) | `3b8c398c0e225e6da4e37837435a7573c78864a6c29940899f526f120bea9e74` |
| Frame-count tolerance | max(2, 1%) | same `OutputVerifier.kt` hash |
| PTS floor / maximum leading drops | 4000 microseconds / 8 | `4566e895b6a288ee1e85cc9e6ec6d1bc5c53296000bd879c41f771a989643a1e` |

The complete frozen specification also binds the remaining bitrate ratio floors, corpus identities, scorer selector/source hash, HDR/color contract, budgets, repository commit/dirty/untracked identity, and requested device conditions before candidate evaluation.

## Exact continuation commands

First satisfy and verify host prerequisites without selecting a fallback device:

```powershell
where.exe ffmpeg
where.exe ffprobe
ffmpeg -version
ffprobe -version
adb devices -l
adb -s <serial> shell am get-current-user
```

Prepare a strict version-1 manifest whose declared hashes/sizes match the immutable media bytes, then dry-run and execute calibration using the OpenAI repo package (the same CLI arguments apply to the Claude package path):

```powershell
$repo = (Resolve-Path "<Compressor checkout>").Path
$calibrationRequest = (Resolve-Path "<pl-calibrate-request.json>").Path
$experiment = "<new or lab-owned experiment directory>"
python -B "$repo\plugins\openai\compressor-pl-lab\scripts\pl_lab.py" workflow calibrate --repository "$repo" --request "$calibrationRequest" --experiment "$experiment"
python -B "$repo\plugins\openai\compressor-pl-lab\scripts\pl_lab.py" workflow calibrate --repository "$repo" --request "$calibrationRequest" --experiment "$experiment" --execute
```

After calibration and a genuine same-spec baseline lineage exist, run the exact benchmark request. The request must include the complete calibration, corpus, baseline, baseline artifact, and baseline evaluation hashes plus explicit serial/user/package/APK/signer/firmware/tool budgets:

```powershell
$benchmarkRequest = (Resolve-Path "<pl-benchmark-request.json>").Path
python -B "$repo\plugins\openai\compressor-pl-lab\scripts\pl_lab.py" workflow benchmark --repository "$repo" --request "$benchmarkRequest" --experiment "$experiment" --gate
python -B "$repo\plugins\openai\compressor-pl-lab\scripts\pl_lab.py" workflow benchmark --repository "$repo" --request "$benchmarkRequest" --experiment "$experiment" --execute --gate
```

The explicit expensive boundary/corpus/device path is the same declared calibration/benchmark sequence with `pl_run_boundary_suite.mode` set to `expensive` and positive `max_candidates`, `max_workers: 1`, `max_duration_seconds`, and `max_storage_bytes`. It must not be launched by hooks.

To resume Claude host loading after authentication:

```powershell
claude auth login
$claudePlugin = (Resolve-Path "plugins\claude\compressor-pl-lab").Path
claude --bare --plugin-dir $claudePlugin --no-session-persistence --permission-mode plan --tools "Read,Glob,Grep" -p 'List the compressor-pl-lab workflow Skills and role agents; do not execute them.'
```

## Residual limitations

- Real Android encode/install/inventory behavior was not claimed; only deterministic fake-tool guards were exercised.
- Real ffmpeg/libvmaf media scoring and the full corpus were not run because their declared tools and corpus were absent.
- ChatGPT cannot run Codex hooks or access this checkout/device/host tools merely by selecting a Skill.
- Producer-role labels are declarative rather than cryptographic identity.
- Version 1 retains the documented adversarial writable-parent junction-swap and `A -> B -> A` pathname execution windows. Ordinary reparse/hard-link/persistent-change attacks are rejected; closing the residual windows requires OS-handle-bound traversal/execution or verified immutable tool-owned copies.
- Payload synchronization is checked and per-file atomic but not one cross-package filesystem transaction. An interrupted generation must be rerun and followed by `--check`.
