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
