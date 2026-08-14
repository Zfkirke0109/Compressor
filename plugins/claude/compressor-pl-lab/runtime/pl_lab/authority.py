"""Repository authority fingerprints and Kotlin threshold extraction."""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
from pathlib import Path
import re
from typing import Any

from .canonical import domain_hash
from .errors import EvidenceBlocked, InvalidInput
from .experiment import ExperimentStore
from .paths import assert_contained, assert_no_reparse_points, logical_path
from .process import ProcessBudget, run_bounded


AUTHORITY_PATHS = (
    "app/src/main/java/compress/joshattic/us/BatchQualityBitratePolicy.kt",
    "app/src/main/java/compress/joshattic/us/quality/QualityProbePolicy.kt",
    "app/src/main/java/compress/joshattic/us/OutputVerifier.kt",
    "app/src/main/java/compress/joshattic/us/SmartPerceptualProfileEngine.kt",
    "app/src/main/java/compress/joshattic/us/BatchCompressorViewModel.kt",
    "app/src/main/java/compress/joshattic/us/CompressorViewModel.kt",
    "app/src/main/java/compress/joshattic/us/ExperimentalEncoderControls.kt",
    "app/src/main/java/compress/joshattic/us/EncoderCapabilityDiagnostics.kt",
    "app/src/main/java/compress/joshattic/us/DeviceCapabilityProfile.kt",
    "app/src/main/java/compress/joshattic/us/Mp4MetadataRemuxer.kt",
    "app/src/main/java/compress/joshattic/us/quality/PerceptualQualityProber.kt",
    "app/src/main/java/compress/joshattic/us/quality/VmafNative.kt",
    "app/src/main/java/compress/joshattic/us/quality/VmafPairScorer.kt",
    "app/src/main/java/compress/joshattic/us/quality/PtsAligner.kt",
    "app/src/main/java/compress/joshattic/us/quality/YuvFrameReader.kt",
    "app/src/main/cpp/vmaf_jni.c",
    "app/src/main/cpp/CMakeLists.txt",
    "app/build.gradle.kts",
    "gradle/libs.versions.toml",
    "scripts/diagnostics/measure_quality.py",
)

_THRESHOLD_GROUPS = {
    "quality_probe": (
        "app/src/main/java/compress/joshattic/us/quality/QualityProbePolicy.kt",
        (
            "WINDOW_MEAN_MIN",
            "WINDOW_P5_MIN",
            "WINDOW_MIN_MIN",
            "MIN_COMPARED_FRAMES_PER_WINDOW",
            "HARD_RATIO_FLOOR",
            "SAFEST_RATIO_CEILING",
            "PROBE_MIN_SOURCE_BITS_PER_PIXEL",
            "REFINEMENT_MIN_GAP",
        ),
    ),
    "bitrate_policy": (
        "app/src/main/java/compress/joshattic/us/BatchQualityBitratePolicy.kt",
        (
            "PERCEPTUAL_LOSSLESS_SIZE_TOLERANCE",
            "PERCEPTUAL_LOSSLESS_MIN_SOURCE_BITS_PER_PIXEL",
            "MIN_PERCEPTUAL_LOSSLESS_PREDICTED_SAVINGS",
            "MIN_PERCEPTUAL_LOSSLESS_SAVINGS_BYTES",
            "PERCEPTUAL_LOSSLESS_MAX_TARGET_RATIO",
            "HDR_120_RATIO_FLOOR",
            "HDR_4K60_RATIO_FLOOR",
            "HDR_4K_RATIO_FLOOR",
            "FPS120_RATIO_FLOOR",
            "FPS60_4K_RATIO_FLOOR",
            "FPS60_RATIO_FLOOR",
            "UHD_RATIO_FLOOR",
            "DEFAULT_RATIO_FLOOR",
        ),
    ),
    "pts_alignment": (
        "app/src/main/java/compress/joshattic/us/quality/PtsAligner.kt",
        ("TOLERANCE_FLOOR_US", "MAX_ALIGNMENT_DROPS"),
    ),
}

_GIT_OUTPUT_LIMIT = 16 * 1024 * 1024
_UNTRACKED_BYTE_LIMIT = 64 * 1024 * 1024
_UNTRACKED_FILE_LIMIT = 10_000


@dataclass(frozen=True)
class AuthoritySnapshot:
    root: Path
    files: dict[str, bytes]
    missing: tuple[str, ...]
    repository_identity: dict[str, Any]


def _authority_bytes(root: Path, relative: str) -> bytes:
    path = assert_contained(root, root / relative)
    assert_no_reparse_points(path, stop_at=root.absolute())
    try:
        return path.read_bytes()
    except OSError as exc:
        raise InvalidInput(f"cannot read authority file: {Path(relative).name}") from exc


