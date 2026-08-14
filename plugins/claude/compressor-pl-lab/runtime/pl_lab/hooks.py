"""Deterministic host-hook dispatcher for fast edit checks and Stop claims."""

from __future__ import annotations

from dataclasses import dataclass
import json
import os
from pathlib import Path
import re
import secrets
import sys
import tempfile
import time
from typing import Any, Callable, Sequence

from .authority import capture_authority_snapshot
from .canonical import domain_hash
from .errors import EvidenceBlocked, InvalidInput, LabError
from .experiment import ExperimentStore
from .judge import judge_evidence
from .operations.report import verify_artifact_bytes
from .paths import assert_no_reparse_points
from .process import ProcessBudget, ProcessResult, run_bounded
from .records import RecordStore
from .specification import ExperimentSpecStore


MAX_HOOK_INPUT_BYTES = 2 * 1024 * 1024
HOOK_TIMEOUT_SECONDS = 240
HOOK_OUTPUT_BYTES = 512 * 1024
LOCK_STALE_SECONDS = 30 * 60

RELEVANT_PATHS = frozenset(
    {
        "app/src/main/java/compress/joshattic/us/batchqualitybitratepolicy.kt",
        "app/src/main/java/compress/joshattic/us/quality/qualityprobepolicy.kt",
        "app/src/main/java/compress/joshattic/us/outputverifier.kt",
        "app/src/main/java/compress/joshattic/us/smartperceptualprofileengine.kt",
        "app/src/main/java/compress/joshattic/us/batchcompressorviewmodel.kt",
        "app/src/main/java/compress/joshattic/us/compressorviewmodel.kt",
        "app/src/main/java/compress/joshattic/us/experimentalencodercontrols.kt",
        "app/src/main/java/compress/joshattic/us/encodercapabilitydiagnostics.kt",
        "app/src/main/java/compress/joshattic/us/devicecapabilityprofile.kt",
        "app/src/main/java/compress/joshattic/us/quality/vmafpairscorer.kt",
        "app/src/main/java/compress/joshattic/us/quality/ptsaligner.kt",
        "app/src/main/java/compress/joshattic/us/quality/vmafnative.kt",
        "app/src/main/cpp/vmaf_jni.c",
        "app/src/main/cpp/cmakelists.txt",
        "app/build.gradle.kts",
        "gradle/libs.versions.toml",
    }
)

GRADLE_TESTS = (
    "compress.joshattic.us.BatchQualityBitratePolicyTest",
    "compress.joshattic.us.BatchQualitySafetyTest",
    "compress.joshattic.us.OutputVerificationFormatterTest",
    "compress.joshattic.us.PixelProvenFloorTest",
    "compress.joshattic.us.ReplacementSizeCheckTest",
    "compress.joshattic.us.SmartPerceptualProfileEngineTest",
    "compress.joshattic.us.VerificationLabelHonestyTest",
    "compress.joshattic.us.quality.PtsAlignerTest",
    "compress.joshattic.us.quality.QualityProbePolicyTest",
)

_PATCH_PATH = re.compile(r"(?m)^\*\*\* (?:(?:Add|Update|Delete) File|Move to):\s*(.+?)\s*$")
_PROTOCOL = re.compile(r"(?m)^PL_([A-Z0-9_]+):[ \t]*(.*)$")
_PL_CONTEXT = re.compile(r"(?i)\b(?:PL|perceptual[- ]lossless|benchmark|candidate|VMAF|quality|evidence)\b")
_UNSTRUCTURED_COMPLETION = re.compile(r"(?i)\b(?:pass(?:ed|es)?|complete(?:d)?|improved?|optimized?|successful(?:ly)?|all\s+checks)\b")
_ABSOLUTE_PROTOCOL_PATH = re.compile(
    r"(?i)(?:^|[\s=])(?:[a-z]:[\\/]|\\\\|//|/(?!/)[^\s]+|\\(?!\\)[^\s]+)"
)


@dataclass(frozen=True)
class HookOutcome:
    exit_code: int = 0
    stdout: str = ""
    stderr: str = ""


def _normalized_path(value: str) -> str:
    normalized = value.strip().replace("\\", "/").casefold()
    if normalized.startswith("//?/"):
        normalized = normalized[4:]
    return re.sub(r"/+", "/", normalized)


