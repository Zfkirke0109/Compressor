"""Containment, logical-path, and reparse-point guards."""

from __future__ import annotations

import os
from pathlib import Path, PurePosixPath
import re
import stat

from .errors import InvalidInput


_WINDOWS_FORBIDDEN = re.compile(r'[<>:"|?*\x00-\x1f]')


def logical_path(value: str) -> str:
    if not isinstance(value, str) or not value:
        raise InvalidInput("logical path must be non-empty")
    if "\\" in value:
        raise InvalidInput("logical paths must use forward slashes")
    if ":" in value or re.match(r"^[A-Za-z]:", value) or value.startswith("/") or value.startswith("//"):
        raise InvalidInput("logical path must be relative")
    parts = value.split("/")
    if any(part in ("", ".", "..") for part in parts):
        raise InvalidInput("logical path contains an unsafe segment")
    if any(_WINDOWS_FORBIDDEN.search(part) or part.endswith((" ", ".")) for part in parts):
        raise InvalidInput("logical path contains non-portable filesystem syntax")
    normalized = PurePosixPath(*parts).as_posix()
    if normalized != value:
        raise InvalidInput("logical path is not normalized")
    return normalized


def _is_reparse(path: Path) -> bool:
    try:
        info = path.lstat()
    except FileNotFoundError:
        return False
    attributes = getattr(info, "st_file_attributes", 0)
    reparse_flag = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400)
    return path.is_symlink() or bool(attributes & reparse_flag)


def assert_no_reparse_points(path: Path, *, stop_at: Path | None = None) -> None:
    current = Path(path).absolute()
    stop = Path(stop_at).absolute() if stop_at is not None else None
    while True:
        if _is_reparse(current):
            raise InvalidInput(f"symlink/reparse segment is forbidden: {current.name}")
        if stop is not None and current == stop:
            return
        parent = current.parent
        if parent == current:
            return
        current = parent


def assert_contained(root: Path, candidate: Path) -> Path:
    root_path = Path(root).absolute()
    candidate_path = Path(candidate).absolute()
    assert_no_reparse_points(root_path)
    assert_no_reparse_points(candidate_path, stop_at=root_path)
    root_resolved = root_path.resolve(strict=False)
    candidate_resolved = candidate_path.resolve(strict=False)
    try:
        candidate_resolved.relative_to(root_resolved)
    except ValueError as exc:
        raise InvalidInput("path escapes the experiment directory") from exc
    return candidate_resolved
