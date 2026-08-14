"""Hash-linked baseline comparison planning."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from ..authority import AuthoritySnapshot
from ..errors import EvidenceBlocked, ImmutableConflict, InvalidInput
from ..records import RecordStore, create_record


def plan(operation_id: str, payload: dict[str, Any]) -> dict[str, Any]:
    return {"evidence_record_count": len(payload["record_sha256s"]), "judge_invoked": False}


def _one(records: list[dict[str, Any]], record_type: str) -> dict[str, Any]:
    matches = [record for record in records if record["record_type"] == record_type]
    if len(matches) != 1:
        raise EvidenceBlocked("comparison input cardinality is invalid", details={"record_type": record_type, "actual": len(matches)})
    return matches[0]


def _metric_averages(records: list[dict[str, Any]]) -> dict[str, float]:
    values: dict[str, list[float]] = {}
    for record in records:
        for metric, value in record["payload"]["metric_results"].items():
            values.setdefault(metric, []).append(float(value))
    return {metric: sum(items) / len(items) for metric, items in sorted(values.items())}


def execute(
    operation_id: str,
    payload: dict[str, Any],
    *,
    store: RecordStore,
    experiment_spec: dict[str, Any],
    repository: Path,
    authority_snapshot: AuthoritySnapshot,
) -> dict[str, Any]:
    try:
        evidence = store.load_evidence(payload["record_sha256s"])
    except (InvalidInput, ImmutableConflict) as exc:
        raise EvidenceBlocked("comparison evidence is unavailable or invalid") from exc
    spec_sha256 = experiment_spec["content_sha256"]
    run_id = experiment_spec["run_id"]
    if any(record["experiment_spec_sha256"] != spec_sha256 or record["run_id"] != run_id for record in evidence):
        raise EvidenceBlocked("comparison evidence crosses a run or specification")
    baseline = _one(evidence, "baseline")
    candidate = _one(evidence, "candidate")
    device = _one(evidence, "device")
    evaluations = [record for record in evidence if record["record_type"] == "evaluation"]
    baseline_evaluations = sorted(
        (record for record in evaluations if record["payload"]["subject_sha256"] == baseline["content_sha256"]),
        key=lambda record: record["content_sha256"],
    )
    candidate_evaluations = sorted(
        (record for record in evaluations if record["payload"]["subject_sha256"] == candidate["content_sha256"]),
        key=lambda record: record["content_sha256"],
    )
    if not baseline_evaluations or not candidate_evaluations or len(baseline_evaluations) + len(candidate_evaluations) != len(evaluations):
        raise EvidenceBlocked("comparison requires evaluations covering only baseline and candidate")
    baseline_metrics = _metric_averages(baseline_evaluations)
    candidate_metrics = _metric_averages(candidate_evaluations)
    deltas = {
        metric: candidate_metrics[metric] - baseline_metrics[metric]
        for metric in sorted(set(baseline_metrics) & set(candidate_metrics))
    }
    checks: list[dict[str, Any]] = []
    for constraint in experiment_spec["threshold_provenance"]["resolved_constraints"]:
        metric = constraint["metric"]
        actual = candidate_metrics.get(metric)
        checks.append({
            "metric": metric,
            "operator": constraint["operator"],
            "expected": constraint["value"],
            "actual": actual,
        })
    confounders = sorted({
        str(item)
        for record in [baseline, candidate, device, *evaluations]
        for item in record["payload"].get("confounders", [])
    })
    blocked_inputs = [
        record["record_type"]
        for record in [baseline, candidate, device, *evaluations]
        if record["payload"].get("status") == "BLOCKED"
    ]
    if blocked_inputs:
        confounders = sorted(set(confounders) | {f"{record_type}_blocked" for record_type in blocked_inputs})
    links = {
        "experiment_spec": spec_sha256,
        "baseline": baseline["content_sha256"],
        "candidate": candidate["content_sha256"],
        "baseline_evaluations": [record["content_sha256"] for record in baseline_evaluations],
        "candidate_evaluations": [record["content_sha256"] for record in candidate_evaluations],
        "device": device["content_sha256"],
    }
    record = create_record(
        "comparison",
        producer_role="deterministic-core",
        run_id=run_id,
        created_at=experiment_spec["created_at"],
        experiment_spec_sha256=spec_sha256,
        links=links,
        payload={
            "baseline_sha256": baseline["content_sha256"],
            "candidate_sha256": candidate["content_sha256"],
            "baseline_evaluation_sha256s": links["baseline_evaluations"],
            "candidate_evaluation_sha256s": links["candidate_evaluations"],
            "device_sha256": device["content_sha256"],
            "deltas": deltas,
            "constraint_checks": checks,
            "status": "BLOCKED" if confounders else "COMPLETE",
            "confounders": confounders,
        },
    )
    store.put(record)
    return {"records": [record], "result": {"comparison_sha256": record["content_sha256"], "metric_deltas": deltas}}
