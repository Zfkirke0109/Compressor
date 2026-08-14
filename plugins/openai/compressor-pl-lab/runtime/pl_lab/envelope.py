"""Machine-readable result envelopes and cross-field exit semantics."""

from __future__ import annotations

from typing import Any

from .canonical import seal_document, verify_sealed_document
from .constants import CORE_VERSION, EXIT_BLOCKED, EXIT_COMPLETED, EXIT_GATE_FAILED, PLUGIN_PACKAGE_VERSION, SCHEMA_VERSION
from .contracts import OPERATION_CONTRACTS
from .errors import InvalidInput
from .paths import logical_path
from .sanitize import assert_sanitized
from .validation import require_run_id, require_sha256, require_utc_timestamp


_REQUIRED = {
    "schema_version", "operation_id", "run_id", "status", "started_at", "finished_at", "dry_run",
    "input_identities", "output_identities", "repository_authority", "versions", "warnings", "errors",
    "artifacts", "result", "content_sha256",
}
_OPTIONAL = {"experiment_spec_sha256", "device_identity", "quality_verdict"}


def build_envelope(
    *,
    operation_id: str,
    run_id: str,
    status: str,
    dry_run: bool,
    started_at: str,
    finished_at: str,
    request_sha256: str,
    repository_authority: dict[str, Any],
    result: dict[str, Any],
    experiment_spec_sha256: str | None = None,
    output_identities: list[dict[str, Any]] | None = None,
    artifacts: list[str] | None = None,
    warnings: list[str] | None = None,
    errors: list[dict[str, Any]] | None = None,
    device_identity: dict[str, Any] | None = None,
    quality_verdict: str | None = None,
    versions: dict[str, Any] | None = None,
) -> dict[str, Any]:
    version_provenance: dict[str, Any] = {
        "deterministic_core": CORE_VERSION,
        "contract": "1",
        "schema": SCHEMA_VERSION,
        "plugin_package": PLUGIN_PACKAGE_VERSION,
    }
    if versions:
        version_provenance.update(versions)
    body: dict[str, Any] = {
        "schema_version": "1",
        "operation_id": operation_id,
        "run_id": run_id,
        "status": status,
        "started_at": started_at,
        "finished_at": finished_at,
        "dry_run": dry_run,
        "input_identities": [{"kind": "request", "sha256": request_sha256}],
        "output_identities": output_identities or [],
        "repository_authority": repository_authority,
        "versions": version_provenance,
        "warnings": warnings or [],
        "errors": errors or [],
        "artifacts": artifacts or [],
        "result": result,
    }
    if experiment_spec_sha256 is not None:
        body["experiment_spec_sha256"] = experiment_spec_sha256
    if device_identity is not None:
        body["device_identity"] = device_identity
    if quality_verdict is not None:
        body["quality_verdict"] = quality_verdict
    envelope = seal_document("result-envelope", body)
    validate_envelope(envelope)
    return envelope


