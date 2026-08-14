"""Schema-valid common result for failures before an operation envelope exists."""

from __future__ import annotations

from typing import Any

from .canonical import seal_document, verify_sealed_document
from .errors import InvalidInput, LabError
from .sanitize import assert_sanitized


def build_error_result(error: LabError) -> dict[str, Any]:
    body: dict[str, Any] = {
        "schema_version": "1",
        "status": "ERROR",
        "dry_run": False,
        "exit_code": error.exit_code,
        "error": error.as_dict(),
        "result": {},
    }
    try:
        assert_sanitized(body)
    except InvalidInput:
        body["error"] = {
            "code": error.error_code,
            "message": "command failed; private diagnostic text was withheld",
            "details": {},
        }
    result = seal_document("command-error-result", body)
    validate_error_result(result)
    return result


def validate_error_result(result: dict[str, Any]) -> None:
    expected = {"schema_version", "status", "dry_run", "exit_code", "error", "result", "content_sha256"}
    if not isinstance(result, dict) or set(result) != expected:
        raise InvalidInput("command error result fields are incomplete or unknown")
    if result["schema_version"] != "1" or result["status"] != "ERROR" or result["dry_run"] is not False:
        raise InvalidInput("command error result state is invalid")
    if not isinstance(result["exit_code"], int) or isinstance(result["exit_code"], bool) or result["exit_code"] not in (10, 11, 12, 13, 14, 15):
        raise InvalidInput("command error result exit code is invalid")
    error = result["error"]
    if not isinstance(error, dict) or set(error) != {"code", "message", "details"} or not isinstance(error["code"], str) or not isinstance(error["message"], str) or not isinstance(error["details"], dict):
        raise InvalidInput("command error detail is invalid")
    if result["result"] != {} or not verify_sealed_document("command-error-result", result):
        raise InvalidInput("command error result content hash mismatch")
    assert_sanitized(result)
