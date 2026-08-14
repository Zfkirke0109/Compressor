"""Independent deterministic judge for complete hash-linked evidence chains."""

from __future__ import annotations

from collections import Counter
from pathlib import Path
from typing import Any

from .authority import AuthoritySnapshot
from .canonical import verify_sealed_document
from .contracts import RECORD_AUTHORITIES
from .errors import EvidenceBlocked, InvalidInput
from .records import create_record, validate_calibration_coverage, validate_record
from .specification import validate_experiment_spec


_CARDINALITY = {
    "corpus": 1,
    "calibration": 1,
    "artifact": 3,
    "baseline": 1,
    "candidate": 1,
    "device": 1,
    "comparison": 1,
}

_PIPELINE_IDENTITY_FIELDS = (
    "metric_id",
    "metric_version",
    "metric_model_sha256",
    "scorer_authority_sha256",
    "python_executable_sha256",
    "ffmpeg_executable_sha256",
    "ffprobe_executable_sha256",
    "ffmpeg_version_sha256",
    "ffprobe_version_sha256",
)


def _blocked(message: str, **details: Any) -> EvidenceBlocked:
    return EvidenceBlocked(message, details=details)


def _iter_hash_links(links: dict[str, Any]) -> list[str]:
    result: list[str] = []
    for value in links.values():
        if isinstance(value, list):
            result.extend(value)
        elif isinstance(value, str):
            result.append(value)
    return result


def assert_acyclic_graph(graph: dict[str, list[str]]) -> None:
    active: set[str] = set()
    done: set[str] = set()

    def visit(identity: str) -> None:
        if identity in active:
            raise _blocked("evidence link graph contains a cycle")
        if identity in done:
            return
        active.add(identity)
        for target in graph.get(identity, []):
            if target in graph:
                visit(target)
        active.remove(identity)
        done.add(identity)

    for identity in graph:
        visit(identity)


def _assert_acyclic(records: dict[str, dict[str, Any]]) -> None:
    assert_acyclic_graph({identity: _iter_hash_links(record.get("links", {})) for identity, record in records.items()})


def _compare(operator: str, actual: float, expected: float) -> bool:
    if operator == ">=":
        return actual >= expected
    if operator == "<=":
        return actual <= expected
    if operator == "==":
        return actual == expected
    raise InvalidInput("unknown frozen constraint operator")


def _metric_averages(records: list[dict[str, Any]]) -> dict[str, float]:
    values: dict[str, list[float]] = {}
    for record in sorted(records, key=lambda item: item["content_sha256"]):
        for metric, value in record["payload"]["metric_results"].items():
            values.setdefault(metric, []).append(float(value))
    return {metric: sum(items) / len(items) for metric, items in sorted(values.items())}


