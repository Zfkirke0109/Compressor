"""Deterministic candidate proposal generation without policy mutation."""

from __future__ import annotations

import hashlib
import math
from pathlib import Path
import re
from typing import Any

from ..authority import AuthoritySnapshot
from ..canonical import domain_hash
from ..errors import EvidenceBlocked, InvalidInput
from ..jsonio import canonical_json_file_bytes
from ..records import RecordStore, create_record
from ..storage import atomic_publish_new


_PROPOSAL_FIELDS = frozenset({"candidate_id", "configuration"})
_CONFIGURATION_FIELDS = frozenset({
    "bitrate_mode",
    "bitrate_ratio",
    "codec",
    "i_frame_interval_seconds",
    "max_b_frames",
    "output_fps",
    "output_height",
    "preserve_hdr",
    "target_bitrate_bps",
    "vendor_profile",
})
_CODECS = frozenset({"video/avc", "video/hevc", "video/av01"})
_BITRATE_MODES = frozenset({"CBR", "VBR"})
_VENDOR_PROFILES = frozenset({"generic", "qualcomm", "samsung"})
_PROPOSAL_ALIAS_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$")
_BUDGET_FIELDS = (
    "max_candidates",
    "max_workers",
    "max_duration_seconds",
    "max_storage_bytes",
)


def plan(operation_id: str, payload: dict[str, Any]) -> dict[str, Any]:
    if operation_id == "pl_encode_candidate":
        return {
            "candidate_id": payload["candidate_id"],
            "source_alias": Path(payload["source_path"]).name,
            "output_logical_path": payload["output_logical_path"],
            "adapter_id": payload["adapter_id"],
            "requested_configuration": payload["requested_configuration"],
            "adapter_registered": False,
            "policy_mutation": False,
        }
    return {
        "proposal_count": len(payload["proposals"]),
        "candidate_budget": payload["max_candidates"],
        "worker_budget": payload["max_workers"],
        "policy_mutation": False,
    }


