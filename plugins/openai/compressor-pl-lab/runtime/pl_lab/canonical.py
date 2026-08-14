"""Canonical JSON and domain-separated content hashes."""

from __future__ import annotations

import hashlib
import json
import math
import re
from typing import Any

from .errors import InvalidInput


_DOMAIN_RE = re.compile(r"^[a-z0-9][a-z0-9:._-]{0,127}$")
SELF_HASH_FIELD = "content_sha256"


def _validate_json_value(value: Any, path: str = "$") -> None:
    if value is None or isinstance(value, (str, bool)):
        return
    if isinstance(value, int) and not isinstance(value, bool):
        return
    if isinstance(value, float):
        if not math.isfinite(value):
            raise InvalidInput(f"non-finite number at {path}")
        return
    if isinstance(value, list):
        for index, item in enumerate(value):
            _validate_json_value(item, f"{path}[{index}]")
        return
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str):
                raise InvalidInput(f"non-string object key at {path}")
            _validate_json_value(item, f"{path}.{key}")
        return
    raise InvalidInput(f"unsupported JSON value at {path}: {type(value).__name__}")


def canonical_bytes(value: Any) -> bytes:
    """Return stable UTF-8 JSON bytes, rejecting ambiguous/non-JSON values."""

    _validate_json_value(value)
    try:
        text = json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
        return text.encode("utf-8")
    except (UnicodeError, ValueError, TypeError) as exc:
        raise InvalidInput(f"value cannot be canonicalized: {exc}") from exc


def domain_hash(domain: str, value: Any) -> str:
    if not isinstance(domain, str) or not _DOMAIN_RE.fullmatch(domain):
        raise InvalidInput("invalid hash domain")
    prefix = f"compressor-pl-lab\x00v1\x00{domain}\x00".encode("ascii")
    return hashlib.sha256(prefix + canonical_bytes(value)).hexdigest()


def seal_document(domain: str, document: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(document, dict):
        raise InvalidInput("sealed document must be an object")
    if SELF_HASH_FIELD in document:
        raise InvalidInput(f"caller must not supply {SELF_HASH_FIELD}")
    sealed = dict(document)
    sealed[SELF_HASH_FIELD] = domain_hash(domain, document)
    return sealed


def verify_sealed_document(domain: str, document: dict[str, Any]) -> bool:
    if not isinstance(document, dict):
        return False
    claimed = document.get(SELF_HASH_FIELD)
    if not isinstance(claimed, str) or not re.fullmatch(r"[0-9a-f]{64}", claimed):
        return False
    body = {key: value for key, value in document.items() if key != SELF_HASH_FIELD}
    try:
        return domain_hash(domain, body) == claimed
    except InvalidInput:
        return False
