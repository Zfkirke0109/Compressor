#!/usr/bin/env python3
"""
Prepare the v2 calibration dataset from all_jobs_normalized.csv.

Fixes over v1 (each traces to the 2026-07-19 adversarial review of the v1 study):
  1. Build-era tagging: rows are tagged pre/post the PTS frame-pairing fix
     (commit 4d50c03, 2026-07-16 19:08). Only capture batches 20260716_191148 and
     20260716_233032 ran post-fix builds. Pre-fix VMAF scores paired frames in decode
     order with a constant one-frame skew and produced proven false accepts AND
     false skips, so post-fix evidence supersedes pre-fix evidence per source.
  2. Known-bad evidence excluded: the interrupted batch 20260716_194104
     ("DO NOT USE AS EVIDENCE"), the proven-false positives of nameHash 4260fa33af75
     (133776.mp4: device cert p5 93.4 vs offline PTS-synced truth p5 26-36) and the
     suspected-false positives of b63ffa895389 (pre-fix p5 95.1 vs post-fix 31.0).
  3. Negative-label hygiene: SKIPPED_WOULD_DEGRADE only becomes training_label=0 when
     the rejection was MEASURED (probe windows measured, or cert failed with scores).
     Misalignment rejections and cert-unavailable-sub-default rejections are NOT
     quality negatives and are excluded with an explicit reason.
  4. Deduplication: exactly one row per nameHash. Rows with pixel evidence are
     preferred, then post-fix era over pre-fix, then the latest timestampMs.
     v1 trained on ~3.8x duplicated rows (412 rows / 108 sources).

Input:  prepared_data/all_jobs_normalized.csv  (from the v1 training bundle)
Output: prepared_data/model_rows_v2.csv, prepared_data/prep_report_v2.json
"""
from pathlib import Path
import argparse
import json

import numpy as np
import pandas as pd

POST_FIX_BATCHES = {"batch_20260716_191148", "batch_20260716_233032"}
EXCLUDED_BATCHES = {"batch_20260716_194104"}  # interrupted; learning store polluted

# nameHash -> (labels_to_drop, reason). Only the proven/suspected-false POSITIVE rows
# are dropped; measured negatives for the same source remain valid evidence.
FALSE_POSITIVE_SOURCES = {
    "4260fa33af75": "proven false win: device cert p5 93.4 (pre-fix pairing) vs offline PTS-synced VMAF p5 26-36 (133776.mp4)",
    "b63ffa895389": "suspected false win: pre-fix cert p5 95.1 vs post-fix honest re-measure p5 31.0",
}