def _positive_int(value: Any, label: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        raise InvalidInput(f"{label} must be a positive integer")
    return value


def _positive_number(value: Any, label: str, *, maximum: float | None = None) -> float:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise InvalidInput(f"{label} must be a positive finite number")
    normalized = float(value)
    if not math.isfinite(normalized) or normalized <= 0 or (maximum is not None and normalized > maximum):
        raise InvalidInput(f"{label} is outside its supported range")
    return normalized


def _normalize_configuration(value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise InvalidInput("candidate proposal configuration must be an object")
    missing = sorted({"codec"} - set(value))
    unknown = sorted(set(value) - _CONFIGURATION_FIELDS)
    if missing or unknown:
        raise InvalidInput(
            "candidate proposal configuration fields are incomplete or unknown",
            details={"missing": missing, "unknown": unknown},
        )

    codec = value["codec"]
    if not isinstance(codec, str) or codec not in _CODECS:
        raise InvalidInput("candidate codec is unsupported")
    normalized: dict[str, Any] = {"codec": codec}

    if "bitrate_mode" in value:
        bitrate_mode = value["bitrate_mode"]
        if not isinstance(bitrate_mode, str) or bitrate_mode not in _BITRATE_MODES:
            raise InvalidInput("candidate bitrate_mode must be CBR or VBR")
        normalized["bitrate_mode"] = bitrate_mode
    if "bitrate_ratio" in value:
        normalized["bitrate_ratio"] = _positive_number(value["bitrate_ratio"], "candidate bitrate_ratio", maximum=1.0)
    if "i_frame_interval_seconds" in value:
        normalized["i_frame_interval_seconds"] = _positive_number(
            value["i_frame_interval_seconds"], "candidate i_frame_interval_seconds"
        )
    if "max_b_frames" in value:
        max_b_frames = value["max_b_frames"]
        if not isinstance(max_b_frames, int) or isinstance(max_b_frames, bool) or max_b_frames < 0:
            raise InvalidInput("candidate max_b_frames must be a nonnegative integer")
        normalized["max_b_frames"] = max_b_frames
    if "output_fps" in value:
        normalized["output_fps"] = _positive_number(value["output_fps"], "candidate output_fps")
    if "output_height" in value:
        output_height = _positive_int(value["output_height"], "candidate output_height")
        if output_height % 2:
            raise InvalidInput("candidate output_height must be even")
        normalized["output_height"] = output_height
    if "preserve_hdr" in value:
        if not isinstance(value["preserve_hdr"], bool):
            raise InvalidInput("candidate preserve_hdr must be boolean")
        normalized["preserve_hdr"] = value["preserve_hdr"]
    if "target_bitrate_bps" in value:
        normalized["target_bitrate_bps"] = _positive_int(value["target_bitrate_bps"], "candidate target_bitrate_bps")
    if "vendor_profile" in value:
        vendor_profile = value["vendor_profile"]
        if not isinstance(vendor_profile, str) or vendor_profile not in _VENDOR_PROFILES:
            raise InvalidInput("candidate vendor_profile is unsupported")
        normalized["vendor_profile"] = vendor_profile
    return {key: normalized[key] for key in sorted(normalized)}


def _constraint_identity(experiment_spec: dict[str, Any]) -> str:
    try:
        constraints = experiment_spec["threshold_provenance"]["resolved_constraints"]
    except (KeyError, TypeError) as exc:
        raise InvalidInput("frozen experiment constraints are unavailable") from exc
    if not isinstance(constraints, list) or not constraints:
        raise InvalidInput("frozen experiment constraints are unavailable")
    return domain_hash("frozen-quality-constraints", constraints)


def candidate_id_for(experiment_spec: dict[str, Any], configuration: dict[str, Any]) -> str:
    """Derive the stable candidate identity from the spec-bound configuration."""

    normalized = _normalize_configuration(configuration)
    spec_sha256 = experiment_spec.get("content_sha256")
    if not isinstance(spec_sha256, str) or re.fullmatch(r"[0-9a-f]{64}", spec_sha256) is None:
        raise InvalidInput("frozen experiment identity is invalid")
    bound = {
        "constraint_set_sha256": _constraint_identity(experiment_spec),
        "encoder": normalized,
        "experiment_spec_sha256": spec_sha256,
    }
    return "candidate-" + domain_hash("candidate-proposal-identity", bound)


def _validate_budgets(payload: dict[str, Any], experiment_spec: dict[str, Any]) -> None:
    try:
        frozen_budgets = experiment_spec["budgets"]
    except (KeyError, TypeError) as exc:
        raise InvalidInput("frozen experiment budgets are unavailable") from exc
    if not isinstance(frozen_budgets, dict) or set(frozen_budgets) != set(_BUDGET_FIELDS):
        raise InvalidInput("frozen experiment budgets are invalid")
    for key in _BUDGET_FIELDS:
        requested = _positive_int(payload.get(key), key)
        frozen = _positive_int(frozen_budgets.get(key), f"experiment_spec.budgets.{key}")
        if requested > frozen:
            raise InvalidInput(f"{key} exceeds the frozen experiment budget")
    if payload["max_workers"] != 1 or frozen_budgets["max_workers"] != 1:
        raise InvalidInput("v1 policy optimization enforces exactly one worker")


def _proposal_documents(payload: dict[str, Any], experiment_spec: dict[str, Any]) -> list[tuple[dict[str, Any], bytes]]:
    proposals = payload.get("proposals")
    if not isinstance(proposals, list) or not proposals:
        raise InvalidInput("policy optimization requires at least one candidate proposal")
    if len(proposals) > payload["max_candidates"]:
        raise InvalidInput("proposal count exceeds its explicit candidate budget")

    spec_sha256 = experiment_spec.get("content_sha256")
    run_id = experiment_spec.get("run_id")
    created_at = experiment_spec.get("created_at")
    constraint_sha256 = _constraint_identity(experiment_spec)
    prepared: list[tuple[dict[str, Any], bytes]] = []
    seen_ids: set[str] = set()
    for index, proposal in enumerate(proposals):
        if not isinstance(proposal, dict) or set(proposal) != _PROPOSAL_FIELDS:
            raise InvalidInput(f"candidate proposal {index} fields are incomplete or unknown")
        normalized = _normalize_configuration(proposal["configuration"])
        candidate_id = candidate_id_for(experiment_spec, normalized)
        supplied_id = proposal["candidate_id"]
        if not isinstance(supplied_id, str) or not _PROPOSAL_ALIAS_RE.fullmatch(supplied_id):
            raise InvalidInput(f"candidate proposal {index} alias is invalid")
        if supplied_id != candidate_id:
            raise InvalidInput(f"candidate proposal {index} identity does not match its spec-bound configuration")
        if candidate_id in seen_ids:
            raise InvalidInput("duplicate candidate proposal")
        seen_ids.add(candidate_id)
        bound_configuration = {
            "constraint_set_sha256": constraint_sha256,
            "encoder": normalized,
        }
        document = {
            "schema_version": "1",
            "artifact_type": "candidate-proposal",
            "producer_role": "codec-scientist",
            "run_id": run_id,
            "created_at": created_at,
            "experiment_spec_sha256": spec_sha256,
            "candidate_id": candidate_id,
            "configuration": bound_configuration,
            "encoded_media_produced": False,
            "policy_mutation": False,
        }
        prepared.append((document, canonical_json_file_bytes(document)))
    prepared.sort(key=lambda item: item[0]["candidate_id"])
    if sum(len(data) for _, data in prepared) > payload["max_storage_bytes"]:
        raise InvalidInput("candidate proposal artifacts exceed the explicit storage budget")
    return prepared


def execute(
    operation_id: str,
    payload: dict[str, Any],
    *,
    store: RecordStore,
    experiment_spec: dict[str, Any],
    repository: Path,
    authority_snapshot: AuthoritySnapshot,
) -> dict[str, Any]:
    if operation_id == "pl_encode_candidate":
        raise EvidenceBlocked("the android-media3 candidate encoder adapter is not registered or tested")
    if operation_id != "pl_optimize_policy":
        raise InvalidInput("unsupported codec operation")
    if not Path(repository).is_dir():
        raise InvalidInput("repository path is unavailable")

    _validate_budgets(payload, experiment_spec)
    prepared = _proposal_documents(payload, experiment_spec)
    staged: list[tuple[dict[str, Any], dict[str, Any], bytes]] = []
    for document, data in prepared:
        candidate_id = document["candidate_id"]
        file_sha256 = hashlib.sha256(data).hexdigest()
        filename = f"{candidate_id}-{file_sha256[:16]}.json"
        logical_path = f"artifacts/candidate-proposals/{filename}"
        record = create_record(
            "candidate",
            producer_role="codec-scientist",
            run_id=experiment_spec["run_id"],
            created_at=experiment_spec["created_at"],
            experiment_spec_sha256=experiment_spec["content_sha256"],
            links={"experiment_spec": experiment_spec["content_sha256"]},
            payload={
                "candidate_id": candidate_id,
                "configuration": document["configuration"],
                "artifact_sha256": file_sha256,
                "status": "COMPLETE",
                "warnings": ["proposal_only_no_encoded_media"],
            },
        )
        artifact_identity = {
            "candidate_id": candidate_id,
            "logical_path": logical_path,
            "sha256": file_sha256,
            "size_bytes": len(data),
        }
        staged.append((record, artifact_identity, data))
    serialized_size = sum(len(data) + len(canonical_json_file_bytes(record)) for record, _, data in staged)
    if serialized_size > payload["max_storage_bytes"]:
        raise InvalidInput("candidate proposal evidence exceeds the explicit storage budget")

    records: list[dict[str, Any]] = []
    proposal_artifacts: list[dict[str, Any]] = []
    for record, artifact_identity, data in staged:
        target = store.experiment.owned_path(*artifact_identity["logical_path"].split("/"))
        atomic_publish_new(target, data, root=store.experiment.root)
        store.put(record)
        records.append(record)
        proposal_artifacts.append(artifact_identity)
    return {
        "records": records,
        "result": {
            "proposal_count": len(records),
            "candidate_ids": [record["payload"]["candidate_id"] for record in records],
            "proposal_artifacts": proposal_artifacts,
            "encoded_media_produced": False,
            "policy_mutation": False,
        },
    }