def _sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _git(repository: Path, *arguments: str, output_limit: int = _GIT_OUTPUT_LIMIT) -> str:
    result = run_bounded(
        ["git", *arguments],
        budget=ProcessBudget(timeout_seconds=20, max_output_bytes=output_limit),
        cwd=repository,
    )
    if result.return_code != 0:
        raise InvalidInput("Git repository identity command failed")
    if result.output_truncated:
        raise EvidenceBlocked("Git repository identity exceeded its output budget")
    return result.stdout


def _verified_experiment_relative(root: Path, experiment_root: Path | None) -> str | None:
    if experiment_root is None:
        return None
    candidate = Path(experiment_root).absolute()
    try:
        candidate_relative = candidate.relative_to(root)
    except ValueError:
        return None
    if not candidate_relative.parts:
        raise InvalidInput("repository root cannot be excluded as an experiment")
    if not candidate.exists():
        return None
    verified = ExperimentStore(candidate).verified_root()
    try:
        relative = verified.relative_to(root)
    except ValueError as exc:
        raise InvalidInput("experiment root escapes the repository") from exc
    if not relative.parts:
        raise InvalidInput("repository root cannot be excluded as an experiment")
    return logical_path(relative.as_posix())


def _repository_identity(root: Path, *, required: bool, experiment_root: Path | None = None) -> dict[str, Any]:
    if not (root / ".git").exists():
        if required:
            raise InvalidInput("Git repository identity is unavailable")
        body = {"vcs": "unavailable", "commit_oid": "unavailable", "dirty": False, "dirty_diff_sha256": domain_hash("repository-dirty-diff", ""), "untracked_identity_sha256": domain_hash("repository-untracked", []), "untracked_count": 0}
        return {**body, "identity_sha256": domain_hash("repository-workspace-identity", body)}
    experiment_relative = _verified_experiment_relative(root, experiment_root)
    commit_oid = _git(root, "rev-parse", "--verify", "HEAD").strip()
    if re.fullmatch(r"[0-9a-fA-F]{40,64}", commit_oid) is None:
        raise InvalidInput("Git commit identity is invalid")
    unstaged = _git(root, "diff", "--no-ext-diff", "--binary", "--", ".")
    staged = _git(root, "diff", "--cached", "--no-ext-diff", "--binary", "--", ".")
    dirty_status = _git(root, "status", "--porcelain=v1", "-z", "--untracked-files=no")
    untracked_arguments = ["ls-files", "--others", "--exclude-standard", "-z"]
    if experiment_relative is not None:
        untracked_arguments.extend(("--", ".", f":(exclude,top,literal){experiment_relative}"))
    untracked_output = _git(root, *untracked_arguments)
    untracked_paths = sorted(
        normalized
        for item in untracked_output.split("\0")
        if item
        for normalized in (logical_path(item),)
        if experiment_relative is None
        or (normalized != experiment_relative and not normalized.startswith(experiment_relative + "/"))
    )
    if len(untracked_paths) > _UNTRACKED_FILE_LIMIT:
        raise EvidenceBlocked("untracked repository identity exceeds its file budget")
    untracked_identities: list[dict[str, Any]] = []
    total_bytes = 0
    for relative in untracked_paths:
        path = assert_contained(root, root / relative)
        assert_no_reparse_points(path, stop_at=root)
        try:
            data = path.read_bytes()
        except OSError as exc:
            raise EvidenceBlocked("untracked repository identity changed during capture") from exc
        total_bytes += len(data)
        if total_bytes > _UNTRACKED_BYTE_LIMIT:
            raise EvidenceBlocked("untracked repository identity exceeds its byte budget")
        untracked_identities.append({"logical_path_sha256": domain_hash("repository-logical-path", relative), "sha256": _sha256_bytes(data), "size_bytes": len(data)})
    body = {
        "vcs": "git",
        "commit_oid": commit_oid.casefold(),
        "dirty": bool(dirty_status),
        "dirty_diff_sha256": domain_hash("repository-dirty-diff", {"unstaged": unstaged, "staged": staged}),
        "untracked_identity_sha256": domain_hash("repository-untracked", untracked_identities),
        "untracked_count": len(untracked_identities),
    }
    return {**body, "identity_sha256": domain_hash("repository-workspace-identity", body)}


