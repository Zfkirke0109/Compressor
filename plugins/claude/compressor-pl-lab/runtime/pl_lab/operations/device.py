"""Read-only inventory and a fail-closed, update-only Android installer."""

from __future__ import annotations

import hashlib
from pathlib import Path
import re
import shutil
import tempfile
import time
from typing import Any, Sequence

from ..authority import AuthoritySnapshot, fingerprint_snapshot
from ..canonical import domain_hash
from ..errors import EvidenceBlocked, IdentityMismatch, InvalidInput, MissingPrerequisite
from ..process import ProcessBudget, ProcessResult, run_bounded
from ..records import RecordStore, create_record
from ..sanitize import device_alias


_OUTPUT_BUDGET_BYTES = 1024 * 1024
_PACKAGE_ID = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+$")
_REMOTE_BASE_APK = re.compile(r"^/[A-Za-z0-9._@+=,/:~\-]+/base\.apk$")
_AAPT_PACKAGE = re.compile(
    r"^package:\s+name='([^']+)'\s+versionCode='([0-9]+)'\s+versionName='([^']+)'(?:\s|$)"
)
_SIGNER_DIGEST = re.compile(
    r"^Signer\s+#\d+\s+certificate\s+SHA-256\s+digest:\s*([0-9A-Fa-f:]+)\s*$",
    re.IGNORECASE,
)


def plan(operation_id: str, payload: dict[str, Any]) -> dict[str, Any]:
    result = {
        "action": "read_only_inventory" if operation_id == "pl_device_inventory" else "guarded_update_only_install",
        "device_alias": device_alias(payload["device_serial"]),
        "android_user": payload["android_user"],
        "package_id": payload["package_id"],
        "identity_guards": ["firmware", "package", "android_user"],
    }
    if operation_id == "pl_build_install":
        result["identity_guards"] += ["apk_hash", "build_identity", "source_identity", "signer"]
        result["forbidden_actions"] = ["uninstall", "clear_data", "reboot", "cross_user", "downgrade"]
    return result


class _Deadline:
    def __init__(self, seconds: int) -> None:
        self._deadline = time.monotonic() + seconds

    def remaining(self) -> float:
        remaining = self._deadline - time.monotonic()
        if remaining <= 0:
            raise EvidenceBlocked("build/install exceeded its duration budget")
        return remaining

    def check(self) -> None:
        self.remaining()


def _sha256_file(path: Path, *, maximum_bytes: int | None = None) -> tuple[str, int]:
    try:
        before = path.stat()
    except OSError as exc:
        raise EvidenceBlocked("APK identity is unavailable") from exc
    if not path.is_file():
        raise EvidenceBlocked("APK identity is unavailable")
    if maximum_bytes is not None and before.st_size > maximum_bytes:
        raise EvidenceBlocked("APK exceeds the storage budget")
    digest = hashlib.sha256()
    size = 0
    try:
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                size += len(chunk)
                if maximum_bytes is not None and size > maximum_bytes:
                    raise EvidenceBlocked("APK exceeds the storage budget")
                digest.update(chunk)
        after = path.stat()
    except EvidenceBlocked:
        raise
    except OSError as exc:
        raise EvidenceBlocked("APK identity is unavailable") from exc
    if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (
        after.st_dev,
        after.st_ino,
        after.st_size,
        after.st_mtime_ns,
    ) or size != after.st_size:
        raise EvidenceBlocked("APK changed while its identity was captured")
    return digest.hexdigest(), size


def _executable_sha256(value: str) -> str:
    candidate = Path(value)
    if candidate.is_absolute() or candidate.parent != Path("."):
        executable = candidate.resolve(strict=False)
        if not executable.is_file():
            raise MissingPrerequisite("required executable is unavailable")
    else:
        located = shutil.which(value)
        if not located:
            raise MissingPrerequisite("required executable is unavailable")
        executable = Path(located).resolve(strict=True)
    digest = hashlib.sha256()
    try:
        with executable.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError as exc:
        raise MissingPrerequisite("required executable cannot be fingerprinted") from exc
    return digest.hexdigest()


