"""Immutable workflow planning and hash-safe resume."""

from __future__ import annotations

from copy import deepcopy
import importlib
from pathlib import Path
from typing import Any

from .authority import capture_authority_snapshot
from .canonical import domain_hash, seal_document, verify_sealed_document
from .constants import EXIT_BLOCKED, EXIT_COMPLETED, EXIT_GATE_FAILED
from .contracts import OPERATION_CONTRACTS, operation_contract
from .errors import EvidenceBlocked, ImmutableConflict, InvalidInput, LabError
from .experiment import ExperimentStore
from .freeze import freeze_specification
from .jsonio import canonical_json_file_bytes, load_json
from .records import RecordStore, create_record, validate_calibration_coverage
from .schema import SchemaRegistry
from .storage import atomic_publish_new
from .specification import ExperimentSpecStore, build_experiment_spec
from .validation import require_run_id, require_sha256, require_utc_timestamp
from .operations.payloads import validate_operation_payload


WORKFLOW_STEPS = {
    "pl-calibrate": ("pl_run_boundary_suite", "pl_score_corpus"),
    "pl-benchmark": ("pl_device_inventory", "pl_build_install", "pl_encode_candidate", "pl_score_pair", "pl_hdr_compare", "pl_pts_compare", "pl_compare_baseline", "pl_generate_report"),
    "pl-investigate": ("pl_compare_baseline", "pl_run_boundary_suite"),
}