def capture_authority_snapshot(
    repository: Path,
    *,
    require_all: bool = True,
    experiment_root: Path | None = None,
) -> AuthoritySnapshot:
    root = Path(repository).resolve(strict=True)
    files: dict[str, bytes] = {}
    missing: list[str] = []
    for relative in AUTHORITY_PATHS:
        path = assert_contained(root, root / relative)
        if not path.is_file():
            missing.append(relative)
            continue
        files[relative] = _authority_bytes(root, relative)
    if require_all and missing:
        raise InvalidInput("repository authority is incomplete", details={"missing": missing})
    if not files:
        raise InvalidInput("no repository authority files found")
    return AuthoritySnapshot(
        root=root,
        files=files,
        missing=tuple(missing),
        repository_identity=_repository_identity(root, required=require_all, experiment_root=experiment_root),
    )


def _parse_kotlin_constant(text: str, name: str) -> int | float:
    pattern = re.compile(
        rf"(?m)^\s*(?:private\s+)?const\s+val\s+{re.escape(name)}\s*=\s*"
        r"([0-9][0-9_]*(?:\.[0-9_]+)?)(?:[fFdDlL])?\s*$"
    )
    matches = pattern.findall(text)
    if len(matches) != 1:
        raise InvalidInput(f"expected exactly one authoritative Kotlin constant: {name}")
    literal = matches[0].replace("_", "")
    return float(literal) if "." in literal else int(literal)


def extract_authority_thresholds(repository: Path | None = None, *, snapshot: AuthoritySnapshot | None = None) -> dict[str, Any]:
    if snapshot is None:
        if repository is None:
            raise InvalidInput("repository or authority snapshot is required")
        snapshot = capture_authority_snapshot(repository, require_all=False)
    result: dict[str, Any] = {}
    for group, (relative, names) in _THRESHOLD_GROUPS.items():
        try:
            data = snapshot.files[relative]
            text = data.decode("utf-8")
        except (KeyError, UnicodeError) as exc:
            raise InvalidInput(f"missing threshold authority: {logical_path(relative)}") from exc
        values = {name: _parse_kotlin_constant(text, name) for name in names}
        result[group] = {
            **values,
            "source_path": logical_path(relative),
            "source_sha256": _sha256_bytes(data),
        }
    output_relative = "app/src/main/java/compress/joshattic/us/OutputVerifier.kt"
    try:
        output_data = snapshot.files[output_relative]
        output_text = output_data.decode("utf-8")
    except (KeyError, UnicodeError) as exc:
        raise InvalidInput(f"missing threshold authority: {logical_path(output_relative)}") from exc
    patterns = {
        "DURATION_ABSOLUTE_MS": r"maxOf\(\s*([0-9_]+)L\s*,\s*\(input\.source\.durationMs\s*\*\s*[0-9.]+\)",
        "DURATION_FRACTION": r"maxOf\(\s*[0-9_]+L\s*,\s*\(input\.source\.durationMs\s*\*\s*([0-9.]+)\)",
        "FRAME_COUNT_ABSOLUTE": r"maxOf\(\s*([0-9_]+)\s*,\s*\(input\.sourceFrameCount\s*\*\s*[0-9.]+\)",
        "FRAME_COUNT_FRACTION": r"maxOf\(\s*[0-9_]+\s*,\s*\(input\.sourceFrameCount\s*\*\s*([0-9.]+)\)",
    }
    output_values: dict[str, int | float | str] = {}
    for name, pattern in patterns.items():
        matches = re.findall(pattern, output_text, flags=re.DOTALL)
        if len(matches) != 1:
            raise InvalidInput(f"expected exactly one authoritative OutputVerifier tolerance: {name}")
        literal = matches[0].replace("_", "")
        output_values[name] = float(literal) if "." in literal else int(literal)
    result["output_verifier"] = {
        **output_values,
        "source_path": logical_path(output_relative),
        "source_sha256": _sha256_bytes(output_data),
    }
    return result


def fingerprint_snapshot(snapshot: AuthoritySnapshot) -> dict[str, Any]:
    files = [{"logical_path": logical_path(relative), "sha256": _sha256_bytes(data)} for relative, data in snapshot.files.items()]
    hash_input = {"files": files, "repository_identity": snapshot.repository_identity}
    return {
        "schema_version": "1",
        "files": files,
        "authority_sha256": domain_hash("repository-authority", hash_input),
        "missing_authorities": [logical_path(item) for item in snapshot.missing],
        "repository_identity": snapshot.repository_identity,
    }


def fingerprint_repository(
    repository: Path,
    *,
    require_all: bool = True,
    experiment_root: Path | None = None,
) -> dict[str, Any]:
    return fingerprint_snapshot(
        capture_authority_snapshot(
            repository,
            require_all=require_all,
            experiment_root=experiment_root,
        )
    )
