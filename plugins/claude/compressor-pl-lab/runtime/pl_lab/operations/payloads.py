"""Operation-specific request payload contracts and safety guards."""

from __future__ import annotations

from pathlib import Path
import re
from typing import Any

from ..errors import InvalidInput
from ..paths import logical_path
from ..validation import require_sha256


_FIELDS: dict[str, tuple[set[str], set[str]]] = {
    "pl_device_inventory": ({"adb_executable", "device_serial", "android_user", "package_id", "expected_firmware_sha256"}, set()),
    "pl_build_install": ({"adb_executable", "apksigner_executable", "aapt_executable", "apk_path", "device_serial", "android_user", "package_id", "expected_build_identity", "expected_signer_sha256", "expected_firmware_sha256", "max_duration_seconds", "max_storage_bytes"}, set()),
    "pl_encode_candidate": ({"candidate_id", "source_path", "output_logical_path", "requested_configuration", "adapter_id", "max_duration_seconds", "max_storage_bytes"}, set()),
    "pl_score_pair": ({"source_path", "output_path", "pair_id", "ffmpeg_executable", "ffprobe_executable", "model_id", "max_duration_seconds", "max_storage_bytes"}, set()),
    "pl_score_corpus": ({"corpus_manifest_path", "ffmpeg_executable", "ffprobe_executable", "model_id", "max_candidates", "max_workers", "max_duration_seconds", "max_storage_bytes"}, set()),
    "pl_hdr_compare": ({"input_evaluation_sha256", "reference", "candidate"}, set()),
    "pl_pts_compare": ({"input_evaluation_sha256", "reference_pts_us", "candidate_pts_us"}, set()),
    "pl_run_boundary_suite": ({"mode"}, {"max_candidates", "max_workers", "max_duration_seconds", "max_storage_bytes"}),
    "pl_optimize_policy": ({"proposals", "max_candidates", "max_workers", "max_duration_seconds", "max_storage_bytes"}, set()),
    "pl_compare_baseline": ({"record_sha256s"}, set()),
    "pl_generate_report": ({"record_sha256s"}, set()),
}


def _positive_int(value: Any, label: str) -> None:
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        raise InvalidInput(f"{label} must be a positive integer")


