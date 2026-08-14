"""HDR, PTS, pair, and corpus quality planning."""

from __future__ import annotations

from copy import deepcopy
import hashlib
import math
import os
from pathlib import Path
import shutil
import stat
import sys
import tempfile
import time
from typing import Any

from ..authority import AuthoritySnapshot
from ..canonical import domain_hash
from ..errors import EvidenceBlocked, ImmutableConflict, InvalidInput, MissingPrerequisite
from ..jsonio import canonical_json_file_bytes, loads_strict
from ..paths import assert_no_reparse_points
from ..process import ProcessBudget, run_bounded
from ..records import RecordStore, create_record
from ..specification import corpus_manifest_snapshot, host_scorer_contract


_SCORER_PATH = "scripts/diagnostics/measure_quality.py"
_OUTPUT_CAPTURE_BYTES = 1024 * 1024
_EVALUATION_RECORD_RESERVE_BYTES = 4096


def plan(operation_id: str, payload: dict[str, Any]) -> dict[str, Any]:
    if operation_id == "pl_hdr_compare":
        return {"comparison": "MATCH" if payload["reference"] == payload["candidate"] else "MISMATCH", "tone_map_substitution": False}
    if operation_id == "pl_pts_compare":
        reference = payload["reference_pts_us"]
        candidate = payload["candidate_pts_us"]
        first_skew = candidate[0] - reference[0]
        return {"reference_frames": len(reference), "candidate_frames": len(candidate), "first_skew_us": first_skew, "mode": "leading_drop_only"}
    if operation_id == "pl_score_pair":
        return {"pair_id": payload["pair_id"], "source_alias": Path(payload["source_path"]).name, "output_alias": Path(payload["output_path"]).name, "model_id": payload["model_id"], "serial_execution": True}
    return {"manifest_alias": Path(payload["corpus_manifest_path"]).name, "model_id": payload["model_id"], "candidate_budget": payload["max_candidates"], "worker_budget": payload["max_workers"], "serial_execution": True}


def _input_evaluation(
    store: RecordStore,
    payload: dict[str, Any],
    *,
    experiment_spec_sha256: str,
    run_id: str,
) -> dict[str, Any]:
    try:
        record = store.find(payload["input_evaluation_sha256"])
    except (InvalidInput, ImmutableConflict, OSError) as exc:
        raise EvidenceBlocked("pure comparison input evaluation is unavailable") from exc
    if record["record_type"] != "evaluation":
        raise EvidenceBlocked("pure comparison input is not an evaluation record")
    if record["payload"].get("evaluation_kind") != "subject_quality":
        raise EvidenceBlocked("pure comparisons require a subject-quality evaluation")
    if record["experiment_spec_sha256"] != experiment_spec_sha256 or record["run_id"] != run_id:
        raise EvidenceBlocked("pure comparison input crosses a run or specification")
    return record


def _stat_identity(value: os.stat_result) -> tuple[int, int, int, int]:
    return (
        value.st_dev,
        value.st_ino,
        value.st_size,
        getattr(value, "st_mtime_ns", int(value.st_mtime * 1_000_000_000)),
    )


def _stable_file(
    path_value: str | Path,
    *,
    reject_hardlinks: bool,
    label: str,
    deadline: float | None = None,
) -> tuple[Path, str, int]:
    candidate = Path(path_value).absolute()
    try:
        assert_no_reparse_points(candidate)
        before = os.lstat(candidate)
        if not stat.S_ISREG(before.st_mode):
            raise OSError("not a regular file")
        if reject_hardlinks and getattr(before, "st_nlink", 1) != 1:
            raise OSError("multiple hard links")
        flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
        descriptor = os.open(candidate, flags)
        try:
            handle_before = os.fstat(descriptor)
            if reject_hardlinks and getattr(handle_before, "st_nlink", 1) != 1:
                raise OSError("multiple hard links")
            digest = hashlib.sha256()
            size = 0
            while True:
                chunk = os.read(descriptor, 1024 * 1024)
                if not chunk:
                    break
                digest.update(chunk)
                size += len(chunk)
                if deadline is not None and time.monotonic() >= deadline:
                    raise OSError("duration budget exceeded while hashing")
            handle_after = os.fstat(descriptor)
        finally:
            os.close(descriptor)
        after = os.lstat(candidate)
        if len({_stat_identity(item) for item in (before, handle_before, handle_after, after)}) != 1:
            raise OSError("identity changed while hashing")
        if reject_hardlinks and getattr(after, "st_nlink", 1) != 1:
            raise OSError("multiple hard links")
        assert_no_reparse_points(candidate)
        return candidate.resolve(strict=True), digest.hexdigest(), size
    except (OSError, InvalidInput) as exc:
        raise EvidenceBlocked(f"{label} is unavailable or unstable") from exc


def _stable_file_bytes(
    path_value: str | Path,
    *,
    maximum_bytes: int,
    label: str,
    deadline: float | None = None,
) -> tuple[bytes, str]:
    candidate = Path(path_value).absolute()
    try:
        assert_no_reparse_points(candidate)
        before = os.lstat(candidate)
        if not stat.S_ISREG(before.st_mode) or getattr(before, "st_nlink", 1) != 1 or before.st_size > maximum_bytes:
            raise OSError("file type, link count, or size is invalid")
        flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
        descriptor = os.open(candidate, flags)
        try:
            handle_before = os.fstat(descriptor)
            if getattr(handle_before, "st_nlink", 1) != 1 or handle_before.st_size > maximum_bytes:
                raise OSError("file link count or size is invalid")
            chunks: list[bytes] = []
            size = 0
            digest = hashlib.sha256()
            while True:
                chunk = os.read(descriptor, min(1024 * 1024, maximum_bytes + 1 - size))
                if not chunk:
                    break
                size += len(chunk)
                if size > maximum_bytes:
                    raise OSError("file exceeds byte budget")
                chunks.append(chunk)
                digest.update(chunk)
                if deadline is not None and time.monotonic() >= deadline:
                    raise OSError("duration budget exceeded while reading")
            handle_after = os.fstat(descriptor)
        finally:
            os.close(descriptor)
        after = os.lstat(candidate)
        if len({_stat_identity(item) for item in (before, handle_before, handle_after, after)}) != 1 or size != after.st_size or getattr(after, "st_nlink", 1) != 1:
            raise OSError("file identity changed while reading")
        assert_no_reparse_points(candidate)
        return b"".join(chunks), digest.hexdigest()
    except (OSError, InvalidInput) as exc:
        raise EvidenceBlocked(f"{label} is unavailable, oversized, or unstable") from exc