def _run(
    argv: Sequence[str],
    *,
    deadline: _Deadline,
    executable_sha256: str,
    sensitive_values: Sequence[str],
    cwd: Path,
) -> ProcessResult:
    result = run_bounded(
        argv,
        budget=ProcessBudget(timeout_seconds=deadline.remaining(), max_output_bytes=_OUTPUT_BUDGET_BYTES),
        cwd=cwd,
        expected_executable_sha256=executable_sha256,
        sensitive_values=sensitive_values,
    )
    deadline.check()
    if result.output_truncated:
        raise EvidenceBlocked("tool output exceeded its evidence budget")
    if result.return_code != 0:
        raise EvidenceBlocked("required tool command failed")
    return result


def _tool(
    executable: str,
    arguments: Sequence[str],
    *,
    deadline: _Deadline,
    executable_sha256: str,
    sensitive_values: Sequence[str],
    cwd: Path,
) -> ProcessResult:
    return _run(
        [executable, *arguments],
        deadline=deadline,
        executable_sha256=executable_sha256,
        sensitive_values=sensitive_values,
        cwd=cwd,
    )


def _adb(
    payload: dict[str, Any],
    arguments: Sequence[str],
    *,
    deadline: _Deadline,
    executable_sha256: str,
    sensitive_values: Sequence[str],
    cwd: Path,
) -> ProcessResult:
    # Keep the selector in one non-overridable helper so every ADB invocation,
    # including discovery, is bound to the requested transport identity.
    return _tool(
        payload["adb_executable"],
        ["-s", payload["device_serial"], *arguments],
        deadline=deadline,
        executable_sha256=executable_sha256,
        sensitive_values=sensitive_values,
        cwd=cwd,
    )


def _apk_metadata(
    payload: dict[str, Any],
    apk: Path,
    *,
    deadline: _Deadline,
    aapt_sha256: str,
    sensitive_values: Sequence[str],
    cwd: Path,
) -> dict[str, Any]:
    result = _tool(
        payload["aapt_executable"],
        ["dump", "badging", str(apk)],
        deadline=deadline,
        executable_sha256=aapt_sha256,
        sensitive_values=sensitive_values,
        cwd=cwd,
    )
    matches = [match for line in result.stdout.splitlines() if (match := _AAPT_PACKAGE.match(line.strip()))]
    if len(matches) != 1:
        raise EvidenceBlocked("APK package/build identity is unavailable")
    match = matches[0]
    return {
        "package_id": match.group(1),
        "version_code": int(match.group(2)),
        "version_name": match.group(3),
    }


def _apk_signer(
    payload: dict[str, Any],
    apk: Path,
    *,
    deadline: _Deadline,
    apksigner_sha256: str,
    sensitive_values: Sequence[str],
    cwd: Path,
) -> str:
    result = _tool(
        payload["apksigner_executable"],
        ["verify", "--print-certs", str(apk)],
        deadline=deadline,
        executable_sha256=apksigner_sha256,
        sensitive_values=sensitive_values,
        cwd=cwd,
    )
    digests: set[str] = set()
    for line in result.stdout.splitlines():
        match = _SIGNER_DIGEST.match(line.strip())
        if match:
            normalized = match.group(1).replace(":", "").lower()
            if re.fullmatch(r"[0-9a-f]{64}", normalized):
                digests.add(normalized)
    if len(digests) != 1:
        raise EvidenceBlocked("APK signer identity is unavailable or ambiguous")
    return next(iter(digests))


def _online_target(
    payload: dict[str, Any],
    *,
    deadline: _Deadline,
    adb_sha256: str,
    sensitive_values: Sequence[str],
    cwd: Path,
) -> None:
    # Enumeration must retain the requested value long enough for an exact
    # in-memory comparison. The captured text is never persisted or returned.
    enumeration_sensitive = [value for value in sensitive_values if value != payload["device_serial"]]
    result = _adb(
        payload,
        ["devices"],
        deadline=deadline,
        executable_sha256=adb_sha256,
        sensitive_values=enumeration_sensitive,
        cwd=cwd,
    )
    rows: list[tuple[str, str]] = []
    for line in result.stdout.splitlines():
        stripped = line.strip()
        if not stripped or stripped == "List of devices attached" or stripped.startswith("*"):
            continue
        columns = stripped.split()
        if len(columns) < 2:
            raise EvidenceBlocked("ADB device enumeration is incomplete")
        rows.append((columns[0], columns[1]))
    target_rows = [state for serial, state in rows if serial == payload["device_serial"]]
    online = [serial for serial, state in rows if state == "device"]
    if not target_rows:
        if online:
            raise IdentityMismatch("a different online device is connected instead of the requested target")
        raise EvidenceBlocked("the requested device transport is unavailable")
    if len(target_rows) != 1:
        raise IdentityMismatch("duplicate device rows prevent a unique target identity")
    if target_rows[0] != "device":
        raise EvidenceBlocked("the requested device transport is offline or unavailable")
    if online != [payload["device_serial"]]:
        raise IdentityMismatch("more than one online device prevents a unique update target")


def _active_user(
    payload: dict[str, Any],
    *,
    deadline: _Deadline,
    adb_sha256: str,
    sensitive_values: Sequence[str],
    cwd: Path,
) -> None:
    result = _adb(
        payload,
        ["shell", "am", "get-current-user"],
        deadline=deadline,
        executable_sha256=adb_sha256,
        sensitive_values=sensitive_values,
        cwd=cwd,
    )
    try:
        actual = int(result.stdout.strip())
    except ValueError as exc:
        raise EvidenceBlocked("active Android user/profile evidence is unavailable") from exc
    if actual != payload["android_user"]:
        raise IdentityMismatch("active Android user/profile does not match the frozen target")


def _installed_for_user(
    payload: dict[str, Any],
    *,
    deadline: _Deadline,
    adb_sha256: str,
    sensitive_values: Sequence[str],
    cwd: Path,
) -> None:
    result = _adb(
        payload,
        [
            "shell",
            "pm",
            "list",
            "packages",
            "--user",
            str(payload["android_user"]),
            payload["package_id"],
        ],
        deadline=deadline,
        executable_sha256=adb_sha256,
        sensitive_values=sensitive_values,
        cwd=cwd,
    )
    packages = [line.removeprefix("package:").strip() for line in result.stdout.splitlines() if line.startswith("package:")]
    if packages != [payload["package_id"]]:
        raise IdentityMismatch("the package is not already installed for the exact Android user")


def _firmware_sha256(
    payload: dict[str, Any],
    *,
    deadline: _Deadline,
    adb_sha256: str,
    sensitive_values: Sequence[str],
    cwd: Path,
) -> str:
    result = _adb(
        payload,
        ["shell", "getprop", "ro.build.fingerprint"],
        deadline=deadline,
        executable_sha256=adb_sha256,
        sensitive_values=sensitive_values,
        cwd=cwd,
    )
    lines = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    if len(lines) != 1:
        raise EvidenceBlocked("firmware fingerprint evidence is unavailable")
    return hashlib.sha256(lines[0].encode("utf-8")).hexdigest()


def _remote_apk_sha256(
    payload: dict[str, Any],
    remote_path: str,
    *,
    deadline: _Deadline,
    adb_sha256: str,
    sensitive_values: Sequence[str],
    cwd: Path,
) -> str:
    result = _adb(
        payload,
        ["shell", "sha256sum", remote_path],
        deadline=deadline,
        executable_sha256=adb_sha256,
        sensitive_values=[*sensitive_values, remote_path],
        cwd=cwd,
    )
    matches = re.findall(r"(?im)^([0-9a-f]{64})\s+\S+\s*$", result.stdout)
    if len(matches) != 1:
        raise EvidenceBlocked("installed APK hash evidence is unavailable")
    return matches[0].lower()