NAMED_WORKFLOWS = {
    "calibrate": "pl-calibrate",
    "benchmark": "pl-benchmark",
    "investigate": "pl-investigate",
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


def _validate_request(request: dict[str, Any]) -> None:
    if not isinstance(request, dict) or set(request) != {"schema_version", "workflow_id", "run_id", "experiment_spec_sha256", "payload"}:
        raise InvalidInput("workflow request fields are incomplete or unknown")
    if request["schema_version"] != "1" or request["workflow_id"] not in WORKFLOW_STEPS or not isinstance(request["payload"], dict):
        raise InvalidInput("workflow request version, ID, or payload is invalid")
    require_run_id(request["run_id"])
    require_sha256(request["experiment_spec_sha256"], "experiment_spec_sha256")


def _request_hash(request: dict[str, Any]) -> str:
    _validate_request(request)
    return domain_hash("workflow-request", request)


def _validate_plan(plan: dict[str, Any]) -> None:
    expected_fields = {"schema_version", "workflow_id", "run_id", "request_sha256", "experiment_spec_sha256", "steps", "created_at", "content_sha256"}
    if not isinstance(plan, dict) or set(plan) != expected_fields:
        raise EvidenceBlocked("workflow plan fields are incomplete or unknown")
    if plan["schema_version"] != "1" or plan["workflow_id"] not in WORKFLOW_STEPS:
        raise EvidenceBlocked("workflow plan version or ID is invalid")
    require_run_id(plan["run_id"])
    require_sha256(plan["request_sha256"], "request_sha256")
    require_sha256(plan["experiment_spec_sha256"], "experiment_spec_sha256")
    require_utc_timestamp(plan["created_at"])
    if not isinstance(plan["steps"], list) or [item.get("operation_id") for item in plan["steps"]] != list(WORKFLOW_STEPS[plan["workflow_id"]]):
        raise EvidenceBlocked("workflow plan operation sequence is invalid")
    if not verify_sealed_document("workflow-plan", plan):
        raise EvidenceBlocked("workflow plan content hash mismatch")


def _validate_completion(completion: dict[str, Any], *, plan: dict[str, Any], step: dict[str, Any]) -> None:
    expected = {"schema_version", "workflow_id", "run_id", "plan_sha256", "step_index", "operation_id", "operation_request_sha256", "output_sha256s", "completed_at", "content_sha256"}
    if not isinstance(completion, dict) or set(completion) != expected:
        raise EvidenceBlocked("workflow step completion fields are incomplete or unknown")
    if completion["schema_version"] != "1" or completion["workflow_id"] != plan["workflow_id"] or completion["run_id"] != plan["run_id"]:
        raise EvidenceBlocked("workflow step completion identity is invalid")
    if completion["plan_sha256"] != plan["content_sha256"] or completion["step_index"] != step["index"] or completion["operation_id"] != step["operation_id"]:
        raise EvidenceBlocked("workflow step completion does not match its plan")
    require_sha256(completion["operation_request_sha256"], "operation_request_sha256")
    if not isinstance(completion["output_sha256s"], list) or not completion["output_sha256s"]:
        raise EvidenceBlocked("workflow step completion has no immutable outputs")
    for output_sha256 in completion["output_sha256s"]:
        require_sha256(output_sha256, "workflow output hash")
    require_utc_timestamp(completion["completed_at"], "completed_at")
    if not verify_sealed_document("workflow-step-completion", completion):
        raise EvidenceBlocked("workflow step completion content hash mismatch")


def _validate_completion_outputs(
    completion: dict[str, Any],
    *,
    record_store: RecordStore,
    experiment_spec_sha256: str,
) -> list[dict[str, Any]]:
    operation_id = completion["operation_id"]
    contract = OPERATION_CONTRACTS[operation_id]
    try:
        outputs = [record_store.find(output_sha256) for output_sha256 in completion["output_sha256s"]]
    except (InvalidInput, ImmutableConflict) as exc:
        raise EvidenceBlocked("workflow completion output is unavailable or invalid") from exc
    output_types = tuple(record["record_type"] for record in outputs)
    if len(contract.output_record_types) == 1:
        if not output_types or any(record_type != contract.output_record_types[0] for record_type in output_types):
            raise EvidenceBlocked("workflow completion output type is unauthorized")
    elif output_types != contract.output_record_types:
        raise EvidenceBlocked("workflow completion output order/types are unauthorized")
    for record in outputs:
        if record["run_id"] != completion["run_id"] or record["experiment_spec_sha256"] != experiment_spec_sha256 or record["producer_role"] != contract.producer_role:
            raise EvidenceBlocked("workflow completion output lineage or authority is invalid")
        if record["record_type"] not in ("verdict", "report") and (record["payload"].get("status") != "COMPLETE" or record["payload"].get("confounders")):
            raise EvidenceBlocked("workflow completion cannot accept blocked or confounded evidence")
        if record["record_type"] in ("verdict", "report") and record["payload"].get("verdict") == "BLOCKED":
            raise EvidenceBlocked("workflow completion cannot accept a BLOCKED verdict")
    return outputs


def plan_workflow(experiment: Path, request: dict[str, Any], *, execute: bool, created_at: str) -> dict[str, Any]:
    _validate_request(request)
    require_utc_timestamp(created_at)
    request_sha256 = _request_hash(request)
    steps = [
        {
            "index": index,
            "operation_id": operation_id,
            "producer_role": OPERATION_CONTRACTS[operation_id].producer_role,
            "status": "PENDING",
        }
        for index, operation_id in enumerate(WORKFLOW_STEPS[request["workflow_id"]], start=1)
    ]
    plan = seal_document(
        "workflow-plan",
        {
            "schema_version": "1",
            "workflow_id": request["workflow_id"],
            "run_id": request["run_id"],
            "request_sha256": request_sha256,
            "experiment_spec_sha256": request["experiment_spec_sha256"],
            "steps": steps,
            "created_at": created_at,
        },
    )
    if not execute:
        return plan
    store = ExperimentStore(experiment)
    store.prepare(execute=True)
    target = store.owned_path("runs", request["run_id"], "workflows", request["workflow_id"], "workflow-plan.json")
    if target.is_file():
        existing = load_json(target)
        _validate_plan(existing)
        if existing["request_sha256"] != request_sha256 or existing["experiment_spec_sha256"] != request["experiment_spec_sha256"]:
            raise EvidenceBlocked("existing workflow plan does not match request/specification hashes")
        return existing
    atomic_publish_new(target, canonical_json_file_bytes(plan), root=store.root)
    return plan


def resume_workflow(experiment: Path, run_id: str, *, request: dict[str, Any], experiment_spec_sha256: str) -> dict[str, Any]:
    require_run_id(run_id)
    require_sha256(experiment_spec_sha256, "experiment_spec_sha256")
    _validate_request(request)
    store = ExperimentStore(experiment)
    store.prepare(execute=False)
    target = store.owned_path("runs", run_id, "workflows", request["workflow_id"], "workflow-plan.json")
    if not target.is_file():
        raise EvidenceBlocked("workflow plan is unavailable for resume")
    plan = load_json(target)
    _validate_plan(plan)
    if plan["run_id"] != run_id or plan["request_sha256"] != _request_hash(request) or plan["experiment_spec_sha256"] != experiment_spec_sha256:
        raise EvidenceBlocked("resume request or experiment-specification hash does not match the original plan")
    remaining = []
    completed = []
    record_store = RecordStore(experiment)
    for step in plan["steps"]:
        completion = store.owned_path("runs", run_id, "workflows", request["workflow_id"], "steps", f"{step['index']:02d}-{step['operation_id']}.json")
        if completion.is_file():
            completion_record = load_json(completion)
            _validate_completion(completion_record, plan=plan, step=step)
            _validate_completion_outputs(
                completion_record,
                record_store=record_store,
                experiment_spec_sha256=experiment_spec_sha256,
            )
            completed.append({"step": step, "completion": completion_record})
        else:
            remaining.append(step)
    return {"schema_version": "1", "plan": plan, "completed_steps": completed, "remaining_steps": remaining}


def record_operation_completion(
    experiment: Path,
    *,
    run_id: str,
    experiment_spec_sha256: str,
    operation_id: str,
    operation_request_sha256: str,
    output_sha256s: list[str],
    completed_at: str,
) -> list[dict[str, Any]]:
    """Publish completion evidence for every matching immutable named plan."""

    require_run_id(run_id)
    require_sha256(experiment_spec_sha256, "experiment_spec_sha256")
    require_sha256(operation_request_sha256, "operation_request_sha256")
    require_utc_timestamp(completed_at, "completed_at")
    if operation_id not in OPERATION_CONTRACTS or not isinstance(output_sha256s, list) or not output_sha256s:
        raise InvalidInput("workflow completion operation or outputs are invalid")
    for output_sha256 in output_sha256s:
        require_sha256(output_sha256, "workflow output hash")
    store = ExperimentStore(experiment)
    record_store = RecordStore(experiment)
    provisional_completion = {
        "operation_id": operation_id,
        "run_id": run_id,
        "output_sha256s": output_sha256s,
    }
    _validate_completion_outputs(
        provisional_completion,
        record_store=record_store,
        experiment_spec_sha256=experiment_spec_sha256,
    )
    completions: list[dict[str, Any]] = []
    for workflow_id, operations in WORKFLOW_STEPS.items():
        if operation_id not in operations:
            continue
        plan_path = store.owned_path("runs", run_id, "workflows", workflow_id, "workflow-plan.json")
        if not plan_path.is_file():
            continue
        plan = load_json(plan_path)
        _validate_plan(plan)
        if plan["experiment_spec_sha256"] != experiment_spec_sha256:
            raise EvidenceBlocked("workflow plan specification does not match operation output")
        step = next(item for item in plan["steps"] if item["operation_id"] == operation_id)
        completion = seal_document(
            "workflow-step-completion",
            {
                "schema_version": "1",
                "workflow_id": workflow_id,
                "run_id": run_id,
                "plan_sha256": plan["content_sha256"],
                "step_index": step["index"],
                "operation_id": operation_id,
                "operation_request_sha256": operation_request_sha256,
                "output_sha256s": output_sha256s,
                "completed_at": completed_at,
            },
        )
        _validate_completion(completion, plan=plan, step=step)
        target = store.owned_path("runs", run_id, "workflows", workflow_id, "steps", f"{step['index']:02d}-{operation_id}.json")
        atomic_publish_new(target, canonical_json_file_bytes(completion), root=store.root)
        completions.append(completion)
    return completions


def _planned_output_sha256(plan_sha256: str, operation_id: str, record_type: str, index: int = 0) -> str:
    return domain_hash(
        "workflow-planned-output",
        {"plan_sha256": plan_sha256, "operation_id": operation_id, "record_type": record_type, "index": index},
    )


def _resolve_step_payload(
    operation_id: str,
    template: dict[str, Any],
    state: dict[str, Any],
) -> dict[str, Any]:
    payload = deepcopy(template)
    if operation_id == "pl_hdr_compare":
        if set(payload) != {"reference", "candidate"}:
            raise InvalidInput("workflow HDR template may contain only comparison evidence")
        payload["input_evaluation_sha256"] = state["candidate_evaluation"]
    elif operation_id == "pl_pts_compare":
        if set(payload) != {"reference_pts_us", "candidate_pts_us"}:
            raise InvalidInput("workflow PTS template may contain only timestamp evidence")
        payload["input_evaluation_sha256"] = state["candidate_evaluation"]
    elif operation_id == "pl_compare_baseline":
        if payload:
            raise InvalidInput("workflow comparison lineage is injected and its template must be empty")
        payload["record_sha256s"] = [
            state["baseline"],
            state["candidate"],
            state["device"],
            *state["baseline_evaluations"],
            *state["candidate_evaluations"],
        ]
    elif operation_id == "pl_generate_report":
        if payload:
            raise InvalidInput("workflow report lineage is injected and its template must be empty")
        payload["record_sha256s"] = [
            state["corpus"],
            state["calibration"],
            state["boundary_artifact"],
            state["baseline_artifact"],
            state["candidate_artifact"],
            state["baseline"],
            state["candidate"],
            state["device"],
            *state["calibration_evaluations"],
            *state["baseline_evaluations"],
            *state["candidate_evaluations"],
            state["comparison"],
        ]
    return payload


def _advance_workflow_state(
    state: dict[str, Any],
    operation_id: str,
    output_sha256s: list[str],
    *,
    record_store: RecordStore | None,
    dry_run: bool,
) -> None:
    if not output_sha256s:
        return
    if operation_id == "pl_device_inventory":
        state["inventory_device"] = output_sha256s[-1]
    elif operation_id == "pl_build_install":
        state["device"] = output_sha256s[-1]
    elif operation_id == "pl_encode_candidate":
        state["candidate"] = output_sha256s[-1]
        if dry_run:
            state["candidate_artifact"] = domain_hash("workflow-planned-candidate-artifact", output_sha256s[-1])
        else:
            if record_store is None:
                raise EvidenceBlocked("workflow record store is unavailable")
            candidate = record_store.find(output_sha256s[-1])
            if candidate["record_type"] != "candidate":
                raise EvidenceBlocked("candidate step output type is invalid")
            state["candidate_artifact"] = candidate["payload"]["artifact_sha256"]
            artifact = record_store.find(state["candidate_artifact"])
            if artifact["record_type"] != "artifact" or artifact["run_id"] != candidate["run_id"] or artifact["experiment_spec_sha256"] != candidate["experiment_spec_sha256"]:
                raise EvidenceBlocked("candidate artifact lineage is unavailable")
    elif operation_id in ("pl_score_pair", "pl_hdr_compare", "pl_pts_compare"):
        state["candidate_evaluation"] = output_sha256s[-1]
        state["candidate_evaluations"] = [output_sha256s[-1]]
    elif operation_id == "pl_compare_baseline":
        state["comparison"] = output_sha256s[-1]
    elif operation_id == "pl_run_boundary_suite":
        state["boundary_artifact"] = output_sha256s[-1]
    elif operation_id == "pl_score_corpus":
        state["corpus_evaluations"] = list(output_sha256s)


def _benchmark_initial_state(
    record_store: RecordStore,
    initial: dict[str, Any],
    *,
    run_id: str,
    experiment_spec_sha256: str,
    experiment_spec: dict[str, Any],
) -> dict[str, Any]:
    frozen_corpus = experiment_spec["corpus"]
    expected = {"corpus_sha256", "calibration_sha256", "baseline_sha256", "baseline_artifact_sha256", "baseline_evaluation_sha256"}
    if not isinstance(initial, dict) or set(initial) != expected:
        raise InvalidInput("benchmark initial_evidence fields are incomplete or unknown")
    for key, identity in initial.items():
        require_sha256(identity, f"initial_evidence.{key}")
    records = {key: record_store.find(identity) for key, identity in initial.items()}
    expected_types = {
        "corpus_sha256": "corpus",
        "calibration_sha256": "calibration",
        "baseline_sha256": "baseline",
        "baseline_artifact_sha256": "artifact",
        "baseline_evaluation_sha256": "evaluation",
    }
    if any(records[key]["record_type"] != expected_type for key, expected_type in expected_types.items()):
        raise EvidenceBlocked("benchmark initial evidence has an invalid record type")
    if any(record["run_id"] != run_id or record["experiment_spec_sha256"] != experiment_spec_sha256 for record in records.values()):
        raise EvidenceBlocked("benchmark initial evidence crosses a run or specification")
    corpus = records["corpus_sha256"]
    calibration = records["calibration_sha256"]
    baseline = records["baseline_sha256"]
    artifact = records["baseline_artifact_sha256"]
    evaluation = records["baseline_evaluation_sha256"]
    if corpus["payload"] != frozen_corpus:
        raise EvidenceBlocked("benchmark corpus record differs from the frozen specification")
    calibration_links = calibration["links"]
    if (
        calibration["payload"].get("status") != "COMPLETE"
        or calibration["payload"].get("confounders")
        or calibration["payload"].get("resolved_constraints")
        != experiment_spec["threshold_provenance"]["resolved_constraints"]
        or calibration["payload"].get("authority_sha256")
        != experiment_spec["repository_authority"]["authority_sha256"]
        or set(calibration_links) != {"experiment_spec", "corpus", "evaluations", "boundary_artifact"}
        or calibration_links.get("experiment_spec") != experiment_spec_sha256
        or calibration_links.get("corpus") != corpus["content_sha256"]
        or not calibration.get("supersedes_sha256")
    ):
        raise EvidenceBlocked("benchmark calibration is incomplete or does not bind the frozen corpus")
    calibration_evaluation_sha256s = calibration_links["evaluations"]
    if (
        len(calibration_evaluation_sha256s) != frozen_corpus["item_count"]
        or len(set(calibration_evaluation_sha256s)) != len(calibration_evaluation_sha256s)
    ):
        raise EvidenceBlocked("benchmark calibration does not fully and uniquely cover the frozen corpus")
    calibration_evaluations = [record_store.find(identity) for identity in calibration_evaluation_sha256s]
    boundary_artifact = record_store.find(calibration_links["boundary_artifact"])
    pair_ids: list[str] = []
    for calibration_evaluation in calibration_evaluations:
        provenance = calibration_evaluation["payload"].get("provenance", {})
        pair_ids.append(provenance.get("pair_id"))
        if (
            calibration_evaluation["record_type"] != "evaluation"
            or calibration_evaluation["run_id"] != run_id
            or calibration_evaluation["experiment_spec_sha256"] != experiment_spec_sha256
            or calibration_evaluation["payload"].get("evaluation_kind") != "corpus_calibration"
            or calibration_evaluation["payload"].get("subject_sha256") != corpus["content_sha256"]
            or calibration_evaluation["payload"].get("status") != "COMPLETE"
            or calibration_evaluation["payload"].get("confounders")
            or calibration_evaluation["links"]
            != {"experiment_spec": experiment_spec_sha256, "corpus": corpus["content_sha256"]}
            or provenance.get("corpus_manifest_sha256") != frozen_corpus["manifest_sha256"]
            or provenance.get("corpus_pair_identities_sha256") != frozen_corpus["pair_identities_sha256"]
        ):
            raise EvidenceBlocked("benchmark calibration evaluation lineage is incomplete or mismatched")
    if any(not isinstance(pair_id, str) or not pair_id for pair_id in pair_ids) or len(set(pair_ids)) != len(pair_ids):
        raise EvidenceBlocked("benchmark calibration pair coverage is invalid")
    try:
        calibration_items = validate_calibration_coverage(corpus, calibration_evaluations)
    except InvalidInput as exc:
        raise EvidenceBlocked("benchmark calibration evaluations are not exact frozen corpus members") from exc
    if (
        boundary_artifact["record_type"] != "artifact"
        or boundary_artifact["run_id"] != run_id
        or boundary_artifact["experiment_spec_sha256"] != experiment_spec_sha256
        or boundary_artifact["payload"].get("status") != "COMPLETE"
        or boundary_artifact["payload"].get("confounders")
    ):
        raise EvidenceBlocked("benchmark calibration boundary artifact is incomplete or mismatched")
    if boundary_artifact["content_sha256"] == artifact["content_sha256"]:
        raise EvidenceBlocked("benchmark calibration boundary artifact cannot be the baseline media artifact")
    from .operations.boundary import validate_boundary_artifact_record
    from .operations.report import verify_artifact_bytes
    validate_boundary_artifact_record(boundary_artifact, experiment_spec)
    verify_artifact_bytes(record_store, [boundary_artifact, artifact])
    if (
        baseline["payload"]["artifact_sha256"] != artifact["content_sha256"]
        or baseline["links"].get("artifact") != artifact["content_sha256"]
        or baseline["payload"].get("status") != "COMPLETE"
        or baseline["payload"].get("confounders")
        or artifact["payload"].get("status") != "COMPLETE"
        or artifact["payload"].get("confounders")
    ):
        raise EvidenceBlocked("benchmark baseline artifact lineage is invalid")
    if (
        evaluation["payload"]["subject_sha256"] != baseline["content_sha256"]
        or evaluation["links"].get("artifact") != artifact["content_sha256"]
        or evaluation["payload"].get("status") != "COMPLETE"
        or evaluation["payload"].get("confounders")
    ):
        raise EvidenceBlocked("benchmark baseline evaluation lineage is invalid")
    if evaluation["payload"].get("evaluation_kind") != "subject_quality":
        raise EvidenceBlocked("benchmark baseline evaluation is not subject-quality evidence")
    baseline_members = [
        item
        for item in calibration_items
        if item["source_sha256"] == artifact["payload"]["sha256"]
        and item["source_size_bytes"] == artifact["payload"]["size_bytes"]
    ]
    if len(baseline_members) != 1:
        raise EvidenceBlocked("benchmark baseline artifact is not exactly one calibrated corpus source")

    expected_scorer_sha256 = next(
        (
            item["sha256"]
            for item in experiment_spec["repository_authority"]["files"]
            if item.get("logical_path") == "scripts/diagnostics/measure_quality.py"
        ),
        None,
    )
    if expected_scorer_sha256 is None:
        raise EvidenceBlocked("benchmark scorer authority is absent from the frozen specification")
    pipeline_evaluations = [*calibration_evaluations, evaluation]
    pipeline_identities: set[tuple[Any, ...]] = set()
    for pipeline_evaluation in pipeline_evaluations:
        provenance = pipeline_evaluation["payload"].get("provenance")
        if not isinstance(provenance, dict) or any(field not in provenance for field in _PIPELINE_IDENTITY_FIELDS):
            raise EvidenceBlocked("benchmark calibration/baseline pipeline provenance is incomplete")
        try:
            for field in _PIPELINE_IDENTITY_FIELDS[2:]:
                require_sha256(provenance[field], f"evaluation.provenance.{field}")
        except InvalidInput as exc:
            raise EvidenceBlocked("benchmark calibration/baseline pipeline provenance is invalid") from exc
        matching_metrics = [
            metric
            for metric in experiment_spec["metrics"]
            if metric["metric_id"] == provenance["metric_id"]
            and metric["version"] == provenance["metric_version"]
            and metric["model_sha256"] == provenance["metric_model_sha256"]
        ]
        if (
            len(matching_metrics) != 1
            or provenance["scorer_authority_sha256"] != expected_scorer_sha256
            or matching_metrics[0].get("provenance", {}).get("host_scorer_sha256") != expected_scorer_sha256
        ):
            raise EvidenceBlocked("benchmark calibration/baseline metric model is not bound to the frozen host scorer")
        pipeline_identities.add(tuple(provenance[field] for field in _PIPELINE_IDENTITY_FIELDS))
    if len(pipeline_identities) != 1:
        raise EvidenceBlocked("benchmark baseline metric pipeline differs from the completed calibration")
    return {
        "corpus": corpus["content_sha256"],
        "calibration": calibration["content_sha256"],
        "calibration_evaluations": calibration_evaluation_sha256s,
        "boundary_artifact": boundary_artifact["content_sha256"],
        "baseline": baseline["content_sha256"],
        "baseline_artifact": artifact["content_sha256"],
        "baseline_evaluations": [evaluation["content_sha256"]],
    }


def _complete_calibration(
    record_store: RecordStore,
    *,
    initial_calibration_sha256: str,
    corpus_sha256: str,
    boundary_artifact_sha256: str,
    evaluation_sha256s: list[str],
    experiment_spec: dict[str, Any],
) -> dict[str, Any]:
    """Publish the complete calibration only after its corpus evaluations exist."""

    if not evaluation_sha256s or len(set(evaluation_sha256s)) != len(evaluation_sha256s):
        raise EvidenceBlocked("calibration requires unique corpus evaluation evidence")
    initial = record_store.find(initial_calibration_sha256)
    corpus = record_store.find(corpus_sha256)
    boundary_artifact = record_store.find(boundary_artifact_sha256)
    evaluations = [record_store.find(identity) for identity in evaluation_sha256s]
    spec_sha256 = experiment_spec["content_sha256"]
    run_id = experiment_spec["run_id"]
    if (
        initial["record_type"] != "calibration"
        or initial["payload"].get("status") != "BLOCKED"
        or initial["payload"].get("confounders") != ["corpus_evaluation_pending"]
        or corpus["record_type"] != "corpus"
        or corpus["payload"] != experiment_spec["corpus"]
        or boundary_artifact["record_type"] != "artifact"
        or boundary_artifact["payload"].get("status") != "COMPLETE"
        or boundary_artifact["payload"].get("confounders")
    ):
        raise EvidenceBlocked("pending calibration or corpus lineage is invalid")
    if any(
        record["record_type"] != "evaluation"
        or record["payload"].get("evaluation_kind") != "corpus_calibration"
        or record["links"] != {"experiment_spec": spec_sha256, "corpus": corpus_sha256}
        or record["payload"].get("subject_sha256") != corpus_sha256
        or record["payload"].get("status") != "COMPLETE"
        or record["payload"].get("confounders")
        for record in evaluations
    ):
        raise EvidenceBlocked("calibration evaluation lineage is incomplete or confounded")
    if any(record["run_id"] != run_id or record["experiment_spec_sha256"] != spec_sha256 for record in [initial, corpus, boundary_artifact, *evaluations]):
        raise EvidenceBlocked("calibration evidence crosses a run or specification")
    try:
        validate_calibration_coverage(corpus, evaluations)
    except InvalidInput as exc:
        raise EvidenceBlocked("calibration evaluations do not exactly cover the frozen corpus") from exc
    from .operations.boundary import validate_boundary_artifact_record
    from .operations.report import verify_artifact_bytes
    validate_boundary_artifact_record(boundary_artifact, experiment_spec)
    verify_artifact_bytes(record_store, [boundary_artifact])
    completed = create_record(
        "calibration",
        producer_role="deterministic-core",
        run_id=run_id,
        created_at=experiment_spec["created_at"],
        experiment_spec_sha256=spec_sha256,
        links={
            "experiment_spec": spec_sha256,
            "corpus": corpus_sha256,
            "evaluations": evaluation_sha256s,
            "boundary_artifact": boundary_artifact_sha256,
        },
        payload={
            "calibration_id": initial["payload"]["calibration_id"],
            "resolved_constraints": experiment_spec["threshold_provenance"]["resolved_constraints"],
            "authority_sha256": experiment_spec["repository_authority"]["authority_sha256"],
            "status": "COMPLETE",
            "confounders": [],
        },
        supersedes_sha256=initial_calibration_sha256,
    )
    record_store.put(completed)
    return completed


def execute_named_workflow(
    name: str,
    *,
    repository: Path,
    experiment: Path,
    request: dict[str, Any],
    execute: bool,
    gate: bool = False,
) -> dict[str, Any]:
    """Validate, execute serially, and resume an immutable named workflow."""

    workflow_id = NAMED_WORKFLOWS.get(name)
    if workflow_id is None:
        raise InvalidInput("unknown named workflow")
    if gate and name != "benchmark":
        raise InvalidInput("--gate is valid only for workflow benchmark")
    common = {"schema_version", "workflow_id", "run_id", "created_at", "payload"}
    expected = common | ({"definition"} if name == "calibrate" else {"experiment_spec_sha256"})
    if not isinstance(request, dict) or set(request) != expected:
        raise InvalidInput("named workflow request fields are incomplete or unknown")
    if request["schema_version"] != "1" or request["workflow_id"] != workflow_id or not isinstance(request["payload"], dict):
        raise InvalidInput("named workflow request version, ID, or payload is invalid")
    require_run_id(request["run_id"])
    require_utc_timestamp(request["created_at"], "created_at")
    root = Path(repository).resolve(strict=True)
    if not (root / "settings.gradle.kts").is_file():
        raise InvalidInput("repository path is not the Compressor root")
    if name == "benchmark":
        expected_payload_fields = {"step_payloads", "initial_evidence"}
    elif name == "investigate":
        expected_payload_fields = {"step_payloads", "target_verdict_sha256"}
    else:
        expected_payload_fields = {"step_payloads"}
    if set(request["payload"]) != expected_payload_fields:
        raise InvalidInput("named workflow payload fields are incomplete or unknown")
    step_payloads = request["payload"]["step_payloads"]
    expected_operations = set(WORKFLOW_STEPS[workflow_id])
    if not isinstance(step_payloads, dict) or set(step_payloads) != expected_operations or any(not isinstance(value, dict) for value in step_payloads.values()):
        raise InvalidInput("named workflow step_payloads must exactly cover its canonical operations")
    if name == "investigate":
        require_sha256(request["payload"]["target_verdict_sha256"], "target_verdict_sha256")

    freeze_result: dict[str, Any] | None = None
    freeze_preview: dict[str, Any] | None = None
    planned_corpus: dict[str, Any] | None = None
    if name == "calibrate":
        freeze_preview = freeze_specification(
            root,
            experiment,
            {"schema_version": "1", "run_id": request["run_id"], "created_at": request["created_at"], "definition": request["definition"]},
            execute=False,
        )
        freeze_result = freeze_preview
        spec_sha256 = freeze_preview["experiment_spec_sha256"]
        authority_snapshot = capture_authority_snapshot(
            root,
            require_all=True,
            experiment_root=experiment,
        )
        spec = build_experiment_spec(
            root,
            run_id=request["run_id"],
            created_at=request["created_at"],
            definition=request["definition"],
            require_all_authorities=True,
            authority_snapshot=authority_snapshot,
        )
        if spec["content_sha256"] != spec_sha256:
            raise EvidenceBlocked("calibration authority or inputs changed during freeze validation")
        planned_corpus = create_record(
            "corpus",
            producer_role="deterministic-core",
            run_id=request["run_id"],
            created_at=request["created_at"],
            experiment_spec_sha256=spec_sha256,
            links={"experiment_spec": spec_sha256},
            payload=spec["corpus"],
        )
        if planned_corpus["content_sha256"] != freeze_preview["corpus_sha256"]:
            raise EvidenceBlocked("planned corpus identity differs from the dry-run freeze result")
    else:
        require_sha256(request["experiment_spec_sha256"], "experiment_spec_sha256")
        authority_snapshot = capture_authority_snapshot(
            root,
            require_all=True,
            experiment_root=experiment,
        )
        spec = ExperimentSpecStore(experiment).get(request["experiment_spec_sha256"], authority_snapshot=authority_snapshot)
        if spec["run_id"] != request["run_id"]:
            raise EvidenceBlocked("workflow run ID does not match the frozen specification")
        spec_sha256 = spec["content_sha256"]

    plan_payload = deepcopy(request["payload"])
    plan_payload["final_gate"] = gate
    plan_request = {
        "schema_version": "1",
        "workflow_id": workflow_id,
        "run_id": request["run_id"],
        "experiment_spec_sha256": spec_sha256,
        "payload": plan_payload,
    }
    plan = plan_workflow(experiment, plan_request, execute=execute, created_at=request["created_at"])
    if name == "calibrate" and execute:
        freeze_result = freeze_specification(
            root,
            experiment,
            {"schema_version": "1", "run_id": request["run_id"], "created_at": request["created_at"], "definition": request["definition"]},
            execute=True,
        )
        if any(
            freeze_result[key] != freeze_preview[key]
            for key in (
                "experiment_spec_sha256",
                "corpus_sha256",
                "calibration_sha256",
                "repository_authority_sha256",
                "planned_identities",
            )
        ):
            raise EvidenceBlocked("specification freeze changed after workflow-plan validation")
        spec = ExperimentSpecStore(experiment).get(spec_sha256, authority_snapshot=authority_snapshot)
    schema_root = Path(__file__).resolve().parent.parent / "schemas"
    schema_registry = SchemaRegistry(schema_root)
    record_store = RecordStore(experiment)
    target_verdict: dict[str, Any] | None = None
    step_results: list[dict[str, Any]] = []
    errors: list[dict[str, Any]] = []
    quality_verdict: str | None = None
    workflow_status = "DRY_RUN" if not execute else "COMPLETED"
    target_validation_error: LabError | None = None
    if name == "calibrate":
        state: dict[str, Any] = {"corpus": freeze_result["corpus_sha256"]}
    elif name == "benchmark":
        state = _benchmark_initial_state(
            record_store,
            request["payload"]["initial_evidence"],
            run_id=request["run_id"],
            experiment_spec_sha256=spec_sha256,
            experiment_spec=spec,
        )
    else:
        try:
            from .judge import judge_evidence
            from .operations.report import verify_artifact_bytes

            target_verdict = record_store.find(request["payload"]["target_verdict_sha256"])
            if target_verdict["record_type"] != "verdict" or target_verdict["run_id"] != request["run_id"] or target_verdict["experiment_spec_sha256"] != spec_sha256:
                raise EvidenceBlocked("investigation target verdict crosses a run or specification")
            if target_verdict["payload"]["verdict"] not in ("FAIL", "BLOCKED"):
                raise EvidenceBlocked("investigation requires an existing FAIL or BLOCKED verdict")
            target_evidence = record_store.load_evidence(target_verdict["payload"]["evidence_sha256s"])
            verify_artifact_bytes(record_store, target_evidence)
            recomputed = judge_evidence(spec, target_evidence, created_at=spec["created_at"], authority_snapshot=authority_snapshot)
            if recomputed != target_verdict:
                raise EvidenceBlocked("investigation target verdict is not reproducible from live evidence")
            target_records = {record["content_sha256"]: record for record in target_evidence}
            baseline = target_records[target_verdict["links"]["baseline"]]
            candidate = target_records[target_verdict["links"]["candidate"]]
            device = target_records[target_verdict["links"]["device"]]
            corpus_records = [record for record in target_evidence if record["record_type"] == "corpus"]
            if len(corpus_records) != 1:
                raise EvidenceBlocked("investigation target corpus cardinality is invalid")
            state = {
                "corpus": corpus_records[0]["content_sha256"],
                "baseline": baseline["content_sha256"],
                "candidate": candidate["content_sha256"],
                "device": device["content_sha256"],
                "baseline_artifact": baseline["payload"]["artifact_sha256"],
                "candidate_artifact": candidate["payload"]["artifact_sha256"],
                "baseline_evaluations": list(target_verdict["links"]["baseline_evaluations"]),
                "candidate_evaluations": list(target_verdict["links"]["candidate_evaluations"]),
                "candidate_evaluation": target_verdict["links"]["candidate_evaluations"][-1],
            }
        except (LabError, KeyError, IndexError, TypeError) as exc:
            if not execute:
                if isinstance(exc, LabError):
                    raise
                raise EvidenceBlocked("investigation target evidence is invalid") from exc
            target_validation_error = exc if isinstance(exc, LabError) else EvidenceBlocked("investigation target evidence is invalid")
            state = {}
            workflow_status = "BLOCKED"
            errors = [{**target_validation_error.as_dict(), "exit_code": target_validation_error.exit_code}]

    def resolved_request(operation_id: str) -> dict[str, Any]:
        try:
            resolved_payload = _resolve_step_payload(operation_id, step_payloads[operation_id], state)
        except KeyError as exc:
            raise EvidenceBlocked("workflow step lineage is unavailable from prior immutable outputs") from exc
        operation_request = {
            "schema_version": "1",
            "operation_id": operation_id,
            "run_id": request["run_id"],
            "experiment_spec_sha256": spec_sha256,
            "payload": resolved_payload,
        }
        contract = operation_contract(operation_id)
        schema_registry.validate(contract.request_schema, operation_request)
        validate_operation_payload(operation_id, resolved_payload, execute=execute)
        return operation_request

    if not execute and target_validation_error is None:
        for operation_id in WORKFLOW_STEPS[workflow_id]:
            operation_request = resolved_request(operation_id)
            module = importlib.import_module(operation_contract(operation_id).module)
            validate_dry_run = getattr(module, "validate_dry_run", None)
            if validate_dry_run is not None:
                in_memory_corpus = (
                    planned_corpus
                    if planned_corpus is not None and not ExperimentStore(experiment).marker_path.is_file()
                    else None
                )
                validate_dry_run(
                    operation_id,
                    operation_request["payload"],
                    store=record_store,
                    experiment_spec=spec,
                    repository=root,
                    authority_snapshot=authority_snapshot,
                    corpus_record=in_memory_corpus,
                    allow_planned_pair=workflow_id == "pl-benchmark",
                )
            module.plan(operation_id, operation_request["payload"])
            contract = operation_contract(operation_id)
            planned_outputs = [
                _planned_output_sha256(plan["content_sha256"], operation_id, record_type, index)
                for index, record_type in enumerate(contract.output_record_types)
            ]
            _advance_workflow_state(state, operation_id, planned_outputs, record_store=None, dry_run=True)
            step_results.append({
                "operation_id": operation_id,
                "status": "DRY_RUN",
                "skipped": False,
                "request_sha256": domain_hash("operation-request", operation_request),
                "output_sha256s": [],
                "envelope_sha256": None,
            })
    elif target_validation_error is None:
        resumed = resume_workflow(experiment, request["run_id"], request=plan_request, experiment_spec_sha256=spec_sha256)
        completed_by_operation = {
            item["completion"]["operation_id"]: item["completion"]
            for item in resumed["completed_steps"]
        }
        from .operations.runtime import execute_operation
        for operation_id in WORKFLOW_STEPS[workflow_id]:
            operation_request = resolved_request(operation_id)
            request_sha256 = domain_hash("operation-request", operation_request)
            completion = completed_by_operation.get(operation_id)
            if completion is not None:
                if completion["operation_request_sha256"] != request_sha256:
                    raise EvidenceBlocked("completed workflow step request does not match the exact resume request")
                output_sha256s = completion["output_sha256s"]
                step_results.append({
                    "operation_id": operation_id,
                    "status": "COMPLETED",
                    "skipped": True,
                    "request_sha256": request_sha256,
                    "output_sha256s": output_sha256s,
                    "envelope_sha256": None,
                })
                if operation_id == "pl_generate_report":
                    quality_verdict = record_store.find(output_sha256s[0])["payload"]["verdict"]
                _advance_workflow_state(state, operation_id, output_sha256s, record_store=record_store, dry_run=False)
                continue
            envelope = execute_operation(
                operation_id,
                operation_request,
                repository=root,
                experiment=experiment,
                execute=True,
            )
            output_sha256s = [item["sha256"] for item in envelope["output_identities"]]
            step_results.append({
                "operation_id": operation_id,
                "status": envelope["status"],
                "skipped": False,
                "request_sha256": request_sha256,
                "output_sha256s": output_sha256s,
                "envelope_sha256": envelope["content_sha256"],
            })
            if operation_id == "pl_generate_report":
                quality_verdict = envelope.get("quality_verdict")
            if envelope["status"] != "COMPLETED":
                workflow_status = envelope["status"]
                errors = deepcopy(envelope["errors"])
                break
            _advance_workflow_state(state, operation_id, output_sha256s, record_store=record_store, dry_run=False)

    calibration_identity: dict[str, str] | None = None
    if execute and name == "calibrate" and workflow_status == "COMPLETED":
        authority_snapshot = capture_authority_snapshot(
            root,
            require_all=True,
            experiment_root=experiment,
        )
        completed_spec = ExperimentSpecStore(experiment).get(spec_sha256, authority_snapshot=authority_snapshot)
        completed_calibration = _complete_calibration(
            record_store,
            initial_calibration_sha256=freeze_result["calibration_sha256"],
            corpus_sha256=freeze_result["corpus_sha256"],
            boundary_artifact_sha256=state["boundary_artifact"],
            evaluation_sha256s=state.get("corpus_evaluations", []),
            experiment_spec=completed_spec,
        )
        calibration_identity = {"kind": "calibration", "sha256": completed_calibration["content_sha256"]}

    investigation_identity: dict[str, str] | None = None
    if execute and name == "investigate" and workflow_status in ("COMPLETED", "BLOCKED"):
        investigation_outputs = [sha256 for step in step_results for sha256 in step["output_sha256s"]]
        finding_code = (
            "target_evidence_unavailable"
            if target_validation_error is not None
            else ("bounded_reproduction_complete" if workflow_status == "COMPLETED" else "bounded_reproduction_blocked")
        )
        investigation_links: dict[str, Any] = {
            "experiment_spec": spec_sha256,
            "subject": request["payload"]["target_verdict_sha256"],
        }
        if investigation_outputs:
            investigation_links["evidence"] = investigation_outputs
        investigation = create_record(
            "investigation",
            producer_role="deterministic-core",
            run_id=request["run_id"],
            created_at=request["created_at"],
            experiment_spec_sha256=spec_sha256,
            links=investigation_links,
            payload={
                "investigation_id": "investigation-" + request["payload"]["target_verdict_sha256"][:16],
                "subject_sha256": request["payload"]["target_verdict_sha256"],
                "finding_codes": [finding_code],
                "status": "COMPLETE" if workflow_status == "COMPLETED" else "BLOCKED",
                "confounders": [] if workflow_status == "COMPLETED" else [finding_code],
            },
        )
        RecordStore(experiment).put(investigation)
        investigation_identity = {"kind": "investigation", "sha256": investigation["content_sha256"]}

    if workflow_status == "DRY_RUN":
        exit_code = EXIT_COMPLETED
    elif workflow_status == "BLOCKED":
        exit_code = errors[0].get("exit_code", EXIT_BLOCKED) if errors else EXIT_BLOCKED
    elif workflow_status == "ERROR":
        exit_code = errors[0].get("exit_code", 15) if errors else 15
    elif gate and quality_verdict == "FAIL":
        exit_code = EXIT_GATE_FAILED
    else:
        exit_code = EXIT_COMPLETED
    body: dict[str, Any] = {
        "schema_version": "1",
        "workflow_id": workflow_id,
        "status": workflow_status,
        "dry_run": not execute,
        "final_gate": gate,
        "experiment_spec_sha256": spec_sha256,
        "plan": plan,
        "step_results": step_results,
        "errors": errors,
        "exit_code": exit_code,
    }
    if freeze_result is not None:
        body["spec_freeze"] = freeze_result
    if quality_verdict is not None:
        body["quality_verdict"] = quality_verdict
    if calibration_identity is not None:
        body["calibration_identity"] = calibration_identity
    if investigation_identity is not None:
        body["investigation_identity"] = investigation_identity
    return seal_document("workflow-command-result", body)