MISALIGNMENT_MARKER = "could not be time-aligned"
UNAVAILABLE_SUBDEFAULT_MARKER = "unavailable for a sub-default-ratio"
CERT_MEASURED_FAIL_MARKER = "sampled VMAF below thresholds"
PROBE_MEASURED_MARKER = "measured visible quality loss"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--data",
        default=str(Path(__file__).resolve().parents[1] / "prepared_data" / "all_jobs_normalized.csv"),
    )
    parser.add_argument(
        "--out",
        default=str(Path(__file__).resolve().parents[1] / "prepared_data"),
    )
    args = parser.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    df = pd.read_csv(args.data)
    report = {"input_rows": int(len(df))}

    # --- Era tagging and known-bad batch exclusion -------------------------------
    df["build_era"] = np.where(
        df["_capture_batch_dir"].isin(POST_FIX_BATCHES), "post_fix", "pre_fix"
    )
    excluded_batch = df["_capture_batch_dir"].isin(EXCLUDED_BATCHES)
    report["excluded_interrupted_batch_rows"] = int(excluded_batch.sum())
    df = df[~excluded_batch].copy()

    fallback = df["fallbackReason"].fillna("")
    planned = df["plannedDecisionReason"].fillna("")

    # --- Relabel with hygiene ----------------------------------------------------
    # label 1: verified real compression (unchanged from v1) that carries pixel
    #          certification evidence we can fit thresholds on.
    # label 0: SKIPPED_WOULD_DEGRADE with MEASURED evidence only.
    # everything else: excluded, with a reason.
    label = pd.Series(np.nan, index=df.index, dtype="float64")
    reason = pd.Series("", index=df.index, dtype="object")

    is_win = (
        (df["terminal"] == "TRANSCODED_SMALLER")
        & df["verified"].fillna(False).astype(bool)
        & df["countsAsRealCompression"].fillna(False).astype(bool)
    )
    has_cert = df["cert_mean_floor"].notna()
    has_probe = df["probe_mean_floor"].notna()
    has_evidence = df["evidence_mean_floor"].notna()

    label[is_win & has_cert] = 1.0
    reason[is_win & has_cert] = "verified win with pixel certification evidence"
    reason[is_win & ~has_cert] = "verified win WITHOUT pixel evidence (structural accept) - cannot fit thresholds on it; excluded"

    is_skip = df["terminal"] == "SKIPPED_WOULD_DEGRADE"
    skip_misaligned = is_skip & fallback.str.contains(MISALIGNMENT_MARKER, regex=False)
    skip_unavailable = is_skip & fallback.str.contains(UNAVAILABLE_SUBDEFAULT_MARKER, regex=False)
    skip_cert_measured = is_skip & fallback.str.contains(CERT_MEASURED_FAIL_MARKER, regex=False) & has_cert
    skip_probe_measured = (
        is_skip
        & ~skip_misaligned
        & ~skip_unavailable
        & ~skip_cert_measured
        & (planned.str.contains(PROBE_MEASURED_MARKER, regex=False) | (has_probe & df["probedRatios"].notna()))
        & has_probe
    )

    label[skip_cert_measured] = 0.0
    reason[skip_cert_measured] = "measured cert failure (sampled VMAF below thresholds)"
    label[skip_probe_measured] = 0.0
    reason[skip_probe_measured] = "measured probe rejection"
    reason[skip_misaligned] = "misalignment rejection - encoder frame loss/retiming, NOT a quality negative; excluded"
    reason[skip_unavailable] = "cert unavailable for sub-default encode - probe floors PASS on a label-0 row; poisonous to fitting; excluded"
    leftover_skip = is_skip & label.isna() & (reason == "")
    reason[leftover_skip] = "skip without measured window evidence; excluded"

    ambiguous = label.isna() & (reason == "")
    reason[ambiguous] = "ambiguous/non-quality terminal; excluded"

    df["training_label_v2"] = label
    df["label_reason_v2"] = reason

    report["label_pass_counts"] = {
        "label_1_candidates": int((label == 1).sum()),
        "label_0_cert_measured": int(skip_cert_measured.sum()),
        "label_0_probe_measured": int(skip_probe_measured.sum()),
        "excluded_misalignment_skips": int(skip_misaligned.sum()),
        "excluded_unavailable_subdefault_skips": int(skip_unavailable.sum()),
        "excluded_structural_wins_no_pixel_evidence": int((is_win & ~has_cert).sum()),
    }

    labeled = df[df["training_label_v2"].notna() & has_evidence].copy()
    report["labeled_rows_with_evidence"] = int(len(labeled))

    # --- Drop proven/suspected false positives -----------------------------------
    dropped_fp = []
    for h, why in FALSE_POSITIVE_SOURCES.items():
        mask = (labeled["nameHash"] == h) & (labeled["training_label_v2"] == 1.0)
        if mask.any():
            dropped_fp.append({"nameHash": h, "rows": int(mask.sum()), "reason": why})
            labeled = labeled[~mask]
    report["dropped_false_positive_rows"] = dropped_fp

    # --- Deduplicate to one row per nameHash -------------------------------------
    # Preference order: post_fix era first, then latest timestampMs.
    labeled["_era_rank"] = (labeled["build_era"] == "post_fix").astype(int)
    labeled = labeled.sort_values(
        ["nameHash", "_era_rank", "timestampMs"], ascending=[True, False, False]
    )
    before = len(labeled)
    label_conflicts = (
        labeled.groupby("nameHash")["training_label_v2"].nunique().pipe(lambda s: s[s > 1])
    )
    deduped = labeled.drop_duplicates(subset=["nameHash"], keep="first").copy()
    report["dedup"] = {
        "rows_before": int(before),
        "rows_after": int(len(deduped)),
        "sources_with_label_conflicts_resolved_by_recency": {
            str(k): int(v) for k, v in label_conflicts.items()
        },
    }

    deduped["training_label_v2"] = deduped["training_label_v2"].astype(int)
    report["final_label_counts"] = {
        "label_1": int((deduped["training_label_v2"] == 1).sum()),
        "label_0": int((deduped["training_label_v2"] == 0).sum()),
        "post_fix_rows": int((deduped["build_era"] == "post_fix").sum()),
        "pre_fix_rows": int((deduped["build_era"] == "pre_fix").sum()),
    }
    report["caveats"] = [
        "Logged window floors are rounded to ONE decimal; production compares unrounded scores. "
        "Any fitted boundary within 0.1 of a data point is inside rounding noise (the v1 candidate's "
        "entire improvement lived inside this noise).",
        "Pre-fix rows that survive dedup were never re-measured post-fix; their scores carry the "
        "decode-order pairing skew and are conservative at best.",
        "No labeled evidence exists below bpp 0.03 (production never probed there), so bits-per-pixel "
        "cutoffs are NOT identifiable from this dataset and are deliberately absent from the v2 search.",
    ]

    cols = [
        "nameHash", "jobId", "_capture_batch_dir", "build_era", "timestampMs",
        "terminal", "training_label_v2", "label_reason_v2",
        "evidence_mean_floor", "evidence_p5_floor", "evidence_min_floor", "evidence_source",
        "cert_mean_floor", "cert_p5_floor", "cert_min_floor",
        "probe_mean_floor", "probe_p5_floor", "probe_min_floor", "probedRatios",
        "source_bits_per_pixel_per_frame", "savedPct", "fallbackReason", "plannedDecisionReason",
    ]
    deduped[cols].to_csv(out / "model_rows_v2.csv", index=False)
    (out / "prep_report_v2.json").write_text(json.dumps(report, indent=2), encoding="utf-8")

    print(json.dumps(report, indent=2))
    print(f"\nWrote: {out / 'model_rows_v2.csv'} ({len(deduped)} rows)")


if __name__ == "__main__":
    main()
