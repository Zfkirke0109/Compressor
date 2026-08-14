"""Typed failures and the stable process-exit taxonomy."""

from __future__ import annotations

from typing import Any

from .constants import (
    EXIT_BLOCKED,
    EXIT_IDENTITY_MISMATCH,
    EXIT_IMMUTABLE_CONFLICT,
    EXIT_INTERNAL,
    EXIT_INVALID_INPUT,
    EXIT_MISSING_PREREQUISITE,
)


class LabError(Exception):
    """Base class for expected, machine-classifiable failures."""

    exit_code = EXIT_INTERNAL
    error_code = "internal_failure"

    def __init__(self, message: str, *, details: dict[str, Any] | None = None) -> None:
        super().__init__(message)
        self.message = message
        self.details = details or {}

    def as_dict(self) -> dict[str, Any]:
        return {"code": self.error_code, "message": self.message, "details": self.details}


class InvalidInput(LabError):
    exit_code = EXIT_INVALID_INPUT
    error_code = "invalid_input"


class MissingPrerequisite(LabError):
    """An unavailable non-evidence executable/runtime detected before work."""

    exit_code = EXIT_MISSING_PREREQUISITE
    error_code = "missing_prerequisite"


class IdentityMismatch(LabError):
    exit_code = EXIT_IDENTITY_MISMATCH
    error_code = "identity_mismatch"


class EvidenceBlocked(LabError):
    """Missing, stale, incompatible, or confounded quality evidence."""

    exit_code = EXIT_BLOCKED
    error_code = "evidence_blocked"


class InternalFailure(LabError):
    exit_code = EXIT_INTERNAL
    error_code = "internal_failure"


class ImmutableConflict(LabError):
    exit_code = EXIT_IMMUTABLE_CONFLICT
    error_code = "immutable_conflict"
