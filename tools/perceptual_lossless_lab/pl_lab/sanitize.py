"""Reject private paths and raw identifiers from durable evidence."""

from __future__ import annotations

import re
from typing import Any, Iterable

from .canonical import domain_hash
from .errors import InvalidInput


_WINDOWS_ABSOLUTE = re.compile(r"(?i)(?:^|[^A-Za-z0-9])[a-z]:[\\/]")
_UNC = re.compile(r"(?:^|[=\s'\"(])\\\\[^\\]+\\")
_POSIX_ABSOLUTE = re.compile(r"(?:^|[=\s'\"(])/(?!/)")
_PRIVATE_WINDOWS = re.compile(r"[\\/]Users[\\/]", re.IGNORECASE)
_DEVICE_ALIAS = re.compile(r"^device-[0-9a-f]{12}$")
_RAW_ID_KEYS = frozenset({"deviceserial", "rawserial", "adbserial", "absolutepath", "privatepath"})


def _normalized_key(key: str) -> str:
    return re.sub(r"[^a-z0-9]", "", key.casefold())


def device_alias(raw_serial: str) -> str:
    if not isinstance(raw_serial, str) or not raw_serial.strip():
        raise InvalidInput("device serial must be a non-empty string")
    digest = domain_hash("private-device-alias", {"serial": raw_serial.strip()})
    return f"device-{digest[:12]}"


def _assert_safe_string(value: str, path: str, sensitive_values: tuple[str, ...]) -> None:
    if _WINDOWS_ABSOLUTE.search(value) or _UNC.search(value):
        raise InvalidInput(f"private absolute path at {path}")
    if _POSIX_ABSOLUTE.search(value) or _PRIVATE_WINDOWS.search(value):
        raise InvalidInput(f"private home path at {path}")
    folded = value.casefold()
    for sensitive in sensitive_values:
        if sensitive and sensitive.casefold() in folded:
            raise InvalidInput(f"sensitive identifier at {path}")


def assert_sanitized(document: Any, *, sensitive_values: Iterable[str] = ()) -> None:
    sensitive = tuple(value for value in sensitive_values if isinstance(value, str) and value)

    def visit(value: Any, path: str) -> None:
        if isinstance(value, dict):
            for key, item in value.items():
                folded_key = key.casefold()
                normalized_key = _normalized_key(key)
                if normalized_key in _RAW_ID_KEYS or normalized_key.endswith("serial"):
                    raise InvalidInput(f"raw identifier field is forbidden: {path}.{key}")
                _assert_safe_string(key, f"{path}.<key>", sensitive)
                if folded_key == "device_alias" and (not isinstance(item, str) or not _DEVICE_ALIAS.fullmatch(item)):
                    raise InvalidInput(f"invalid device alias at {path}.{key}")
                visit(item, f"{path}.{key}")
        elif isinstance(value, list):
            for index, item in enumerate(value):
                visit(item, f"{path}[{index}]")
        elif isinstance(value, str):
            _assert_safe_string(value, path, sensitive)

    visit(document, "$")