def affected_paths(payload: dict[str, Any]) -> set[str]:
    tool_input = payload.get("tool_input")
    if not isinstance(tool_input, dict):
        return set()
    values: list[str] = []
    for key in ("file_path", "path"):
        value = tool_input.get(key)
        if isinstance(value, str):
            values.append(value)
    files = tool_input.get("files")
    if isinstance(files, list):
        values.extend(value for value in files if isinstance(value, str))
    for key in ("patch", "command"):
        patch = tool_input.get(key)
        if isinstance(patch, str) and len(patch.encode("utf-8")) <= MAX_HOOK_INPUT_BYTES:
            values.extend(_PATCH_PATH.findall(patch))
    return {_normalized_path(value) for value in values if value.strip()}


def is_relevant_path(value: str) -> bool:
    path = _normalized_path(value)
    return any(path == relative or path.endswith("/" + relative) for relative in RELEVANT_PATHS)


def _repository_root(payload: dict[str, Any]) -> Path:
    candidates: list[Path] = []
    cwd = payload.get("cwd")
    if isinstance(cwd, str) and cwd:
        candidates.append(Path(cwd))
    candidates.append(Path.cwd())
    for candidate in candidates:
        try:
            resolved = candidate.resolve(strict=True)
        except OSError:
            continue
        for root in (resolved, *resolved.parents):
            if (root / "settings.gradle.kts").is_file() and (root / "app" / "build.gradle.kts").is_file():
                return root
    raise InvalidInput("hook input does not identify the Compressor repository")


def _lock_directory(explicit: Path | None = None, *, repository: Path | None = None) -> Path:
    if explicit is not None:
        root = explicit
    else:
        configured = os.environ.get("PLUGIN_DATA") or os.environ.get("CLAUDE_PLUGIN_DATA")
        root = Path(configured) if configured else Path(tempfile.gettempdir()) / "compressor-pl-lab-hooks"
        if repository is not None:
            repository_identity = domain_hash(
                "hook-repository",
                _normalized_path(str(repository.resolve(strict=True))),
            )[:16]
            root = root / f"repository-{repository_identity}"
    root.mkdir(parents=True, exist_ok=True)
    return root


class _InvocationLock:
    def __init__(self, root: Path) -> None:
        self.path = root / "fast-check.lock"
        self.token = secrets.token_hex(16)
        self.acquired = False

    def __enter__(self) -> bool:
        for attempt in range(2):
            try:
                descriptor = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            except FileExistsError:
                try:
                    stale = time.time() - self.path.stat().st_mtime > LOCK_STALE_SECONDS
                except OSError:
                    stale = False
                if stale and attempt == 0:
                    try:
                        self.path.unlink()
                    except OSError:
                        return False
                    continue
                return False
            with os.fdopen(descriptor, "w", encoding="ascii") as handle:
                handle.write(self.token)
            self.acquired = True
            return True
        return False

    def __exit__(self, exc_type: object, exc: object, traceback: object) -> None:
        if not self.acquired:
            return
        try:
            if self.path.read_text(encoding="ascii") == self.token:
                self.path.unlink()
        except OSError:
            pass


CommandRunner = Callable[[Sequence[str], Path], ProcessResult]


def _run_command(argv: Sequence[str], cwd: Path) -> ProcessResult:
    return run_bounded(
        argv,
        cwd=cwd,
        budget=ProcessBudget(timeout_seconds=HOOK_TIMEOUT_SECONDS, max_output_bytes=HOOK_OUTPUT_BYTES),
    )


def _java_executable() -> str:
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if candidate.is_file():
            return str(candidate)
    return "java"


def _fast_commands(repository: Path) -> list[list[str]]:
    test_runner = repository / "tools" / "perceptual_lossless_lab" / "run_tests.py"
    sync = repository / "tools" / "perceptual_lossless_lab" / "scripts" / "sync_plugin_payloads.py"
    wrapper_jar = repository / "gradle" / "wrapper" / "gradle-wrapper.jar"
    if not test_runner.is_file() or not sync.is_file() or not wrapper_jar.is_file():
        raise InvalidInput("required fast-check files are unavailable")
    gradle = [
        _java_executable(),
        "-Dorg.gradle.appname=gradlew",
        "-classpath",
        str(wrapper_jar),
        "org.gradle.wrapper.GradleWrapperMain",
        ":app:testDebugUnitTest",
        "--offline",
        "--no-daemon",
    ]
    for test_name in GRADLE_TESTS:
        gradle.extend(("--tests", test_name))
    return [
        [sys.executable, "-B", str(test_runner), "--fast"],
        [sys.executable, "-B", str(sync), "--check"],
        gradle,
    ]


