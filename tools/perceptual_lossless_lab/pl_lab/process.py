"""Bounded argv-only subprocess execution with executable provenance."""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
import os
from pathlib import Path
import signal
import shutil
import subprocess
import threading
import time
from typing import Sequence

from .errors import EvidenceBlocked, InvalidInput, MissingPrerequisite
from .validation import require_sha256


_CAPTURE_CHUNK_BYTES = 64 * 1024
_TERMINATION_GRACE_SECONDS = 0.25
_READER_JOIN_SECONDS = 1.0


@dataclass(frozen=True)
class ProcessBudget:
    timeout_seconds: float
    max_output_bytes: int

    def validate(self) -> None:
        if not isinstance(self.timeout_seconds, (int, float)) or isinstance(self.timeout_seconds, bool) or self.timeout_seconds <= 0:
            raise InvalidInput("process timeout budget must be positive")
        if not isinstance(self.max_output_bytes, int) or isinstance(self.max_output_bytes, bool) or self.max_output_bytes <= 0:
            raise InvalidInput("process output budget must be a positive integer")


@dataclass(frozen=True)
class ProcessResult:
    return_code: int
    stdout: str
    stderr: str
    elapsed_ms: int
    executable_sha256: str
    output_truncated: bool


class _CappedCapture:
    """Drain a pipe completely while retaining only a fixed byte prefix."""

    def __init__(self, shared: "_SharedCaptureLimit") -> None:
        self._shared = shared
        self._chunks: list[bytes] = []
        self.truncated = False

    def append(self, chunk: bytes) -> None:
        retained = self._shared.retain(chunk)
        if retained:
            self._chunks.append(retained)
        if len(retained) != len(chunk):
            self.truncated = True

    def value(self) -> bytes:
        return b"".join(self._chunks)


class _SharedCaptureLimit:
    """One synchronized byte ceiling shared by stdout and stderr."""

    def __init__(self, maximum: int) -> None:
        self._maximum = maximum
        self._size = 0
        self._lock = threading.Lock()
        self.exceeded = threading.Event()

    def retain(self, chunk: bytes) -> bytes:
        with self._lock:
            remaining = self._maximum - self._size
            retained = chunk[:max(0, remaining)]
            self._size += len(retained)
            if len(retained) != len(chunk):
                self.exceeded.set()
            return retained


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError as exc:
        raise MissingPrerequisite("executable cannot be fingerprinted") from exc
    return digest.hexdigest()


def _resolve_executable(value: str) -> Path:
    candidate = Path(value)
    if candidate.is_absolute() or candidate.parent != Path("."):
        resolved = candidate.resolve(strict=False)
        if not resolved.is_file():
            raise MissingPrerequisite("required executable is unavailable")
        return resolved
    located = shutil.which(value)
    if not located:
        raise MissingPrerequisite("required executable is unavailable")
    return Path(located).resolve(strict=True)


def _bounded_text(data: bytes, maximum: int, sensitive_values: tuple[str, ...]) -> tuple[str, bool]:
    text = data.decode("utf-8", errors="replace")
    for sensitive in sensitive_values:
        if sensitive:
            text = text.replace(sensitive, "[REDACTED]")
    encoded = text.encode("utf-8")
    if len(encoded) <= maximum:
        return text, False
    clipped = encoded[:maximum]
    return clipped.decode("utf-8", errors="ignore"), True


def _safe_environment() -> dict[str, str]:
    return {
        key: value for key, value in os.environ.items()
        if key.upper() in {"SYSTEMROOT", "WINDIR", "PATH", "PATHEXT", "TEMP", "TMP"}
    }


def _drain_pipe(
    stream: object,
    capture: _CappedCapture,
    failures: list[BaseException],
) -> None:
    try:
        reader = getattr(stream, "read1", None) or getattr(stream, "read")
        while True:
            chunk = reader(_CAPTURE_CHUNK_BYTES)
            if not chunk:
                break
            capture.append(chunk)
    except (OSError, ValueError) as exc:
        failures.append(exc)
    finally:
        try:
            getattr(stream, "close")()
        except (OSError, ValueError):
            pass


