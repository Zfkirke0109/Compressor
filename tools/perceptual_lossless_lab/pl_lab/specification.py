"""Build frozen, content-addressed experiment specifications."""

from __future__ import annotations

from copy import deepcopy
import hashlib
import os
from pathlib import Path
import re
import stat
import time
from typing import Any

from .authority import AuthoritySnapshot, capture_authority_snapshot, extract_authority_thresholds, fingerprint_snapshot
from .canonical import domain_hash, seal_document, verify_sealed_document
from .errors import EvidenceBlocked, ImmutableConflict, InvalidInput
from .experiment import ExperimentStore
from .jsonio import canonical_json_file_bytes, load_json, loads_strict
from .paths import assert_contained, assert_no_reparse_points, logical_path
from .sanitize import assert_sanitized
from .storage import atomic_publish_new
from .validation import require_run_id, require_sha256, require_utc_timestamp


_DEFINITION_FIELDS = frozenset({"corpus", "metrics", "pts", "hdr", "tolerances", "device_requirements", "budgets", "constraints"})
_CONSTRAINT_AUTHORITIES = {
    "vmaf_mean": (">=", "quality_probe", "WINDOW_MEAN_MIN"),
    "vmaf_p5": (">=", "quality_probe", "WINDOW_P5_MIN"),
    "vmaf_min": (">=", "quality_probe", "WINDOW_MIN_MIN"),
    "compared_frames": (">=", "quality_probe", "MIN_COMPARED_FRAMES_PER_WINDOW"),
}
_PAIR_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$")
_MAX_MANIFEST_BYTES = 4 * 1024 * 1024
_HOST_SCORER_PATH = "scripts/diagnostics/measure_quality.py"
_HOST_MODEL_RE = re.compile(rb'(?m)^VMAF_MODEL\s*=\s*"(version=([A-Za-z0-9._-]+))"\s*$')
_HOST_SCHEMA_RE = re.compile(rb"(?m)^SCHEMA_VERSION\s*=\s*([1-9][0-9]*)\s*$")


def host_scorer_contract(scorer_data: bytes) -> dict[str, Any]:
    """Derive the host metric identity from the exact captured scorer bytes."""

    selector_match = _HOST_MODEL_RE.search(scorer_data)
    schema_match = _HOST_SCHEMA_RE.search(scorer_data)
    if selector_match is None or schema_match is None:
        raise EvidenceBlocked("host VMAF scorer metric contract is unavailable")
    schema_version = int(schema_match.group(1))
    selector = selector_match.group(1).decode("ascii")
    model_id = selector_match.group(2).decode("ascii")
    return {
        "metric_id": "vmaf",
        "version": f"{schema_version}.0.0",
        "model_id": model_id,
        "provenance": {
            "host_scorer_sha256": _sha256_bytes(scorer_data),
            "model_selector": selector,
            "harness_schema_version": schema_version,
        },
    }


def _stable_file_identity(
    path: Path,
    label: str,
    *,
    capture_bytes: bool,
    maximum_bytes: int | None = None,
    expected_size: int | None = None,
    deadline: float | None = None,
) -> tuple[bytes | None, str, int, tuple[int, int, int, int, int]]:
    """Hash a stable, single-link regular file without following reparse points."""

    path = Path(path).absolute()
    try:
        if deadline is not None and time.monotonic() > deadline:
            raise EvidenceBlocked(f"{label} hashing exceeded its duration budget")
        assert_no_reparse_points(path)
        path_before = os.lstat(path)
        if not stat.S_ISREG(path_before.st_mode):
            raise OSError(f"{label} is not a regular file")
        if getattr(path_before, "st_nlink", 1) != 1:
            raise OSError(f"{label} has multiple hard links")
        if maximum_bytes is not None and path_before.st_size > maximum_bytes:
            raise InvalidInput(f"{label} exceeds its maximum size")
        if expected_size is not None and path_before.st_size != expected_size:
            raise EvidenceBlocked(f"{label} size does not match its declared identity")

        flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
        descriptor = os.open(path, flags)
        try:
            handle_before = os.fstat(descriptor)
            if not stat.S_ISREG(handle_before.st_mode) or getattr(handle_before, "st_nlink", 1) != 1:
                raise OSError(f"{label} handle identity is invalid")
            digest = hashlib.sha256()
            captured = bytearray() if capture_bytes else None
            size = 0
            while True:
                if deadline is not None and time.monotonic() > deadline:
                    raise EvidenceBlocked(f"{label} hashing exceeded its duration budget")
                chunk = os.read(descriptor, 1024 * 1024)
                if not chunk:
                    break
                size += len(chunk)
                if maximum_bytes is not None and size > maximum_bytes:
                    raise InvalidInput(f"{label} exceeds its maximum size")
                digest.update(chunk)
                if captured is not None:
                    captured.extend(chunk)
            handle_after = os.fstat(descriptor)
        finally:
            os.close(descriptor)

        path_after = os.lstat(path)
        identities = [
            (
                item.st_dev,
                item.st_ino,
                item.st_size,
                getattr(item, "st_mtime_ns", int(item.st_mtime * 1_000_000_000)),
                getattr(item, "st_nlink", 1),
            )
            for item in (path_before, handle_before, handle_after, path_after)
        ]
        if len(set(identities)) != 1 or identities[0][-1] != 1:
            raise OSError(f"{label} identity changed while hashing")
        if deadline is not None and time.monotonic() > deadline:
            raise EvidenceBlocked(f"{label} hashing exceeded its duration budget")
        assert_no_reparse_points(path)
        return (bytes(captured) if captured is not None else None, digest.hexdigest(), size, identities[0])
    except ValueError as exc:
        raise InvalidInput(f"{label} path is invalid") from exc
    except InvalidInput:
        raise
    except OSError as exc:
        raise EvidenceBlocked(f"{label} is unavailable") from exc


