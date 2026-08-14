"""Shared strict primitive validators."""

from __future__ import annotations

from datetime import datetime, timezone
import re
from typing import Any

from .errors import InvalidInput


RUN_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
UTC_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$")


def require_run_id(value: Any) -> str:
    if not isinstance(value, str) or RUN_ID_RE.fullmatch(value) is None:
        raise InvalidInput("invalid run ID")
    return value


def require_sha256(value: Any, path: str) -> str:
    if not isinstance(value, str) or SHA256_RE.fullmatch(value) is None:
        raise InvalidInput(f"invalid SHA-256 identity at {path}")
    return value


def require_utc_timestamp(value: Any, path: str = "created_at") -> str:
    if not isinstance(value, str) or UTC_RE.fullmatch(value) is None:
        raise InvalidInput(f"{path} must be UTC RFC3339 with seconds")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as exc:
        raise InvalidInput(f"{path} is not a real UTC timestamp") from exc
    if parsed.tzinfo != timezone.utc:
        raise InvalidInput(f"{path} must use UTC")
    return value
