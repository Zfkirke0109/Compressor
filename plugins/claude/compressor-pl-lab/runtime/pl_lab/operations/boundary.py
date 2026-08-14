"""Explicit fast/expensive boundary-suite planning."""

from __future__ import annotations

import hashlib
from pathlib import Path
from typing import Any

from ..authority import AuthoritySnapshot
from ..errors import EvidenceBlocked, InternalFailure
from ..jsonio import canonical_json_file_bytes
from ..records import RecordStore, create_record
from ..storage import atomic_publish_new
from .quality import _pts_result


def plan(operation_id: str, payload: dict[str, Any]) -> dict[str, Any]:
    result = {
        "mode": payload["mode"],
        "fast_slices": ["verifier_truth_tables", "policy_boundaries", "identity_alignment_fixtures", "schema_contracts"],
        "device_or_corpus_automatic": False,
    }
    if payload["mode"] == "expensive":
        result["budgets"] = {key: payload[key] for key in ("max_candidates", "max_workers", "max_duration_seconds", "max_storage_bytes") if key in payload}
    return result


def _fast_results(spec: dict[str, Any]) -> dict[str, Any]:
    constraint_cases: list[dict[str, Any]] = []
    for constraint in spec["threshold_provenance"]["resolved_constraints"]:
        value = constraint["value"]
        if constraint["operator"] != ">=" or not isinstance(value, (int, float)) or isinstance(value, bool):
            raise InternalFailure("unsupported frozen boundary semantics")
        below = value - (1 if isinstance(value, int) else 0.000001)
        constraint_cases.extend((
            {"metric": constraint["metric"], "case": "at_boundary", "actual": value, "expected": True, "actual_result": value >= value},
            {"metric": constraint["metric"], "case": "below_boundary", "actual": below, "expected": False, "actual_result": below >= value},
        ))
    pts = spec["pts"]
    cfr = lambda count, interval, offset=0: [offset + index * interval for index in range(count)]
    internal_reference = cfr(30, 33_333)
    leading_gap_reference = cfr(30, 33_367, 33_367)
    pts_cases = [
        ("aligned_cfr", cfr(36, 33_367), cfr(36, 33_367), "ALIGNED"),
        ("leading_dist_drop", cfr(35, 33_367, 33_367), cfr(35, 33_367), "ALIGNED"),
        ("leading_ref_drop", cfr(35, 33_367), cfr(35, 33_367, 33_367), "ALIGNED"),
        ("matching_vfr", [0, 16_000, 33_000, 383_000, 400_000, 750_000, 1_100_000], [0, 16_000, 33_000, 383_000, 400_000, 750_000, 1_100_000], "ALIGNED"),
        ("jitter_within_floor", cfr(30, 33_367), [value + 900 for value in cfr(30, 33_367)], "ALIGNED"),
        ("retimed_double_cadence", cfr(30, 33_333), cfr(60, 16_667, 8_000), "MISALIGNED"),
        ("internal_missing_frames", internal_reference, [value for index, value in enumerate(internal_reference) if index not in range(10, 13)], "MISALIGNED"),
        ("leading_repair_then_internal_gap", leading_gap_reference, [value for index, value in enumerate(cfr(30, 33_367)) if index != 15], "MISALIGNED"),
        ("internal_gap_after_short_interval", [0, 33_367, 66_734], [0, 33_367, 41_700], "MISALIGNED"),
        # Half-frame grid offset at 25 fps: scored as aligned by the superseded
        # half-interval tolerance, which is what made VMAF report 1.860/0.000/0.000
        # for a healthy encode. Must now produce no pixel evidence at all.
        ("half_frame_offset_25fps", cfr(30, 40_000), cfr(30, 40_000, 20_000), "MISALIGNED"),
        # Worst-case ms-granular probe-clip start (-999 us, constant): legitimately
        # aligned and must keep pairing at every frame rate.
        ("ms_granular_clip_start", cfr(30, 33_367), [value - 999 for value in cfr(30, 33_367)], "ALIGNED"),
    ]
    pts_results = []
    for name, reference, candidate, expected in pts_cases:
        outcome = _pts_result(reference, candidate, tolerance_floor_us=pts["tolerance_floor_us"], max_drops=pts["max_alignment_drops"])
        pts_results.append({"case": name, "expected": expected, "actual": outcome["status"], "matched": outcome["status"] == expected})
    tolerances = spec["tolerances"]
    verifier_cases = [
        {"case": "duration_at_absolute_boundary", "expected": True, "actual_result": 500 <= max(tolerances["duration_absolute_ms"], 10000 * tolerances["duration_fraction"])},
        {"case": "duration_above_absolute_boundary", "expected": False, "actual_result": 501 <= max(tolerances["duration_absolute_ms"], 10000 * tolerances["duration_fraction"])},
        {"case": "frame_at_absolute_boundary", "expected": True, "actual_result": 2 <= max(tolerances["frame_count_absolute"], 100 * tolerances["frame_count_fraction"])},
        {"case": "frame_above_absolute_boundary", "expected": False, "actual_result": 3 <= max(tolerances["frame_count_absolute"], 100 * tolerances["frame_count_fraction"])},
    ]
    all_matched = all(case["expected"] == case["actual_result"] for case in constraint_cases + verifier_cases) and all(case["matched"] for case in pts_results)
    if not all_matched:
        raise InternalFailure("deterministic fast boundary truth table failed")
    return {
        "schema_version": "1",
        "suite": "perceptual-lossless-fast-boundaries",
        "repository_authority_sha256": spec["repository_authority"]["authority_sha256"],
        "constraint_cases": constraint_cases,
        "pts_cases": pts_results,
        "verifier_cases": verifier_cases,
        "outcome": "COMPLETE",
    }


