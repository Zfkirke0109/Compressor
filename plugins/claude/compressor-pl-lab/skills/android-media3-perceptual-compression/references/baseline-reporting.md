# Baseline comparison and reporting

Baseline reproduction uses the same corpus manifest, frozen experiment specification, schemas, metric/model pipeline, source identities, and declared numeric tolerances. Hardware codec bytes need not be identical across devices or firmware; any such difference must be captured as provenance or a confounder.

## Evidence DAG

A final judge consumes distinct baseline and candidate artifacts, matching evaluations, required device evidence, and a comparison, all from the same experiment/specification lineage. Every referenced record is loaded by content hash and rehashed. No dangling, duplicate-conflicting, cross-experiment, self, cyclic, or self-approved link is valid.

Only the deterministic judge issues the overall `PASS`, `FAIL`, or `BLOCKED` verdict. A report renders an existing verdict and is never itself evidence.

Reports include logical paths and hashes, not raw serials, usernames, home paths, UNC paths, secrets, or untracked media. They distinguish:

- `PASS`: complete evidence satisfies every frozen constraint.
- `FAIL`: complete evidence contains at least one measured constraint violation.
- `BLOCKED`: evidence is absent, invalid, stale, mismatched, unsafe, or confounded.

A completed quality `FAIL` is still a valid execution. Only `--gate` converts that verdict to the policy-gate exit code.