def _resolve_tool(value: str, *, deadline: float | None = None) -> tuple[Path, str]:
    candidate = Path(value)
    if candidate.is_absolute() or candidate.parent != Path("."):
        resolved = candidate.absolute()
    else:
        located = shutil.which(value)
        if not located:
            raise MissingPrerequisite("required scoring executable is unavailable")
        resolved = Path(located).absolute()
    try:
        path, digest, _ = _stable_file(resolved, reject_hardlinks=False, label="scoring executable", deadline=deadline)
    except EvidenceBlocked as exc:
        if deadline is not None and time.monotonic() >= deadline:
            raise EvidenceBlocked("scoring tool fingerprint exceeded the corpus duration budget") from exc
        raise MissingPrerequisite("required scoring executable cannot be fingerprinted") from exc
    return path, digest


def _exact_lineage_for_pair(
    store: RecordStore,
    source_path: Path,
    source_sha256: str,
    source_size: int,
    output_path: Path,
    output_sha256: str,
    output_size: int,
    *,
    experiment_spec_sha256: str,
    run_id: str,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any], dict[str, Any]]:
    artifacts = store.all_of_type("artifact")

    def artifact_for(path: Path, digest: str, size: int, label: str) -> dict[str, Any]:
        matches: list[dict[str, Any]] = []
        for record in artifacts:
            if record["run_id"] != run_id or record["experiment_spec_sha256"] != experiment_spec_sha256:
                continue
            payload = record["payload"]
            if payload["sha256"] != digest or payload["size_bytes"] != size:
                continue
            matches.append(record)
        if len(matches) != 1:
            raise EvidenceBlocked(f"{label} does not resolve to exactly one immutable artifact")
        from .report import verify_artifact_bytes

        verify_artifact_bytes(store, matches)
        if matches[0]["payload"].get("status") != "COMPLETE" or matches[0]["payload"].get("confounders"):
            raise EvidenceBlocked(f"{label} artifact is blocked or confounded")
        return matches[0]

    source_artifact = artifact_for(source_path, source_sha256, source_size, "source")
    output_artifact = artifact_for(output_path, output_sha256, output_size, "output")
    subjects: list[dict[str, Any]] = []
    for record_type in ("baseline", "candidate"):
        for record in store.all_of_type(record_type):
            if (
                record["run_id"] == run_id
                and record["experiment_spec_sha256"] == experiment_spec_sha256
                and record["payload"]["artifact_sha256"] == output_artifact["content_sha256"]
                and record["links"].get("artifact") == output_artifact["content_sha256"]
            ):
                subjects.append(record)
    if len(subjects) != 1:
        raise EvidenceBlocked("output artifact does not resolve to exactly one evaluation subject")
    subject = subjects[0]
    if subject["payload"].get("status") != "COMPLETE" or subject["payload"].get("confounders"):
        raise EvidenceBlocked("evaluation subject is blocked or confounded")
    source_subjects = [
        record
        for record in store.all_of_type("baseline")
        if record["run_id"] == run_id
        and record["experiment_spec_sha256"] == experiment_spec_sha256
        and record["payload"]["artifact_sha256"] == source_artifact["content_sha256"]
        and record["links"].get("artifact") == source_artifact["content_sha256"]
    ]
    if subject["record_type"] == "candidate" and len(source_subjects) != 1:
        raise EvidenceBlocked("candidate scoring requires exactly one frozen baseline source")
    device_sha256 = subject["links"].get("device")
    try:
        device = store.find(device_sha256)
    except (InvalidInput, ImmutableConflict) as exc:
        raise EvidenceBlocked("evaluation device lineage is unavailable") from exc
    if device["record_type"] != "device" or device["run_id"] != run_id or device["experiment_spec_sha256"] != experiment_spec_sha256:
        raise EvidenceBlocked("evaluation device lineage is invalid")
    if device["payload"].get("status") != "COMPLETE" or device["payload"].get("confounders"):
        raise EvidenceBlocked("evaluation device evidence is blocked or confounded")
    if source_subjects and source_subjects[0]["links"].get("device") != device["content_sha256"]:
        raise EvidenceBlocked("baseline and evaluated subject use different devices")
    return source_artifact, output_artifact, subject, device


def _finite_metric(report: dict[str, Any], key: str) -> float:
    value = report.get(key)
    if not isinstance(value, (int, float)) or isinstance(value, bool) or not math.isfinite(float(value)):
        raise EvidenceBlocked("scorer report contains an invalid metric")
    return float(value)


