"""Independent judge/report planning."""

from __future__ import annotations

import hashlib
import os
from pathlib import Path
import stat
from typing import Any

from ..authority import AuthoritySnapshot
from ..errors import EvidenceBlocked, ImmutableConflict, InvalidInput
from ..judge import judge_evidence
from ..paths import assert_no_reparse_points
from ..records import RecordStore, create_record


def plan(operation_id: str, payload: dict[str, Any]) -> dict[str, Any]:
    return {"evidence_record_count": len(payload["record_sha256s"]), "verdicts": ["PASS", "FAIL", "BLOCKED"], "judge_required": True}


def verify_artifact_bytes(store: RecordStore, evidence: list[dict[str, Any]]) -> None:
    """Resolve every artifact record to immutable experiment-owned bytes."""

    for record in evidence:
        if record["record_type"] != "artifact":
            continue
        logical = record["payload"]["logical_path"]
        path = store.experiment.owned_path(*logical.split("/"))
        try:
            assert_no_reparse_points(path, stop_at=store.experiment.root)
            path_before = os.lstat(path)
            if not stat.S_ISREG(path_before.st_mode):
                raise OSError("artifact is not a regular file")
            if getattr(path_before, "st_nlink", 1) != 1:
                raise OSError("artifact has multiple hard links")
            flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
            descriptor = os.open(path, flags)
            try:
                handle_before = os.fstat(descriptor)
                if getattr(handle_before, "st_nlink", 1) != 1:
                    raise OSError("artifact has multiple hard links")
                digest = hashlib.sha256()
                size = 0
                while True:
                    chunk = os.read(descriptor, 1024 * 1024)
                    if not chunk:
                        break
                    digest.update(chunk)
                    size += len(chunk)
                handle_after = os.fstat(descriptor)
            finally:
                os.close(descriptor)
            path_after = os.lstat(path)
            identities = [
                (
                    item.st_dev,
                    item.st_ino,
                    item.st_size,
                    getattr(item, "st_mtime_ns", int(item.st_mtime * 1_000_000_000)),
                )
                for item in (path_before, handle_before, handle_after, path_after)
            ]
            if len(set(identities)) != 1 or getattr(path_after, "st_nlink", 1) != 1:
                raise OSError("artifact identity changed while hashing")
            assert_no_reparse_points(path, stop_at=store.experiment.root)
        except (OSError, InvalidInput) as exc:
            raise EvidenceBlocked("artifact bytes are unavailable") from exc
        if size != record["payload"]["size_bytes"] or digest.hexdigest() != record["payload"]["sha256"]:
            raise EvidenceBlocked("artifact bytes do not match immutable evidence")


def execute(
    operation_id: str,
    payload: dict[str, Any],
    *,
    store: RecordStore,
    experiment_spec: dict[str, Any],
    repository: Path,
    authority_snapshot: AuthoritySnapshot,
) -> dict[str, Any]:
    try:
        evidence = store.load_evidence(payload["record_sha256s"])
    except (InvalidInput, ImmutableConflict) as exc:
        raise EvidenceBlocked("report evidence is unavailable or invalid") from exc
    verify_artifact_bytes(store, evidence)
    verdict = judge_evidence(
        experiment_spec,
        evidence,
        created_at=experiment_spec["created_at"],
        authority_snapshot=authority_snapshot,
    )
    store.put_verified_verdict(
        verdict,
        experiment_spec=experiment_spec,
        evidence=evidence,
        authority_snapshot=authority_snapshot,
    )
    verdict_value = verdict["payload"]["verdict"]
    evidence_sha256s = [record["content_sha256"] for record in evidence]
    report = create_record(
        "report",
        producer_role="regression-judge",
        run_id=experiment_spec["run_id"],
        created_at=experiment_spec["created_at"],
        experiment_spec_sha256=experiment_spec["content_sha256"],
        links={
            "experiment_spec": experiment_spec["content_sha256"],
            "verdict": verdict["content_sha256"],
        },
        payload={
            "verdict_sha256": verdict["content_sha256"],
            "verdict": verdict_value,
            "summary": {
                "evidence_record_count": len(evidence),
                "reason_codes": verdict["payload"]["reasons"],
            },
            "evidence_sha256s": evidence_sha256s,
            "numeric_tolerances": experiment_spec["tolerances"],
        },
    )
    store.put(report)
    return {
        "records": [verdict, report],
        "result": {
            "verdict": verdict_value,
            "verdict_sha256": verdict["content_sha256"],
            "report_sha256": report["content_sha256"],
        },
    }
