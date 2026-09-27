#!/usr/bin/env python3
"""b177 F9: policy labels are never promoted to perceptual truth."""
from __future__ import annotations

from label_basis import (HUMAN, POLICY, basis, disclaimer, may_recommend_threshold_change,
                         measurement_status, structural_status)


def test_policy_labels_can_never_recommend_a_threshold_change():
    assert basis([None, None]) == POLICY
    assert basis([1, None]) == POLICY
    assert basis([1, float("nan")]) == POLICY
    assert basis([]) == POLICY
    assert not may_recommend_threshold_change(POLICY)
    assert "not independent human perceptual ground truth" in disclaimer(POLICY)


def test_only_a_fully_human_labelled_set_is_human():
    assert basis([1, 0, 1]) == HUMAN
    assert may_recommend_threshold_change(HUMAN)


def test_policy_placeholders_and_invalid_human_labels_cannot_unlock_candidate():
    for labels in (["unreviewed", "unreviewed"], [2, 0], [True, False], [1, "0"], [1, float("inf")]):
        assert basis(labels) == POLICY
    assert basis([1, 0], [POLICY, POLICY]) == POLICY
    assert basis([1, 0], [HUMAN, HUMAN]) == HUMAN
    assert basis([1, 0], [HUMAN, POLICY]) == POLICY


def test_study_selection_uses_human_labels_when_present():
    import pandas as pd
    import run_study_v2 as study

    rows = pd.DataFrame({
        "training_label_v2": [0, 1], "human_visibility_label": [1, 0],
        "evidence_mean_floor": [97.0, 94.0], "evidence_p5_floor": [94.0, 88.0],
        "evidence_min_floor": [90.0, 78.0],
    })
    policy = study.evaluate(rows, 95.5, 91.0, 84.0, "training_label_v2")
    human = study.evaluate(rows, 95.5, 91.0, 84.0, "human_visibility_label")
    assert policy["FA"] == 1 and human["FA"] == 0
    assert human["TA"] == 1
    assert study.select(rows, "human_visibility_label")["train_ta"] == 1
    assert study.select(rows, "training_label_v2")["train_ta"] == 0


def test_study_cli_uses_human_labels_in_report_not_opposite_policy_labels():
    import json
    import os
    import subprocess
    import sys
    import tempfile

    import pandas as pd

    with tempfile.TemporaryDirectory() as td:
        rows = []
        for i in range(6):
            visible = i >= 3
            rows.append({
                "nameHash": f"source-{i}", "timestampMs": i,
                "evidence_mean_floor": 94.5 if visible else 98.0,
                "evidence_p5_floor": 88.0 if visible else 95.0,
                "evidence_min_floor": 78.0 if visible else 91.0,
                "policy_label": 1 if visible else 0,
                "training_label_v2": 1 if visible else 0,
                "human_visibility_label": 0 if visible else 1,
                "label_source": HUMAN,
            })
        data = os.path.join(td, "rows.csv")
        output = os.path.join(td, "study")
        pd.DataFrame(rows).to_csv(data, index=False)
        subprocess.run([sys.executable, os.path.join(os.path.dirname(__file__), "run_study_v2.py"),
                        "--data", data, "--output", output, "--bootstrap", "2"], check=True,
                       capture_output=True, text=True)
        report = json.load(open(os.path.join(output, "study_report_v2.json")))
        assert report["label_basis"] == HUMAN
        assert (report["label_1"], report["label_0"]) == (3, 3)
        assert report["full_dataset_selection_in_sample_only"]["train_ta"] == 3
        assert report["reference_holdout_confusions"]["current_production"] == {
            "TA": 3, "TR": 3, "FA": 0, "FR": 0,
        }
        assert report["verdict"]["recommendation"] == "no_change"


def test_measurement_status_keeps_absence_apart_from_failure():
    assert measurement_status({"certificationDecision": "measured_below_bar"}) == "adequate"
    assert measurement_status({"certificationDecision": "insufficient_frames"}) == "insufficient"
    assert measurement_status({"certificationDecision": "partial_unavailable"}) == "partial"
    assert measurement_status({"certificationStatus": "ran_misalignment_rejected"}) == "misaligned"
    assert measurement_status({"certificationStatus": "ran_unavailable"}) == "unavailable"
    assert measurement_status({"certificationStatus": "skipped_hdr_not_pixel_validated"}) == "not_run"


def test_structural_status_prefers_the_structural_field():
    assert structural_status({"structuralVerified": True, "verified": False}) == "passed"
    assert structural_status({"verified": False}) == "failed"
    assert structural_status({}) == "not_run"


if __name__ == "__main__":
    failures = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print(f"PASS {name}")
            except Exception as exc:  # noqa: BLE001
                failures += 1
                print(f"FAIL {name}: {exc!r}")
    print(f"\n{'FAILED' if failures else 'OK'} ({failures} failure(s))")
    raise SystemExit(1 if failures else 0)
