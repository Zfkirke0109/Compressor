"""Deterministically freeze and publish an experiment specification."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from .authority import capture_authority_snapshot
from .canonical import seal_document
from .errors import InvalidInput
from .experiment import ExperimentStore
from .records import RecordStore, create_record
from .sanitize import assert_sanitized
from .specification import ExperimentSpecStore, build_experiment_spec
from .validation import require_run_id, require_utc_timestamp


def _validate_request(request: dict[str, Any]) -> None:
    expected = {"schema_version", "run_id", "created_at", "definition"}
    if not isinstance(request, dict) or set(request) != expected:
        raise InvalidInput("spec-freeze request fields are incomplete or unknown")
    if request["schema_version"] != "1" or not isinstance(request["definition"], dict):
        raise InvalidInput("spec-freeze request version or definition is invalid")
    require_run_id(request["run_id"])
    require_utc_timestamp(request["created_at"], "created_at")


def freeze_specification(
    repository: Path,
    experiment: Path,
    request: dict[str, Any],
    *,
    execute: bool,
) -> dict[str, Any]:
    """Build before mutation, then atomically publish spec and calibration."""

    _validate_request(request)
    authority_snapshot = capture_authority_snapshot(
        repository,
        require_all=True,
        experiment_root=experiment,
    )
    spec = build_experiment_spec(
        repository,
        run_id=request["run_id"],
        created_at=request["created_at"],
        definition=request["definition"],
        require_all_authorities=True,
        authority_snapshot=authority_snapshot,
    )
    spec_sha256 = spec["content_sha256"]
    corpus = create_record(
        "corpus",
        producer_role="deterministic-core",
        run_id=request["run_id"],
        created_at=request["created_at"],
        experiment_spec_sha256=spec_sha256,
        links={"experiment_spec": spec_sha256},
        payload=spec["corpus"],
    )
    calibration = create_record(
        "calibration",
        producer_role="deterministic-core",
        run_id=request["run_id"],
        created_at=request["created_at"],
        experiment_spec_sha256=spec_sha256,
        links={"experiment_spec": spec_sha256, "corpus": corpus["content_sha256"]},
        payload={
            "calibration_id": "calibration-" + spec_sha256[:16],
            "resolved_constraints": spec["threshold_provenance"]["resolved_constraints"],
            "authority_sha256": spec["repository_authority"]["authority_sha256"],
            "status": "BLOCKED",
            "confounders": ["corpus_evaluation_pending"],
        },
    )
    output_identities: list[dict[str, str]] = []
    if execute:
        ExperimentStore(experiment).prepare(execute=True)
        ExperimentSpecStore(experiment).put(spec, authority_snapshot=authority_snapshot)
        RecordStore(experiment).put(corpus)
        RecordStore(experiment).put(calibration)
        output_identities = [
            {"kind": "experiment_spec", "sha256": spec_sha256},
            {"kind": "corpus", "sha256": corpus["content_sha256"]},
            {"kind": "calibration", "sha256": calibration["content_sha256"]},
        ]
    body = {
        "schema_version": "1",
        "status": "COMPLETED" if execute else "DRY_RUN",
        "dry_run": not execute,
        "run_id": request["run_id"],
        "experiment_spec_sha256": spec_sha256,
        "corpus_sha256": corpus["content_sha256"],
        "calibration_sha256": calibration["content_sha256"],
        "repository_authority_sha256": spec["repository_authority"]["authority_sha256"],
        "planned_identities": [
            {"kind": "experiment_spec", "sha256": spec_sha256},
            {"kind": "corpus", "sha256": corpus["content_sha256"]},
            {"kind": "calibration", "sha256": calibration["content_sha256"]},
        ],
        "output_identities": output_identities,
    }
    assert_sanitized(body)
    return seal_document("spec-freeze-result", body)
