"""Common deterministic operation dispatcher."""

from __future__ import annotations

from datetime import datetime, timezone
import importlib
from pathlib import Path
from typing import Any

from ..authority import capture_authority_snapshot, fingerprint_snapshot
from ..canonical import domain_hash
from ..contracts import operation_contract
from ..envelope import build_envelope
from ..errors import EvidenceBlocked, ImmutableConflict, LabError, InvalidInput
from ..experiment import ExperimentStore
from ..records import RecordStore, validate_record
from ..schema import SchemaRegistry
from ..specification import ExperimentSpecStore
from ..workflow import record_operation_completion
from .payloads import validate_operation_payload


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def _version_provenance(
    experiment_spec: dict[str, Any],
    payload: dict[str, Any],
    records: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    metric_values = {(item["metric_id"], item["version"]) for item in experiment_spec["metrics"]}
    model_values = {(item["model_id"], item["model_sha256"]) for item in experiment_spec["metrics"]}
    metrics = [{"metric_id": metric_id, "version": version} for metric_id, version in sorted(metric_values)]
    models = [{"model_id": model_id, "sha256": sha256} for model_id, sha256 in sorted(model_values)]
    result: dict[str, Any] = {"metrics": metrics, "models": models}

    configurations: list[dict[str, Any]] = []
    requested = payload.get("requested_configuration")
    if isinstance(requested, dict):
        configurations.append(requested)
    proposals = payload.get("proposals")
    if isinstance(proposals, list):
        configurations.extend(
            item["configuration"]
            for item in proposals
            if isinstance(item, dict) and isinstance(item.get("configuration"), dict)
        )
    for record in records or []:
        if record.get("record_type") == "candidate":
            configuration = record.get("payload", {}).get("configuration")
            if isinstance(configuration, dict):
                configurations.append(configuration.get("encoder", configuration))
    codecs = {
        (configuration["codec"], domain_hash("codec-configuration", configuration))
        for configuration in configurations
        if isinstance(configuration.get("codec"), str) and configuration["codec"]
    }
    if codecs:
        result["codecs"] = [
            {"codec_id": codec_id, "configuration_sha256": identity}
            for codec_id, identity in sorted(codecs)
        ]

    tools: set[tuple[str, str]] = set()
    for record in records or []:
        payload_value = record.get("payload", {})
        provenance = payload_value.get("provenance")
        if isinstance(provenance, dict):
            mapping = {
                "scorer_authority_sha256": "measure_quality",
                "python_executable_sha256": "python",
                "ffmpeg_executable_sha256": "ffmpeg",
                "ffprobe_executable_sha256": "ffprobe",
            }
            for key, tool_id in mapping.items():
                value = provenance.get(key)
                if isinstance(value, str):
                    tools.add((tool_id, value))
        actual = payload_value.get("actual_configuration")
        if isinstance(actual, dict) and isinstance(actual.get("tool_sha256s"), dict):
            for tool_id, value in actual["tool_sha256s"].items():
                if isinstance(tool_id, str) and isinstance(value, str):
                    tools.add((tool_id, value))
        tool_versions = payload_value.get("tool_versions")
        if isinstance(tool_versions, dict):
            for tool_id, value in tool_versions.items():
                if isinstance(tool_id, str):
                    tools.add((tool_id, domain_hash("tool-version", value)))
    if tools:
        result["tools"] = [{"tool_id": tool_id, "sha256": identity} for tool_id, identity in sorted(tools)]
    return result


def execute_operation(
    operation_id: str,
    request: dict[str, Any],
    *,
    repository: Path,
    experiment: Path,
    execute: bool,
) -> dict[str, Any]:
    contract = operation_contract(operation_id)
    schema_root = Path(__file__).resolve().parents[2] / "schemas"
    SchemaRegistry(schema_root).validate(contract.request_schema, request)
    if request["operation_id"] != operation_id:
        raise InvalidInput("request operation ID does not match CLI subcommand")
    validate_operation_payload(operation_id, request["payload"], execute=execute)
    root = Path(repository).resolve(strict=True)
    if not (root / "settings.gradle.kts").is_file():
        raise InvalidInput("repository path is not the Compressor root")
    authority_snapshot = capture_authority_snapshot(
        root,
        require_all=True,
        experiment_root=experiment,
    )
    authority = fingerprint_snapshot(authority_snapshot)
    started = _now()
    module = importlib.import_module(contract.module)
    request_sha256 = domain_hash("operation-request", request)
    spec: dict[str, Any] | None = None
    try:
        experiment_store = ExperimentStore(experiment)
        experiment_store.prepare(execute=False)
        try:
            spec = ExperimentSpecStore(experiment).get(request["experiment_spec_sha256"], authority_snapshot=authority_snapshot)
        except (InvalidInput, ImmutableConflict) as exc:
            raise EvidenceBlocked("frozen experiment specification is unavailable or invalid") from exc
        if request["run_id"] != spec["run_id"]:
            raise EvidenceBlocked("operation run ID does not match the frozen specification")
        if authority["authority_sha256"] != spec["repository_authority"]["authority_sha256"]:
            raise EvidenceBlocked("repository authority drifted after specification freeze")
        if not execute:
            validate_dry_run = getattr(module, "validate_dry_run", None)
            if validate_dry_run is not None:
                validate_dry_run(
                    operation_id,
                    request["payload"],
                    store=RecordStore(experiment),
                    experiment_spec=spec,
                    repository=root,
                    authority_snapshot=authority_snapshot,
                )
        result = module.plan(operation_id, request["payload"])
        versions = _version_provenance(spec, request["payload"])
        if not execute:
            return build_envelope(
                operation_id=operation_id,
                run_id=request["run_id"],
                status="DRY_RUN",
                dry_run=True,
                started_at=started,
                finished_at=_now(),
                request_sha256=request_sha256,
                repository_authority=authority,
                experiment_spec_sha256=request["experiment_spec_sha256"],
                device_identity={"device_alias": result["device_alias"]} if "device_alias" in result else None,
                result=result,
                versions=versions,
            )

        experiment_store.prepare(execute=True)
        handler = getattr(module, "execute", None)
        if handler is None:
            raise EvidenceBlocked("operation has no registered executable adapter")
        executed = handler(
            operation_id,
            request["payload"],
            store=RecordStore(experiment),
            experiment_spec=spec,
            repository=root,
            authority_snapshot=authority_snapshot,
        )
        records = executed.get("records")
        if not isinstance(records, list) or not records:
            raise EvidenceBlocked("operation adapter did not publish immutable records")
        record_types = [record.get("record_type") if isinstance(record, dict) else None for record in records]
        if len(contract.output_record_types) == 1:
            if any(record_type != contract.output_record_types[0] for record_type in record_types):
                raise EvidenceBlocked("operation adapter published an unauthorized record type")
        elif tuple(record_types) != contract.output_record_types:
            raise EvidenceBlocked("operation adapter did not publish its exact ordered record contract")
        stored = RecordStore(experiment)
        for record in records:
            try:
                validate_record(record)
                if record["producer_role"] != contract.producer_role or record["run_id"] != request["run_id"] or record["experiment_spec_sha256"] != request["experiment_spec_sha256"]:
                    raise EvidenceBlocked("operation adapter record authority or lineage is invalid")
                if stored.get(record["record_type"], record["content_sha256"]) != record:
                    raise EvidenceBlocked("operation adapter record differs from immutable storage")
            except (InvalidInput, ImmutableConflict) as exc:
                raise EvidenceBlocked("operation adapter published a schema-invalid record") from exc
        output_identities = [
            {"kind": record["record_type"], "sha256": record["content_sha256"]}
            for record in records
        ]
        artifact_paths = [
            record["payload"]["logical_path"]
            for record in records
            if record["record_type"] == "artifact"
        ]
        verdict = next(
            (record["payload"]["verdict"] for record in records if record["record_type"] == "verdict"),
            None,
        )
        evidence_blocked = any(
            record["record_type"] not in ("verdict", "report")
            and (record["payload"].get("status") == "BLOCKED" or bool(record["payload"].get("confounders")))
            for record in records
        )
        finished = _now()
        if verdict != "BLOCKED" and not evidence_blocked:
            record_operation_completion(
                experiment,
                run_id=request["run_id"],
                experiment_spec_sha256=request["experiment_spec_sha256"],
                operation_id=operation_id,
                operation_request_sha256=request_sha256,
                output_sha256s=[identity["sha256"] for identity in output_identities],
                completed_at=finished,
            )
        completed_status = "BLOCKED" if verdict == "BLOCKED" or evidence_blocked else "COMPLETED"
        completed_errors = ([{"code": "evidence_blocked", "message": "operation produced blocked or confounded evidence", "details": {}, "exit_code": 13}] if completed_status == "BLOCKED" else [])
        versions = _version_provenance(spec, request["payload"], records)
        return build_envelope(
            operation_id=operation_id,
            run_id=request["run_id"],
            status=completed_status,
            dry_run=False,
            started_at=started,
            finished_at=finished,
            request_sha256=request_sha256,
            repository_authority=authority,
            experiment_spec_sha256=request["experiment_spec_sha256"],
            output_identities=output_identities,
            artifacts=artifact_paths,
            quality_verdict=verdict,
            errors=completed_errors,
            result=executed.get("result", {}),
            versions=versions,
        )
    except LabError as exc:
        status = "BLOCKED" if isinstance(exc, EvidenceBlocked) else "ERROR"
        finished = _now()
        return build_envelope(
            operation_id=operation_id,
            run_id=request["run_id"],
            status=status,
            dry_run=False,
            started_at=started,
            finished_at=finished,
            request_sha256=request_sha256,
            repository_authority=authority,
            experiment_spec_sha256=request["experiment_spec_sha256"],
            quality_verdict="BLOCKED" if operation_id == "pl_generate_report" and status == "BLOCKED" else None,
            errors=[{**exc.as_dict(), "exit_code": exc.exit_code}],
            result={"completed": False},
            versions=_version_provenance(spec, request["payload"]) if spec is not None else None,
        )
