"""Repository identity coverage for verified experiment-root exclusion."""

from __future__ import annotations

import hashlib
from pathlib import Path
import subprocess
import tempfile
import unittest

from pl_lab.authority import capture_authority_snapshot, fingerprint_snapshot
from pl_lab.errors import ImmutableConflict
from pl_lab.experiment import ExperimentStore


class ExperimentAuthorityExclusionTests(unittest.TestCase):
    def _git(self, root: Path, *arguments: str) -> None:
        subprocess.run(
            ["git", *arguments],
            cwd=root,
            check=True,
            capture_output=True,
        )

    def _repository(self, root: Path) -> None:
        self._git(root, "init", "--quiet")
        authority = root / "app/src/main/java/compress/joshattic/us/BatchQualityBitratePolicy.kt"
        authority.parent.mkdir(parents=True)
        authority.write_text("object BatchQualityBitratePolicy\n", encoding="utf-8")
        scorer = root / "scripts/diagnostics/measure_quality.py"
        scorer.parent.mkdir(parents=True)
        scorer.write_bytes(b"print('quality')\n")
        self._git(root, "add", "--", ".")
        self._git(
            root,
            "-c",
            "user.name=PL Lab Test",
            "-c",
            "user.email=pl-lab@example.invalid",
            "commit",
            "--quiet",
            "-m",
            "baseline",
        )

    def _snapshot(self, repository: Path, experiment: Path):
        return capture_authority_snapshot(
            repository,
            require_all=False,
            experiment_root=experiment,
        )

    def test_owned_experiment_outputs_are_excluded_but_other_untracked_sources_are_counted(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            repository = Path(temp)
            self._repository(repository)
            experiment = repository / "pl-experiment"
            ExperimentStore(experiment).prepare(execute=True)
            (experiment / "records").mkdir()
            (experiment / "records/result.json").write_text("{}\n", encoding="utf-8")

            before = self._snapshot(repository, experiment)
            (experiment / "artifacts").mkdir()
            (experiment / "artifacts/candidate.mp4").write_bytes(b"candidate")
            after_output = self._snapshot(repository, experiment)

            self.assertEqual(before.repository_identity["untracked_count"], 0)
            self.assertEqual(
                fingerprint_snapshot(before)["authority_sha256"],
                fingerprint_snapshot(after_output)["authority_sha256"],
            )

            kotlin = repository / "app/src/main/java/compress/joshattic/us/Unrelated.kt"
            kotlin.write_text("object Unrelated\n", encoding="utf-8")
            config = repository / "local-test-config.json"
            config.write_text('{"enabled":true}\n', encoding="utf-8")
            after_sources = self._snapshot(repository, experiment)

            self.assertEqual(after_sources.repository_identity["untracked_count"], 2)
            self.assertNotEqual(
                fingerprint_snapshot(after_output)["authority_sha256"],
                fingerprint_snapshot(after_sources)["authority_sha256"],
            )

    def test_unowned_in_repository_experiment_root_cannot_be_excluded(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            repository = Path(temp)
            self._repository(repository)
            unowned = repository / "unowned-experiment"
            unowned.mkdir()
            (unowned / "hidden.kt").write_text("object Hidden\n", encoding="utf-8")

            with self.assertRaises(ImmutableConflict):
                self._snapshot(repository, unowned)

    def test_registered_score_pair_harness_is_bound_as_authority(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            repository = Path(temp)
            self._repository(repository)

            fingerprint = fingerprint_snapshot(
                capture_authority_snapshot(repository, require_all=False)
            )
            files = {item["logical_path"]: item["sha256"] for item in fingerprint["files"]}

            self.assertEqual(
                files.get("scripts/diagnostics/measure_quality.py"),
                hashlib.sha256(b"print('quality')\n").hexdigest(),
            )


if __name__ == "__main__":
    unittest.main()