def _read_evidence_bytes(path: Path, label: str) -> bytes:
    data, _, _, _ = _stable_file_identity(path, label, capture_bytes=True)
    assert data is not None
    return data


def _sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def corpus_manifest_snapshot(
    path: Path,
    *,
    max_items: int | None = None,
    max_total_bytes: int | None = None,
    max_duration_seconds: int | None = None,
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Validate a strict v1 pair manifest and bind every declared media identity."""

    for value, label in (
        (max_items, "corpus item budget"),
        (max_total_bytes, "corpus byte budget"),
        (max_duration_seconds, "corpus duration budget"),
    ):
        if value is not None and (not isinstance(value, int) or isinstance(value, bool) or value <= 0):
            raise InvalidInput(f"{label} must be a positive integer")
    deadline = time.monotonic() + max_duration_seconds if max_duration_seconds is not None else None
    manifest_path = Path(path).absolute()
    if manifest_path.suffix.casefold() != ".json":
        raise InvalidInput("corpus manifest must be strict v1 JSON")
    data, manifest_sha256, _, _ = _stable_file_identity(
        manifest_path,
        "corpus manifest",
        capture_bytes=True,
        maximum_bytes=_MAX_MANIFEST_BYTES,
        deadline=deadline,
    )
    assert data is not None
    try:
        document = loads_strict(data.decode("utf-8"))
    except (UnicodeError, InvalidInput) as exc:
        raise InvalidInput("corpus JSON manifest is invalid") from exc
    manifest = _exact_fields(document, {"schema_version", "items"}, set(), "corpus manifest")
    if manifest["schema_version"] != "1" or not isinstance(manifest["items"], list) or not manifest["items"]:
        raise InvalidInput("corpus JSON manifest version/items are invalid")
    if max_items is not None and len(manifest["items"]) > max_items:
        raise InvalidInput("corpus item count exceeds its frozen budget")

    required_item_fields = {
        "pair_id",
        "source_path",
        "source_sha256",
        "source_size_bytes",
        "output_path",
        "output_sha256",
        "output_size_bytes",
    }
    manifest_root = manifest_path.parent.absolute()
    items: list[dict[str, Any]] = []
    pair_ids: set[str] = set()
    source_paths: set[str] = set()
    output_paths: set[str] = set()
    declared_paths: dict[str, tuple[str, int, Path, str]] = {}
    for index, item_value in enumerate(manifest["items"]):
        item = _exact_fields(item_value, required_item_fields, set(), f"corpus items[{index}]")
        pair_id = item["pair_id"]
        if not isinstance(pair_id, str) or _PAIR_ID_RE.fullmatch(pair_id) is None:
            raise InvalidInput(f"corpus items[{index}].pair_id is invalid")
        if pair_id in pair_ids:
            raise InvalidInput("corpus manifest contains duplicate pair IDs")
        pair_ids.add(pair_id)

        normalized: dict[str, Any] = {"pair_id": pair_id}
        for side in ("source", "output"):
            path_key = f"{side}_path"
            hash_key = f"{side}_sha256"
            size_key = f"{side}_size_bytes"
            relative = logical_path(item[path_key])
            require_sha256(item[hash_key], f"corpus items[{index}].{hash_key}")
            declared_size = _positive_int(item[size_key], f"corpus items[{index}].{size_key}")
            media_path = assert_contained(manifest_root, manifest_root.joinpath(*relative.split("/")))
            declared = (item[hash_key], declared_size, media_path, side)
            existing = declared_paths.get(relative)
            if existing is not None and existing[:2] != declared[:2]:
                raise InvalidInput("corpus path has conflicting declared identities")
            declared_paths.setdefault(relative, declared)
            normalized.update({path_key: relative, hash_key: item[hash_key], size_key: declared_size})

        if normalized["source_path"] == normalized["output_path"]:
            raise InvalidInput("corpus source and output paths must be distinct")
        source_paths.add(normalized["source_path"])
        if normalized["output_path"] in output_paths:
            raise InvalidInput("corpus manifest contains duplicate output paths")
        output_paths.add(normalized["output_path"])
        items.append(normalized)

    if source_paths & output_paths:
        raise InvalidInput("corpus source and output path sets must be disjoint")
    declared_total_bytes = sum(value[1] for value in declared_paths.values())
    if max_total_bytes is not None and declared_total_bytes > max_total_bytes:
        raise InvalidInput("corpus declared media exceeds its frozen byte budget")

    verified_paths: dict[str, tuple[str, int, tuple[int, int, int, int, int]]] = {}
    for relative, (declared_hash, declared_size, media_path, side) in declared_paths.items():
        _, observed_hash, observed_size, observed_stat = _stable_file_identity(
            media_path,
            f"corpus {side} media",
            capture_bytes=False,
            expected_size=declared_size,
            deadline=deadline,
        )
        if (observed_hash, observed_size) != (declared_hash, declared_size):
            raise EvidenceBlocked(f"corpus {side} media does not match its declared identity")
        verified_paths[relative] = (observed_hash, observed_size, observed_stat)

    source_physical_identities: set[tuple[Any, ...]] = set()
    output_physical_identities: set[tuple[Any, ...]] = set()
    for item in items:
        for side in ("source", "output"):
            relative = item[f"{side}_path"]
            media_path = declared_paths[relative][2]
            observed_stat = verified_paths[relative][2]
            physical_identity: tuple[Any, ...]
            if observed_stat[1]:
                physical_identity = ("inode", observed_stat[0], observed_stat[1])
            else:
                physical_identity = ("path", os.path.normcase(str(media_path.resolve(strict=True))))
            if side == "output" and physical_identity in output_physical_identities:
                raise InvalidInput("corpus manifest contains duplicate physical outputs")
            (source_physical_identities if side == "source" else output_physical_identities).add(physical_identity)

    if source_physical_identities & output_physical_identities:
        raise InvalidInput("corpus source and output path sets must be disjoint")
    items.sort(key=lambda item: item["pair_id"])
    source_identities = [
        {
            "source_path": source_path,
            "source_sha256": verified_paths[source_path][0],
            "source_size_bytes": verified_paths[source_path][1],
        }
        for source_path in sorted(source_paths)
    ]
    identity = {
        "manifest_alias": manifest_path.name,
        "manifest_sha256": manifest_sha256,
        "source_identities_sha256": domain_hash("corpus-source-identities", source_identities),
        "pair_identities_sha256": domain_hash("corpus-pair-identities", items),
        "item_count": len(items),
    }
    assert_sanitized(identity)
    return identity, items


def _corpus_identity(path: Path) -> dict[str, Any]:
    identity, _ = corpus_manifest_snapshot(path)
    return identity


def _exact_fields(value: Any, required: set[str], optional: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise InvalidInput(f"{label} must be an object")
    missing = sorted(required - set(value))
    unknown = sorted(set(value) - required - optional)
    if missing or unknown:
        raise InvalidInput(f"invalid {label}", details={"missing": missing, "unknown": unknown})
    return value


def _positive_int(value: Any, label: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        raise InvalidInput(f"{label} must be a positive integer")
    return value


def _nonempty_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise InvalidInput(f"{label} must be a nonempty string")
    return value


def _finite_number(value: Any, label: str) -> int | float:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise InvalidInput(f"{label} must be numeric")
    if isinstance(value, float) and (value != value or value in (float("inf"), float("-inf"))):
        raise InvalidInput(f"{label} must be finite")
    return value


def build_experiment_spec(
    repository: Path,
    *,
    run_id: str,
    created_at: str,
    definition: dict[str, Any],
    require_all_authorities: bool = True,
    authority_snapshot: AuthoritySnapshot | None = None,
) -> dict[str, Any]:
    require_run_id(run_id)
    require_utc_timestamp(created_at)
    if not isinstance(definition, dict) or set(definition) != _DEFINITION_FIELDS:
        raise InvalidInput("experiment definition fields are incomplete or unknown")

    root = Path(repository).resolve(strict=True)
    if authority_snapshot is None:
        authority_snapshot = capture_authority_snapshot(root, require_all=require_all_authorities)
    elif authority_snapshot.root.resolve(strict=True) != root:
        raise InvalidInput("authority snapshot belongs to a different repository")
    elif require_all_authorities and authority_snapshot.missing:
        raise InvalidInput("repository authority snapshot is incomplete")
    thresholds = extract_authority_thresholds(snapshot=authority_snapshot)
    authority = fingerprint_snapshot(authority_snapshot)

    corpus_input = _exact_fields(definition["corpus"], {"manifest_path"}, set(), "corpus")
    budgets = deepcopy(_exact_fields(definition["budgets"], {"max_candidates", "max_workers", "max_duration_seconds", "max_storage_bytes"}, set(), "budgets"))
    for key in budgets:
        _positive_int(budgets[key], f"budgets.{key}")
    if budgets["max_workers"] != 1:
        raise InvalidInput("v1 enforces exactly one worker")
    manifest_path = Path(corpus_input["manifest_path"])
    corpus, _ = corpus_manifest_snapshot(
        manifest_path,
        max_items=budgets["max_candidates"],
        max_total_bytes=budgets["max_storage_bytes"],
        max_duration_seconds=budgets["max_duration_seconds"],
    )

    metrics_input = definition["metrics"]
    if not isinstance(metrics_input, list) or not metrics_input:
        raise InvalidInput("at least one metric must be declared")
    metrics: list[dict[str, Any]] = []
    for index, metric_value in enumerate(metrics_input):
        metric = _exact_fields(metric_value, {"metric_id", "version", "model_id"}, {"model_path", "model_source"}, f"metrics[{index}]")
        if ("model_source" in metric) == ("model_path" in metric):
            raise InvalidInput("each metric needs exactly one model identity source")
        if "model_path" in metric:
            model_path = Path(metric["model_path"])
            if not model_path.is_file():
                raise EvidenceBlocked("metric model is unavailable")
            model_sha256 = _sha256_bytes(_read_evidence_bytes(model_path, "metric model"))
            model_provenance = {"model_asset_sha256": model_sha256}
        elif metric["model_source"] == "native_builtin":
            native_path = root / "app/src/main/cpp/vmaf_jni.c"
            library_path = root / "app/src/main/cpp/prebuilt/arm64-v8a/libvmaf.a"
            native_data = _read_evidence_bytes(native_path, "native VMAF source")
            library_data = _read_evidence_bytes(library_path, "native VMAF library")
            if metric["model_id"].encode("utf-8") not in native_data:
                raise InvalidInput("declared built-in model is not named by native authority")
            model_provenance = {"native_source_sha256": _sha256_bytes(native_data), "native_library_sha256": _sha256_bytes(library_data)}
            model_sha256 = domain_hash("native-vmaf-model", {"model_id": metric["model_id"], **model_provenance})
        elif metric["model_source"] == "host_libvmaf_builtin":
            scorer_data = authority_snapshot.files.get(_HOST_SCORER_PATH)
            if scorer_data is None:
                raise EvidenceBlocked("host VMAF scorer authority is unavailable")
            host_contract = host_scorer_contract(scorer_data)
            if any(metric[key] != host_contract[key] for key in ("metric_id", "version", "model_id")):
                raise InvalidInput("host VMAF metric does not match the scorer's authoritative contract")
            model_provenance = host_contract["provenance"]
            model_sha256 = domain_hash("host-libvmaf-model", {"model_id": metric["model_id"], **model_provenance})
        else:
                raise InvalidInput("unsupported metric model source")
        metrics.append({"metric_id": metric["metric_id"], "version": metric["version"], "model_id": metric["model_id"], "model_sha256": model_sha256, "provenance": model_provenance})

    pts_input = _exact_fields(definition["pts"], {"mode", "timebase"}, set(), "pts")
    if pts_input["mode"] != "leading_drop_only" or pts_input["timebase"] != "microseconds":
        raise InvalidInput("PTS mode must match the repository leading-drop microsecond authority")
    pts = {
        **pts_input,
        "tolerance_floor_us": thresholds["pts_alignment"]["TOLERANCE_FLOOR_US"],
        "max_alignment_drops": thresholds["pts_alignment"]["MAX_ALIGNMENT_DROPS"],
    }

    hdr = deepcopy(_exact_fields(definition["hdr"], {"comparison", "tone_map_substitution"}, set(), "hdr"))
    if hdr["comparison"] != "metadata_exact" or hdr["tone_map_substitution"] is not False:
        raise InvalidInput("HDR comparison must be metadata-exact without tone-map substitution")
    tolerances = deepcopy(_exact_fields(
        definition["tolerances"],
        {"size_ratio", "duration_absolute_ms", "duration_fraction", "frame_count_absolute", "frame_count_fraction"},
        set(),
        "tolerances",
    ))
    authoritative_tolerances = {
        "size_ratio": thresholds["bitrate_policy"]["PERCEPTUAL_LOSSLESS_SIZE_TOLERANCE"],
        "duration_absolute_ms": thresholds["output_verifier"]["DURATION_ABSOLUTE_MS"],
        "duration_fraction": thresholds["output_verifier"]["DURATION_FRACTION"],
        "frame_count_absolute": thresholds["output_verifier"]["FRAME_COUNT_ABSOLUTE"],
        "frame_count_fraction": thresholds["output_verifier"]["FRAME_COUNT_FRACTION"],
    }
    if tolerances != authoritative_tolerances:
        raise InvalidInput("declared tolerances do not match repository authority")
    device_requirements = deepcopy(_exact_fields(
        definition["device_requirements"], {"required"}, {"firmware_sha256", "device_alias", "android_user", "package_id", "build_identity", "signer_sha256"}, "device_requirements"
    ))
    if not isinstance(device_requirements["required"], bool):
        raise InvalidInput("device required flag must be boolean")
    if device_requirements["required"]:
        required_device = {"device_alias", "android_user", "package_id", "build_identity", "signer_sha256", "firmware_sha256"}
        missing_device = sorted(required_device - set(device_requirements))
        if missing_device:
            raise InvalidInput("required device identity is incomplete", details={"missing": missing_device})
        if not isinstance(device_requirements["android_user"], int) or isinstance(device_requirements["android_user"], bool) or device_requirements["android_user"] < 0:
            raise InvalidInput("Android user must be a nonnegative integer")
        for key in ("signer_sha256", "firmware_sha256"):
            require_sha256(device_requirements[key], f"device_requirements.{key}")

    constraints_input = definition["constraints"]
    if not isinstance(constraints_input, list) or not constraints_input:
        raise InvalidInput("at least one frozen constraint is required")
    resolved_constraints: list[dict[str, Any]] = []
    seen_metrics: set[str] = set()
    for index, item in enumerate(constraints_input):
        constraint = _exact_fields(item, {"metric", "operator", "authority_group", "authority_name"}, set(), f"constraints[{index}]")
        expected_semantics = _CONSTRAINT_AUTHORITIES.get(constraint["metric"])
        if expected_semantics is None or (constraint["operator"], constraint["authority_group"], constraint["authority_name"]) != expected_semantics:
            raise InvalidInput("constraint semantics do not match the repository authority map")
        group = thresholds.get(constraint["authority_group"])
        if not isinstance(group, dict) or constraint["authority_name"] not in group or constraint["authority_name"].startswith("source_"):
            raise InvalidInput("constraint does not reference an extracted repository threshold")
        if constraint["metric"] in seen_metrics:
            raise InvalidInput("duplicate metric constraint")
        seen_metrics.add(constraint["metric"])
        resolved_constraints.append({**constraint, "value": group[constraint["authority_name"]]})
    if seen_metrics != set(_CONSTRAINT_AUTHORITIES):
        raise InvalidInput("the complete frozen constraint set is required")

    body = {
        "schema_version": "1",
        "record_type": "experiment_spec",
        "producer_role": "deterministic-core",
        "run_id": run_id,
        "created_at": created_at,
        "corpus": corpus,
        "threshold_provenance": {
            "authority_sha256": authority["authority_sha256"],
            "sources": thresholds,
            "resolved_constraints": resolved_constraints,
        },
        "metrics": metrics,
        "pts": pts,
        "hdr": hdr,
        "tolerances": tolerances,
        "device_requirements": device_requirements,
        "budgets": budgets,
        "repository_authority": authority,
        "links": {},
        "payload": {},
    }
    assert_sanitized(body)
    sealed = seal_document("record:experiment_spec", body)
    validate_experiment_spec(sealed, require_complete_authority=require_all_authorities)
    return sealed


def validate_experiment_spec(
    spec: dict[str, Any],
    *,
    require_complete_authority: bool = True,
    repository: Path | None = None,
    authority_snapshot: AuthoritySnapshot | None = None,
) -> None:
    allowed = {"schema_version", "record_type", "producer_role", "run_id", "created_at", "corpus", "threshold_provenance", "metrics", "pts", "hdr", "tolerances", "device_requirements", "budgets", "repository_authority", "links", "payload", "supersedes_sha256", "content_sha256"}
    required = allowed - {"supersedes_sha256"}
    if not isinstance(spec, dict) or set(spec) - allowed or required - set(spec):
        raise InvalidInput("experiment specification fields are incomplete or unknown")
    if spec["schema_version"] != "1" or spec["record_type"] != "experiment_spec" or spec["producer_role"] != "deterministic-core":
        raise InvalidInput("experiment specification authority/version is invalid")
    require_run_id(spec["run_id"])
    require_utc_timestamp(spec["created_at"])
    if not verify_sealed_document("record:experiment_spec", spec):
        raise InvalidInput("experiment specification content hash mismatch")
    corpus = _exact_fields(spec["corpus"], {"manifest_alias", "manifest_sha256", "source_identities_sha256", "pair_identities_sha256", "item_count"}, set(), "frozen corpus")
    manifest_alias = _nonempty_string(corpus["manifest_alias"], "corpus.manifest_alias")
    if Path(manifest_alias).name != manifest_alias or "/" in manifest_alias or "\\" in manifest_alias:
        raise InvalidInput("corpus manifest alias must be a filename")
    require_sha256(corpus["manifest_sha256"], "corpus.manifest_sha256")
    require_sha256(corpus["source_identities_sha256"], "corpus.source_identities_sha256")
    require_sha256(corpus["pair_identities_sha256"], "corpus.pair_identities_sha256")
    _positive_int(corpus["item_count"], "corpus.item_count")
    if not isinstance(spec["metrics"], list) or not spec["metrics"]:
        raise InvalidInput("frozen metric provenance is missing")
    for metric in spec["metrics"]:
        if not isinstance(metric, dict) or set(metric) != {"metric_id", "version", "model_id", "model_sha256", "provenance"}:
            raise InvalidInput("frozen metric provenance is invalid")
        for key in ("metric_id", "version", "model_id"):
            _nonempty_string(metric[key], f"metrics.{key}")
        require_sha256(metric["model_sha256"], "metrics.model_sha256")
        metric_provenance = metric["provenance"]
        if not isinstance(metric_provenance, dict):
            raise InvalidInput("frozen metric model provenance is invalid")
        if set(metric_provenance) == {"model_asset_sha256"}:
            require_sha256(metric_provenance["model_asset_sha256"], "metrics.provenance.model_asset_sha256")
            if metric_provenance["model_asset_sha256"] != metric["model_sha256"]:
                raise InvalidInput("metric asset and model hashes differ")
        elif set(metric_provenance) == {"native_source_sha256", "native_library_sha256"}:
            require_sha256(metric_provenance["native_source_sha256"], "metrics.provenance.native_source_sha256")
            require_sha256(metric_provenance["native_library_sha256"], "metrics.provenance.native_library_sha256")
            expected_model_sha256 = domain_hash("native-vmaf-model", {"model_id": metric["model_id"], **metric_provenance})
            if metric["model_sha256"] != expected_model_sha256:
                raise InvalidInput("native metric model identity is inconsistent")
        elif set(metric_provenance) == {"host_scorer_sha256", "model_selector", "harness_schema_version"}:
            require_sha256(metric_provenance["host_scorer_sha256"], "metrics.provenance.host_scorer_sha256")
            schema_version = _positive_int(metric_provenance["harness_schema_version"], "metrics.provenance.harness_schema_version")
            expected_selector = f"version={metric['model_id']}"
            if metric_provenance["model_selector"] != expected_selector:
                raise InvalidInput("host metric selector does not match its model ID")
            if metric["metric_id"] != "vmaf" or metric["version"] != f"{schema_version}.0.0":
                raise InvalidInput("host metric name/version does not match its harness schema")
            expected_model_sha256 = domain_hash("host-libvmaf-model", {"model_id": metric["model_id"], **metric_provenance})
            if metric["model_sha256"] != expected_model_sha256:
                raise InvalidInput("host metric model identity is inconsistent")
        else:
            raise InvalidInput("frozen metric model provenance shape is invalid")

    pts = _exact_fields(spec["pts"], {"mode", "timebase", "tolerance_floor_us", "max_alignment_drops"}, set(), "frozen PTS contract")
    if pts["mode"] != "leading_drop_only" or pts["timebase"] != "microseconds":
        raise InvalidInput("frozen PTS semantics are invalid")
    _positive_int(pts["tolerance_floor_us"], "pts.tolerance_floor_us")
    _positive_int(pts["max_alignment_drops"], "pts.max_alignment_drops")
    hdr = _exact_fields(spec["hdr"], {"comparison", "tone_map_substitution"}, set(), "frozen HDR contract")
    if hdr["comparison"] != "metadata_exact" or hdr["tone_map_substitution"] is not False:
        raise InvalidInput("frozen HDR semantics are invalid")
    tolerances = _exact_fields(spec["tolerances"], {"size_ratio", "duration_absolute_ms", "duration_fraction", "frame_count_absolute", "frame_count_fraction"}, set(), "frozen verifier tolerances")
    for key in tolerances:
        number = _finite_number(tolerances[key], f"tolerances.{key}")
        if number < 0:
            raise InvalidInput(f"tolerances.{key} must be nonnegative")
    if not isinstance(tolerances["duration_absolute_ms"], int) or isinstance(tolerances["duration_absolute_ms"], bool):
        raise InvalidInput("duration absolute tolerance must be an integer")
    if not isinstance(tolerances["frame_count_absolute"], int) or isinstance(tolerances["frame_count_absolute"], bool):
        raise InvalidInput("frame-count absolute tolerance must be an integer")
    device_requirements = _exact_fields(
        spec["device_requirements"],
        {"required"},
        {"firmware_sha256", "device_alias", "android_user", "package_id", "build_identity", "signer_sha256"},
        "frozen device requirements",
    )
    if not isinstance(device_requirements["required"], bool):
        raise InvalidInput("frozen device required flag must be boolean")
    if device_requirements["required"]:
        missing_device = sorted({"firmware_sha256", "device_alias", "android_user", "package_id", "build_identity", "signer_sha256"} - set(device_requirements))
        if missing_device:
            raise InvalidInput("frozen required device identity is incomplete", details={"missing": missing_device})
    if "device_alias" in device_requirements:
        _nonempty_string(device_requirements["device_alias"], "device_requirements.device_alias")
    if "android_user" in device_requirements and (not isinstance(device_requirements["android_user"], int) or isinstance(device_requirements["android_user"], bool) or device_requirements["android_user"] < 0):
        raise InvalidInput("frozen Android user is invalid")
    if "package_id" in device_requirements:
        _nonempty_string(device_requirements["package_id"], "device_requirements.package_id")
    for key in ("firmware_sha256", "signer_sha256"):
        if key in device_requirements:
            require_sha256(device_requirements[key], f"device_requirements.{key}")
    if "build_identity" in device_requirements:
        build_identity = _exact_fields(device_requirements["build_identity"], {"version_code", "version_name", "apk_sha256", "source_sha256"}, set(), "frozen device build identity")
        _positive_int(build_identity["version_code"], "device_requirements.build_identity.version_code")
        _nonempty_string(build_identity["version_name"], "device_requirements.build_identity.version_name")
        require_sha256(build_identity["apk_sha256"], "device_requirements.build_identity.apk_sha256")
        require_sha256(build_identity["source_sha256"], "device_requirements.build_identity.source_sha256")
    budgets = _exact_fields(spec["budgets"], {"max_candidates", "max_workers", "max_duration_seconds", "max_storage_bytes"}, set(), "frozen budgets")
    for key, value in budgets.items():
        _positive_int(value, f"budgets.{key}")
    if budgets["max_workers"] != 1:
        raise InvalidInput("frozen v1 budgets require exactly one worker")
    provenance = _exact_fields(spec["threshold_provenance"], {"authority_sha256", "sources", "resolved_constraints"}, set(), "threshold provenance")
    require_sha256(provenance["authority_sha256"], "threshold_provenance.authority_sha256")
    if not isinstance(provenance["sources"], dict) or set(provenance["sources"]) != {"quality_probe", "bitrate_policy", "pts_alignment", "output_verifier"}:
        raise InvalidInput("threshold sources are invalid")
    for group_name, source in provenance["sources"].items():
        if not isinstance(source, dict) or not isinstance(source.get("source_path"), str):
            raise InvalidInput(f"threshold source {group_name} is invalid")
        require_sha256(source.get("source_sha256"), f"threshold_provenance.sources.{group_name}.source_sha256")
        numeric_values = {key: value for key, value in source.items() if key not in ("source_path", "source_sha256")}
        if not numeric_values:
            raise InvalidInput(f"threshold source {group_name} has no values")
        for key, value in numeric_values.items():
            _finite_number(value, f"threshold_provenance.sources.{group_name}.{key}")
    resolved = provenance["resolved_constraints"]
    if not isinstance(resolved, list) or len(resolved) != len(_CONSTRAINT_AUTHORITIES):
        raise InvalidInput("frozen constraints are incomplete")
    seen: set[str] = set()
    for constraint in resolved:
        if not isinstance(constraint, dict) or set(constraint) != {"metric", "operator", "authority_group", "authority_name", "value"}:
            raise InvalidInput("frozen constraint shape is invalid")
        metric = constraint["metric"]
        expected = _CONSTRAINT_AUTHORITIES.get(metric)
        if expected is None or (constraint["operator"], constraint["authority_group"], constraint["authority_name"]) != expected:
            raise InvalidInput("frozen constraint semantics are invalid")
        source = provenance["sources"].get(constraint["authority_group"])
        if not isinstance(source, dict) or source.get(constraint["authority_name"]) != constraint["value"]:
            raise InvalidInput("frozen constraint value does not match threshold source")
        _finite_number(constraint["value"], f"constraint.{metric}.value")
        seen.add(metric)
    if seen != set(_CONSTRAINT_AUTHORITIES):
        raise InvalidInput("frozen constraint metrics are incomplete")
    repository_authority = _exact_fields(spec["repository_authority"], {"schema_version", "authority_sha256", "files", "missing_authorities", "repository_identity"}, set(), "repository authority")
    if repository_authority["schema_version"] != "1":
        raise InvalidInput("repository authority version is invalid")
    if repository_authority["authority_sha256"] != provenance["authority_sha256"]:
        raise InvalidInput("threshold and repository authority fingerprints differ")
    if require_complete_authority and repository_authority["missing_authorities"]:
        raise InvalidInput("repository authority fingerprint is incomplete")
    if not isinstance(repository_authority["missing_authorities"], list) or any(not isinstance(item, str) for item in repository_authority["missing_authorities"]):
        raise InvalidInput("repository missing-authority list is invalid")
    identity = repository_authority["repository_identity"]
    identity_fields = {"vcs", "commit_oid", "dirty", "dirty_diff_sha256", "untracked_identity_sha256", "untracked_count", "identity_sha256"}
    if not isinstance(identity, dict) or set(identity) != identity_fields:
        raise InvalidInput("repository workspace identity is incomplete")
    if identity["vcs"] not in ("git", "unavailable") or not isinstance(identity["dirty"], bool) or not isinstance(identity["untracked_count"], int) or isinstance(identity["untracked_count"], bool) or identity["untracked_count"] < 0:
        raise InvalidInput("repository workspace identity fields are invalid")
    if identity["vcs"] == "git" and (not isinstance(identity["commit_oid"], str) or re.fullmatch(r"[0-9a-f]{40,64}", identity["commit_oid"]) is None):
        raise InvalidInput("repository Git commit identity is invalid")
    if require_complete_authority and identity["vcs"] != "git":
        raise InvalidInput("complete repository authority requires Git identity")
    for key in ("dirty_diff_sha256", "untracked_identity_sha256", "identity_sha256"):
        require_sha256(identity[key], f"repository_identity.{key}")
    identity_body = {key: value for key, value in identity.items() if key != "identity_sha256"}
    if identity["identity_sha256"] != domain_hash("repository-workspace-identity", identity_body):
        raise InvalidInput("repository workspace identity hash mismatch")
    if not isinstance(repository_authority["files"], list) or not repository_authority["files"]:
        raise InvalidInput("repository authority file identities are empty")
    for item in repository_authority["files"]:
        if not isinstance(item, dict) or set(item) != {"logical_path", "sha256"}:
            raise InvalidInput("repository authority file identity is invalid")
        from .paths import logical_path
        logical_path(item["logical_path"])
        require_sha256(item["sha256"], "repository authority file hash")
    if len({item["logical_path"] for item in repository_authority["files"]}) != len(repository_authority["files"]):
        raise InvalidInput("repository authority file identities are duplicated")
    expected_authority_sha256 = domain_hash("repository-authority", {"files": repository_authority["files"], "repository_identity": identity})
    if repository_authority["authority_sha256"] != expected_authority_sha256:
        raise InvalidInput("repository authority aggregate hash mismatch")
    authority_files = {
        item.get("logical_path"): item.get("sha256")
        for item in repository_authority["files"]
        if isinstance(item, dict)
    }
    for source in provenance["sources"].values():
        if not isinstance(source, dict) or authority_files.get(source.get("source_path")) != source.get("source_sha256"):
            raise InvalidInput("threshold source is not bound to the repository authority snapshot")
    if not isinstance(spec["links"], dict) or spec["links"] or not isinstance(spec["payload"], dict) or spec["payload"]:
        raise InvalidInput("experiment specification links/payload must be empty in v1")
    if tolerances != {
        "size_ratio": provenance["sources"]["bitrate_policy"]["PERCEPTUAL_LOSSLESS_SIZE_TOLERANCE"],
        "duration_absolute_ms": provenance["sources"]["output_verifier"]["DURATION_ABSOLUTE_MS"],
        "duration_fraction": provenance["sources"]["output_verifier"]["DURATION_FRACTION"],
        "frame_count_absolute": provenance["sources"]["output_verifier"]["FRAME_COUNT_ABSOLUTE"],
        "frame_count_fraction": provenance["sources"]["output_verifier"]["FRAME_COUNT_FRACTION"],
    }:
        raise InvalidInput("frozen verifier tolerances are inconsistent")
    if pts["tolerance_floor_us"] != provenance["sources"]["pts_alignment"]["TOLERANCE_FLOOR_US"] or pts["max_alignment_drops"] != provenance["sources"]["pts_alignment"]["MAX_ALIGNMENT_DROPS"]:
        raise InvalidInput("frozen PTS thresholds are inconsistent")
    if repository is not None and authority_snapshot is not None:
        raise InvalidInput("provide repository or authority snapshot, not both")
    if repository is not None:
        authority_snapshot = capture_authority_snapshot(repository, require_all=require_complete_authority)
    if authority_snapshot is not None:
        live_authority = fingerprint_snapshot(authority_snapshot)
        live_thresholds = extract_authority_thresholds(snapshot=authority_snapshot)
        if repository_authority != live_authority:
            raise InvalidInput("frozen repository authority does not match the live captured snapshot")
        if provenance["sources"] != live_thresholds:
            raise InvalidInput("frozen thresholds do not match the live captured Kotlin authorities")
        host_metrics = [
            metric
            for metric in spec["metrics"]
            if set(metric["provenance"]) == {"host_scorer_sha256", "model_selector", "harness_schema_version"}
        ]
        if host_metrics:
            scorer_data = authority_snapshot.files.get(_HOST_SCORER_PATH)
            if scorer_data is None:
                raise InvalidInput("live host scorer authority is unavailable")
            live_contract = host_scorer_contract(scorer_data)
            for metric in host_metrics:
                if any(metric[key] != live_contract[key] for key in ("metric_id", "version", "model_id", "provenance")):
                    raise InvalidInput("frozen host metric does not match the live scorer contract")
    assert_sanitized(spec)


class ExperimentSpecStore:
    """Immutable experiment-specification publication and retrieval."""

    def __init__(self, experiment: Path) -> None:
        self.experiment = ExperimentStore(experiment)

    def _path(self, sha256: str) -> Path:
        require_sha256(sha256, "experiment specification hash")
        return self.experiment.owned_path("records", "experiment_spec", f"{sha256}.json")

    def put(self, spec: dict[str, Any], *, repository: Path | None = None, authority_snapshot: AuthoritySnapshot | None = None) -> Path:
        validate_experiment_spec(spec, require_complete_authority=True, repository=repository, authority_snapshot=authority_snapshot)
        target = self._path(spec["content_sha256"])
        atomic_publish_new(target, canonical_json_file_bytes(spec), root=self.experiment.root)
        return target

    def get(self, sha256: str, *, repository: Path | None = None, authority_snapshot: AuthoritySnapshot | None = None) -> dict[str, Any]:
        target = self._path(sha256)
        if not target.is_file():
            raise EvidenceBlocked("experiment specification is unavailable")
        try:
            spec = load_json(target)
            validate_experiment_spec(spec, require_complete_authority=True, repository=repository, authority_snapshot=authority_snapshot)
        except (InvalidInput, ImmutableConflict) as exc:
            raise EvidenceBlocked("stored experiment specification is invalid") from exc
        if spec["content_sha256"] != sha256:
            raise EvidenceBlocked("experiment specification filename/hash mismatch")
        return spec