def _scorer_structure(report: dict[str, Any]) -> dict[str, int]:
    structural = report.get("structural_validation")
    cadence = structural.get("cadence") if isinstance(structural, dict) else None
    source = cadence.get("source") if isinstance(cadence, dict) else None
    output = cadence.get("output") if isinstance(cadence, dict) else None
    if not isinstance(structural, dict) or structural.get("passed") is not True or not isinstance(source, dict) or not isinstance(output, dict):
        raise EvidenceBlocked("repository scorer structural evidence is incomplete")
    result: dict[str, int] = {}
    for side, value in (("source", source), ("output", output)):
        frame_count = value.get("decoded_frame_count")
        duration_seconds = value.get("frame_timeline_duration_seconds")
        if not isinstance(frame_count, int) or isinstance(frame_count, bool) or frame_count <= 0:
            raise EvidenceBlocked("repository scorer frame-count evidence is invalid")
        if not isinstance(duration_seconds, (int, float)) or isinstance(duration_seconds, bool) or not math.isfinite(float(duration_seconds)) or duration_seconds <= 0:
            raise EvidenceBlocked("repository scorer duration evidence is invalid")
        result[f"{side}_frame_count"] = frame_count
        result[f"{side}_duration_ms"] = int(round(float(duration_seconds) * 1000.0))
    return result