def _installed_package_identity(
    payload: dict[str, Any],
    *,
    deadline: _Deadline,
    adb_sha256: str,
    sensitive_values: Sequence[str],
    cwd: Path,
) -> dict[str, Any]:
    result = _adb(
        payload,
        ["shell", "dumpsys", "package", payload["package_id"]],
        deadline=deadline,
        executable_sha256=adb_sha256,
        sensitive_values=sensitive_values,
        cwd=cwd,
    )
    version_codes = set(re.findall(r"(?m)\bversionCode=([0-9]+)\b", result.stdout))
    version_names = set(re.findall(r"(?m)\bversionName=([^\s]+)", result.stdout))
    signer_values = {
        value.replace(":", "").lower()
        for value in re.findall(r"(?im)SHA-256\s+(?:certificate\s+)?digest\s*:\s*([0-9a-f:]{64,95})", result.stdout)
    }
    signer_values = {value for value in signer_values if re.fullmatch(r"[0-9a-f]{64}", value)}
    if len(version_codes) != 1 or len(version_names) != 1 or len(signer_values) != 1:
        raise EvidenceBlocked("installed package build or signer evidence is unavailable")
    return {
        "version_code": int(next(iter(version_codes))),
        "version_name": next(iter(version_names)),
        "signer_sha256": next(iter(signer_values)),
    }


def _execute_inventory(
    payload: dict[str, Any],
    *,
    store: RecordStore,
    experiment_spec: dict[str, Any],
    repository: Path,
    authority_snapshot: AuthoritySnapshot,
) -> dict[str, Any]:
    if not _PACKAGE_ID.fullmatch(payload["package_id"]):
        raise InvalidInput("package ID is invalid")
    frozen = experiment_spec.get("device_requirements")
    if not isinstance(frozen, dict) or frozen.get("required") is not True:
        raise EvidenceBlocked("device inventory requires a fully frozen device identity")
    budgets = experiment_spec.get("budgets")
    duration_budget = budgets.get("max_duration_seconds") if isinstance(budgets, dict) else None
    if not isinstance(duration_budget, int) or isinstance(duration_budget, bool) or duration_budget <= 0:
        raise EvidenceBlocked("frozen inventory duration budget is unavailable")
    deadline = _Deadline(min(duration_budget, 30))
    adb_sha256 = _executable_sha256(payload["adb_executable"])
    sensitive_values = (payload["device_serial"],)
    _online_target(payload, deadline=deadline, adb_sha256=adb_sha256, sensitive_values=sensitive_values, cwd=repository)
    _active_user(payload, deadline=deadline, adb_sha256=adb_sha256, sensitive_values=sensitive_values, cwd=repository)
    _installed_for_user(payload, deadline=deadline, adb_sha256=adb_sha256, sensitive_values=sensitive_values, cwd=repository)
    firmware_sha256 = _firmware_sha256(payload, deadline=deadline, adb_sha256=adb_sha256, sensitive_values=sensitive_values, cwd=repository)
    remote_apk = _remote_base_apk(payload, deadline=deadline, adb_sha256=adb_sha256, sensitive_values=sensitive_values, cwd=repository)
    apk_sha256 = _remote_apk_sha256(payload, remote_apk, deadline=deadline, adb_sha256=adb_sha256, sensitive_values=sensitive_values, cwd=repository)
    installed = _installed_package_identity(payload, deadline=deadline, adb_sha256=adb_sha256, sensitive_values=sensitive_values, cwd=repository)
    thermal_status = _thermal_status(payload, deadline=deadline, adb_sha256=adb_sha256, sensitive_values=sensitive_values, cwd=repository)
    source_sha256 = fingerprint_snapshot(authority_snapshot)["authority_sha256"]
    actual_identity = {
        "required": True,
        "device_alias": device_alias(payload["device_serial"]),
        "android_user": payload["android_user"],
        "package_id": payload["package_id"],
        "build_identity": {
            "version_code": installed["version_code"],
            "version_name": installed["version_name"],
            "apk_sha256": apk_sha256,
            "source_sha256": source_sha256,
        },
        "signer_sha256": installed["signer_sha256"],
        "firmware_sha256": firmware_sha256,
    }
    requested_identity = {
        "device_alias": device_alias(payload["device_serial"]),
        "android_user": payload["android_user"],
        "package_id": payload["package_id"],
        "firmware_sha256": payload["expected_firmware_sha256"],
    }
    if any(frozen.get(key) != value for key, value in requested_identity.items()) or frozen != actual_identity:
        raise IdentityMismatch("live device/package/build/signer identity does not match the frozen target")
    confounders = [] if thermal_status == 0 else ["thermal_status_non_nominal"]
    record = create_record(
        "device",
        producer_role="android-device-lab",
        run_id=experiment_spec["run_id"],
        created_at=experiment_spec["created_at"],
        experiment_spec_sha256=experiment_spec["content_sha256"],
        links={"experiment_spec": experiment_spec["content_sha256"]},
        payload={
            "device_alias": actual_identity["device_alias"],
            "android_user": actual_identity["android_user"],
            "package_id": actual_identity["package_id"],
            "build_identity": actual_identity["build_identity"],
            "signer_sha256": actual_identity["signer_sha256"],
            "firmware_fingerprint_sha256": actual_identity["firmware_sha256"],
            "requested_configuration": {"inventory_mode": "read_only"},
            "actual_configuration": {"inventory_mode": "read_only"},
            "thermals": {"status": "NOMINAL" if not confounders else "CONFOUNDED", "max_status": thermal_status},
            "status": "COMPLETE" if not confounders else "BLOCKED",
            "confounders": confounders,
        },
    )
    store.put(record)
    return {
        "records": [record],
        "result": {
            "device_alias": actual_identity["device_alias"],
            "android_user": actual_identity["android_user"],
            "package_id": actual_identity["package_id"],
            "inventory_mode": "read_only",
        },
    }


