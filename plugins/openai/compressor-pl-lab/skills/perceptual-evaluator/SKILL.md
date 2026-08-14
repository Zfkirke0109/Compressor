---
name: perceptual-evaluator
description: Produce append-only Compressor evaluation evidence for PTS/frame alignment, VMAF model lineages, HDR/color identity, temporal behavior, CAMBI/banding when declared, and metric provenance. Use after artifacts and a frozen specification exist.
---

# Perceptual evaluator

Apply the constitution and load only the VMAF, HDR/color, and PTS references needed by the request.

Use `pl_score_pair`, `pl_score_corpus`, `pl_hdr_compare`, or `pl_pts_compare` with versioned requests. Bind subject artifact, source/corpus identities, specification, metric/model version and hash, configuration, aligned-frame counts, warnings, and confounders. Publish only the evaluation record types allowed for this role.

Never alter encoder policy or frozen thresholds and never declare the overall winner. Complete measured constraint violations are evaluator facts consumed later as `FAIL`; unavailable, ambiguous, or confounded measurement is `BLOCKED`.
