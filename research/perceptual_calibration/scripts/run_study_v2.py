#!/usr/bin/env python3
"""
v2 threshold calibration: deterministic exhaustive grid with honest held-out validation.

This is CONSTRAINED OFFLINE CALIBRATION of three production constants, not a trained
model. Design decisions (each traces to the 2026-07-19 adversarial review of v1):

  - EXHAUSTIVE GRID instead of Optuna/TPE. The v1 review proved the objective landscape
    is cheap to evaluate exactly (the tied optimum was a 16,302-point box and the "best"
    bpp value was sampler noise). A grid is deterministic, needs no seed, and reports the
    ENTIRE tied set instead of one arbitrary point.
  - BPP IS NOT SEARCHED. No labeled row below bpp 0.0359 exists, every positive sits at
    bpp >= 0.1015, and production never probed below 0.03 - the parameter is structurally
    unidentifiable from this data. PROBE_MIN_SOURCE_BITS_PER_PIXEL stays at 0.03.
  - FALSE ACCEPTANCE IS A HARD CONSTRAINT (FA=0 on the selection set), not a 100x cost.
  - REAL HOLDOUT: nested GroupKFold - thresholds are selected on train folds only and
    scored on the held-out fold. v1 averaged the same fixed thresholds over all folds,
    which is arithmetically identical to in-sample evaluation.
  - MINIMAL MOVEMENT: among tied-feasible thresholds, the point closest to current
    production wins; movement is never rewarded in either direction.
  - BOOTSTRAP STABILITY: group-level resampling reports how often current production is
    itself inside the tied-optimal feasible set.
  - A Kotlin candidate is emitted ONLY if every outer fold's selection box agrees, the
    pooled held-out confusion is clean, and the consensus excludes current production.
    Otherwise the study says NO_CHANGE_RECOMMENDED - that is a valid, expected outcome.

Input:  prepared_data/model_rows_v2.csv (from prepare_dataset_v2.py)
Output: results_v2/study_report_v2.json, study_report_v2.md, and either
        kotlin_candidate_v2.txt or NO_CHANGE_RECOMMENDED.txt
"""
from pathlib import Path
import argparse
import json

import numpy as np
import pandas as pd
from sklearn.model_selection import GroupKFold

CURRENT = {"mean": 95.5, "p5": 91.0, "min": 84.0}
PRODUCTION_BPP_FLOOR = 0.03  # documented, NOT searched

MEAN_GRID = np.round(np.arange(94.5, 97.5 + 1e-9, 0.1), 1)   # 31
P5_GRID = np.round(np.arange(88.0, 94.0 + 1e-9, 0.1), 1)     # 61
MIN_GRID = np.round(np.arange(78.0, 90.0 + 1e-9, 0.1), 1)    # 121


def confusion_cube(rows: pd.DataFrame):
    """FA/FR/TA counts for every grid combo, via einsum. Returns (FA, FR, TA) cubes
    with shape (len(MEAN_GRID), len(P5_GRID), len(MIN_GRID))."""
    y = rows["training_label_v2"].to_numpy()
    mean_f = rows["evidence_mean_floor"].to_numpy(dtype=float)
    p5_f = rows["evidence_p5_floor"].to_numpy(dtype=float)
    min_f = rows["evidence_min_floor"].to_numpy(dtype=float)

    A = (mean_f[None, :] >= MEAN_GRID[:, None]).astype(np.float64)  # (31, N)
    B = (p5_f[None, :] >= P5_GRID[:, None]).astype(np.float64)      # (61, N)
    C = (min_f[None, :] >= MIN_GRID[:, None]).astype(np.float64)    # (121, N)

    w_pos = (y == 1).astype(np.float64)
    w_neg = (y == 0).astype(np.float64)

    TA = np.einsum("in,jn,kn->ijk", A, B, C * w_pos)
    FA = np.einsum("in,jn,kn->ijk", A, B, C * w_neg)
    n_pos = w_pos.sum()
    FR = n_pos - TA  # rejected positives
    return FA, FR, TA


