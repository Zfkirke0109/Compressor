#!/usr/bin/env python3
"""Exercise device-check reporting with a fake adb and Gradle."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class DeviceChecksTest(unittest.TestCase):
    def run_checks(self, result, relative=False):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            repo = root / "repo"
            script = repo / "scripts" / "device" / "run-device-checks.sh"
            script.parent.mkdir(parents=True)
            shutil.copyfile(Path(__file__).with_name(script.name), script)
            gradle = repo / "gradlew"
            gradle.write_text("#!/bin/bash\nexit 0\n")
            gradle.chmod(0o755)
            adb = root / "adb"
            adb.write_text(
                '#!/bin/bash\n'
                'if [[ "$1" == push ]]; then test -f "$2"; fi\n'
                'if [[ "$1 $2 $3" == "shell am instrument" ]]; then printf "%s\\n" "$TEST_RESULT"; fi\n'
            )
            adb.chmod(0o755)
            clip = root / "camera clips" / "test.mp4"
            clip.parent.mkdir()
            clip.write_bytes(b"synthetic clip")
            arg = str(clip.relative_to(root) if relative else clip)
            proc = subprocess.run(
                ["bash", str(script), arg], cwd=root,
                env={**os.environ, "PATH": f"{root}:{os.environ['PATH']}", "TEST_RESULT": result},
                text=True, capture_output=True, check=False
            )
            report = clip.with_suffix(".device-checks.txt")
            return proc, report.read_text() if report.exists() else None, hashlib.sha256(clip.read_bytes()).hexdigest()[:16]

    def test_relative_clip_is_hashed_and_report_is_written_beside_it(self):
        proc, report, digest = self.run_checks("OK (1 test)", relative=True)
        self.assertEqual(0, proc.returncode, proc.stdout + proc.stderr)
        self.assertIn(f"sha256={digest}", report)

    def test_assumption_ignored_and_zero_test_runs_are_incomplete(self):
        for result in ("INSTRUMENTATION_STATUS_CODE: -4\nOK (1 test)",
                       "INSTRUMENTATION_STATUS_CODE: -3\nOK (1 test)", "OK (0 tests)"):
            with self.subTest(result=result):
                proc, report, _ = self.run_checks(result)
                self.assertEqual(2, proc.returncode, proc.stdout + proc.stderr)
                self.assertIn(result, report)
                self.assertNotIn("device checks passed", proc.stdout)


if __name__ == "__main__":
    unittest.main()