def judge_evidence(
    experiment_spec: dict[str, Any],
    evidence: list[dict[str, Any]],
    *,
    created_at: str,
    repository: Path | None = None,
    authority_snapshot: AuthoritySnapshot | None = None,
) -> dict[str, Any]:
    try:
        validate_experiment_spec(
            experiment_spec,
            require_complete_authority=True,
            repository=repository,
            authority_snapshot=authority_snapshot,
        )
    except InvalidInput as exc:
        raise _blocked("experiment specification is missing or invalid", error=exc.message) from exc
    spec_hash = experiment_spec["content_sha256"]
    run_id = experiment_spec.get("run_id")

    by_hash: dict[str, dict[str, Any]] = {spec_hash: experiment_spec}
    counts: Counter[str] = Counter()
    try:
        for record in evidence:
            validate_record(record)
            identity = record["content_sha256"]
            if identity in by_hash:
                raise _blocked("duplicate evidence identity", sha256=identity)
            if record["experiment_spec_sha256"] != spec_hash or record["run_id"] != run_id:
                raise _blocked("cross-specification or cross-run evidence")
            expected_role = RECORD_AUTHORITIES.get(record["record_type"])
            if record["producer_role"] != expected_role:
                raise _blocked("self-approved or unauthorized evidence")
            by_hash[identity] = record
            counts[record["record_type"]] += 1
    except EvidenceBlocked:
        raise
    except InvalidInput as exc:
        raise _blocked("schema-invalid evidence", error=exc.message) from exc

    for record_type, expected in _CARDINALITY.items():
        if counts[record_type] != expected:
            raise _blocked("invalid evidence cardinality", record_type=record_type, expected=expected, actual=counts[record_type])
    expected_calibration_evaluations = experiment_spec["corpus"]["item_count"]
    if counts["evaluation"] < expected_calibration_evaluations + 2:
        raise _blocked("calibration, baseline, and candidate evaluation records are required")
    unexpected = set(counts) - set(_CARDINALITY) - {"evaluation"}
    if unexpected:
        raise _blocked("judge received unsupported evidence types", record_types=sorted(unexpected))

    _assert_acyclic(by_hash)
    for record in evidence:
        for target in _iter_hash_links(record["links"]):
            if target not in by_hash:
                raise _blocked("evidence contains a dangling immutable link", sha256=target)
        if record.get("supersedes_sha256") in by_hash:
            raise _blocked("evidence contains both a superseded record and its successor")
    typed = {record_type: [record for record in evidence if record["record_type"] == record_type] for record_type in counts}
    corpus = typed["corpus"][0]
    calibration = typed["calibration"][0]
    baseline = typed["baseline"][0]
    candidate = typed["candidate"][0]
    device = typed["device"][0]
    comparison = typed["comparison"][0]
    artifacts = typed["artifact"]
    evaluations = typed["evaluation"]
    calibration_evaluations = sorted(
        (record for record in evaluations if record["payload"].get("evaluation_kind") == "corpus_calibration"),
        key=lambda record: record["payload"]["provenance"]["pair_id"],
    )
    subject_evaluations = [
        record for record in evaluations if record["payload"].get("evaluation_kind") == "subject_quality"
    ]
    if len(calibration_evaluations) != expected_calibration_evaluations:
        raise _blocked(
            "calibration evaluations do not fully cover the frozen corpus",
            expected=expected_calibration_evaluations,
            actual=len(calibration_evaluations),
        )
    if len(calibration_evaluations) + len(subject_evaluations) != len(evaluations):
        raise _blocked("judge received an unsupported evaluation kind")
    if corpus["links"] != {"experiment_spec": spec_hash} or corpus["payload"] != experiment_spec["corpus"]:
        raise _blocked("corpus evidence does not match the frozen specification")
    calibration_evaluation_hashes = calibration["links"].get("evaluations")
    boundary_artifact_hash = calibration["links"].get("boundary_artifact")
    if (
        calibration["links"].get("experiment_spec") != spec_hash
        or calibration["links"].get("corpus") != corpus["content_sha256"]
        or set(calibration["links"]) != {"experiment_spec", "corpus", "evaluations", "boundary_artifact"}
        or not isinstance(calibration_evaluation_hashes, list)
        or len(calibration_evaluation_hashes) != expected_calibration_evaluations
        or len(set(calibration_evaluation_hashes)) != len(calibration_evaluation_hashes)
        or set(calibration_evaluation_hashes)
        != {record["content_sha256"] for record in calibration_evaluations}
        or calibration["payload"].get("status") != "COMPLETE"
        or calibration["payload"].get("confounders")
        or calibration["payload"].get("resolved_constraints")
        != experiment_spec["threshold_provenance"]["resolved_constraints"]
        or calibration["payload"].get("authority_sha256")
        != experiment_spec["repository_authority"]["authority_sha256"]
        or not calibration.get("supersedes_sha256")
    ):
        raise _blocked("complete calibration does not exactly bind the frozen experiment and corpus")
    try:
        calibration_items = validate_calibration_coverage(corpus, calibration_evaluations)
    except InvalidInput as exc:
        raise _blocked("calibration evaluations are not exact frozen corpus members", error=exc.message) from exc
    for evaluation in calibration_evaluations:
        provenance = evaluation["payload"]["provenance"]
        if (
            evaluation["links"] != {"experiment_spec": spec_hash, "corpus": corpus["content_sha256"]}
            or evaluation["payload"]["subject_sha256"] != corpus["content_sha256"]
            or evaluation["payload"].get("status") != "COMPLETE"
            or evaluation["payload"].get("confounders")
            or provenance.get("corpus_manifest_sha256") != corpus["payload"]["manifest_sha256"]
            or provenance.get("corpus_pair_identities_sha256") != corpus["payload"]["pair_identities_sha256"]
        ):
            raise _blocked("calibration evaluation does not match its exact frozen corpus lineage")

    baseline_evaluations = sorted(
        (record for record in subject_evaluations if record["payload"]["subject_sha256"] == baseline["content_sha256"]),
        key=lambda record: record["content_sha256"],
    )
    candidate_evaluations = sorted(
        (record for record in subject_evaluations if record["payload"]["subject_sha256"] == candidate["content_sha256"]),
        key=lambda record: record["content_sha256"],
    )
    if not baseline_evaluations or not candidate_evaluations or len(baseline_evaluations) + len(candidate_evaluations) != len(subject_evaluations):
        raise _blocked("evaluations must cover exactly the baseline and candidate")
    comparison_links = {
        "experiment_spec": spec_hash,
        "baseline": baseline["content_sha256"],
        "candidate": candidate["content_sha256"],
        "baseline_evaluations": [record["content_sha256"] for record in baseline_evaluations],
        "candidate_evaluations": [record["content_sha256"] for record in candidate_evaluations],
        "device": device["content_sha256"],
    }
    if comparison["links"] != comparison_links:
        raise _blocked("comparison evidence links do not exactly match the chain")
    payload = comparison["payload"]
    if payload["baseline_sha256"] != comparison_links["baseline"] or payload["candidate_sha256"] != comparison_links["candidate"] or payload["device_sha256"] != comparison_links["device"] or payload["baseline_evaluation_sha256s"] != comparison_links["baseline_evaluations"] or payload["candidate_evaluation_sha256s"] != comparison_links["candidate_evaluations"]:
        raise _blocked("comparison payload identities do not match its links")
    baseline_metrics = _metric_averages(baseline_evaluations)
    candidate_metrics = _metric_averages(candidate_evaluations)
    expected_deltas = {
        metric: candidate_metrics[metric] - baseline_metrics[metric]
        for metric in sorted(set(baseline_metrics) & set(candidate_metrics))
    }
    expected_comparison_checks = [
        {
            "metric": constraint["metric"],
            "operator": constraint["operator"],
            "expected": constraint["value"],
            "actual": candidate_metrics.get(constraint["metric"]),
        }
        for constraint in experiment_spec["threshold_provenance"]["resolved_constraints"]
    ]
    if payload["deltas"] != expected_deltas or payload["constraint_checks"] != expected_comparison_checks:
        raise _blocked("comparison deltas or checks do not match recomputed evidence")
    artifact_by_hash = {record["content_sha256"]: record for record in artifacts}
    expected_artifact_hashes = {
        baseline["payload"]["artifact_sha256"],
        candidate["payload"]["artifact_sha256"],
        boundary_artifact_hash,
    }
    if None in expected_artifact_hashes or set(artifact_by_hash) != expected_artifact_hashes:
        raise _blocked("artifact evidence must contain exactly baseline, candidate, and calibration boundary artifacts")
    boundary_artifact = artifact_by_hash[boundary_artifact_hash]
    if boundary_artifact["payload"].get("status") != "COMPLETE" or boundary_artifact["payload"].get("confounders"):
        raise _blocked("calibration boundary artifact is incomplete or confounded")
    from .operations.boundary import validate_boundary_artifact_record
    validate_boundary_artifact_record(boundary_artifact, experiment_spec)
    baseline_members = [
        item
        for item in calibration_items
        if item["source_sha256"] == artifact_by_hash[baseline["payload"]["artifact_sha256"]]["payload"]["sha256"]
        and item["source_size_bytes"] == artifact_by_hash[baseline["payload"]["artifact_sha256"]]["payload"]["size_bytes"]
    ]
    if len(baseline_members) != 1:
        raise _blocked("baseline artifact is not exactly one authenticated calibration corpus source")
    for subject in (baseline, candidate):
        artifact_hash = subject["payload"]["artifact_sha256"]
        if subject["links"].get("artifact") != artifact_hash or artifact_hash not in artifact_by_hash:
            raise _blocked("subject artifact lineage is incomplete")
        if subject["links"].get("device") != device["content_sha256"]:
            raise _blocked("subject device lineage is incomplete")
    for evaluation in subject_evaluations:
        subject_hash = evaluation["payload"]["subject_sha256"]
        subject = baseline if subject_hash == baseline["content_sha256"] else candidate
        if evaluation["links"].get("subject") != subject_hash or evaluation["links"].get("artifact") != subject["payload"]["artifact_sha256"] or evaluation["links"].get("device") != device["content_sha256"]:
            raise _blocked("evaluation artifact/device lineage is incomplete")

    expected_links = {
        **comparison_links,
        "corpus": corpus["content_sha256"],
        "calibration": calibration["content_sha256"],
        "calibration_evaluations": calibration_evaluation_hashes,
        "boundary_artifact": boundary_artifact_hash,
        "comparison": comparison["content_sha256"],
    }

    blocked_reasons: list[str] = []
    if repository is None and authority_snapshot is None:
        blocked_reasons.append("repository_authority_not_live_validated")
    completed_quality_violations: list[str] = []
    for record in [calibration, *artifacts, baseline, candidate, device, comparison, *evaluations]:
        payload = record["payload"]
        if payload.get("status") == "BLOCKED":
            blocked_reasons.append(f"{record['record_type']}_blocked")
        if payload.get("confounders"):
            blocked_reasons.append(f"{record['record_type']}_confounded")
        if record["record_type"] == "evaluation" and payload.get("evaluation_kind") == "subject_quality":
            alignment_status = payload.get("alignment", {}).get("status")
            hdr_status = payload.get("hdr", {}).get("status")
            if alignment_status == "BLOCKED":
                blocked_reasons.append("pts_alignment_blocked")
            elif alignment_status == "MISALIGNED":
                completed_quality_violations.append("pts_alignment")
            elif alignment_status != "ALIGNED":
                blocked_reasons.append("pts_alignment_not_completed")
            if hdr_status == "BLOCKED":
                blocked_reasons.append("hdr_comparison_blocked")
            elif hdr_status == "MISMATCH":
                completed_quality_violations.append("hdr_metadata")
            elif hdr_status != "MATCH":
                blocked_reasons.append("hdr_comparison_not_completed")

    expected_model_hashes = {metric["model_sha256"] for metric in experiment_spec["metrics"]}
    authority_files = {
        item["logical_path"]: item["sha256"]
        for item in experiment_spec["repository_authority"]["files"]
    }
    expected_scorer_sha256 = authority_files.get("scripts/diagnostics/measure_quality.py")
    if expected_scorer_sha256 is None:
        blocked_reasons.append("scorer_authority_missing")
    baseline_artifact_record = artifact_by_hash[baseline["payload"]["artifact_sha256"]]
    candidate_artifact_record = artifact_by_hash[candidate["payload"]["artifact_sha256"]]
    verifier_checks: list[dict[str, Any]] = []
    pipeline_identities: set[tuple[Any, ...]] = set()
    for evaluation in evaluations:
        provenance = evaluation["payload"].get("provenance", {})
        model_sha256 = provenance.get("metric_model_sha256")
        matching_metrics = [
            item
            for item in experiment_spec["metrics"]
            if item["model_sha256"] == model_sha256
            and item["metric_id"] == provenance.get("metric_id")
            and item["version"] == provenance.get("metric_version")
        ]
        common_provenance = {
            "metric_model_sha256",
            "metric_id",
            "metric_version",
            "scorer_authority_sha256",
            "scorer_report_sha256",
            "python_executable_sha256",
            "ffmpeg_executable_sha256",
            "ffprobe_executable_sha256",
            "ffmpeg_version_sha256",
            "ffprobe_version_sha256",
            "source_content_sha256",
            "output_content_sha256",
            "source_frame_count",
            "output_frame_count",
            "source_duration_ms",
            "output_duration_ms",
        }
        required_provenance = common_provenance | (
            {
                "corpus_manifest_sha256", "corpus_pair_identities_sha256", "pair_id",
                "source_path", "source_size_bytes", "output_path", "output_size_bytes",
            }
            if evaluation["payload"]["evaluation_kind"] == "corpus_calibration"
            else {"reference_artifact_sha256"}
        )
        if not isinstance(provenance, dict) or required_provenance - set(provenance):
            blocked_reasons.append("evaluation_provenance_incomplete")
            continue
        hash_fields = [
            "metric_model_sha256", "scorer_authority_sha256",
            "scorer_report_sha256", "python_executable_sha256", "ffmpeg_executable_sha256",
            "ffprobe_executable_sha256", "ffmpeg_version_sha256", "ffprobe_version_sha256",
            "source_content_sha256", "output_content_sha256",
        ]
        if evaluation["payload"]["evaluation_kind"] == "subject_quality":
            hash_fields.append("reference_artifact_sha256")
        else:
            hash_fields.extend(("corpus_manifest_sha256", "corpus_pair_identities_sha256"))
        invalid_hash = False
        for key in hash_fields:
            value = provenance.get(key)
            if not isinstance(value, str) or len(value) != 64 or any(character not in "0123456789abcdef" for character in value):
                blocked_reasons.append("evaluation_provenance_hash_invalid:" + key)
                invalid_hash = True
        if model_sha256 not in expected_model_hashes or len(matching_metrics) != 1:
            blocked_reasons.append("evaluation_model_provenance_invalid")
        elif "host_scorer_sha256" in matching_metrics[0].get("provenance", {}) and matching_metrics[0]["provenance"]["host_scorer_sha256"] != expected_scorer_sha256:
            blocked_reasons.append("evaluation_host_model_binding_invalid")
        if provenance.get("scorer_authority_sha256") != expected_scorer_sha256:
            blocked_reasons.append("evaluation_scorer_authority_invalid")
        if not invalid_hash and all(field in provenance for field in _PIPELINE_IDENTITY_FIELDS):
            pipeline_identities.add(tuple(provenance[field] for field in _PIPELINE_IDENTITY_FIELDS))
        if evaluation["payload"]["evaluation_kind"] == "corpus_calibration":
            continue
        subject_hash = evaluation["payload"]["subject_sha256"]
        subject_artifact = baseline_artifact_record if subject_hash == baseline["content_sha256"] else candidate_artifact_record
        expected_reference = baseline_artifact_record
        if provenance.get("reference_artifact_sha256") != expected_reference["content_sha256"]:
            blocked_reasons.append("evaluation_reference_artifact_invalid")
        if provenance.get("source_content_sha256") != expected_reference["payload"]["sha256"] or provenance.get("output_content_sha256") != subject_artifact["payload"]["sha256"]:
            blocked_reasons.append("evaluation_media_identity_invalid")
        numeric_fields = ("source_frame_count", "output_frame_count", "source_duration_ms", "output_duration_ms")
        if any(not isinstance(provenance.get(key), int) or isinstance(provenance.get(key), bool) or provenance[key] <= 0 for key in numeric_fields):
            blocked_reasons.append("evaluation_verifier_provenance_invalid")
            continue
        duration_delta = abs(provenance["output_duration_ms"] - provenance["source_duration_ms"])
        duration_limit = max(
            experiment_spec["tolerances"]["duration_absolute_ms"],
            provenance["source_duration_ms"] * experiment_spec["tolerances"]["duration_fraction"],
        )
        frame_delta = abs(provenance["output_frame_count"] - provenance["source_frame_count"])
        frame_limit = max(
            experiment_spec["tolerances"]["frame_count_absolute"],
            provenance["source_frame_count"] * experiment_spec["tolerances"]["frame_count_fraction"],
        )
        verifier_satisfied = duration_delta <= duration_limit and frame_delta <= frame_limit
        verifier_checks.append({
            "metric": "output_verifier",
            "operator": "within_frozen_tolerances",
            "expected": {"duration_ms": duration_limit, "frame_count": frame_limit},
            "actual": {"duration_ms": duration_delta, "frame_count": frame_delta},
            "subject_sha256": subject_hash,
            "outcome": "SATISFIED" if verifier_satisfied else ("BASELINE_INVALID" if subject_hash == baseline["content_sha256"] else "VIOLATED"),
        })
        if not verifier_satisfied:
            if subject_hash == baseline["content_sha256"]:
                blocked_reasons.append("baseline_output_verifier_invalid")
            else:
                completed_quality_violations.append("output_verifier")
    if len(pipeline_identities) != 1:
        blocked_reasons.append("calibration_baseline_candidate_pipeline_mismatch")
    build_identity = device["payload"].get("build_identity")
    required_build_fields = {"version_code", "version_name", "apk_sha256", "source_sha256"}
    if not isinstance(build_identity, dict) or set(build_identity) != required_build_fields:
        blocked_reasons.append("device_build_identity_incomplete")
    elif build_identity.get("source_sha256") != experiment_spec["repository_authority"]["authority_sha256"]:
        blocked_reasons.append("device_source_identity_invalid")
    if not device["payload"].get("requested_configuration") or not device["payload"].get("actual_configuration"):
        blocked_reasons.append("device_configuration_incomplete")
    elif device["payload"]["requested_configuration"] != device["payload"]["actual_configuration"] or candidate["payload"]["configuration"] != device["payload"]["actual_configuration"]:
        blocked_reasons.append("device_configuration_mismatch")
    thermals = device["payload"].get("thermals")
    if not isinstance(thermals, dict) or thermals.get("status") != "NOMINAL":
        blocked_reasons.append("device_thermal_evidence_incomplete")
    required_device = experiment_spec["device_requirements"]
    if required_device["required"]:
        device_mapping = {
            "device_alias": "device_alias",
            "android_user": "android_user",
            "package_id": "package_id",
            "build_identity": "build_identity",
            "signer_sha256": "signer_sha256",
            "firmware_sha256": "firmware_fingerprint_sha256",
        }
        for expected_key, actual_key in device_mapping.items():
            if device["payload"].get(actual_key) != required_device.get(expected_key):
                blocked_reasons.append("required_device_identity_mismatch:" + expected_key)

    checks: list[dict[str, Any]] = []
    violations: list[str] = list(completed_quality_violations)
    missing: list[str] = []
    constraints = experiment_spec.get("threshold_provenance", {}).get("resolved_constraints")
    if not isinstance(constraints, list) or not constraints:
        raise _blocked("frozen constraints are missing")
    for constraint in constraints:
        metric = constraint.get("metric")
        operator = constraint.get("operator")
        expected = constraint.get("value")
        baseline_values = [record["payload"]["metric_results"].get(metric) for record in baseline_evaluations]
        candidate_values = [record["payload"]["metric_results"].get(metric) for record in candidate_evaluations]
        actual_values = [*baseline_values, *candidate_values]
        if any(not isinstance(value, (int, float)) or isinstance(value, bool) for value in actual_values):
            missing.append(str(metric))
            continue
        baseline_satisfied = all(_compare(operator, float(actual), float(expected)) for actual in baseline_values)
        candidate_satisfied = all(_compare(operator, float(actual), float(expected)) for actual in candidate_values)
        checks.append({"metric": metric, "operator": operator, "expected": expected, "baseline_actual": baseline_values, "candidate_actual": candidate_values, "outcome": "SATISFIED" if baseline_satisfied and candidate_satisfied else ("BASELINE_INVALID" if not baseline_satisfied else "VIOLATED")})
        if not baseline_satisfied:
            blocked_reasons.append("baseline_constraint_invalid:" + str(metric))
        elif not candidate_satisfied:
            violations.append(str(metric))

    size_limit = baseline_artifact_record["payload"]["size_bytes"] * (1.0 + experiment_spec["tolerances"]["size_ratio"])
    size_actual = candidate_artifact_record["payload"]["size_bytes"]
    size_satisfied = size_actual <= size_limit
    checks.extend(verifier_checks)
    checks.append({
        "metric": "output_size",
        "operator": "<=",
        "expected": size_limit,
        "actual": size_actual,
        "outcome": "SATISFIED" if size_satisfied else "VIOLATED",
    })
    if not size_satisfied:
        violations.append("output_size")

    if missing:
        blocked_reasons.append("missing_metrics:" + ",".join(sorted(missing)))
    verdict = "BLOCKED" if blocked_reasons else ("FAIL" if violations else "PASS")
    reasons = blocked_reasons if blocked_reasons else (["constraints_violated:" + ",".join(sorted(violations))] if violations else ["all_frozen_constraints_satisfied"])
    evidence_hashes = [record["content_sha256"] for record in evidence]
    return create_record(
        "verdict",
        producer_role="regression-judge",
        run_id=run_id,
        created_at=created_at,
        experiment_spec_sha256=spec_hash,
        links=expected_links,
        payload={"verdict": verdict, "reasons": reasons, "evidence_sha256s": evidence_hashes, "constraint_checks": checks},
    )