def _thermal_status(
    payload: dict[str, Any],
    *,
    deadline: _Deadline,
    adb_sha256: str,
    sensitive_values: Sequence[str],
    cwd: Path,
) -> int:
    result = _adb(
        payload,
        ["shell", "cmd", "thermalservice", "get-status"],
        deadline=deadline,
        executable_sha256=adb_sha256,
        sensitive_values=sensitive_values,
        cwd=cwd,
    )
    values = re.findall(r"(?i)(?:thermal\s+status\s*:\s*)?([0-6])", result.stdout.strip())
    if len(values) != 1:
        raise EvidenceBlocked("thermal status evidence is unavailable")
    return int(values[0])


def _remote_base_apk(
    payload: dict[str, Any],
    *,
    deadline: _Deadline,
    adb_sha256: str,
    sensitive_values: Sequence[str],
    cwd: Path,
) -> str:
    result = _adb(
        payload,
        ["shell", "pm", "path", "--user", str(payload["android_user"]), payload["package_id"]],
        deadline=deadline,
        executable_sha256=adb_sha256,
        sensitive_values=sensitive_values,
        cwd=cwd,
    )
    paths = [line.removeprefix("package:").strip() for line in result.stdout.splitlines() if line.startswith("package:")]
    base_paths = [path for path in paths if _REMOTE_BASE_APK.fullmatch(path)]
    if len(base_paths) != 1:
        raise EvidenceBlocked("installed base APK path is unavailable or unsafe")
    return base_paths[0]


def _remote_size(
    payload: dict[str, Any],
    remote_path: str,
    *,
    deadline: _Deadline,
    adb_sha256: str,
    sensitive_values: Sequence[str],
    cwd: Path,
) -> int:
    result = _adb(
        payload,
        ["shell", "stat", "-c", "%s", remote_path],
        deadline=deadline,
        executable_sha256=adb_sha256,
        sensitive_values=[*sensitive_values, remote_path],
        cwd=cwd,
    )
    try:
        size = int(result.stdout.strip())
    except ValueError as exc:
        raise EvidenceBlocked("installed APK size evidence is unavailable") from exc
    if size <= 0 or size > payload["max_storage_bytes"]:
        raise EvidenceBlocked("installed APK exceeds the storage budget")
    return size


def _pull_installed_apk(
    payload: dict[str, Any],
    destination: Path,
    *,
    deadline: _Deadline,
    adb_sha256: str,
    sensitive_values: Sequence[str],
    cwd: Path,
) -> tuple[str, int]:
    remote_path = _remote_base_apk(
        payload,
        deadline=deadline,
        adb_sha256=adb_sha256,
        sensitive_values=sensitive_values,
        cwd=cwd,
    )
    expected_size = _remote_size(
        payload,
        remote_path,
        deadline=deadline,
        adb_sha256=adb_sha256,
        sensitive_values=sensitive_values,
        cwd=cwd,
    )
    _adb(
        payload,
        ["pull", remote_path, str(destination)],
        deadline=deadline,
        executable_sha256=adb_sha256,
        sensitive_values=[*sensitive_values, remote_path, str(destination)],
        cwd=cwd,
    )
    digest, actual_size = _sha256_file(destination, maximum_bytes=payload["max_storage_bytes"])
    if actual_size != expected_size:
        raise EvidenceBlocked("installed APK changed while it was captured")
    return digest, actual_size