def post_tool_use(
    payload: dict[str, Any],
    *,
    runner: CommandRunner = _run_command,
    lock_root: Path | None = None,
) -> HookOutcome:
    if not isinstance(payload, dict):
        return HookOutcome(2, stderr="Compressor PL Lab hook rejected malformed input.\n")
    if os.environ.get("PL_LAB_HOOK_ACTIVE") == "1":
        return HookOutcome()
    paths = affected_paths(payload)
    if not paths or not any(is_relevant_path(path) for path in paths):
        return HookOutcome()
    try:
        repository = _repository_root(payload)
        with _InvocationLock(_lock_directory(lock_root, repository=repository)) as acquired:
            if not acquired:
                return HookOutcome(
                    2,
                    stderr="Compressor PL Lab fast check is already running; retry validation after it finishes.\n",
                )
            previous = os.environ.get("PL_LAB_HOOK_ACTIVE")
            os.environ["PL_LAB_HOOK_ACTIVE"] = "1"
            try:
                for index, command in enumerate(_fast_commands(repository), start=1):
                    result = runner(command, repository)
                    if result.return_code != 0:
                        return HookOutcome(2, stderr=f"Compressor PL Lab fast check {index} failed. Run the documented fast suite for details.\n")
            finally:
                if previous is None:
                    os.environ.pop("PL_LAB_HOOK_ACTIVE", None)
                else:
                    os.environ["PL_LAB_HOOK_ACTIVE"] = previous
    except (LabError, OSError) as exc:
        label = exc.code if isinstance(exc, LabError) else "hook_runtime_error"
        return HookOutcome(2, stderr=f"Compressor PL Lab hook could not validate the relevant edit ({label}).\n")
    return HookOutcome()


def _protocol_fields(message: str) -> dict[str, str]:
    pairs = _PROTOCOL.findall(message)
    result: dict[str, str] = {}
    for key, value in pairs:
        if key in result:
            raise InvalidInput("completion protocol contains duplicate fields")
        result[key] = value.strip()
    return result


def _logical_child(root: Path, value: str, label: str) -> Path:
    if not value or Path(value).is_absolute() or re.match(r"^[A-Za-z]:", value) or value.startswith(("\\", "/")):
        raise EvidenceBlocked(f"{label} must be a nonempty logical path")
    pieces = re.split(r"[\\/]", value)
    if any(piece in ("", ".", "..") for piece in pieces):
        raise EvidenceBlocked(f"{label} contains an unsafe segment")
    target = root.joinpath(*pieces)
    try:
        target.resolve(strict=False).relative_to(root.resolve(strict=True))
    except (OSError, ValueError) as exc:
        raise EvidenceBlocked(f"{label} escapes the experiment") from exc
    return target


def validate_completion_report(repository: Path, experiment: Path, report_logical_path: str, report_sha256: str, claimed_status: str) -> None:
    store_root = ExperimentStore(experiment)
    store_root.prepare(execute=False)
    expected_path = _logical_child(store_root.root, report_logical_path, "report path")
    expected_record_path = store_root.owned_path("records", "report", f"{report_sha256}.json")
    if expected_path != expected_record_path:
        raise EvidenceBlocked("report path does not match its content identity")
    assert_no_reparse_points(expected_path, stop_at=store_root.root)
    records = RecordStore(experiment)
    report = records.get("report", report_sha256)
    authority_snapshot = capture_authority_snapshot(
        repository,
        require_all=True,
        experiment_root=experiment,
    )
    spec = ExperimentSpecStore(experiment).get(report["experiment_spec_sha256"], authority_snapshot=authority_snapshot)
    evidence = records.load_evidence(report["payload"]["evidence_sha256s"])
    verify_artifact_bytes(records, evidence)
    expected_verdict = judge_evidence(spec, evidence, created_at=spec["created_at"], authority_snapshot=authority_snapshot)
    verdict_sha256 = report["payload"]["verdict_sha256"]
    stored_verdict = records.get("verdict", verdict_sha256)
    if expected_verdict["content_sha256"] != verdict_sha256 or report["links"]["verdict"] != verdict_sha256:
        raise EvidenceBlocked("report verdict is not reproducible from its evidence")
    actual = stored_verdict["payload"]["verdict"]
    if report["payload"]["verdict"] != actual or claimed_status != actual:
        raise EvidenceBlocked("completion claim does not match the report verdict")