def _execute_score_pair(
    payload: dict[str, Any],
    *,
    store: RecordStore,
    experiment_spec: dict[str, Any],
    repository: Path,
    authority_snapshot: AuthoritySnapshot,
    publish: bool = True,
    resolved_tools: dict[str, tuple[Path, str]] | None = None,
    corpus_record: dict[str, Any] | None = None,
    expected_media: dict[str, Any] | None = None,
    deadline: float | None = None,
) -> dict[str, Any]:
    def require_time() -> int:
        return payload["max_duration_seconds"] if deadline is None else min(
            payload["max_duration_seconds"],
            _remaining_corpus_seconds(deadline),
        )

    require_time()
    frozen_budgets = experiment_spec["budgets"]
    for key in ("max_duration_seconds", "max_storage_bytes"):
        if payload[key] > frozen_budgets[key]:
            raise InvalidInput(f"{key} exceeds the frozen experiment budget")
    metrics = [item for item in experiment_spec["metrics"] if item["model_id"] == payload["model_id"]]
    if len(metrics) != 1:
        raise EvidenceBlocked("requested metric model is not uniquely frozen")
    metric = metrics[0]
    source_path, source_sha256, source_size = _stable_file(payload["source_path"], reject_hardlinks=True, label="source media", deadline=deadline)
    output_path, output_sha256, output_size = _stable_file(payload["output_path"], reject_hardlinks=True, label="output media", deadline=deadline)
    require_time()
    if expected_media is not None:
        expected_fields = {
            "source_path", "source_sha256", "source_size_bytes",
            "output_path", "output_sha256", "output_size_bytes",
        }
        if set(expected_media) != expected_fields or (
            source_sha256,
            source_size,
            output_sha256,
            output_size,
        ) != (
            expected_media["source_sha256"],
            expected_media["source_size_bytes"],
            expected_media["output_sha256"],
            expected_media["output_size_bytes"],
        ):
            raise EvidenceBlocked("pair media do not match their frozen corpus identities")
    source_artifact: dict[str, Any] | None = None
    output_artifact: dict[str, Any] | None = None
    subject: dict[str, Any] | None = None
    device: dict[str, Any] | None = None
    if corpus_record is None:
        source_artifact, output_artifact, subject, device = _exact_lineage_for_pair(
            store,
            source_path,
            source_sha256,
            source_size,
            output_path,
            output_sha256,
            output_size,
            experiment_spec_sha256=experiment_spec["content_sha256"],
            run_id=experiment_spec["run_id"],
        )
    else:
        if experiment_spec["device_requirements"].get("required") is not False:
            raise EvidenceBlocked("host corpus calibration is forbidden when a device is required")
        if (
            corpus_record.get("record_type") != "corpus"
            or corpus_record.get("run_id") != experiment_spec["run_id"]
            or corpus_record.get("experiment_spec_sha256") != experiment_spec["content_sha256"]
            or corpus_record.get("links") != {"experiment_spec": experiment_spec["content_sha256"]}
            or corpus_record.get("payload") != experiment_spec["corpus"]
        ):
            raise EvidenceBlocked("corpus calibration lineage does not match the frozen corpus")

    scorer_bytes = authority_snapshot.files.get(_SCORER_PATH)
    if scorer_bytes is None:
        raise EvidenceBlocked("repository scorer is absent from the frozen authority snapshot")
    scorer_sha256 = hashlib.sha256(scorer_bytes).hexdigest()
    live_metric_contract = host_scorer_contract(scorer_bytes)
    expected_model_provenance = live_metric_contract["provenance"]
    if (
        any(metric.get(key) != live_metric_contract[key] for key in ("metric_id", "version", "model_id"))
        or
        metric.get("provenance") != expected_model_provenance
        or metric.get("model_sha256")
        != domain_hash("host-libvmaf-model", {"model_id": metric["model_id"], **expected_model_provenance})
    ):
        raise EvidenceBlocked("repository scorer cannot authenticate the frozen metric model")
    scorer_path, live_scorer_sha256, _ = _stable_file(repository / _SCORER_PATH, reject_hardlinks=False, label="repository scorer", deadline=deadline)
    if live_scorer_sha256 != scorer_sha256:
        raise EvidenceBlocked("repository scorer drifted after authority capture")
    if resolved_tools is None:
        resolved_tools = {
            "python": _resolve_tool(sys.executable, deadline=deadline),
            "ffmpeg": _resolve_tool(payload["ffmpeg_executable"], deadline=deadline),
            "ffprobe": _resolve_tool(payload["ffprobe_executable"], deadline=deadline),
        }
    if set(resolved_tools) != {"python", "ffmpeg", "ffprobe"}:
        raise EvidenceBlocked("resolved scorer tool set is incomplete")
    python_path, python_sha256 = resolved_tools["python"]
    ffmpeg_path, ffmpeg_sha256 = resolved_tools["ffmpeg"]
    ffprobe_path, ffprobe_sha256 = resolved_tools["ffprobe"]
    for label, (tool_path, expected_sha256) in resolved_tools.items():
        _, observed_sha256, _ = _stable_file(tool_path, reject_hardlinks=False, label=f"{label} executable", deadline=deadline)
        if observed_sha256 != expected_sha256:
            raise EvidenceBlocked("resolved scoring tool changed before measurement")
    scorer_timeout = require_time()

    temp_root = store.experiment.owned_path("temp", "quality")
    temp_root.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="score-pair-", dir=temp_root) as temp_dir:
        report_path = Path(temp_dir) / f"{payload['pair_id']}.json"
        argv = [
            str(python_path),
            str(scorer_path),
            "--source", str(source_path),
            "--output", str(output_path),
            "--pair-id", payload["pair_id"],
            "--ffmpeg", str(ffmpeg_path),
            "--ffprobe", str(ffprobe_path),
            "--json", str(report_path),
            "--threads", "1",
            "--chunk-frames", "0",
            "--timeout", str(scorer_timeout),
        ]
        process = run_bounded(
            argv,
            budget=ProcessBudget(
                timeout_seconds=scorer_timeout,
                max_output_bytes=min(payload["max_storage_bytes"], _OUTPUT_CAPTURE_BYTES),
            ),
            cwd=repository,
            expected_executable_sha256=python_sha256,
            sensitive_values=(str(source_path), str(output_path), str(report_path), str(temp_root)),
        )
        if process.output_truncated:
            raise EvidenceBlocked("scorer output exceeded its capture budget")
        if process.return_code != 0:
            raise EvidenceBlocked("repository scorer did not produce complete evidence")
        report_bytes, _ = _stable_file_bytes(
            report_path,
            maximum_bytes=payload["max_storage_bytes"],
            label="repository scorer report",
            deadline=deadline,
        )
        try:
            report = loads_strict(report_bytes.decode("utf-8"))
        except (UnicodeError, InvalidInput) as exc:
            raise EvidenceBlocked("repository scorer report JSON is invalid") from exc
    require_time()

    _, scorer_after, _ = _stable_file(scorer_path, reject_hardlinks=False, label="repository scorer", deadline=deadline)
    _, ffmpeg_after, _ = _stable_file(ffmpeg_path, reject_hardlinks=False, label="ffmpeg executable", deadline=deadline)
    _, ffprobe_after, _ = _stable_file(ffprobe_path, reject_hardlinks=False, label="ffprobe executable", deadline=deadline)
    source_after = _stable_file(source_path, reject_hardlinks=True, label="source media", deadline=deadline)
    output_after = _stable_file(output_path, reject_hardlinks=True, label="output media", deadline=deadline)
    if scorer_after != scorer_sha256 or ffmpeg_after != ffmpeg_sha256 or ffprobe_after != ffprobe_sha256:
        raise EvidenceBlocked("scoring executable changed during measurement")
    if source_after[1:] != (source_sha256, source_size) or output_after[1:] != (output_sha256, output_size):
        raise EvidenceBlocked("media changed during quality measurement")
    if expected_media is not None and (
        source_after[1],
        source_after[2],
        output_after[1],
        output_after[2],
    ) != (
        expected_media["source_sha256"],
        expected_media["source_size_bytes"],
        expected_media["output_sha256"],
        expected_media["output_size_bytes"],
    ):
        raise EvidenceBlocked("measured media no longer match their frozen corpus identities")
    require_time()

    if not isinstance(report, dict):
        raise EvidenceBlocked("repository scorer report root is invalid")
    harness = report.get("harness_provenance")
    privacy = report.get("privacy")
    tool_provenance = report.get("tool_provenance")
    if (
        report.get("schema_version") != 3
        or report.get("pair_id") != payload["pair_id"]
        or report.get("status") != "COMPLETE"
        or report.get("report_complete") is not True
        or report.get("measurement_valid") is not True
        or not isinstance(harness, dict)
        or harness.get("script_basename") != "measure_quality.py"
        or harness.get("script_sha256") != scorer_sha256
        or not isinstance(privacy, dict)
        or privacy.get("input_paths_recorded") is not False
        or privacy.get("input_basenames_recorded") is not False
        or not isinstance(tool_provenance, dict)
        or tool_provenance.get("vmaf_model") != metric["provenance"]["model_selector"]
    ):
        raise EvidenceBlocked("repository scorer report contract is incomplete or inconsistent")
    compared_frames = report.get("compared_frame_count")
    if not isinstance(compared_frames, int) or isinstance(compared_frames, bool) or compared_frames <= 0:
        raise EvidenceBlocked("repository scorer compared-frame count is invalid")
    metric_results = {
        "vmaf_mean": _finite_metric(report, "vmaf_mean"),
        "vmaf_p5": _finite_metric(report, "vmaf_5pct_low"),
        "vmaf_min": _finite_metric(report, "vmaf_min"),
        "compared_frames": compared_frames,
    }
    structure = _scorer_structure(report)
    report_sha256 = hashlib.sha256(report_bytes).hexdigest()
    evaluation_kind = "corpus_calibration" if corpus_record is not None else "subject_quality"
    subject_sha256 = corpus_record["content_sha256"] if corpus_record is not None else subject["content_sha256"]
    identity_input: dict[str, Any] = {
        "evaluation_kind": evaluation_kind,
        "experiment_spec_sha256": experiment_spec["content_sha256"],
        "pair_id": payload["pair_id"],
        "subject_sha256": subject_sha256,
        "report_sha256": report_sha256,
    }
    if source_artifact is not None:
        identity_input["source_artifact_sha256"] = source_artifact["content_sha256"]
    else:
        identity_input["source_content_sha256"] = source_sha256
        identity_input["output_content_sha256"] = output_sha256
    evaluation_id = "evaluation-" + domain_hash(
        "quality-evaluation-identity",
        identity_input,
    )[:24]
    links = (
        {"experiment_spec": experiment_spec["content_sha256"], "corpus": corpus_record["content_sha256"]}
        if corpus_record is not None
        else {
            "experiment_spec": experiment_spec["content_sha256"],
            "subject": subject["content_sha256"],
            "artifact": output_artifact["content_sha256"],
            "device": device["content_sha256"],
        }
    )
    provenance = {
        "metric_id": metric["metric_id"],
        "metric_version": metric["version"],
        "metric_model_sha256": metric["model_sha256"],
        "scorer_authority_sha256": scorer_sha256,
        "scorer_report_sha256": report_sha256,
        "python_executable_sha256": process.executable_sha256,
        "ffmpeg_executable_sha256": ffmpeg_sha256,
        "ffprobe_executable_sha256": ffprobe_sha256,
        "ffmpeg_version_sha256": domain_hash("tool-version", tool_provenance.get("ffmpeg")),
        "ffprobe_version_sha256": domain_hash("tool-version", tool_provenance.get("ffprobe")),
        "source_content_sha256": source_sha256,
        "output_content_sha256": output_sha256,
        **structure,
    }
    if corpus_record is not None:
        provenance.update({
            "corpus_manifest_sha256": experiment_spec["corpus"]["manifest_sha256"],
            "corpus_pair_identities_sha256": experiment_spec["corpus"]["pair_identities_sha256"],
            "pair_id": payload["pair_id"],
            "source_path": expected_media["source_path"],
            "source_size_bytes": expected_media["source_size_bytes"],
            "output_path": expected_media["output_path"],
            "output_size_bytes": expected_media["output_size_bytes"],
        })
    else:
        provenance["reference_artifact_sha256"] = source_artifact["content_sha256"]
    record = create_record(
        "evaluation",
        producer_role="perceptual-evaluator",
        run_id=experiment_spec["run_id"],
        created_at=experiment_spec["created_at"],
        experiment_spec_sha256=experiment_spec["content_sha256"],
        links=links,
        payload={
            "evaluation_kind": evaluation_kind,
            "evaluation_id": evaluation_id,
            "subject_sha256": subject_sha256,
            "metric_results": metric_results,
            "alignment": {"status": "NOT_APPLICABLE"},
            "hdr": {"status": "NOT_APPLICABLE"},
            "provenance": provenance,
            "status": "COMPLETE",
            "confounders": [],
        },
    )
    if publish:
        store.put(record)
    return {
        "records": [record],
        "result": {
            "evaluation_sha256": record["content_sha256"],
            "pair_id": payload["pair_id"],
            "metric_results": metric_results,
            "scorer_report_size_bytes": len(report_bytes),
            "serial_execution": True,
        },
    }