def _device_snapshot(
    payload: dict[str, Any],
    installed_copy: Path,
    *,
    deadline: _Deadline,
    tool_sha256s: dict[str, str],
    sensitive_values: Sequence[str],
    cwd: Path,
) -> dict[str, Any]:
    _online_target(
        payload,
        deadline=deadline,
        adb_sha256=tool_sha256s["adb"],
        sensitive_values=sensitive_values,
        cwd=cwd,
    )
    _active_user(
        payload,
        deadline=deadline,
        adb_sha256=tool_sha256s["adb"],
        sensitive_values=sensitive_values,
        cwd=cwd,
    )
    _installed_for_user(
        payload,
        deadline=deadline,
        adb_sha256=tool_sha256s["adb"],
        sensitive_values=sensitive_values,
        cwd=cwd,
    )
    firmware_sha256 = _firmware_sha256(
        payload,
        deadline=deadline,
        adb_sha256=tool_sha256s["adb"],
        sensitive_values=sensitive_values,
        cwd=cwd,
    )
    thermal_status = _thermal_status(
        payload,
        deadline=deadline,
        adb_sha256=tool_sha256s["adb"],
        sensitive_values=sensitive_values,
        cwd=cwd,
    )
    apk_sha256, apk_size = _pull_installed_apk(
        payload,
        installed_copy,
        deadline=deadline,
        adb_sha256=tool_sha256s["adb"],
        sensitive_values=sensitive_values,
        cwd=cwd,
    )
    metadata = _apk_metadata(
        payload,
        installed_copy,
        deadline=deadline,
        aapt_sha256=tool_sha256s["aapt"],
        sensitive_values=[*sensitive_values, str(installed_copy)],
        cwd=cwd,
    )
    signer_sha256 = _apk_signer(
        payload,
        installed_copy,
        deadline=deadline,
        apksigner_sha256=tool_sha256s["apksigner"],
        sensitive_values=[*sensitive_values, str(installed_copy)],
        cwd=cwd,
    )
    return {
        "firmware_sha256": firmware_sha256,
        "thermal_status": thermal_status,
        "apk_sha256": apk_sha256,
        "apk_size": apk_size,
        "metadata": metadata,
        "signer_sha256": signer_sha256,
    }


def _validate_spec_identity(
    payload: dict[str, Any],
    experiment_spec: dict[str, Any],
    *,
    apk_sha256: str,
    source_sha256: str,
) -> None:
    frozen = experiment_spec.get("device_requirements")
    if not isinstance(frozen, dict) or frozen.get("required") is not True:
        raise EvidenceBlocked("build/install requires a fully frozen device identity")
    expected_build = {
        "version_code": payload["expected_build_identity"]["version_code"],
        "version_name": payload["expected_build_identity"]["version_name"],
        "apk_sha256": apk_sha256,
        "source_sha256": source_sha256,
    }
    expected = {
        "required": True,
        "device_alias": device_alias(payload["device_serial"]),
        "android_user": payload["android_user"],
        "package_id": payload["package_id"],
        "build_identity": expected_build,
        "signer_sha256": payload["expected_signer_sha256"],
        "firmware_sha256": payload["expected_firmware_sha256"],
    }
    if frozen != expected:
        raise IdentityMismatch("build/install request does not match the frozen device/build identity")
    budgets = experiment_spec.get("budgets")
    if not isinstance(budgets, dict):
        raise EvidenceBlocked("frozen experiment budgets are unavailable")
    for key in ("max_duration_seconds", "max_storage_bytes"):
        frozen_value = budgets.get(key)
        if not isinstance(frozen_value, int) or isinstance(frozen_value, bool) or frozen_value <= 0:
            raise EvidenceBlocked("frozen experiment budgets are unavailable")
        if payload[key] > frozen_value:
            raise IdentityMismatch("build/install request exceeds the frozen experiment budget")


