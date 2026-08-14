"""Strict JSON parsing helpers."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from .canonical import canonical_bytes
from .errors import InvalidInput


def _reject_constant(value: str) -> None:
    raise InvalidInput(f"non-finite JSON number is forbidden: {value}")


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise InvalidInput(f"duplicate JSON object key: {key}")
        result[key] = value
    return result


def loads_strict(text: str) -> Any:
    try:
        return json.loads(
            text,
            parse_constant=_reject_constant,
            object_pairs_hook=_unique_object,
        )
    except InvalidInput:
        raise
    except (json.JSONDecodeError, TypeError) as exc:
        raise InvalidInput(f"invalid JSON: {exc}") from exc


def load_json(path: Path) -> Any:
    try:
        return loads_strict(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError) as exc:
        raise InvalidInput(f"cannot read JSON input: {path.name}") from exc


def canonical_json_file_bytes(value: Any) -> bytes:
    return canonical_bytes(value) + b"\n"
