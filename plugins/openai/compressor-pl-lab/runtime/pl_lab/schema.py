"""Small strict validator for the JSON Schema subset used by this package."""

from __future__ import annotations

from pathlib import Path
import math
import re
from typing import Any

from .canonical import canonical_bytes
from .errors import InvalidInput
from .jsonio import load_json
from .paths import assert_contained


_TYPE_CHECKS = {
    "object": lambda value: isinstance(value, dict),
    "array": lambda value: isinstance(value, list),
    "string": lambda value: isinstance(value, str),
    "integer": lambda value: isinstance(value, int) and not isinstance(value, bool),
    "number": lambda value: isinstance(value, int) and not isinstance(value, bool) or (
        isinstance(value, float) and math.isfinite(value)
    ),
    "boolean": lambda value: isinstance(value, bool),
    "null": lambda value: value is None,
}

_SUPPORTED_KEYWORDS = frozenset({
    "$schema",
    "title",
    "description",
    "type",
    "const",
    "enum",
    "oneOf",
    "not",
    "required",
    "properties",
    "additionalProperties",
    "items",
    "minItems",
    "maxItems",
    "uniqueItems",
    "minLength",
    "maxLength",
    "pattern",
    "minimum",
    "maximum",
})


def _assert_supported_schema(schema: Any, path: str = "$") -> None:
    if not isinstance(schema, dict):
        raise InvalidInput(f"schema node at {path} must be an object")
    unknown = sorted(set(schema) - _SUPPORTED_KEYWORDS)
    if unknown:
        raise InvalidInput(f"unsupported JSON Schema keywords at {path}", details={"unknown": unknown})
    declared_type = schema.get("type")
    if declared_type is not None:
        types = [declared_type] if isinstance(declared_type, str) else declared_type
        if not isinstance(types, list) or not types or any(item not in _TYPE_CHECKS for item in types):
            raise InvalidInput(f"unsupported JSON Schema type at {path}")
    additional = schema.get("additionalProperties")
    if additional is not None and not isinstance(additional, bool):
        raise InvalidInput(f"schema objects for additionalProperties are unsupported at {path}")
    properties = schema.get("properties", {})
    if not isinstance(properties, dict):
        raise InvalidInput(f"properties at {path} must be an object")
    for key, child in properties.items():
        _assert_supported_schema(child, f"{path}.properties.{key}")
    if "items" in schema:
        _assert_supported_schema(schema["items"], f"{path}.items")
    for index, child in enumerate(schema.get("oneOf", [])):
        _assert_supported_schema(child, f"{path}.oneOf[{index}]")
    if "not" in schema:
        _assert_supported_schema(schema["not"], f"{path}.not")


class SchemaRegistry:
    def __init__(self, root: Path) -> None:
        self.root = Path(root).resolve(strict=True)

    def load(self, relative: str) -> dict[str, Any]:
        path = assert_contained(self.root, self.root / relative)
        schema = load_json(path)
        if not isinstance(schema, dict):
            raise InvalidInput("schema root must be an object")
        _assert_supported_schema(schema)
        return schema

    def validate(self, relative: str, instance: Any) -> None:
        self._validate(self.load(relative), instance, "$")

    def _validate(self, schema: dict[str, Any], value: Any, path: str) -> None:
        if "const" in schema and value != schema["const"]:
            raise InvalidInput(f"{path} must equal {schema['const']!r}")
        if "enum" in schema and value not in schema["enum"]:
            raise InvalidInput(f"{path} is not an allowed value")
        if "oneOf" in schema:
            successes = 0
            for option in schema["oneOf"]:
                try:
                    self._validate(option, value, path)
                except InvalidInput:
                    pass
                else:
                    successes += 1
            if successes != 1:
                raise InvalidInput(f"{path} must satisfy exactly one schema")
        if "not" in schema:
            try:
                self._validate(schema["not"], value, path)
            except InvalidInput:
                pass
            else:
                raise InvalidInput(f"{path} must not satisfy the excluded schema")
        declared_type = schema.get("type")
        if declared_type is not None:
            allowed = [declared_type] if isinstance(declared_type, str) else declared_type
            if not any(_TYPE_CHECKS.get(item, lambda _: False)(value) for item in allowed):
                raise InvalidInput(f"{path} has the wrong JSON type")
        if isinstance(value, dict):
            required = schema.get("required", [])
            missing = [key for key in required if key not in value]
            if missing:
                raise InvalidInput(f"{path} is missing required properties", details={"missing": missing})
            properties = schema.get("properties", {})
            if schema.get("additionalProperties") is False:
                unknown = sorted(set(value) - set(properties))
                if unknown:
                    raise InvalidInput(f"{path} has unknown properties", details={"unknown": unknown})
            for key, item in value.items():
                child = properties.get(key)
                if isinstance(child, dict):
                    self._validate(child, item, f"{path}.{key}")
        elif isinstance(value, list):
            if "minItems" in schema and len(value) < schema["minItems"]:
                raise InvalidInput(f"{path} has too few items")
            if "maxItems" in schema and len(value) > schema["maxItems"]:
                raise InvalidInput(f"{path} has too many items")
            if schema.get("uniqueItems"):
                identities = [canonical_bytes(item) for item in value]
                if len(set(identities)) != len(identities):
                    raise InvalidInput(f"{path} contains duplicate items")
            item_schema = schema.get("items")
            if isinstance(item_schema, dict):
                for index, item in enumerate(value):
                    self._validate(item_schema, item, f"{path}[{index}]")
        elif isinstance(value, str):
            if "minLength" in schema and len(value) < schema["minLength"]:
                raise InvalidInput(f"{path} is too short")
            if "maxLength" in schema and len(value) > schema["maxLength"]:
                raise InvalidInput(f"{path} is too long")
            if "pattern" in schema and re.search(schema["pattern"], value) is None:
                raise InvalidInput(f"{path} does not match its required pattern")
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            if "minimum" in schema and value < schema["minimum"]:
                raise InvalidInput(f"{path} is below its minimum")
            if "maximum" in schema and value > schema["maximum"]:
                raise InvalidInput(f"{path} is above its maximum")