def execute(
    operation_id: str,
    payload: dict[str, Any],
    *,
    store: RecordStore,
    experiment_spec: dict[str, Any],
    repository: Path,
    authority_snapshot: AuthoritySnapshot,
) -> dict[str, Any]:
    if operation_id == "pl_device_inventory":
        return _execute_inventory(
            payload,
            store=store,
            experiment_spec=experiment_spec,
            repository=repository,
            authority_snapshot=authority_snapshot,
        )
    if operation_id != "pl_build_install":
        raise EvidenceBlocked("unknown device operation")
    if not _PACKAGE_ID.fullmatch(payload["package_id"]):
        raise InvalidInput("package ID is invalid")

    deadline = _Deadline(payload["max_duration_seconds"])
    # Resolve and fingerprint every prerequisite before any device interaction.
    tool_sha256s = {
        "adb": _executable_sha256(payload["adb_executable"]),
        "aapt": _executable_sha256(payload["aapt_executable"]),
        "apksigner": _executable_sha256(payload["apksigner_executable"]),
    }
    deadline.check()

    try:
        target_apk = Path(payload["apk_path"]).resolve(strict=True)
    except OSError as exc:
        raise EvidenceBlocked("target APK is unavailable") from exc
    target_apk_sha256, target_apk_size = _sha256_file(
        target_apk,
        maximum_bytes=payload["max_storage_bytes"],
    )
    source_sha256 = fingerprint_snapshot(authority_snapshot)["authority_sha256"]
    _validate_spec_identity(
        payload,
        experiment_spec,
        apk_sha256=target_apk_sha256,
        source_sha256=source_sha256,
    )
    sensitive_values = (payload["device_serial"], str(target_apk))
    target_metadata = _apk_metadata(
        payload,
        target_apk,
        deadline=deadline,
        aapt_sha256=tool_sha256s["aapt"],
        sensitive_values=sensitive_values,
        cwd=repository,
    )
    if target_metadata != {
        "package_id": payload["package_id"],
        "version_code": payload["expected_build_identity"]["version_code"],
        "version_name": payload["expected_build_identity"]["version_name"],
    }:
        raise IdentityMismatch("target APK package/build identity does not match the request")
    target_signer = _apk_signer(
        payload,
        target_apk,
        deadline=deadline,
        apksigner_sha256=tool_sha256s["apksigner"],
        sensitive_values=sensitive_values,
        cwd=repository,
    )
    if target_signer != payload["expected_signer_sha256"]:
        raise IdentityMismatch("target APK signer does not match the frozen signer")

    # Both installed APK captures live under a temporary directory owned by the
    # already-verified experiment. No device mutation occurs before all checks.
    store.experiment.owned_path("temporary")
    with tempfile.TemporaryDirectory(prefix=".pl-build-install-", dir=store.experiment.root) as temp_name:
        temp_root = Path(temp_name)
        pre_copy = temp_root / "installed-before.apk"
        post_copy = temp_root / "installed-after.apk"
        before = _device_snapshot(
            payload,
            pre_copy,
            deadline=deadline,
            tool_sha256s=tool_sha256s,
            sensitive_values=[*sensitive_values, str(temp_root)],
            cwd=repository,
        )
        if before["firmware_sha256"] != payload["expected_firmware_sha256"]:
            raise IdentityMismatch("device firmware does not match the frozen fingerprint")
        if before["metadata"]["package_id"] != payload["package_id"]:
            raise IdentityMismatch("installed package identity does not match the target")
        if before["signer_sha256"] != payload["expected_signer_sha256"]:
            raise IdentityMismatch("installed signer does not match the frozen signer")
        if before["metadata"]["version_code"] > target_metadata["version_code"]:
            raise IdentityMismatch("update-only installation refuses a downgrade")
        if before["thermal_status"] != 0:
            raise EvidenceBlocked("device thermal state confounds installation evidence")

        # Re-read the input after every preflight step so a replaced APK cannot
        # cross the final mutation boundary under an earlier approved hash.
        final_target_sha256, final_target_size = _sha256_file(
            target_apk,
            maximum_bytes=payload["max_storage_bytes"],
        )
        if (final_target_sha256, final_target_size) != (target_apk_sha256, target_apk_size):
            raise EvidenceBlocked("target APK changed after preflight")

        install = _adb(
            payload,
            ["install", "--user", str(payload["android_user"]), "-r", str(target_apk)],
            deadline=deadline,
            executable_sha256=tool_sha256s["adb"],
            sensitive_values=[*sensitive_values, str(temp_root)],
            cwd=repository,
        )
        if [line.strip() for line in install.stdout.splitlines() if line.strip()] != ["Success"]:
            raise EvidenceBlocked("ADB did not return an unambiguous install success result")

        after = _device_snapshot(
            payload,
            post_copy,
            deadline=deadline,
            tool_sha256s=tool_sha256s,
            sensitive_values=[*sensitive_values, str(temp_root)],
            cwd=repository,
        )

    expected_post = {
        "package_id": payload["package_id"],
        "version_code": target_metadata["version_code"],
        "version_name": target_metadata["version_name"],
    }
    if after["metadata"] != expected_post:
        raise IdentityMismatch("post-install package/build identity does not match the target APK")
    if after["apk_sha256"] != target_apk_sha256:
        raise IdentityMismatch("post-install APK hash does not match the target APK")
    if after["signer_sha256"] != payload["expected_signer_sha256"]:
        raise IdentityMismatch("post-install signer does not match the frozen signer")
    if after["firmware_sha256"] != payload["expected_firmware_sha256"]:
        raise IdentityMismatch("device firmware changed during installation")

    build_identity = {
        "version_code": target_metadata["version_code"],
        "version_name": target_metadata["version_name"],
        "apk_sha256": target_apk_sha256,
        "source_sha256": source_sha256,
    }
    build_identity_sha256 = domain_hash("android-build-identity", build_identity)
    post_confounded = after["thermal_status"] != 0
    confounders = ["post_install_thermal_state"] if post_confounded else []
    record = create_record(
        "device",
        producer_role="android-device-lab",
        run_id=experiment_spec["run_id"],
        created_at=experiment_spec["created_at"],
        experiment_spec_sha256=experiment_spec["content_sha256"],
        links={"experiment_spec": experiment_spec["content_sha256"]},
        payload={
            "device_alias": device_alias(payload["device_serial"]),
            "android_user": payload["android_user"],
            "package_id": payload["package_id"],
            "build_identity": build_identity,
            "signer_sha256": after["signer_sha256"],
            "firmware_fingerprint_sha256": after["firmware_sha256"],
            "requested_configuration": {
                "install_mode": "update_only",
                "build_identity": build_identity,
                "build_identity_sha256": build_identity_sha256,
                "signer_sha256": payload["expected_signer_sha256"],
                "firmware_fingerprint_sha256": payload["expected_firmware_sha256"],
                "max_duration_seconds": payload["max_duration_seconds"],
                "max_storage_bytes": payload["max_storage_bytes"],
            },
            "actual_configuration": {
                "install_mode": "update_only",
                "package_preexisting": True,
                "preinstall_build_identity": before["metadata"],
                "preinstall_apk_sha256": before["apk_sha256"],
                "preinstall_apk_size_bytes": before["apk_size"],
                "postinstall_build_identity": after["metadata"],
                "postinstall_apk_sha256": after["apk_sha256"],
                "postinstall_apk_size_bytes": after["apk_size"],
                "target_apk_size_bytes": target_apk_size,
                "build_identity_sha256": build_identity_sha256,
                "tool_sha256s": tool_sha256s,
            },
            "thermals": {
                "status": "CONFOUNDED" if post_confounded else "NOMINAL",
                "pre_install_status": before["thermal_status"],
                "post_install_status": after["thermal_status"],
            },
            "status": "BLOCKED" if post_confounded else "COMPLETE",
            "confounders": confounders,
        },
    )
    store.put(record)
    return {
        "records": [record],
        "result": {
            "device_sha256": record["content_sha256"],
            "device_alias": record["payload"]["device_alias"],
            "installation": "updated",
            "mutation_performed": True,
            "build_identity_sha256": build_identity_sha256,
        },
    }
