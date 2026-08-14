---
name: pl-investigate
description: Diagnose a specific Compressor perceptual-lossless FAIL or BLOCKED verdict from immutable evidence, optionally performing its declared bounded comparison and boundary reproduction. Use without mutating production policy, thresholds, measurements, or the target verdict.
---

# PL investigate

Apply the bundled `android-media3-perceptual-compression` constitution. Investigation explains a captured verdict; it does not issue a replacement verdict or benchmark report.

## Require the exact request

Require an explicit Compressor repository, lab-owned experiment, and version 1 request with exactly `schema_version`, `workflow_id`, `run_id`, `created_at`, `experiment_spec_sha256`, and `payload`. Require `workflow_id: "pl-investigate"`; the run ID must match the frozen specification.

Require `payload` to contain exactly:

- `target_verdict_sha256`: an existing immutable `FAIL` or `BLOCKED` verdict in the same experiment/run/specification.
- `step_payloads.pl_compare_baseline`: `{}`; the workflow derives the original complete evidence lineage after reproducing the target verdict.
- `step_payloads.pl_run_boundary_suite`: `mode`; expensive mode also requires positive `max_candidates`, `max_duration_seconds`, `max_storage_bytes`, and `max_workers: 1`.

Do not add a device, corpus, encode, report, or policy step to this workflow.

## Run and resume

1. Resolve the [bundled CLI](../../scripts/pl_lab.py), then dry-run:

   `python -B "<resolved-script>" workflow investigate --repository "<repo>" --request "<request.json>" --experiment "<dir>"`

   Dry-run rehashes referenced records/artifacts, verifies same-lineage evidence, and recomputes the target verdict against live repository authority. It writes no evidence. An invalid or non-reproducible target is `BLOCKED`.
2. Add `--execute` only for the declared comparison and boundary suite. The workflow publishes their immutable outputs and then one `investigation` record. If target validation or the bounded reproduction is unavailable during execution, publish an honest `BLOCKED` investigation record when the contract permits it; do not invent a successful reproduction.
3. Resume with the identical request, experiment, and specification. Reuse only completions whose resolved request hashes and immutable outputs still validate; retry or report the first missing/mismatched step without overwriting history.

Fast boundary mode is local. Expensive boundary mode requires all declared budgets and a registered adapter. Hypotheses and findings cannot edit production policy, frozen thresholds, measurements, source media, or the target verdict.

Do not claim investigation `PASS`, `FAIL`, or overall completion through the benchmark Stop protocol. If required evidence is unavailable, use exactly `PL_STATUS: BLOCKED` and `PL_MISSING: <non-path evidence description>`. Otherwise report the immutable investigation record identity without a `PL_STATUS` trailer.

Codex invocation: `$compressor-pl-lab:pl-investigate`. In ChatGPT, type `@` and select this Skill. ChatGPT can guide or inspect supplied evidence, but it does not gain local repository/device/Python/host-binary access and does not execute Codex hooks.
