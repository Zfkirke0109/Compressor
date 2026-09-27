#!/usr/bin/env python3
"""What a calibration label is evidence OF (b177 F9). Standard library only.

The existing study's labels come from the app's own outcomes: a verified win is 1, a measured
rejection is 0, and `build_corpus.py` labels "compressible" with the production VMAF floors. Those
are POLICY labels: they say what the current gate decided. Resampling them (group holdout,
bootstrap) improves statistical hygiene but cannot turn them into independent perceptual truth, so
a study on policy labels can check operational consistency and nothing more. In particular it
cannot show the current thresholds are the human-visibility boundary, nor justify moving them.

A threshold change may only be evaluated against HUMAN labels: blinded judgments under a declared
viewing protocol (observer, display, distance, brightness, ambient), with source/master-separated
holdout. Until such labels exist this module answers "no" to every request to recommend a change.

Fields kept apart on every row:
  policy_label           1/0 from the frozen production gate (or empty)
  measurement_status     adequate | insufficient | unavailable | misaligned | partial | not_run
  structural_status      passed | failed | not_run
  human_visibility_label 1 (not visible) / 0 (visible) / empty; only from a human protocol
"""
from __future__ import annotations

from typing import Any, Iterable, Mapping

POLICY = "policy"
HUMAN = "human"

_DECISION_STATUS = {
    "passed": "adequate",
    "measured_below_bar": "adequate",
    "insufficient_frames": "insufficient",
    "unavailable": "unavailable",
    "partial_unavailable": "partial",
    "misaligned": "misaligned",
}


def measurement_status(record: Mapping[str, Any]) -> str:
    """From a job record: whether its pixel evidence was an adequate measurement at all."""
    decision = record.get("certificationDecision")
    if decision in _DECISION_STATUS:
        return _DECISION_STATUS[decision]
    status = str(record.get("certificationStatus") or "")
    if "insufficient" in status:
        return "insufficient"
    if "misalign" in status:
        return "misaligned"
    if "partial" in status:
        return "partial"
    if "unavailable" in status:
        return "unavailable"
    if status.startswith("ran_scored") or status.startswith("ran_floor_recovery_scored"):
        return "adequate"
    return "not_run"


def structural_status(record: Mapping[str, Any]) -> str:
    v = record.get("structuralVerified", record.get("verified"))
    if v is True:
        return "passed"
    if v is False:
        return "failed"
    return "not_run"


def basis(human_values: Iterable[Any]) -> str:
    """HUMAN only when every row carries a human label; one policy row makes the set policy."""
    values = list(human_values)
    if values and all(v not in (None, "") and v == v for v in values):  # v == v rejects NaN
        return HUMAN
    return POLICY


def may_recommend_threshold_change(label_basis: str) -> bool:
    return label_basis == HUMAN


def disclaimer(label_basis: str) -> str:
    if label_basis == HUMAN:
        return ("Labels are human visibility judgments; results hold only for the declared viewing "
                "protocol and the sources sampled, with source-separated holdout.")
    return ("Labels are POLICY-derived (the app's own accept/reject outcomes under the current gate), "
            "not independent human perceptual ground truth. This checks operational consistency; it "
            "cannot establish the human-visibility boundary or justify changing a threshold.")