def select(rows: pd.DataFrame):
    """Hard-constraint selection on a training set.
    Returns dict with the tied-optimal feasible box and its distance-minimal point."""
    FA, FR, TA = confusion_cube(rows)
    feasible = FA == 0
    if not feasible.any():
        return {"feasible": False}

    fr_min = FR[feasible].min()
    tied = feasible & (FR == fr_min)
    ta_max = TA[tied].max()
    tied = tied & (TA == ta_max)

    idx = np.argwhere(tied)
    means, p5s, mins = MEAN_GRID[idx[:, 0]], P5_GRID[idx[:, 1]], MIN_GRID[idx[:, 2]]

    # Distance-minimal representative: normalized L1 distance from current production.
    dist = (
        np.abs(means - CURRENT["mean"]) / (MEAN_GRID[-1] - MEAN_GRID[0])
        + np.abs(p5s - CURRENT["p5"]) / (P5_GRID[-1] - P5_GRID[0])
        + np.abs(mins - CURRENT["min"]) / (MIN_GRID[-1] - MIN_GRID[0])
    )
    best = int(np.argmin(dist))
    current_in_set = bool(
        ((means == CURRENT["mean"]) & (p5s == CURRENT["p5"]) & (mins == CURRENT["min"])).any()
    )
    return {
        "feasible": True,
        "tied_count": int(len(idx)),
        "box": {
            "mean": [float(means.min()), float(means.max())],
            "p5": [float(p5s.min()), float(p5s.max())],
            "min": [float(mins.min()), float(mins.max())],
        },
        "train_fr": float(fr_min),
        "train_ta": float(ta_max),
        "current_in_tied_set": current_in_set,
        "selected": {"mean": float(means[best]), "p5": float(p5s[best]), "min": float(mins[best])},
        "_tied_points": np.stack([means, p5s, mins], axis=1),
    }


def evaluate(rows: pd.DataFrame, mean, p5, mn):
    y = rows["training_label_v2"].to_numpy()
    pred = (
        (rows["evidence_mean_floor"].to_numpy(dtype=float) >= mean)
        & (rows["evidence_p5_floor"].to_numpy(dtype=float) >= p5)
        & (rows["evidence_min_floor"].to_numpy(dtype=float) >= mn)
    )
    return {
        "TA": int(((pred == 1) & (y == 1)).sum()),
        "TR": int(((pred == 0) & (y == 0)).sum()),
        "FA": int(((pred == 1) & (y == 0)).sum()),
        "FR": int(((pred == 0) & (y == 1)).sum()),
    }


def add_confusions(*cs):
    return {k: sum(c[k] for c in cs) for k in ("TA", "TR", "FA", "FR")}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--data",
        default=str(Path(__file__).resolve().parents[1] / "prepared_data" / "model_rows_v2.csv"),
    )
    parser.add_argument("--output", default=str(Path(__file__).resolve().parents[1] / "results_v2"))
    parser.add_argument("--bootstrap", type=int, default=200)
    parser.add_argument("--seed", type=int, default=20260719, help="bootstrap resampling only")
    args = parser.parse_args()

    out = Path(args.output)
    out.mkdir(parents=True, exist_ok=True)

    df = pd.read_csv(args.data)
    df = df.dropna(subset=["evidence_mean_floor", "evidence_p5_floor", "evidence_min_floor"])
    assert df["nameHash"].is_unique, "prepare_dataset_v2 must emit one row per nameHash"
    n_pos = int((df["training_label_v2"] == 1).sum())
    n_neg = int((df["training_label_v2"] == 0).sum())

    report = {
        "study_kind": "constrained offline calibration (exhaustive grid, hard FA constraint); NOT a trained model",
        "rows": int(len(df)), "label_1": n_pos, "label_0": n_neg,
        "current_production": {**CURRENT, "bpp_floor_not_searched": PRODUCTION_BPP_FLOOR},
        "grid": {"mean": [94.5, 97.5, 0.1], "p5": [88.0, 94.0, 0.1], "min": [78.0, 90.0, 0.1],
                  "combos": int(len(MEAN_GRID) * len(P5_GRID) * len(MIN_GRID))},
    }

    # --- Full-dataset selection (reported for transparency, NOT used for the verdict)
    full_sel = select(df)
    full_pub = {k: v for k, v in full_sel.items() if not k.startswith("_")}
    report["full_dataset_selection_in_sample_only"] = full_pub

    # --- Nested grouped holdout ---------------------------------------------------
    k = min(5, df["nameHash"].nunique())
    splitter = GroupKFold(n_splits=k)
    folds, holdout_total = [], []
    boxes = []
    for train_idx, test_idx in splitter.split(df, df["training_label_v2"], df["nameHash"]):
        train, test = df.iloc[train_idx], df.iloc[test_idx]
        sel = select(train)
        entry = {"train_rows": int(len(train)), "test_rows": int(len(test))}
        if not sel["feasible"]:
            entry["feasible"] = False
            folds.append(entry)
            continue
        s = sel["selected"]
        held = evaluate(test, s["mean"], s["p5"], s["min"])
        entry.update({k2: v for k2, v in sel.items() if not k2.startswith("_")})
        entry["holdout_confusion"] = held
        folds.append(entry)
        holdout_total.append(held)
        boxes.append(sel["box"])
    report["nested_grouped_holdout"] = {
        "n_splits": k,
        "folds": folds,
        "pooled_holdout_confusion": add_confusions(*holdout_total) if holdout_total else None,
    }

    # Consensus box = intersection of per-fold tied boxes
    consensus = None
    if len(boxes) == len(folds) and boxes:
        lo = {a: max(b[a][0] for b in boxes) for a in ("mean", "p5", "min")}
        hi = {a: min(b[a][1] for b in boxes) for a in ("mean", "p5", "min")}
        if all(lo[a] <= hi[a] for a in lo):
            consensus = {a: [lo[a], hi[a]] for a in lo}
    report["consensus_box"] = consensus
    current_in_consensus = bool(
        consensus
        and all(consensus[a][0] - 1e-9 <= CURRENT[m] <= consensus[a][1] + 1e-9
                for a, m in (("mean", "mean"), ("p5", "p5"), ("min", "min")))
    )
    report["current_production_in_consensus_box"] = current_in_consensus

    # --- Reference evaluations on the same held-out folds --------------------------
    refs = {
        "current_production": (95.5, 91.0, 84.0),
        "v1_optuna_candidate_windows": (95.6, 91.2, 84.4),
    }
    ref_holdout = {}
    for name, (m, p, mi) in refs.items():
        per = []
        for train_idx, test_idx in splitter.split(df, df["training_label_v2"], df["nameHash"]):
            per.append(evaluate(df.iloc[test_idx], m, p, mi))
        ref_holdout[name] = add_confusions(*per)
    report["reference_holdout_confusions"] = ref_holdout

    # --- Chronological validation ---------------------------------------------------
    chron = df.sort_values("timestampMs")
    split_at = int(len(chron) * 0.7)
    early, late = chron.iloc[:split_at], chron.iloc[split_at:]
    sel_early = select(early)
    chron_entry = {"early_rows": int(len(early)), "late_rows": int(len(late))}
    if sel_early["feasible"]:
        s = sel_early["selected"]
        chron_entry["selected_on_early"] = s
        chron_entry["late_confusion_selected"] = evaluate(late, s["mean"], s["p5"], s["min"])
        chron_entry["late_confusion_current"] = evaluate(late, *[CURRENT[a] for a in ("mean", "p5", "min")])
        chron_entry["current_in_early_tied_set"] = sel_early["current_in_tied_set"]
    report["chronological_validation"] = chron_entry

    # --- Bootstrap stability --------------------------------------------------------
    rng = np.random.default_rng(args.seed)
    sel_means, sel_p5s, sel_mins = [], [], []
    current_in_set_count, infeasible_count = 0, 0
    for _ in range(args.bootstrap):
        # One row per nameHash (asserted above), so group resampling = row resampling.
        pick = rng.integers(0, len(df), size=len(df))
        sample = df.iloc[pick]
        s = select(sample)
        if not s["feasible"]:
            infeasible_count += 1
            continue
        sel_means.append(s["selected"]["mean"])
        sel_p5s.append(s["selected"]["p5"])
        sel_mins.append(s["selected"]["min"])
        if s["current_in_tied_set"]:
            current_in_set_count += 1
    def ci(v):
        return [float(np.percentile(v, 2.5)), float(np.percentile(v, 50)), float(np.percentile(v, 97.5))] if v else None
    report["bootstrap"] = {
        "resamples": args.bootstrap,
        "infeasible_resamples": infeasible_count,
        "selected_mean_ci_2p5_50_97p5": ci(sel_means),
        "selected_p5_ci_2p5_50_97p5": ci(sel_p5s),
        "selected_min_ci_2p5_50_97p5": ci(sel_mins),
        "fraction_current_in_tied_set": (
            current_in_set_count / max(1, args.bootstrap - infeasible_count)
        ),
    }

    # --- Verdict ---------------------------------------------------------------------
    pooled = report["nested_grouped_holdout"]["pooled_holdout_confusion"]
    emit_candidate = bool(
        consensus
        and pooled and pooled["FA"] == 0
        and not current_in_consensus
        and report["bootstrap"]["fraction_current_in_tied_set"] < 0.05
    )
    if emit_candidate:
        # Distance-minimal point of the consensus box.
        cand = {
            "mean": min(max(CURRENT["mean"], consensus["mean"][0]), consensus["mean"][1]),
            "p5": min(max(CURRENT["p5"], consensus["p5"][0]), consensus["p5"][1]),
            "min": min(max(CURRENT["min"], consensus["min"][0]), consensus["min"][1]),
        }
        report["verdict"] = {"recommendation": "candidate", "candidate": cand}
        (out / "kotlin_candidate_v2.txt").write_text(
            "// RESEARCH CANDIDATE ONLY - requires frozen-harness and S23 Ultra device validation.\n"
            "// Emitted because: consensus across all held-out folds excluded current production,\n"
            "// pooled holdout FA=0, and bootstrap rarely retained current production.\n"
            f"const val WINDOW_MEAN_MIN = {cand['mean']}\n"
            f"const val WINDOW_P5_MIN = {cand['p5']}\n"
            f"const val WINDOW_MIN_MIN = {cand['min']}\n"
            f"// PROBE_MIN_SOURCE_BITS_PER_PIXEL unchanged at {PRODUCTION_BPP_FLOOR} (not identifiable from data)\n",
            encoding="utf-8",
        )
    else:
        reasons = []
        if current_in_consensus:
            reasons.append("current production lies inside the consensus tied-optimal box")
        if consensus is None:
            reasons.append("no consensus across held-out folds")
        if pooled and pooled["FA"] > 0:
            reasons.append("held-out false accepts observed")
        if report["bootstrap"]["fraction_current_in_tied_set"] >= 0.05:
            reasons.append(
                f"bootstrap keeps current production tied-optimal in "
                f"{report['bootstrap']['fraction_current_in_tied_set']:.0%} of resamples"
            )
        report["verdict"] = {"recommendation": "no_change", "reasons": reasons}
        (out / "NO_CHANGE_RECOMMENDED.txt").write_text(
            "No production change is supported by this dataset.\n\nReasons:\n- "
            + "\n- ".join(reasons)
            + "\n\nKeep: WINDOW_MEAN_MIN=95.5, WINDOW_P5_MIN=91.0, WINDOW_MIN_MIN=84.0, "
            "PROBE_MIN_SOURCE_BITS_PER_PIXEL=0.03\n",
            encoding="utf-8",
        )

    (out / "study_report_v2.json").write_text(json.dumps(report, indent=2), encoding="utf-8")

    md = ["# v2 calibration study report", "",
          f"- Rows: {report['rows']} (one per source; {n_pos} wins / {n_neg} measured rejections)",
          f"- Verdict: **{report['verdict']['recommendation']}**", ""]
    if consensus:
        md.append(f"- Consensus tied-optimal box: mean {consensus['mean']}, p5 {consensus['p5']}, min {consensus['min']}")
    md.append(f"- Current production in consensus box: {current_in_consensus}")
    if pooled:
        md.append(f"- Pooled held-out confusion (selected-on-train): {pooled}")
    md.append(f"- Held-out (current production): {ref_holdout['current_production']}")
    md.append(f"- Held-out (v1 Optuna candidate windows): {ref_holdout['v1_optuna_candidate_windows']}")
    md.append(f"- Bootstrap: current tied-optimal in {report['bootstrap']['fraction_current_in_tied_set']:.0%} of resamples")
    md.append("")
    md.append("This is constrained offline calibration on retrospective device captures; it cannot")
    md.append("certify visual quality. Any candidate still requires the frozen VMAF harness and")
    md.append("fresh S23 Ultra captures before production use.")
    (out / "study_report_v2.md").write_text("\n".join(md), encoding="utf-8")

    print(json.dumps(report["verdict"], indent=2))
    print(f"\nFull report: {out / 'study_report_v2.json'}")


if __name__ == "__main__":
    main()
