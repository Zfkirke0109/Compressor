"""Exclusive atomic publication for immutable evidence."""

from __future__ import annotations

import errno
import os
from pathlib import Path
import secrets

from .errors import ImmutableConflict
from .paths import assert_contained, assert_no_reparse_points


def _existing_matches(target: Path, data: bytes) -> bool:
    try:
        existing = target.read_bytes()
    except OSError as exc:
        raise ImmutableConflict(f"cannot verify existing immutable file: {target.name}") from exc
    if existing == data:
        return False
    raise ImmutableConflict(f"immutable file already exists with different content: {target.name}")


def atomic_publish_new(target: Path, data: bytes, *, root: Path | None = None) -> bool:
    """Publish complete bytes without overwrite; identical retries are idempotent."""

    target = Path(target)
    containment_root = Path(root) if root is not None else target.parent
    if not isinstance(data, bytes):
        raise TypeError("data must be bytes")
    if not containment_root.exists():
        raise ImmutableConflict("atomic publication root does not exist")
    target = assert_contained(containment_root, target)
    assert_no_reparse_points(target, stop_at=Path(containment_root).absolute())
    target.parent.mkdir(parents=True, exist_ok=True)
    assert_no_reparse_points(target.parent, stop_at=Path(containment_root).absolute())
    if target.exists():
        assert_no_reparse_points(target, stop_at=Path(containment_root).absolute())
        return _existing_matches(target, data)

    temporary = target.parent / f".{target.name}.{os.getpid()}.{secrets.token_hex(8)}.tmp"
    descriptor = None
    try:
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0), 0o600)
        with os.fdopen(descriptor, "wb", closefd=True) as handle:
            descriptor = None
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        try:
            assert_no_reparse_points(target.parent, stop_at=Path(containment_root).absolute())
            os.link(temporary, target)
        except FileExistsError:
            return _existing_matches(target, data)
        except OSError as exc:
            if os.name == "nt" and exc.errno in (errno.EPERM, errno.EACCES, errno.ENOTSUP):
                try:
                    os.rename(temporary, target)
                except FileExistsError:
                    return _existing_matches(target, data)
                return True
            raise ImmutableConflict(f"atomic publication failed: {target.name}") from exc
        return True
    finally:
        if descriptor is not None:
            os.close(descriptor)
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass
