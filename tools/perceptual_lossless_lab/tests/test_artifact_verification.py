from __future__ import annotations

import hashlib
import os
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

from pl_lab.errors import EvidenceBlocked
from pl_lab.experiment import ExperimentStore
from pl_lab.operations.report import verify_artifact_bytes
from pl_lab.records import RecordStore, create_record


SPEC_SHA256 = "a" * 64


def _artifact(experiment: Path, data: bytes = b"artifact-fixture\n") -> tuple[RecordStore, Path, dict]:
    ExperimentStore(experiment).prepare(execute=True)
    path = experiment / "artifacts" / "candidate.mp4"
    path.parent.mkdir(parents=True)
    path.write_bytes(data)
    record = create_record(
        "artifact",
        producer_role="deterministic-core",
        run_id="run-artifact-001",
        created_at="2026-08-14T00:00:00Z",
        experiment_spec_sha256=SPEC_SHA256,
        links={"experiment_spec": SPEC_SHA256},
        payload={
            "artifact_id": "candidate-media",
            "logical_path": "artifacts/candidate.mp4",
            "sha256": hashlib.sha256(data).hexdigest(),
            "size_bytes": len(data),
            "status": "COMPLETE",
        },
    )
    return RecordStore(experiment), path, record


class ArtifactVerificationTests(unittest.TestCase):
    def test_stable_single_link_artifact_is_verified(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store, _, record = _artifact(Path(temp_dir) / "experiment")
            verify_artifact_bytes(store, [record])

    def test_hard_linked_artifact_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store, path, record = _artifact(Path(temp_dir) / "experiment")
            try:
                os.link(path, path.with_name("alias.mp4"))
            except (OSError, NotImplementedError):
                self.skipTest("hard links are unavailable")
            with self.assertRaises(EvidenceBlocked):
                verify_artifact_bytes(store, [record])

    def test_handle_identity_change_during_hash_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            store, path, record = _artifact(Path(temp_dir) / "experiment")
            stable = os.stat(path)
            changed = SimpleNamespace(
                st_dev=stable.st_dev,
                st_ino=stable.st_ino,
                st_size=stable.st_size + 1,
                st_mtime=stable.st_mtime,
                st_mtime_ns=stable.st_mtime_ns,
                st_nlink=1,
            )
            real_fstat = os.fstat
            calls = 0

            def fstat_with_change(descriptor: int):
                nonlocal calls
                calls += 1
                return real_fstat(descriptor) if calls == 1 else changed

            with patch("pl_lab.operations.report.os.fstat", side_effect=fstat_with_change):
                with self.assertRaises(EvidenceBlocked):
                    verify_artifact_bytes(store, [record])


if __name__ == "__main__":
    unittest.main()