def _remaining_corpus_seconds(deadline: float) -> int:
    remaining = int(deadline - time.monotonic())
    if remaining < 1:
        raise EvidenceBlocked("serial corpus scoring exceeded its duration budget")
    return remaining


def _score_pair_preflight(
    payload: dict[str, Any],
    *,
    store: RecordStore,
    experiment_spec: dict[str, Any],
    repository: Path,
    authority_snapshot: AuthoritySnapshot,
) -> None:
    """Validate a direct pair request and every live prerequisite without measuring."""

    frozen = experiment_spec["budgets"]
    for key in ("max_duration_seconds", "max_storage_bytes"):
        if payload[key] > frozen[key]:
            raise InvalidInput(f"{key} exceeds the frozen experiment budget")
    if payload["max_storage_bytes"] <= _EVALUATION_RECORD_RESERVE_BYTES:
        raise EvidenceBlocked("pair evidence reserve exceeds the storage budget")
    deadline = time.monotonic() + payload["max_duration_seconds"]
    metrics = [item for item in experiment_spec["metrics"] if item["model_id"] == payload["model_id"]]
    if len(metrics) != 1:
        raise EvidenceBlocked("requested metric model is not uniquely frozen")
    metric = metrics[0]
    source_path, source_sha256, source_size = _stable_file(
        payload["source_path"], reject_hardlinks=True, label="source media", deadline=deadline
    )
    output_path, output_sha256, output_size = _stable_file(
        payload["output_path"], reject_hardlinks=True, label="output media", deadline=deadline
    )
    _exact_lineage_for_pair(
        store,
        source_path,
        source_sha256,
        source_size,
        output_path,
        output_sha256,
        output_size,
        experiment_spec_sha256=experiment_spec["content_sha256"],
        run_id=experiment_spec["run_id"],
    )
    scorer_bytes = authority_snapshot.files.get(_SCORER_PATH)
    if scorer_bytes is None:
        raise EvidenceBlocked("repository scorer is absent from the frozen authority snapshot")
    live_contract = host_scorer_contract(scorer_bytes)
    if (
        any(metric.get(key) != live_contract[key] for key in ("metric_id", "version", "model_id"))
        or metric.get("provenance") != live_contract["provenance"]
        or metric.get("model_sha256")
        != domain_hash("host-libvmaf-model", {"model_id": metric["model_id"], **live_contract["provenance"]})
    ):
        raise EvidenceBlocked("repository scorer cannot authenticate the frozen metric model")
    scorer_path, scorer_sha256, _ = _stable_file(
        repository / _SCORER_PATH,
        reject_hardlinks=False,
        label="repository scorer",
        deadline=deadline,
    )
    if scorer_sha256 != live_contract["provenance"]["host_scorer_sha256"]:
        raise EvidenceBlocked("repository scorer drifted after authority capture")
    resolved_tools = {
        "python": _resolve_tool(sys.executable, deadline=deadline),
        "ffmpeg": _resolve_tool(payload["ffmpeg_executable"], deadline=deadline),
        "ffprobe": _resolve_tool(payload["ffprobe_executable"], deadline=deadline),
    }
    for label, (tool_path, expected_sha256) in resolved_tools.items():
        _, observed_sha256, _ = _stable_file(
            tool_path, reject_hardlinks=False, label=f"{label} executable", deadline=deadline
        )
        if observed_sha256 != expected_sha256:
            raise EvidenceBlocked("resolved scoring tool changed during pair preflight")
    _remaining_corpus_seconds(deadline)