ReportValidator = Callable[[Path, Path, str, str, str], None]


def stop_hook(payload: dict[str, Any], *, report_validator: ReportValidator = validate_completion_report) -> HookOutcome:
    if not isinstance(payload, dict) or payload.get("stop_hook_active") is True:
        return HookOutcome()
    message = payload.get("last_assistant_message")
    if not isinstance(message, str):
        return HookOutcome()
    try:
        fields = _protocol_fields(message)
    except InvalidInput:
        return HookOutcome(stdout=json.dumps({"decision": "block", "reason": "Completion protocol fields must be unique."}, separators=(",", ":")) + "\n")
    if not fields:
        if _PL_CONTEXT.search(message) and _UNSTRUCTURED_COMPLETION.search(message):
            reason = "A success/completion claim requires PL_STATUS plus a current hash-validated report."
            return HookOutcome(stdout=json.dumps({"decision": "block", "reason": reason}, separators=(",", ":")) + "\n")
        return HookOutcome()
    status = fields.get("STATUS")
    if status not in ("PASS", "FAIL", "BLOCKED"):
        reason = "PL_STATUS must be exactly PASS, FAIL, or BLOCKED."
        return HookOutcome(stdout=json.dumps({"decision": "block", "reason": reason}, separators=(",", ":")) + "\n")
    if status == "BLOCKED":
        missing = fields.get("MISSING", "")
        if set(fields) == {"STATUS", "MISSING"} and missing and not _ABSOLUTE_PROTOCOL_PATH.search(missing):
            return HookOutcome()
        reason = "PL_STATUS BLOCKED requires only PL_MISSING with named evidence and no absolute or private path."
        return HookOutcome(stdout=json.dumps({"decision": "block", "reason": reason}, separators=(",", ":")) + "\n")
    required = ("EXPERIMENT", "REPORT", "REPORT_SHA256")
    allowed = {"STATUS", *required}
    if (
        set(fields) != allowed
        or any(not fields.get(key) for key in required)
        or re.fullmatch(r"[0-9a-f]{64}", fields["REPORT_SHA256"]) is None
        or any(_ABSOLUTE_PROTOCOL_PATH.search(value) for value in fields.values())
    ):
        reason = "PASS/FAIL requires only PL_EXPERIMENT, PL_REPORT, and a valid PL_REPORT_SHA256 using logical paths."
        return HookOutcome(stdout=json.dumps({"decision": "block", "reason": reason}, separators=(",", ":")) + "\n")
    try:
        repository = _repository_root(payload)
        experiment = _logical_child(repository, fields["EXPERIMENT"], "experiment path")
        report_validator(repository, experiment, fields["REPORT"], fields["REPORT_SHA256"], status)
    except (LabError, OSError, KeyError, TypeError):
        reason = "The claimed verdict report is absent, invalid, stale, mismatched, or confounded."
        return HookOutcome(stdout=json.dumps({"decision": "block", "reason": reason}, separators=(",", ":")) + "\n")
    return HookOutcome()


def _read_payload() -> dict[str, Any] | None:
    data = sys.stdin.buffer.read(MAX_HOOK_INPUT_BYTES + 1)
    if len(data) > MAX_HOOK_INPUT_BYTES:
        return None
    try:
        value = json.loads(data.decode("utf-8"))
    except (UnicodeError, json.JSONDecodeError):
        return None
    return value if isinstance(value, dict) else None


def dispatch_main(argv: Sequence[str] | None = None) -> int:
    args = list(argv if argv is not None else sys.argv[1:])
    if len(args) != 1 or args[0] not in ("post-tool-use", "stop"):
        print("Compressor PL Lab hook mode is invalid.", file=sys.stderr)
        return 2
    payload = _read_payload()
    if payload is None:
        if args[0] == "post-tool-use":
            print("Compressor PL Lab hook rejected malformed input.", file=sys.stderr)
            return 2
        return 0
    outcome = post_tool_use(payload) if args[0] == "post-tool-use" else stop_hook(payload)
    if outcome.stdout:
        sys.stdout.write(outcome.stdout)
    if outcome.stderr:
        sys.stderr.write(outcome.stderr)
    return outcome.exit_code


if __name__ == "__main__":
    raise SystemExit(dispatch_main())