def expected_boundary_artifact(experiment_spec: dict[str, Any]) -> tuple[dict[str, Any], bytes]:
    """Return the one canonical fast-boundary artifact accepted by calibration."""

    data = canonical_json_file_bytes(_fast_results(experiment_spec))
    file_sha256 = hashlib.sha256(data).hexdigest()
    payload = {
        "artifact_id": "boundary-fast-" + file_sha256[:16],
        "logical_path": f"artifacts/boundary-fast-{file_sha256[:16]}.json",
        "sha256": file_sha256,
        "size_bytes": len(data),
        "status": "COMPLETE",
        "tool_versions": {"boundary_contract": "1"},
    }
    return payload, data


def validate_boundary_artifact_record(record: dict[str, Any], experiment_spec: dict[str, Any]) -> None:
    expected_payload, _ = expected_boundary_artifact(experiment_spec)
    if (
        record.get("record_type") != "artifact"
        or record.get("run_id") != experiment_spec["run_id"]
        or record.get("experiment_spec_sha256") != experiment_spec["content_sha256"]
        or record.get("links") != {"experiment_spec": experiment_spec["content_sha256"]}
        or record.get("payload") != expected_payload
    ):
        raise EvidenceBlocked("calibration boundary evidence is not the canonical fast boundary suite artifact")


def execute(
    operation_id: str,
    payload: dict[str, Any],
    *,
    store: RecordStore,
    experiment_spec: dict[str, Any],
    repository: Path,
    authority_snapshot: AuthoritySnapshot,
) -> dict[str, Any]:
    if payload["mode"] != "fast":
        raise EvidenceBlocked("expensive boundary execution requires the registered corpus/device adapter")
    artifact_payload, data = expected_boundary_artifact(experiment_spec)
    filename = Path(artifact_payload["logical_path"]).name
    target = store.experiment.owned_path("artifacts", filename)
    atomic_publish_new(target, data, root=store.experiment.root)
    record = create_record(
        "artifact",
        producer_role="deterministic-core",
        run_id=experiment_spec["run_id"],
        created_at=experiment_spec["created_at"],
        experiment_spec_sha256=experiment_spec["content_sha256"],
        links={"experiment_spec": experiment_spec["content_sha256"]},
        payload=artifact_payload,
    )
    store.put(record)
    results = _fast_results(experiment_spec)
    return {"records": [record], "result": {"artifact_sha256": record["content_sha256"], "truth_table_sha256": artifact_payload["sha256"], "case_count": len(results["constraint_cases"]) + len(results["pts_cases"]) + len(results["verifier_cases"])}}