def _taskkill_tree(process_id: int, environment: dict[str, str]) -> None:
    system_root = environment.get("SYSTEMROOT") or environment.get("WINDIR")
    executable: Path | None = None
    if system_root:
        candidate = Path(system_root) / "System32" / "taskkill.exe"
        if candidate.is_file():
            executable = candidate
    if executable is None:
        located = shutil.which("taskkill", path=environment.get("PATH"))
        if located:
            executable = Path(located)
    if executable is None:
        return
    try:
        killer = subprocess.Popen(
            [str(executable), "/PID", str(process_id), "/T", "/F"],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            env=environment,
            shell=False,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        try:
            killer.wait(timeout=_READER_JOIN_SECONDS)
        except subprocess.TimeoutExpired:
            killer.kill()
            killer.wait(timeout=_READER_JOIN_SECONDS)
    except (OSError, subprocess.SubprocessError):
        return


def _terminate_process_tree(process: subprocess.Popen[bytes], environment: dict[str, str]) -> None:
    """Best-effort termination of the isolated process group and descendants."""

    if os.name == "nt":
        # taskkill /T addresses descendants while the root PID still identifies
        # the tree. CREATE_NEW_PROCESS_GROUP remains the fallback when taskkill
        # is unavailable or denied.
        _taskkill_tree(process.pid, environment)
        if process.poll() is None:
            try:
                process.send_signal(signal.CTRL_BREAK_EVENT)
            except (AttributeError, OSError, ValueError):
                pass
            try:
                process.wait(timeout=_TERMINATION_GRACE_SECONDS)
            except subprocess.TimeoutExpired:
                try:
                    process.kill()
                except OSError:
                    pass
        return

    try:
        os.killpg(process.pid, signal.SIGTERM)
    except (ProcessLookupError, PermissionError):
        pass
    deadline = time.monotonic() + _TERMINATION_GRACE_SECONDS
    while process.poll() is None and time.monotonic() < deadline:
        time.sleep(0.01)
    # A child may ignore SIGTERM even after the group leader exits, so address
    # the group a second time rather than relying only on process.poll().
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        pass
    try:
        process.wait(timeout=_READER_JOIN_SECONDS)
    except subprocess.TimeoutExpired:
        try:
            process.kill()
        except OSError:
            pass


def _post_execution_hash(executable: Path) -> str:
    try:
        return _file_sha256(executable)
    except MissingPrerequisite as exc:
        raise EvidenceBlocked("executable cannot be re-fingerprinted after subprocess execution") from exc


def run_bounded(
    argv: Sequence[str],
    *,
    budget: ProcessBudget,
    cwd: Path | None = None,
    expected_executable_sha256: str | None = None,
    sensitive_values: Sequence[str] = (),
) -> ProcessResult:
    if isinstance(argv, (str, bytes)) or not isinstance(argv, Sequence) or not argv:
        raise InvalidInput("subprocess command must be a non-empty argv sequence")
    if any(not isinstance(item, str) or not item or "\x00" in item or "\n" in item or "\r" in item for item in argv):
        raise InvalidInput("subprocess argv contains an unsafe element")
    budget.validate()
    executable = _resolve_executable(argv[0])
    before = _file_sha256(executable)
    if expected_executable_sha256 is not None:
        require_sha256(expected_executable_sha256, "expected_executable_sha256")
        if before != expected_executable_sha256:
            raise EvidenceBlocked("executable fingerprint does not match the frozen prerequisite")
    command = [str(executable), *argv[1:]]
    environment = _safe_environment()
    sensitive = tuple(value for value in sensitive_values if isinstance(value, str) and value)
    redaction_overlap = max((len(value.encode("utf-8")) for value in sensitive), default=0)
    capture_limit = budget.max_output_bytes + redaction_overlap
    started = time.monotonic()
    try:
        process = subprocess.Popen(
            command,
            cwd=str(cwd) if cwd is not None else None,
            env=environment,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            shell=False,
            **(
                {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP}
                if os.name == "nt"
                else {"start_new_session": True}
            ),
        )
    except OSError as exc:
        raise MissingPrerequisite("required executable could not start") from exc

    assert process.stdout is not None
    assert process.stderr is not None
    shared_capture = _SharedCaptureLimit(capture_limit)
    stdout_capture = _CappedCapture(shared_capture)
    stderr_capture = _CappedCapture(shared_capture)
    reader_failures: list[BaseException] = []
    readers = [
        threading.Thread(
            target=_drain_pipe,
            args=(process.stdout, stdout_capture, reader_failures),
            name="pl-lab-stdout-reader",
            daemon=True,
        ),
        threading.Thread(
            target=_drain_pipe,
            args=(process.stderr, stderr_capture, reader_failures),
            name="pl-lab-stderr-reader",
            daemon=True,
        ),
    ]
    for reader in readers:
        reader.start()

    timeout_cause: subprocess.TimeoutExpired | None = None
    output_exceeded = False
    return_code: int | None = None
    deadline = started + float(budget.timeout_seconds)
    while True:
        if shared_capture.exceeded.is_set():
            output_exceeded = True
            _terminate_process_tree(process, environment)
            break
        remaining_seconds = deadline - time.monotonic()
        if remaining_seconds <= 0:
            timeout_cause = subprocess.TimeoutExpired(command, budget.timeout_seconds)
            _terminate_process_tree(process, environment)
            break
        try:
            return_code = process.wait(timeout=min(0.05, remaining_seconds))
            break
        except subprocess.TimeoutExpired:
            continue

    for reader in readers:
        reader.join(timeout=_READER_JOIN_SECONDS)
    readers_incomplete = any(reader.is_alive() for reader in readers)
    elapsed_ms = int((time.monotonic() - started) * 1000)
    after = _post_execution_hash(executable)
    if after != before:
        raise EvidenceBlocked("executable changed while the bounded subprocess was running")
    if output_exceeded or shared_capture.exceeded.is_set():
        raise EvidenceBlocked("bounded subprocess exceeded its output budget")
    if timeout_cause is not None:
        raise EvidenceBlocked("bounded subprocess exceeded its duration budget") from timeout_cause
    if reader_failures or readers_incomplete:
        raise EvidenceBlocked("bounded subprocess output could not be captured completely")
    if return_code is None:
        return_code = process.returncode
    if return_code is None:
        raise EvidenceBlocked("bounded subprocess did not yield an exit status")

    stdout, stdout_truncated = _bounded_text(stdout_capture.value(), budget.max_output_bytes, sensitive)
    remaining = max(0, budget.max_output_bytes - len(stdout.encode("utf-8")))
    stderr, stderr_truncated = _bounded_text(stderr_capture.value(), remaining, sensitive)
    return ProcessResult(
        return_code=return_code,
        stdout=stdout,
        stderr=stderr,
        elapsed_ms=elapsed_ms,
        executable_sha256=before,
        output_truncated=(
            stdout_capture.truncated
            or stderr_capture.truncated
            or stdout_truncated
            or stderr_truncated
        ),
    )