def _score_corpus_preflight(
    payload: dict[str, Any],
    *,
    store: RecordStore,
    experiment_spec: dict[str, Any],
    repository: Path,
    authority_snapshot: AuthoritySnapshot,
    corpus_record_override: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Resolve immutable corpus inputs and prerequisites without writing."""

    frozen = experiment_spec["budgets"]
    for key in ("max_candidates", "max_workers", "max_duration_seconds", "max_storage_bytes"):
        if payload[key] > frozen[key]:
            raise InvalidInput(f"{key} exceeds the frozen experiment budget")
    if payload["max_workers"] != 1 or frozen["max_workers"] != 1:
        raise InvalidInput("v1 corpus scoring enforces exactly one worker")
    if experiment_spec["device_requirements"].get("required") is not False:
        raise EvidenceBlocked("host corpus calibration is forbidden when a device is required")
    manifest_path = Path(payload["corpus_manifest_path"]).absolute()
    deadline = time.monotonic() + payload["max_duration_seconds"]

    identity, items = corpus_manifest_snapshot(
        manifest_path,
        max_items=payload["max_candidates"],
        max_total_bytes=payload["max_storage_bytes"],
        max_duration_seconds=_remaining_corpus_seconds(deadline),
    )
    if identity != experiment_spec["corpus"]:
        raise EvidenceBlocked("live corpus manifest/media do not match the frozen specification")
    if len(items) > payload["max_candidates"]:
        raise InvalidInput("corpus item count exceeds the explicit candidate budget")
    models = sorted({item["model_id"] for item in experiment_spec["metrics"]})
    if len(models) != 1 or payload["model_id"] != models[0]:
        raise EvidenceBlocked("serial corpus scoring requires exactly one frozen metric model")

    corpus_candidates = [corpus_record_override] if corpus_record_override is not None else store.all_of_type("corpus")
    corpus_matches = [
        record
        for record in corpus_candidates
        if record.get("record_type") == "corpus"
        and record["run_id"] == experiment_spec["run_id"]
        and record["experiment_spec_sha256"] == experiment_spec["content_sha256"]
        and record["links"] == {"experiment_spec": experiment_spec["content_sha256"]}
        and record["payload"] == experiment_spec["corpus"]
    ]
    if len(corpus_matches) != 1:
        raise EvidenceBlocked("serial corpus scoring requires one exact frozen corpus record")
    corpus_record = corpus_matches[0]

    media_sizes: dict[str, int] = {}
    for item in items:
        for side in ("source", "output"):
            media_sizes[item[f"{side}_path"]] = item[f"{side}_size_bytes"]
    corpus_media_bytes = sum(media_sizes.values())
    if corpus_media_bytes + _EVALUATION_RECORD_RESERVE_BYTES >= payload["max_storage_bytes"]:
        raise EvidenceBlocked("serial corpus media and evidence preflight exceed the storage budget")

    scorer_bytes = authority_snapshot.files.get(_SCORER_PATH)
    if scorer_bytes is None:
        raise EvidenceBlocked("repository scorer is absent from the frozen authority snapshot")
    _, scorer_sha256, _ = _stable_file(repository / _SCORER_PATH, reject_hardlinks=False, label="repository scorer", deadline=deadline)
    if scorer_sha256 != hashlib.sha256(scorer_bytes).hexdigest():
        raise EvidenceBlocked("repository scorer drifted after authority capture")
    metric = next((item for item in experiment_spec["metrics"] if item["model_id"] == payload["model_id"]), None)
    live_metric_contract = host_scorer_contract(scorer_bytes)
    expected_model_provenance = live_metric_contract["provenance"]
    if (
        metric is None
        or any(metric.get(key) != live_metric_contract[key] for key in ("metric_id", "version", "model_id"))
        or metric.get("provenance") != expected_model_provenance
        or metric.get("model_sha256")
        != domain_hash("host-libvmaf-model", {"model_id": payload["model_id"], **expected_model_provenance})
    ):
        raise EvidenceBlocked("serial corpus scorer cannot authenticate the frozen metric model")
    resolved_tools = {
        "python": _resolve_tool(sys.executable, deadline=deadline),
        "ffmpeg": _resolve_tool(payload["ffmpeg_executable"], deadline=deadline),
        "ffprobe": _resolve_tool(payload["ffprobe_executable"], deadline=deadline),
    }
    _remaining_corpus_seconds(deadline)
    return {
        "manifest_path": manifest_path,
        "deadline": deadline,
        "identity": identity,
        "items": items,
        "corpus_record": corpus_record,
        "corpus_media_bytes": corpus_media_bytes,
        "resolved_tools": resolved_tools,
    }


def validate_dry_run(
    operation_id: str,
    payload: dict[str, Any],
    *,
    store: RecordStore,
    experiment_spec: dict[str, Any],
    repository: Path,
    authority_snapshot: AuthoritySnapshot,
    corpus_record: dict[str, Any] | None = None,
    allow_planned_pair: bool = False,
) -> None:
    if operation_id == "pl_score_pair" and not allow_planned_pair:
        _score_pair_preflight(
            payload,
            store=store,
            experiment_spec=experiment_spec,
            repository=repository,
            authority_snapshot=authority_snapshot,
        )
    elif operation_id == "pl_score_corpus":
        _score_corpus_preflight(
            payload,
            store=store,
            experiment_spec=experiment_spec,
            repository=repository,
            authority_snapshot=authority_snapshot,
            corpus_record_override=corpus_record,
        )


def _validate_staged_corpus_evaluation(
    record: dict[str, Any],
    item: dict[str, Any],
    *,
    corpus_record: dict[str, Any],
    experiment_spec: dict[str, Any],
) -> None:
    payload = record.get("payload")
    provenance = payload.get("provenance") if isinstance(payload, dict) else None
    if (
        record.get("record_type") != "evaluation"
        or not isinstance(payload, dict)
        or payload.get("evaluation_kind") != "corpus_calibration"
        or payload.get("subject_sha256") != corpus_record["content_sha256"]
        or record.get("links") != {
            "experiment_spec": experiment_spec["content_sha256"],
            "corpus": corpus_record["content_sha256"],
        }
        or not isinstance(provenance, dict)
        or provenance.get("pair_id") != item["pair_id"]
        or provenance.get("corpus_manifest_sha256") != experiment_spec["corpus"]["manifest_sha256"]
        or provenance.get("corpus_pair_identities_sha256") != experiment_spec["corpus"]["pair_identities_sha256"]
        or provenance.get("source_content_sha256") != item["source_sha256"]
        or provenance.get("source_path") != item["source_path"]
        or provenance.get("source_size_bytes") != item["source_size_bytes"]
        or provenance.get("output_content_sha256") != item["output_sha256"]
        or provenance.get("output_path") != item["output_path"]
        or provenance.get("output_size_bytes") != item["output_size_bytes"]
    ):
        raise EvidenceBlocked("staged corpus evaluation does not match its frozen pair lineage")


def _execute_score_corpus(
    payload: dict[str, Any],
    *,
    store: RecordStore,
    experiment_spec: dict[str, Any],
    repository: Path,
    authority_snapshot: AuthoritySnapshot,
) -> dict[str, Any]:
    prepared = _score_corpus_preflight(
        payload,
        store=store,
        experiment_spec=experiment_spec,
        repository=repository,
        authority_snapshot=authority_snapshot,
    )
    manifest_path = prepared["manifest_path"]
    deadline = prepared["deadline"]
    identity = prepared["identity"]
    items = prepared["items"]
    corpus_record = prepared["corpus_record"]
    corpus_media_bytes = prepared["corpus_media_bytes"]
    resolved_tools = prepared["resolved_tools"]
    records: list[dict[str, Any]] = []
    report_bytes = 0
    manifest_root = manifest_path.parent
    for item in items:
        remaining_storage = payload["max_storage_bytes"] - corpus_media_bytes - report_bytes - sum(
            len(canonical_json_file_bytes(value)) for value in records
        )
        if remaining_storage <= _EVALUATION_RECORD_RESERVE_BYTES:
            raise EvidenceBlocked("serial corpus evidence exceeded its storage budget")
        pair_payload = {
            "source_path": str(manifest_root.joinpath(*item["source_path"].split("/"))),
            "output_path": str(manifest_root.joinpath(*item["output_path"].split("/"))),
            "pair_id": item["pair_id"],
            "ffmpeg_executable": payload["ffmpeg_executable"],
            "ffprobe_executable": payload["ffprobe_executable"],
            "model_id": payload["model_id"],
            "max_duration_seconds": _remaining_corpus_seconds(deadline),
            "max_storage_bytes": remaining_storage - _EVALUATION_RECORD_RESERVE_BYTES,
        }
        measured = _execute_score_pair(
            pair_payload,
            store=store,
            experiment_spec=experiment_spec,
            repository=repository,
            authority_snapshot=authority_snapshot,
            publish=False,
            resolved_tools=resolved_tools,
            corpus_record=corpus_record,
            expected_media={key: item[key] for key in (
                "source_path", "source_sha256", "source_size_bytes",
                "output_path", "output_sha256", "output_size_bytes",
            )},
            deadline=deadline,
        )
        measured_records = measured.get("records")
        if not isinstance(measured_records, list) or len(measured_records) != 1 or not isinstance(measured_records[0], dict):
            raise EvidenceBlocked("pair scorer did not return one corpus evaluation")
        record = measured_records[0]
        _validate_staged_corpus_evaluation(
            record,
            item,
            corpus_record=corpus_record,
            experiment_spec=experiment_spec,
        )
        records.append(record)
        report_bytes += measured["result"]["scorer_report_size_bytes"]
        if corpus_media_bytes + report_bytes + sum(len(canonical_json_file_bytes(value)) for value in records) > payload["max_storage_bytes"]:
            raise EvidenceBlocked("serial corpus evidence exceeded its storage budget")

    final_identity, final_items = corpus_manifest_snapshot(
        manifest_path,
        max_items=payload["max_candidates"],
        max_total_bytes=payload["max_storage_bytes"],
        max_duration_seconds=_remaining_corpus_seconds(deadline),
    )
    if final_identity != identity or final_items != items:
        raise EvidenceBlocked("corpus manifest/media changed during serial scoring")
    for record in records:
        store.put(record)
    return {
        "records": records,
        "result": {
            "evaluation_sha256s": [record["content_sha256"] for record in records],
            "pair_ids": [item["pair_id"] for item in items],
            "corpus_record_sha256": corpus_record["content_sha256"],
            "corpus_provenance": {
                "manifest_sha256": identity["manifest_sha256"],
                "source_identities_sha256": identity["source_identities_sha256"],
                "pair_identities_sha256": identity["pair_identities_sha256"],
                "item_count": identity["item_count"],
            },
            "corpus_media_size_bytes": corpus_media_bytes,
            "evidence_size_bytes": report_bytes + sum(len(canonical_json_file_bytes(value)) for value in records),
            "serial_execution": True,
            "worker_count": 1,
        },
    }


def _pts_result(reference: list[int], candidate: list[int], *, tolerance_floor_us: int, max_drops: int) -> dict[str, Any]:
    ref_index = 0
    candidate_index = 0
    ref_seen = -1
    candidate_seen = -1
    last_ref: int | None = None
    last_candidate: int | None = None
    min_ref_gap: int | None = None
    min_candidate_gap: int | None = None
    ref_dropped = 0
    candidate_dropped = 0
    paired = False
    skews: list[int] = []
    reason = "aligned"

    def observe(value: int, *, reference_stream: bool) -> None:
        nonlocal last_ref, last_candidate, min_ref_gap, min_candidate_gap
        last = last_ref if reference_stream else last_candidate
        if last is not None:
            gap = value - last
            if gap > 0:
                if reference_stream:
                    min_ref_gap = gap if min_ref_gap is None else min(min_ref_gap, gap)
                else:
                    min_candidate_gap = gap if min_candidate_gap is None else min(min_candidate_gap, gap)
        if reference_stream:
            last_ref = value
        else:
            last_candidate = value

    def current_tolerance() -> int:
        # Fixed, never derived from the observed frame interval. A correctly aligned pair's
        # skew is bounded by how the streams were ADDRESSED (<= 999 us from the probe clip's
        # ms-granular start), not by how far apart their frames are, so a rule that widened
        # with the interval scored half-frame-offset pairs as measurements at low frame
        # rates. Kotlin authority: PtsAligner.toleranceUs().
        return tolerance_floor_us

    while ref_index < len(reference) and candidate_index < len(candidate):
        if ref_index > ref_seen:
            observe(reference[ref_index], reference_stream=True)
            ref_seen = ref_index
        if candidate_index > candidate_seen:
            observe(candidate[candidate_index], reference_stream=False)
            candidate_seen = candidate_index
        tolerance = current_tolerance()
        skew = reference[ref_index] - candidate[candidate_index]
        if abs(skew) <= tolerance:
            paired = True
            skews.append(skew)
            ref_index += 1
            candidate_index += 1
            continue
        if paired:
            reason = "internal_frame_misalignment"
            break
        if ref_dropped + candidate_dropped >= max_drops:
            reason = "leading_drop_budget_exhausted"
            break
        if skew > 0:
            candidate_dropped += 1
            candidate_index += 1
        else:
            ref_dropped += 1
            ref_index += 1
    status = "ALIGNED" if reason == "aligned" and bool(skews) else "MISALIGNED"
    return {
        "status": status,
        "mode": "leading_drop_only",
        "tolerance_us": current_tolerance(),
        "reference_dropped": ref_dropped,
        "candidate_dropped": candidate_dropped,
        "compared_frames": len(skews),
        "max_abs_skew_us": max((abs(value) for value in skews), default=0),
        "reason": reason,
    }


def execute(
    operation_id: str,
    payload: dict[str, Any],
    *,
    store: RecordStore,
    experiment_spec: dict[str, Any],
    repository: Path,
    authority_snapshot: AuthoritySnapshot,
) -> dict[str, Any]:
    """Execute the registered pair scorer and the two pure comparisons."""

    spec_sha256 = experiment_spec["content_sha256"]
    run_id = experiment_spec["run_id"]
    if operation_id == "pl_score_pair":
        return _execute_score_pair(
            payload,
            store=store,
            experiment_spec=experiment_spec,
            repository=repository,
            authority_snapshot=authority_snapshot,
        )
    if operation_id == "pl_score_corpus":
        return _execute_score_corpus(
            payload,
            store=store,
            experiment_spec=experiment_spec,
            repository=repository,
            authority_snapshot=authority_snapshot,
        )
    if operation_id not in ("pl_hdr_compare", "pl_pts_compare"):
        raise EvidenceBlocked("quality scoring requires the registered repository scorer adapter")
    prior = _input_evaluation(store, payload, experiment_spec_sha256=spec_sha256, run_id=run_id)
    record_payload = deepcopy(prior["payload"])
    provenance = deepcopy(record_payload["provenance"])
    transforms = provenance.get("pure_comparisons", [])
    if not isinstance(transforms, list):
        raise EvidenceBlocked("input evaluation comparison provenance is invalid")
    if operation_id == "pl_hdr_compare":
        matches = payload["reference"] == payload["candidate"]
        hdr = {
            "status": "MATCH" if matches else "MISMATCH",
            "comparison": experiment_spec["hdr"]["comparison"],
            "tone_map_substitution": False,
        }
        alignment = record_payload["alignment"]
        record_payload["hdr"] = hdr
        transforms.append({"operation_id": operation_id, "input_sha256": prior["content_sha256"]})
    else:
        alignment = _pts_result(
            payload["reference_pts_us"],
            payload["candidate_pts_us"],
            tolerance_floor_us=experiment_spec["pts"]["tolerance_floor_us"],
            max_drops=experiment_spec["pts"]["max_alignment_drops"],
        )
        hdr = record_payload["hdr"]
        record_payload["alignment"] = alignment
        transforms.append({"operation_id": operation_id, "input_sha256": prior["content_sha256"]})
    provenance["pure_comparisons"] = transforms
    record_payload["provenance"] = provenance
    current_result = record_payload["hdr"] if operation_id == "pl_hdr_compare" else record_payload["alignment"]
    blocked = bool(record_payload["confounders"]) or current_result.get("status") == "BLOCKED"
    record_payload["status"] = "BLOCKED" if blocked else "COMPLETE"
    record = create_record(
        "evaluation",
        producer_role="perceptual-evaluator",
        run_id=run_id,
        created_at=experiment_spec["created_at"],
        experiment_spec_sha256=spec_sha256,
        links=deepcopy(prior["links"]),
        payload=record_payload,
        supersedes_sha256=prior["content_sha256"],
    )
    store.put(record)
    return {"records": [record], "result": {"evaluation_sha256": record["content_sha256"], "alignment": alignment, "hdr": hdr}}