def validate_envelope(envelope: dict[str, Any]) -> None:
    if not isinstance(envelope, dict):
        raise InvalidInput("result envelope must be an object")
    missing = sorted(_REQUIRED - set(envelope))
    unknown = sorted(set(envelope) - _REQUIRED - _OPTIONAL)
    if missing or unknown:
        raise InvalidInput("result envelope fields are incomplete or unknown", details={"missing": missing, "unknown": unknown})
    if envelope["schema_version"] != "1" or envelope["operation_id"] not in OPERATION_CONTRACTS:
        raise InvalidInput("result envelope version or operation ID is invalid")
    require_run_id(envelope["run_id"])
    require_utc_timestamp(envelope["started_at"], "started_at")
    require_utc_timestamp(envelope["finished_at"], "finished_at")
    if not isinstance(envelope["dry_run"], bool):
        raise InvalidInput("dry_run must be boolean")
    status = envelope["status"]
    if status not in ("DRY_RUN", "COMPLETED", "BLOCKED", "ERROR"):
        raise InvalidInput("unknown process status")
    if (status == "DRY_RUN") != envelope["dry_run"]:
        raise InvalidInput("DRY_RUN status and dry_run flag contradict")
    if not isinstance(envelope["input_identities"], list) or not envelope["input_identities"]:
        raise InvalidInput("result envelope requires input identities")
    for identity in [*envelope["input_identities"], *envelope["output_identities"]]:
        if not isinstance(identity, dict) or set(identity) != {"kind", "sha256"}:
            raise InvalidInput("result identity shape is invalid")
        require_sha256(identity["sha256"], f"{identity.get('kind', 'unknown')} identity")
    if status == "COMPLETED" and not envelope["output_identities"]:
        raise InvalidInput("completed execution requires output identities")
    if status == "DRY_RUN" and (envelope["output_identities"] or envelope["artifacts"] or envelope["errors"]):
        raise InvalidInput("dry-run envelope cannot claim outputs, artifacts, or errors")
    if status in ("BLOCKED", "ERROR") and not envelope["errors"]:
        raise InvalidInput("blocked/error envelope requires structured errors")
    if not isinstance(envelope["repository_authority"], dict) or not envelope["repository_authority"].get("files"):
        raise InvalidInput("repository authority provenance is empty")
    versions = envelope["versions"]
    required_versions = {"deterministic_core", "contract", "schema", "plugin_package"}
    optional_versions = {"metrics", "models", "codecs", "tools"}
    if not isinstance(versions, dict) or set(versions) - required_versions - optional_versions or required_versions - set(versions):
        raise InvalidInput("tool/version provenance is incomplete or unknown")
    if versions["deterministic_core"] != CORE_VERSION or versions["contract"] != "1" or versions["schema"] != SCHEMA_VERSION or versions["plugin_package"] != PLUGIN_PACKAGE_VERSION:
        raise InvalidInput("core/package version provenance is invalid")
    version_lists = {
        "metrics": ({"metric_id", "version"}, ()),
        "models": ({"model_id", "sha256"}, ("sha256",)),
        "codecs": ({"codec_id", "configuration_sha256"}, ("configuration_sha256",)),
        "tools": ({"tool_id", "sha256"}, ("sha256",)),
    }
    for key, (fields, hash_fields) in version_lists.items():
        if key not in versions:
            continue
        values = versions[key]
        if not isinstance(values, list) or not values:
            raise InvalidInput(f"{key} version provenance must be a nonempty list")
        seen: set[tuple[str, ...]] = set()
        for index, value in enumerate(values):
            if not isinstance(value, dict) or set(value) != fields or any(not isinstance(item, str) or not item for item in value.values()):
                raise InvalidInput(f"{key} version provenance item is invalid")
            for hash_field in hash_fields:
                require_sha256(value[hash_field], f"versions.{key}[{index}].{hash_field}")
            identity = tuple(value[field] for field in sorted(fields))
            if identity in seen:
                raise InvalidInput(f"{key} version provenance contains duplicates")
            seen.add(identity)
    if not all(isinstance(item, str) for item in envelope["warnings"]):
        raise InvalidInput("warnings must be strings")
    if not all(isinstance(item, dict) for item in envelope["errors"]):
        raise InvalidInput("errors must be objects")
    if not isinstance(envelope["result"], dict):
        raise InvalidInput("result must be an object")
    for artifact in envelope["artifacts"]:
        logical_path(artifact)
    if "experiment_spec_sha256" in envelope:
        require_sha256(envelope["experiment_spec_sha256"], "experiment_spec_sha256")
    verdict = envelope.get("quality_verdict")
    if verdict is not None:
        if envelope["operation_id"] != "pl_generate_report" or verdict not in ("PASS", "FAIL", "BLOCKED"):
            raise InvalidInput("only pl_generate_report may emit a quality verdict")
        if verdict == "BLOCKED" and status != "BLOCKED":
            raise InvalidInput("BLOCKED verdict requires BLOCKED process status")
        if verdict in ("PASS", "FAIL") and status != "COMPLETED":
            raise InvalidInput("PASS/FAIL verdict requires completed calculation")
    elif envelope["operation_id"] == "pl_generate_report" and status != "DRY_RUN":
        raise InvalidInput("executed report envelope requires a quality verdict")
    if not verify_sealed_document("result-envelope", envelope):
        raise InvalidInput("result envelope content hash mismatch")
    assert_sanitized(envelope)


def exit_for_envelope(operation_id: str, status: str, verdict: str | None, *, gate: bool) -> int:
    if gate and operation_id != "pl_generate_report":
        raise InvalidInput("--gate is valid only for pl_generate_report")
    if status == "BLOCKED" or verdict == "BLOCKED":
        return EXIT_BLOCKED
    if status != "COMPLETED":
        raise InvalidInput("exit matrix requires a completed or blocked executed result")
    if gate and verdict == "FAIL":
        return EXIT_GATE_FAILED
    return EXIT_COMPLETED