def _forbid_authority_smuggling(value: Any, path: str = "payload") -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            normalized = re.sub(r"[^a-z0-9]", "", key.casefold())
            if "threshold" in normalized or normalized in {"verdict", "overallverdict", "qualityverdict", "winner", "approved"}:
                raise InvalidInput(f"payload cannot claim thresholds/verdict authority at {path}.{key}")
            _forbid_authority_smuggling(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _forbid_authority_smuggling(child, f"{path}[{index}]")


def _budgets(payload: dict[str, Any], *, candidates: bool = False, workers: bool = False) -> None:
    for key in ("max_duration_seconds", "max_storage_bytes"):
        _positive_int(payload[key], key)
    if candidates:
        _positive_int(payload["max_candidates"], "max_candidates")
    if workers:
        _positive_int(payload["max_workers"], "max_workers")
        if payload["max_workers"] != 1:
            raise InvalidInput("v1 enforces one worker")


def validate_operation_payload(operation_id: str, payload: dict[str, Any], *, execute: bool) -> None:
    contract = _FIELDS.get(operation_id)
    if contract is None or not isinstance(payload, dict):
        raise InvalidInput("unknown operation or non-object payload")
    required, optional = contract
    missing = sorted(required - set(payload))
    unknown = sorted(set(payload) - required - optional)
    if missing or unknown:
        raise InvalidInput("operation payload fields are incomplete or unknown", details={"missing": missing, "unknown": unknown})
    _forbid_authority_smuggling(payload)
    if operation_id in ("pl_device_inventory", "pl_build_install"):
        if not all(isinstance(payload[key], str) and payload[key] for key in ("device_serial", "package_id", "adb_executable")):
            raise InvalidInput("device identity fields must be non-empty strings")
        if not isinstance(payload["android_user"], int) or isinstance(payload["android_user"], bool) or payload["android_user"] < 0:
            raise InvalidInput("Android user must be a nonnegative integer")
        require_sha256(payload["expected_firmware_sha256"], "expected_firmware_sha256")
    if operation_id == "pl_build_install":
        _budgets(payload)
        require_sha256(payload["expected_signer_sha256"], "expected_signer_sha256")
        build = payload["expected_build_identity"]
        if not isinstance(build, dict) or set(build) != {"version_code", "version_name"} or not isinstance(build["version_code"], int) or isinstance(build["version_code"], bool) or not isinstance(build["version_name"], str):
            raise InvalidInput("expected build identity is invalid")
    if operation_id == "pl_encode_candidate":
        _budgets(payload)
        logical_path(payload["output_logical_path"])
        if payload["adapter_id"] != "android-media3" or not isinstance(payload["requested_configuration"], dict):
            raise InvalidInput("candidate must name the registered android-media3 adapter and a configuration")
    if operation_id == "pl_score_pair":
        _budgets(payload)
        if payload["model_id"] != "vmaf_v0.6.1":
            raise InvalidInput("v1 score-pair model must match repository authority")
    if operation_id in ("pl_score_corpus", "pl_optimize_policy"):
        _budgets(payload, candidates=True, workers=True)
        if operation_id == "pl_score_corpus":
            if not all(isinstance(payload[key], str) and payload[key] for key in ("ffmpeg_executable", "ffprobe_executable")):
                raise InvalidInput("corpus scoring executables must be non-empty strings")
            if payload["model_id"] != "vmaf_v0.6.1":
                raise InvalidInput("v1 score-corpus model must match repository authority")
        else:
            if not isinstance(payload["proposals"], list) or not payload["proposals"] or len(payload["proposals"]) > payload["max_candidates"]:
                raise InvalidInput("proposal count exceeds its explicit candidate budget")
    if operation_id == "pl_hdr_compare":
        require_sha256(payload["input_evaluation_sha256"], "input_evaluation_sha256")
        for side in ("reference", "candidate"):
            value = payload[side]
            expected = {"standard", "transfer", "range", "bit_depth", "dynamic_metadata_sha256"}
            if not isinstance(value, dict) or set(value) != expected:
                raise InvalidInput("HDR evidence fields are incomplete or unknown")
            for key in ("standard", "transfer", "range", "bit_depth"):
                if not isinstance(value[key], int) or isinstance(value[key], bool):
                    raise InvalidInput("HDR numeric evidence must be integer")
            require_sha256(value["dynamic_metadata_sha256"], f"{side}.dynamic_metadata_sha256")
    if operation_id == "pl_pts_compare":
        require_sha256(payload["input_evaluation_sha256"], "input_evaluation_sha256")
        for key in ("reference_pts_us", "candidate_pts_us"):
            values = payload[key]
            if not isinstance(values, list) or not values or any(not isinstance(item, int) or isinstance(item, bool) for item in values):
                raise InvalidInput("PTS evidence must be a non-empty integer list")
            if any(right <= left for left, right in zip(values, values[1:])):
                raise InvalidInput("PTS evidence must be strictly increasing")
    if operation_id == "pl_run_boundary_suite":
        if payload["mode"] not in ("fast", "expensive"):
            raise InvalidInput("boundary suite mode must be fast or expensive")
        if payload["mode"] == "expensive" and execute:
            needed = {"max_candidates", "max_workers", "max_duration_seconds", "max_storage_bytes"}
            if not needed <= set(payload):
                raise InvalidInput("expensive boundary execution requires explicit budgets")
            _budgets(payload, candidates=True, workers=True)
    if operation_id in ("pl_compare_baseline", "pl_generate_report"):
        values = payload["record_sha256s"]
        if not isinstance(values, list) or not values:
            raise InvalidInput("at least one evidence record hash is required")
        for index, value in enumerate(values):
            require_sha256(value, f"record_sha256s[{index}]")
