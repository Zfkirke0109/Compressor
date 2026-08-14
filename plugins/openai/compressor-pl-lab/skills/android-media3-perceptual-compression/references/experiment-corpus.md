# Experiment and corpus execution

## Before execution

Create a versioned corpus manifest whose items and media identities are derived from validated bytes. Freeze a content-addressed experiment specification containing corpus identity, constraints and provenance, metric/model configuration, PTS/HDR configuration, device requirements, positive duration/storage/candidate/worker budgets, repository code/config fingerprint, and tool versions.

The only accepted v1 manifest is strict JSON with exactly `schema_version: "1"` and nonempty `items`. Each item contains exactly `pair_id`, `source_path`, `source_sha256`, `source_size_bytes`, `output_path`, `output_sha256`, and `output_size_bytes`. Paths use normalized relative POSIX syntax beneath the manifest directory. Pair IDs and output paths are unique; source/output path sets are disjoint. The runtime streams and verifies the declared media bytes using no-follow, single-link, containment, and before/after file-identity checks. Legacy CSV/numeric-schema manifests, unknown fields, traversal, absolute/private paths, reparse points, hard links, and size/hash mismatches are rejected or blocked. Durable records contain aliases and hashes, never absolute paths.

Use a new or lab-owned experiment directory. Dry-run must perform no device mutation and create no experiment evidence. Execution defaults to one worker. Estimate storage first and maintain runtime counters.

## Resume and failure

- Resume only the same request and specification hashes.
- Completed records are immutable; an identical publish is idempotent and different bytes at the same identity conflict.
- Rehash every input before use and verify it did not change afterward.
- Bound time, output, and process trees; use argv-only subprocesses and an environment allowlist.
- Missing corpus/model/device evidence or confounded conditions yield `BLOCKED`. An unavailable executable before evidence collection is a prerequisite error.

`pl-calibrate` establishes the frozen specification and calibration. `pl-benchmark` consumes it. `pl-investigate` consumes an existing `FAIL` or `BLOCKED` lineage and may run only a declared bounded reproduction.
